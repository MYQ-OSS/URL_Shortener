package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import top.mayiqin.url_shortener.common.Base62Codec;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.shortcode.ShortCodeGenerator;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 跳转链路：短码 → 长链。
 *
 * <p><b>这是整个系统 QPS 最高的路径，必须"瘦"到极致。</b>
 * 它刻意不走 Spring Security 过滤器链、不开事务、不打同步 INFO 日志、
 * 缓存里直接存 URL 字符串而不是 JSON 对象（省掉反序列化）。
 *
 * <h3>完整防护链（顺序有讲究，每一步的位置都要能解释）</h3>
 * <pre>
 *  0. 格式校验        长度/字符非法 → 直接 404，不查任何存储。成本最低的一道防线
 *  1. Caffeine L1     命中就完全不走网络（~100ns）
 *  2. Redis L2        分布式共享（~0.5ms，1 次 RT）
 *  3. BloomFilter     拦"从来不存在"的短码 → 防穿透。必须在查 DB 之前
 *  4. 空值缓存        拦"BF 假阳性"和"曾存在但已删除"的短码
 *  5. 分布式锁        防击穿：热点 key 过期瞬间只放 1 个请求进 DB
 *  6. 双重检查        拿到锁后再查一次缓存——前面排队的请求可能已经回写了
 *  7. MySQL           最后一道
 *  8. 回写 L2 + L1    不存在也要写空值缓存
 * </pre>
 *
 * <h3>为什么"布隆过滤器"和"空值缓存"两个都要？（高频击穿题）</h3>
 * <ul>
 *   <li>BF 有<b>假阳性</b>：判断"可能存在"的短码里有一部分其实不存在 →
 *       会穿透到 DB → 需要空值缓存兜住</li>
 *   <li>BF <b>不支持删除</b>：已删除的短链在 BF 里还是"存在" → 也需要空值缓存</li>
 *   <li>反过来光有空值缓存不够：攻击者构造<b>海量不同的</b>随机短码，
 *       每个都要先查一次 DB 才能建立空值缓存，DB 还是会被打穿 → 需要 BF 在前面挡住</li>
 * </ul>
 * <b>两者是互补的：BF 挡掉 99.99% 不存在的请求，空值缓存兜住漏过来的那部分。</b>
 */
@Slf4j
@Service
public class LinkQueryService {

    /** 长链缓存 key 前缀。 */
    private static final String KEY_LINK = "link:uri:";
    /** 空值缓存 key 前缀。 */
    private static final String KEY_NULL = "link:null:";
    /** 击穿保护锁 key 前缀。 */
    private static final String KEY_LOCK = "link:lock:";

    /**
     * 长链缓存 TTL。
     *
     * <p>刻意设得很长（30 天）。理由：<b>短码 → 长链的映射近乎不可变</b>，
     * 缓存几乎不会失效。这带来一个很漂亮的架构优势——
     * <b>MySQL 挂了跳转链路仍然可用</b>，只有创建链路降级为不可用。
     */
    private static final Duration TTL_LINK = Duration.ofDays(30);

    /**
     * 空值缓存 TTL 必须<b>短</b>（2 分钟）。
     *
     * <p>因为它缓存的是"不存在"，而"不存在"随时可能变成"存在"（新建短链）。
     * 设太长会导致新建的短链在 TTL 内一直 404。
     * <b>创建链路里预热缓存 + 主动删除空值缓存，就是为了消掉这个窗口。</b>
     */
    private static final Duration TTL_NULL = Duration.ofMinutes(2);

    private final Cache<String, String> localCache;
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ShortCodeGenerator codeGenerator;
    private final LinkMapper linkMapper;

    public LinkQueryService(Cache<String, String> localCache,
                            StringRedisTemplate redis,
                            RedissonClient redisson,
                            ShortCodeGenerator codeGenerator,
                            LinkMapper linkMapper) {
        this.localCache = localCache;
        this.redis = redis;
        this.redisson = redisson;
        this.codeGenerator = codeGenerator;
        this.linkMapper = linkMapper;
    }

    /**
     * 解析短码为长链。
     *
     * @return 目标长链；{@code null} 表示不存在/已禁用/已过期，调用方应返回失效页
     */
    public String resolve(String shortUri) {
        // ── 0. 格式校验：不查任何存储 ────────────────────────────────
        if (!Base62Codec.isWellFormed(shortUri)) {
            return null;
        }

        // ── 1. L1 本地缓存 ─────────────────────────────────────────
        String hit = localCache.getIfPresent(shortUri);
        if (hit != null) {
            return hit.isEmpty() ? null : hit;   // 空串是本地的空值标记
        }

        // ── 2. L2 Redis ───────────────────────────────────────────
        String cached = redis.opsForValue().get(KEY_LINK + shortUri);
        if (cached != null) {
            localCache.put(shortUri, cached);
            return cached;
        }

        // ── 3. 布隆过滤器：false 表示一定不存在 ──────────────────────
        if (!codeGenerator.mightExist(shortUri)) {
            localCache.put(shortUri, "");
            return null;
        }

        // ── 4. 空值缓存 ────────────────────────────────────────────
        if (Boolean.TRUE.equals(redis.hasKey(KEY_NULL + shortUri))) {
            localCache.put(shortUri, "");
            return null;
        }

        // ── 5/6/7/8. 分布式锁 + 双重检查 + 查库 + 回写 ────────────────
        RLock lock = redisson.getLock(KEY_LOCK + shortUri);
        try {
            // 只等 500ms。等不到说明有人正在回源，与其继续排队不如直接查一次库——
            // 排队等待会把线程占死，在高并发下比多查一次库更危险。
            if (!lock.tryLock(500, 3000, TimeUnit.MILLISECONDS)) {
                return loadFromDb(shortUri);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loadFromDb(shortUri);
        }
        try {
            // 双重检查：等锁期间可能已经有人回写了
            String again = redis.opsForValue().get(KEY_LINK + shortUri);
            if (again != null) {
                localCache.put(shortUri, again);
                return again;
            }
            String url = loadFromDb(shortUri);
            if (url == null) {
                redis.opsForValue().set(KEY_NULL + shortUri, "1", TTL_NULL);
                localCache.put(shortUri, "");
            } else {
                redis.opsForValue().set(KEY_LINK + shortUri, url, TTL_LINK);
                localCache.put(shortUri, url);
            }
            return url;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private String loadFromDb(String shortUri) {
        Link link = linkMapper.selectOne(Wrappers.<Link>lambdaQuery()
                .eq(Link::getShortUri, shortUri)
                .eq(Link::getDelFlag, 0));
        return (link != null && link.redirectable()) ? link.getOriginUrl() : null;
    }

    /**
     * 失效一个短码的所有层级缓存。
     *
     * <p>调用方：禁用/封禁/删除/修改目标地址。
     *
     * <p><b>⚠️ 本地缓存的失效是这里最难的部分。</b>Redis 好清（DEL），
     * 但每个实例的 Caffeine 是各自独立的，必须靠 Redis Pub/Sub 广播。
     * 而 Pub/Sub 是 fire-and-forget <b>不保证送达</b>（实例正好在重连时会漏消息），
     * 所以 Caffeine 必须<b>同时</b>设短 TTL 作为第二层保险：
     * <ul>
     *   <li>广播：让 99% 的情况下 100ms 内生效</li>
     *   <li>短 TTL：保证最坏情况下 5 秒必定生效</li>
     * </ul>
     * 两层各解决一半问题。见 {@code CacheInvalidateListener}。
     */
    public void invalidate(String shortUri) {
        redis.delete(KEY_LINK + shortUri);
        redis.delete(KEY_NULL + shortUri);
        localCache.invalidate(shortUri);
        redis.convertAndSend(CacheInvalidateListener.TOPIC, shortUri);
    }

    /** 创建成功后预热：这是消掉"空值缓存导致新短链 404"窗口的关键。 */
    public void warmUp(String shortUri, String originUrl) {
        redis.delete(KEY_NULL + shortUri);
        redis.opsForValue().set(KEY_LINK + shortUri, originUrl, TTL_LINK);
    }
}

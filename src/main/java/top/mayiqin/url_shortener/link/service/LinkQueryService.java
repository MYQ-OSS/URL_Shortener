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

    /**
     * 压测分阶段开关（见 benchmark/README.md 2.5 的逐阶段表）：
     * <ul>
     *   <li>{@code l1-enabled=false, l2-enabled=false} → 裸 MySQL 基线</li>
     *   <li>{@code l1-enabled=false, l2-enabled=true}  → 只有 Redis L2</li>
     *   <li>两者都开（默认）→ 完整防护链</li>
     * </ul>
     * 生产环境保持默认全开。开关只影响本实例，用启动参数覆盖即可，不改代码。
     */
    private final boolean l1Enabled;
    private final boolean l2Enabled;

    public LinkQueryService(Cache<String, String> localCache,
                            StringRedisTemplate redis,
                            RedissonClient redisson,
                            ShortCodeGenerator codeGenerator,
                            LinkMapper linkMapper,
                            @org.springframework.beans.factory.annotation.Value("${shortlink.cache.l1-enabled:true}") boolean l1Enabled,
                            @org.springframework.beans.factory.annotation.Value("${shortlink.cache.l2-enabled:true}") boolean l2Enabled) {
        this.localCache = localCache;
        this.redis = redis;
        this.redisson = redisson;
        this.codeGenerator = codeGenerator;
        this.linkMapper = linkMapper;
        this.l1Enabled = l1Enabled;
        this.l2Enabled = l2Enabled;
        if (!l1Enabled || !l2Enabled) {
            log.warn("缓存开关非默认状态（l1={}, l2={}）——仅限压测使用，生产必须全开", l1Enabled, l2Enabled);
        }
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
        if (l1Enabled) {
            String hit = localCache.getIfPresent(shortUri);
            if (hit != null) {
                return hit.isEmpty() ? null : hit;   // 空串是本地的空值标记
            }
        }

        if (!l2Enabled) {
            // 压测阶段开关：绕过全部 Redis 链路（L2 / BF / 空值缓存 / 锁），直查 DB
            return loadFromDb(shortUri);
        }

        try {
            return resolveViaRedis(shortUri);
        } catch (RuntimeException e) {
            // ── Redis 故障降级（ADR-015 故障降级矩阵）──────────────────
            // L2/BF/锁全在 Redis 上，任何一个调用抛异常都说明 Redis 不可用。
            // 此时跳过整条 Redis 链直查 DB：QPS 下降（L1 兜住热点，其余落 DB），
            // 但错误率保持 0——这正是"Redis 挂了跳转还能用"的演练预期。
            // 防穿透暂时失效（BF 不可用），DB 靠索引硬扛，这是有意识的取舍。
            redisDownWarn(e);
            String url = loadFromDb(shortUri);
            if (url != null) {
                putLocal(shortUri, url);   // L1 还能暖一点是一点
            }
            return url;
        }
    }

    /** L2 → BF → 空值缓存 → 锁 → 回源 → 回写。Redis 故障时由调用方捕获降级。 */
    private String resolveViaRedis(String shortUri) {
        // ── 2. L2 Redis ───────────────────────────────────────────
        String cached = redis.opsForValue().get(KEY_LINK + shortUri);
        if (cached != null) {
            putLocal(shortUri, cached);
            return cached;
        }

        // ── 3. 布隆过滤器：false 表示一定不存在 ──────────────────────
        if (!codeGenerator.mightExist(shortUri)) {
            putLocal(shortUri, "");
            return null;
        }

        // ── 4. 空值缓存 ────────────────────────────────────────────
        if (Boolean.TRUE.equals(redis.hasKey(KEY_NULL + shortUri))) {
            putLocal(shortUri, "");
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
                putLocal(shortUri, again);
                return again;
            }
            String url = loadFromDb(shortUri);
            if (url == null) {
                redis.opsForValue().set(KEY_NULL + shortUri, "1", TTL_NULL);
                putLocal(shortUri, "");
            } else {
                redis.opsForValue().set(KEY_LINK + shortUri, url, TTL_LINK);
                putLocal(shortUri, url);
            }
            return url;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** Redis 故障告警限频：高并发下每请求都打日志等于二次雪崩，5 秒最多一条。 */
    private final java.util.concurrent.atomic.AtomicLong lastRedisWarnAt = new java.util.concurrent.atomic.AtomicLong();

    private void redisDownWarn(RuntimeException e) {
        long now = System.currentTimeMillis();
        long last = lastRedisWarnAt.get();
        if (now - last > 5000 && lastRedisWarnAt.compareAndSet(last, now)) {
            log.warn("Redis 故障，跳转链路降级为直查 DB（L2/BF/锁暂不可用）: {}", e.getMessage());
        }
    }

    /** L1 写入统一走这里：压测开关关闭时不碰本地缓存。 */
    private void putLocal(String shortUri, String value) {
        if (l1Enabled) {
            localCache.put(shortUri, value);
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
        if (l2Enabled) {
            try {
                redis.delete(KEY_LINK + shortUri);
                redis.delete(KEY_NULL + shortUri);
                redis.convertAndSend(CacheInvalidateListener.TOPIC, shortUri);
            } catch (RuntimeException e) {
                // Redis 故障时广播失效：本实例下面照样清 L1，其他实例靠 5s TTL 兜底
                redisDownWarn(e);
            }
        }
        if (l1Enabled) {
            localCache.invalidate(shortUri);
        }
    }

    /** 创建成功后预热：这是消掉"空值缓存导致新短链 404"窗口的关键。 */
    public void warmUp(String shortUri, String originUrl) {
        if (!l2Enabled) {
            return;   // 压测阶段关闭 L2 时没有可预热的东西
        }
        try {
            redis.delete(KEY_NULL + shortUri);
            redis.opsForValue().set(KEY_LINK + shortUri, originUrl, TTL_LINK);
        } catch (RuntimeException e) {
            // Redis 故障时预热失败：首次访问会 miss → 直查 DB（降级路径）兜住
            redisDownWarn(e);
        }
    }
}

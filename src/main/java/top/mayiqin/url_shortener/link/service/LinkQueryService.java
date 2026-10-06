package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import top.mayiqin.url_shortener.common.ShortCodeFormat;
import top.mayiqin.url_shortener.common.RedisAvailability;
import org.springframework.web.server.ResponseStatusException;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 多级缓存、空值缓存和受限回源；过期判断及版本栅栏保护仍在缓存命中、回写时执行。 */
@Slf4j
@Service
public class LinkQueryService {

    /** 长链缓存 key 前缀。 */
    private static final String KEY_LINK = "link:uri:";
    /** 空值缓存 key 前缀。 */
    private static final String KEY_NULL = "link:null:";
    /** 击穿保护锁 key 前缀。 */
    private static final String KEY_LOCK = "link:lock:";
    private static final String KEY_VERSION = "link:ver:";

    // 正向缓存的 30 天上限与实际有效期由 CachedLink.ttl() 统一计算。
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
    private final LinkMapper linkMapper;
    private final DbReadGuard dbGuard;
    private final RedisAvailability redisAvailability;
    private final DefaultRedisScript<Long> invalidateScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> writeScript = new DefaultRedisScript<>();

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
                            LinkMapper linkMapper,
                            DbReadGuard dbGuard,
                            RedisAvailability redisAvailability,
                            @org.springframework.beans.factory.annotation.Value("${shortlink.cache.l1-enabled:true}") boolean l1Enabled,
                            @org.springframework.beans.factory.annotation.Value("${shortlink.cache.l2-enabled:true}") boolean l2Enabled) {
        this.localCache = localCache;
        this.redis = redis;
        this.redisson = redisson;
        this.linkMapper = linkMapper;
        this.dbGuard = dbGuard;
        this.redisAvailability = redisAvailability;
        invalidateScript.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/invalidate_link_cache.lua")));
        invalidateScript.setResultType(Long.class);
        writeScript.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/write_link_cache.lua")));
        writeScript.setResultType(Long.class);
        this.l1Enabled = l1Enabled;
        this.l2Enabled = l2Enabled;
        if (!l1Enabled || !l2Enabled) {
            log.warn("缓存开关非默认状态（l1={}, l2={}）——仅限压测使用，请根据压测目的确认此配置", l1Enabled, l2Enabled);
        }
    }

    /**
     * 解析短码为长链。
     *
     * @return 目标长链；{@code null} 表示不存在/已禁用/已过期，调用方应返回失效页
     */
    public String resolve(String shortUri) {
        // ── 0. 格式校验：不查任何存储 ────────────────────────────────
        if (!ShortCodeFormat.isWellFormed(shortUri)) {
            return null;
        }

        // ── 1. L1 本地缓存 ─────────────────────────────────────────
        if (l1Enabled) {
            String hit = localCache.getIfPresent(shortUri);
            if (hit != null) {
                if (hit.isEmpty()) return null;
                CachedLink cached = CachedLink.parse(hit);
                if (cached != null) return cached.expired() ? null : cached.url();
                // 旧版本只有 URL，没有有效期和状态信息；必须回源校验。
                localCache.invalidate(shortUri);
            }
        }

        if (!l2Enabled || redisAvailability.isOpen()) {
            // 压测开关或 Redis 故障时，直查 DB；回源预算仍然有效。
            return fallback(shortUri);
        }

        try {
            return resolveViaRedis(shortUri);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            // ── Redis 故障降级（ADR-015 故障降级矩阵）──────────────────
            // L2 和锁共用 Redis；故障时进入有预算保护的 DB 降级路径。
            // 跳过 Redis 链后受本地回源预算保护；超额返回 503，避免故障扩散到 DB。
            redisAvailability.failed();
            redisDownWarn(e);
            return fallback(shortUri);
        }
    }

    /** L2 → 空值缓存 → 锁 → 回源 → 回写。Redis 故障时由调用方捕获降级。 */
    private String resolveViaRedis(String shortUri) {
        // ── 2. L2 Redis ───────────────────────────────────────────
        String cached = redis.opsForValue().get(KEY_LINK + shortUri);
        if (cached != null) {
            CachedLink entry = CachedLink.parse(cached);
            if (entry != null) {
                putLocal(shortUri, cached);
                return entry.expired() ? null : entry.url();
            }
            // 滚动升级时不能继续信任旧版不带截止时间的 URL 缓存。
            redis.delete(KEY_LINK + shortUri);
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
                return fallback(shortUri);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback(shortUri);
        }
        try {
            // 双重检查：等锁期间可能已经有人回写了
            String again = redis.opsForValue().get(KEY_LINK + shortUri);
            if (again != null) {
                CachedLink entry = CachedLink.parse(again);
                if (entry != null) {
                    putLocal(shortUri, again);
                    return entry.expired() ? null : entry.url();
                }
                redis.delete(KEY_LINK + shortUri);
            }
            String version = currentVersion(shortUri);
            CachedLink entry = loadFromDb(shortUri);
            if (entry == null) {
                if (!writeCache(shortUri, version, false, "1", TTL_NULL)) return fallback(shortUri);
                putLocal(shortUri, "");
            } else {
                Duration ttl = entry.ttl();
                if (ttl.isNegative() || ttl.isZero()) return null;
                if (!writeCache(shortUri, version, true, entry.encode(), ttl)) return fallback(shortUri);
                putLocal(shortUri, entry.encode());
            }
            return entry == null || entry.expired() ? null : entry.url();
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
            log.warn("Redis 故障，跳转链路降级为受限 DB 回源（L2/锁暂不可用）: {}", e.getMessage());
        }
    }

    /** L1 写入统一走这里：压测开关关闭时不碰本地缓存。 */
    private void putLocal(String shortUri, String value) {
        if (l1Enabled) {
            localCache.put(shortUri, value);
        }
    }

    private CachedLink loadFromDb(String shortUri) {
        Link link = dbGuard.execute(() -> linkMapper.selectOne(Wrappers.<Link>lambdaQuery()
                .eq(Link::getShortUri, shortUri)
                .eq(Link::getDelFlag, 0)));
        return (link != null && link.redirectable())
                ? CachedLink.of(link.getOriginUrl(), link.getValidType() != null && link.getValidType() == 1
                        ? link.getValidEnd() : null) : null;
    }

    private String fallback(String shortUri) {
        CachedLink entry = loadFromDb(shortUri);
        putLocal(shortUri, entry == null ? "" : entry.encode());
        return entry == null || entry.expired() ? null : entry.url();
    }

    private String currentVersion(String shortUri) {
        String version = redis.opsForValue().get(KEY_VERSION + shortUri);
        return version == null ? "0" : version;
    }

    private boolean writeCache(String shortUri, String expectedVersion, boolean positive,
                               String payload, Duration ttl) {
        String key = (positive ? KEY_LINK : KEY_NULL) + shortUri;
        String other = (positive ? KEY_NULL : KEY_LINK) + shortUri;
        Long saved = redis.execute(writeScript, List.of(KEY_VERSION + shortUri, key, other),
                expectedVersion, payload, Long.toString(Math.max(1, ttl.toMillis())));
        if (saved == null) throw new IllegalStateException("缓存写入脚本未返回结果");
        return saved == 1L;
    }

    /** 提升缓存版本后原子删除 L2 并广播；L1 短 TTL 兜底漏收广播的实例。 */
    public boolean invalidate(String shortUri) {
        boolean cleared = true;
        if (l2Enabled) {
            try {
                Long result = redis.execute(invalidateScript,
                        List.of(KEY_VERSION + shortUri, KEY_LINK + shortUri, KEY_NULL + shortUri),
                        CacheInvalidateListener.TOPIC, shortUri);
                if (result == null || result != 1L) throw new IllegalStateException("缓存失效脚本未返回成功");
            } catch (RuntimeException e) {
                // 调用方保留待失效消息，Redis 恢复后继续 DEL + 广播。
                redisDownWarn(e);
                redisAvailability.failed();
                cleared = false;
            }
        }
        if (l1Enabled) {
            localCache.invalidate(shortUri);
        }
        return cleared;
    }

    /** 创建成功后预热：这是消掉"空值缓存导致新短链 404"窗口的关键。 */
    public void warmUp(String shortUri, String originUrl, LocalDateTime validEnd) {
        if (l1Enabled) localCache.invalidate(shortUri);
        if (!l2Enabled) {
            return;   // 压测阶段关闭 L2 时没有可预热的东西
        }
        try {
            CachedLink entry = CachedLink.of(originUrl, validEnd);
            Duration ttl = entry.ttl();
            if (ttl.isZero() || ttl.isNegative()) return;
            String version = currentVersion(shortUri);
            if (writeCache(shortUri, version, true, entry.encode(), ttl)) {
                redis.convertAndSend(CacheInvalidateListener.TOPIC, shortUri);
            }
        } catch (RuntimeException e) {
            // Redis 故障时预热失败：首次访问会 miss → 直查 DB（降级路径）兜住
            redisDownWarn(e);
        }
    }
}

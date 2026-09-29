package top.mayiqin.url_shortener.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.mayiqin.url_shortener.idgen.SegmentIdGenerator;
import top.mayiqin.url_shortener.link.service.ExpiredLinkCleanupJob;
import top.mayiqin.url_shortener.link.service.LinkGotoSyncJob;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import top.mayiqin.url_shortener.risk.UrlRiskChecker;
import top.mayiqin.url_shortener.shortcode.BloomFilterHolder;
import top.mayiqin.url_shortener.shortcode.BloomFilterRebuilder;
import top.mayiqin.url_shortener.stats.service.StatsCollector;
import top.mayiqin.url_shortener.stats.service.StatsFlushService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运维/压测辅助接口。
 *
 * <p>受 AdminInterceptor 拦截保护，访问需在 Header 携带 'X-Admin-Token'。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final BloomFilterRebuilder rebuilder;
    private final BloomFilterHolder bloomHolder;
    private final Cache<String, String> localCache;
    private final SegmentIdGenerator idGenerator;
    private final StatsCollector statsCollector;
    private final StatsFlushService statsFlushService;
    private final UrlRiskChecker riskChecker;
    private final ExpiredLinkCleanupJob cleanupJob;
    private final LinkGotoSyncJob gotoSyncJob;

    public AdminController(BloomFilterRebuilder rebuilder,
                           BloomFilterHolder bloomHolder,
                           Cache<String, String> localCache,
                           SegmentIdGenerator idGenerator,
                           StatsCollector statsCollector,
                           StatsFlushService statsFlushService,
                           UrlRiskChecker riskChecker,
                           ExpiredLinkCleanupJob cleanupJob,
                           LinkGotoSyncJob gotoSyncJob) {
        this.rebuilder = rebuilder;
        this.bloomHolder = bloomHolder;
        this.localCache = localCache;
        this.idGenerator = idGenerator;
        this.statsCollector = statsCollector;
        this.statsFlushService = statsFlushService;
        this.riskChecker = riskChecker;
        this.cleanupJob = cleanupJob;
        this.gotoSyncJob = gotoSyncJob;
    }

    /**
     * ★ 压测前必调：从 DB 全量重建布隆过滤器。
     *
     * <p>灌完种子数据后 BF 里没有这些短码，所有压测请求都会被 BF 判"一定不存在"直接 404。
     * 这个坑非常隐蔽——QPS 会高得离谱（因为根本没查缓存和 DB），很容易误以为优化成功。
     *
     * <pre>curl -X POST <a href="http://localhost:8080/admin/bloom/rebuild">...</a></pre>
     */
    @PostMapping("/bloom/rebuild")
    public ResponseEntity<Map<String, Object>> rebuildBloom() {
        long t0 = System.currentTimeMillis();
        long count = rebuilder.rebuild();
        return ResponseEntity.ok(Map.of(
                "loaded", count,
                "version", bloomHolder.version(),
                "elapsedMs", System.currentTimeMillis() - t0));
    }

    /**
     * 压测辅助：清空本地缓存，让下一轮从冷启动开始。
     *
     * <p><b>每一轮压测前都要调</b>，否则上一轮预热出来的缓存会让这一轮的数字虚高，
     * "优化前 / 优化后"的对比就不成立了。
     */
    @PostMapping("/cache/local/clear")
    public ResponseEntity<Map<String, Object>> clearLocalCache() {
        long before = localCache.estimatedSize();
        localCache.invalidateAll();
        localCache.cleanUp();
        return ResponseEntity.ok(Map.of("cleared", before));
    }

    /**
     * 压测过程中看这些数，比看 wrk 的输出更能定位瓶颈。
     *
     * <pre>curl -s localhost:8080/admin/stats | python -m json.tool</pre>
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        CacheStats cs = localCache.stats();
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("size", localCache.estimatedSize());
        local.put("hitCount", cs.hitCount());
        local.put("missCount", cs.missCount());
        // ★ 这个数才是"L1 命中率"。它和"整体缓存命中率"是两个不同的概念：
        //   L1 命中率     = Caffeine 命中 / 总请求          （Zipf α=1.0 下约 60-75%）
        //   整体命中率    = (L1 + Redis 命中) / 总请求       （稳态下 99.9%+，因为 Redis 装得下全部数据）
        // 简历和面试里混着说会被问穿，必须分开报。
        local.put("hitRate", String.format("%.4f", cs.hitRate()));
        local.put("evictionCount", cs.evictionCount());

        Map<String, Object> idgen = new LinkedHashMap<>();
        idgen.put("currentRemainRatio", String.format("%.4f", idGenerator.currentRemainRatio()));
        // 持续增长 = step 太小、预加载赶不上消耗（见 ADR-002）
        idgen.put("syncLoadCount", idGenerator.syncLoadCount());
        // 大于 0 说明命中了"预加载与同步取号竞态导致号段倒序"的那个坑（已被修正逻辑拦下）
        idgen.put("staleSegmentCount", idGenerator.staleSegmentCount());

        // 统计链路健康度：pending 持续增长 = MQ 不可用（降级暂存中）；
        // dropped > 0 = 暂存已满开始丢弃，必须告警——静默丢数据比丢数据更危险
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pending", statsCollector.pendingCount());
        stats.put("dropped", statsCollector.droppedCount());
        stats.put("flushedRows", statsFlushService.flushedRows());
        stats.put("lastFlushAt", statsFlushService.lastFlushAt());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("localCache", local);
        out.put("idGenerator", idgen);
        out.put("bloomVersion", bloomHolder.version());
        out.put("bloomTrusted", bloomHolder.isTrusted());
        out.put("stats", stats);
        return ResponseEntity.ok(out);
    }

    /**
     * 动态重载风控域名黑白名单。
     */
    @PostMapping("/risk/reload")
    public ResponseEntity<Map<String, Object>> reloadRiskRules(
            @org.springframework.web.bind.annotation.RequestBody Map<String, List<String>> body) {
        List<String> blocked = body.get("blockedDomains");
        List<String> allowed = body.get("allowedDomains");
        riskChecker.reloadRules(blocked, allowed);
        return ResponseEntity.ok(Map.of("status", "success", "message", "Risk rules reloaded successfully"));
    }

    /**
     * 手动触发过期短链接与缓存清理。
     */
    @PostMapping("/expired/clean")
    public ResponseEntity<Map<String, Object>> cleanExpiredLinks() {
        long cleaned = cleanupJob.cleanupExpiredLinks();
        return ResponseEntity.ok(Map.of("status", "success", "cleanedRows", cleaned));
    }

    @GetMapping("/messages/failed")
    public ResponseEntity<List<LocalMessage>> failedMessages(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(gotoSyncJob.listFailed(limit));
    }

    @PostMapping("/messages/{shortUri:[0-9a-zA-Z]{6}}/{id}/retry")
    public ResponseEntity<Void> retryFailedMessage(@PathVariable String shortUri, @PathVariable long id) {
        return gotoSyncJob.retryFailed(id, shortUri)
                ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}

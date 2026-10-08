package top.mayiqin.url_shortener.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.mayiqin.url_shortener.link.service.ExpiredLinkCleanupJob;
import top.mayiqin.url_shortener.link.service.ExpiredLinkCleanupJob.CleanupResult;
import top.mayiqin.url_shortener.link.service.ExpiredLinkCleanupJob.CleanupStatus;
import top.mayiqin.url_shortener.risk.UrlRiskChecker;
import top.mayiqin.url_shortener.stats.service.StatsCollector;
import top.mayiqin.url_shortener.stats.service.StatsFlushService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 仅保留主版本实际需要的运行指标和维护操作；所有入口均受管理员鉴权保护。 */
@RestController
@RequestMapping("/admin")
public class AdminController {
    /**
     * 人工触发的时间预算，必须明显小于前端维护操作的 30 秒超时（api.ts）：
     * 预算用尽就带 {@code truncated} 返回，由运维再点一次，而不是让客户端先超时、服务端仍在跑。
     */
    private static final long MANUAL_CLEAN_BUDGET_MILLIS = 20_000L;

    private final Cache<String, String> localCache;
    private final StatsCollector statsCollector;
    private final StatsFlushService statsFlushService;
    private final UrlRiskChecker riskChecker;
    private final ExpiredLinkCleanupJob cleanupJob;

    public AdminController(Cache<String, String> localCache, StatsCollector statsCollector,
                           StatsFlushService statsFlushService, UrlRiskChecker riskChecker,
                           ExpiredLinkCleanupJob cleanupJob) {
        this.localCache = localCache;
        this.statsCollector = statsCollector;
        this.statsFlushService = statsFlushService;
        this.riskChecker = riskChecker;
        this.cleanupJob = cleanupJob;
    }

    @PostMapping("/cache/local/clear")
    public ResponseEntity<Map<String, Object>> clearLocalCache() {
        long before = localCache.estimatedSize();
        localCache.invalidateAll();
        localCache.cleanUp();
        return ResponseEntity.ok(Map.of("cleared", before));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        CacheStats cs = localCache.stats();
        Map<String, Object> local = new LinkedHashMap<>();
        local.put("size", localCache.estimatedSize());
        local.put("hitCount", cs.hitCount());
        local.put("missCount", cs.missCount());
        local.put("hitRate", String.format(Locale.ROOT, "%.4f", cs.hitRate()));
        local.put("evictionCount", cs.evictionCount());
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pending", statsCollector.pendingCount());
        stats.put("bufferCapacity", statsCollector.bufferCapacity());
        stats.put("dropped", statsCollector.droppedCount());
        stats.put("publishFailures", statsCollector.publishFailureCount());
        stats.put("flushedRows", statsFlushService.flushedRows());
        stats.put("lastFlushAt", statsFlushService.lastFlushAt());
        stats.put("deliveryMode", "best-effort");
        return ResponseEntity.ok(Map.of("localCache", local, "stats", stats));
    }

    @PostMapping("/risk/reload")
    public ResponseEntity<Map<String, Object>> reloadRiskRules(@RequestBody RiskRules rules) {
        riskChecker.reloadRules(rules.blockedDomains(), rules.allowedDomains());
        return ResponseEntity.ok(Map.of("status", "success"));
    }

    /**
     * 手动触发过期短链清理。
     *
     * <p>响应不再用单一的 {@code cleanedRows} 表达结果：{@code status} 区分跑完/提前结束/
     * 没抢到锁/被中断，{@code executed} 表示本次是否真的执行了清理，{@code moreRemaining}
     * 表示是否应稍后再试。避免出现“{@code cleanedRows=0} 但其实是锁被占、什么都没做”的歧义。
     */
    @PostMapping("/expired/clean")
    public ResponseEntity<Map<String, Object>> cleanExpiredLinks() {
        CleanupResult result = cleanupJob.cleanupExpiredLinks(MANUAL_CLEAN_BUDGET_MILLIS);
        boolean executed = result.status() == CleanupStatus.COMPLETED || result.status() == CleanupStatus.TRUNCATED;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.status().name().toLowerCase(Locale.ROOT));
        body.put("executed", executed);
        body.put("cleanedRows", result.cleanedRows());
        body.put("moreRemaining", result.moreRemaining());
        return ResponseEntity.ok(body);
    }

    public record RiskRules(List<String> blockedDomains, List<String> allowedDomains) { }
}

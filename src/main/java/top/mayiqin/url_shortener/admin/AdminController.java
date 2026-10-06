package top.mayiqin.url_shortener.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.mayiqin.url_shortener.link.service.ExpiredLinkCleanupJob;
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

    @PostMapping("/expired/clean")
    public ResponseEntity<Map<String, Object>> cleanExpiredLinks() {
        return ResponseEntity.ok(Map.of("status", "success", "cleanedRows", cleanupJob.cleanupExpiredLinks()));
    }

    public record RiskRules(List<String> blockedDomains, List<String> allowedDomains) { }
}

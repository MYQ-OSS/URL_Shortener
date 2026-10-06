package top.mayiqin.url_shortener.config;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import top.mayiqin.url_shortener.stats.service.StatsCollector;

/** 主版本的缓存和统计水位，不再暴露实验发号器、Bloom 等未启用组件的指标。 */
@Component
public class DomainMetrics {
    public DomainMetrics(MeterRegistry registry, Cache<String, String> localCache,
                         StatsCollector statsCollector) {
        Gauge.builder("shortlink.cache.local.hit_rate", localCache, c -> c.stats().hitRate())
                .description("L1 本地缓存命中率").register(registry);
        Gauge.builder("shortlink.cache.local.size", localCache, Cache::estimatedSize)
                .description("L1 本地缓存条目数").register(registry);
        Gauge.builder("shortlink.cache.local.eviction_count", localCache, c -> c.stats().evictionCount())
                .description("L1 累计淘汰次数").register(registry);
        Gauge.builder("shortlink.stats.pending", statsCollector, StatsCollector::pendingCount)
                .description("内存中待确认的统计事件数，含正在发送的事件").register(registry);
        Gauge.builder("shortlink.stats.dropped", statsCollector, StatsCollector::droppedCount)
                .description("本实例运行期间明确丢弃的统计事件数，不包括崩溃损失").register(registry);
        Gauge.builder("shortlink.stats.publish_failures", statsCollector, StatsCollector::publishFailureCount)
                .description("统计投递未确认次数，内存事件稍后重试").register(registry);
    }
}

package top.mayiqin.url_shortener.config;

import com.github.benmanes.caffeine.cache.Cache;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import top.mayiqin.url_shortener.idgen.SegmentIdGenerator;
import top.mayiqin.url_shortener.shortcode.BloomFilterHolder;
import top.mayiqin.url_shortener.stats.service.StatsCollector;

/**
 * Prometheus 域指标（Week 5 可观测性）。
 *
 * <p>把散落在 {@code /admin/stats} 里的关键水位统一暴露成 Micrometer 指标，
 * 让 Grafana / 告警规则能直接消费。指标命名遵循 {@code shortlink.*} 前缀，
 * 与 Micrometer 的全局 tag（{@code application=url-shortener}）叠加。
 *
 * <h3>每个指标为什么值得监控</h3>
 * <ul>
 *   <li><b>cache.local.hit_rate</b>：L1 命中率的持续观测。掉得厉害说明热点漂移或 TTL 过短。</li>
 *   <li><b>cache.local.size / eviction</b>：容量与淘汰压力。eviction 飙升说明 maximumSize 偏小。</li>
 *   <li><b>stats.pending</b>：待投递统计事件。持续增长 = 消费跟不上或 MQ 不可用。</li>
 *   <li><b>stats.dropped</b>：因队列满被丢弃的事件数。<b>>0 就该告警</b>——静默丢数据最危险。</li>
 *   <li><b>idgen.remain_ratio</b>：当前号段剩余比例。贴近 0 且频繁同步取号 = step 偏小。</li>
 *   <li><b>idgen.sync_load_count</b>：同步取号次数。持续增长 = 预加载赶不上消耗（ADR-002）。</li>
 *   <li><b>bloom.version</b>：当前 BF 版本号。重建后会跳变，可用于确认重建生效。</li>
 * </ul>
 *
 * <p>全部用 Gauge（瞬时值），因为这些是"水位"而非累计计数；
 * 累计语义（如总创建数）应另用 Counter，这里聚焦运维最关心的水位类。
 */
@Component
public class DomainMetrics {

    public DomainMetrics(MeterRegistry registry,
                         Cache<String, String> localCache,
                         StatsCollector statsCollector,
                         SegmentIdGenerator idGenerator,
                         BloomFilterHolder bloomHolder) {

        Gauge.builder("shortlink.cache.local.hit_rate", localCache, c -> c.stats().hitRate())
                .description("L1 本地缓存命中率")
                .register(registry);
        Gauge.builder("shortlink.cache.local.size", localCache, Cache::estimatedSize)
                .description("L1 本地缓存条目数")
                .register(registry);
        Gauge.builder("shortlink.cache.local.eviction_count", localCache, c -> c.stats().evictionCount())
                .description("L1 累计淘汰次数")
                .register(registry);

        Gauge.builder("shortlink.stats.pending", statsCollector, StatsCollector::pendingCount)
                .description("待投递统计事件数")
                .register(registry);
        Gauge.builder("shortlink.stats.dropped", statsCollector, StatsCollector::droppedCount)
                .description("因队列满被丢弃的统计事件数")
                .register(registry);
        Gauge.builder("shortlink.stats.publish_failures", statsCollector, StatsCollector::publishFailureCount)
                .description("统计投递失败次数，事件保留在磁盘等待重试")
                .register(registry);

        Gauge.builder("shortlink.idgen.remain_ratio", idGenerator, SegmentIdGenerator::currentRemainRatio)
                .description("当前号段剩余比例")
                .register(registry);
        Gauge.builder("shortlink.idgen.sync_load_count", idGenerator, SegmentIdGenerator::syncLoadCount)
                .description("同步取号次数")
                .register(registry);
        Gauge.builder("shortlink.idgen.stale_segment_count", idGenerator, SegmentIdGenerator::staleSegmentCount)
                .description("被丢弃的倒序号段数")
                .register(registry);

        Gauge.builder("shortlink.bloom.version", bloomHolder, BloomFilterHolder::version)
                .description("当前布隆过滤器版本号")
                .register(registry);
    }
}

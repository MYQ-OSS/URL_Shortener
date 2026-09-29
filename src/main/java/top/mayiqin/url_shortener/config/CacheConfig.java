package top.mayiqin.url_shortener.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import top.mayiqin.url_shortener.link.service.CacheInvalidateListener;

import java.time.Duration;

@Configuration
public class CacheConfig {

    /**
     * L1 本地缓存。
     *
     * <p><b>它是"高并发读"的真正解法</b>，也是热点短链问题的唯一出路：
     * 某个短链突然爆火时，Redis Cluster 下这个 key 只落在一个 slot / 一个节点上，
     * <b>横向扩容 Redis 完全无效</b>。只有本地缓存能把流量吃在应用层。
     *
     * <p>参数取舍：
     * <ul>
     *   <li>{@code maximumSize} 默认 <b>100_000</b>，这个值是算出来的不是拍的。
     *       Zipf α=1.0 / N=1000万 时（数值计算见 ADR-012）：
     *       <pre>
     *          10,000 条 -> L1 覆盖 58.6%,  2.4 MB
     *         100,000 条 -> L1 覆盖 72.4%, 23.8 MB   &lt;-- 多 21MB 换 +13.8pp，划算
     *         500,000 条 -> L1 覆盖 82.1%,  119 MB   &lt;-- 边际收益明显递减
     *       </pre>
     *       期望延迟 207us -> 138us（-33%）。
     *       <p><b>顺带一个必须分清的概念</b>：上面说的是 <b>L1 命中率</b>，
     *       不等于"整体缓存命中率"。后者包含 Redis L2（Redis 装得下全部 1000 万条），
     *       稳态下几乎不落 DB，所以整体命中率是 99.9%+。
     *       简历和面试里混着说会被问穿，必须分开报。
     *       <p>另外 Caffeine 用 W-TinyLFU（内含 Frequency Sketch）淘汰，
     *       本身就有热点倾向性，等于自带了一层轻量的热点探测。</li>
     *   <li>{@code expireAfterWrite(5s)} —— Redis 可用但 Pub/Sub 广播丢失时，
     *       本地实例最晚 5 秒重新从 L2 读取；Redis 失效期间的旧 L2 值由持久消息补偿清除。</li>
     *   <li>{@code recordStats()} —— 命中率要能被 Prometheus 抓到，否则没法证明缓存有效。</li>
     * </ul>
     */
    @Bean
    public Cache<String, String> localCache(
            @Value("${shortlink.cache.local-max-size:10000}") long maxSize,
            @Value("${shortlink.cache.local-ttl-seconds:5}") long ttlSeconds) {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .recordStats()
                .build();
    }

    // 布隆过滤器的创建与版本管理已移到 BloomFilterHolder / BloomFilterRebuilder，
    // 因为它需要支持"双 BF 滚动重建"（见 ADR-004）：
    //   - BF 不支持删除位，删短链后假阳性率单调上升，必须定时全量重建
    //   - 重建绝不能"原地清空后重填"，那会造成全站短链集体 404 的窗口
    //   - 所以要"写新的 -> 切指针 -> 删旧的"，而这需要一个持有当前版本的组件
    //
    // 容量与内存（面试爱问"占多少内存"）：
    //   每元素比特数 m/n = -ln(p) / (ln2)^2      哈希函数个数 k = (m/n) * ln2
    //   n=1000万, p=0.01%  ->  19.17 bit,  22.9 MiB, k=13   <-- 本项目
    //   n=1亿,    p=0.01%  ->  19.17 bit, 228.5 MiB, k=13
    //   n=1亿,    p=1%     ->   9.59 bit, 114.3 MiB, k=7

    /** 订阅本地缓存失效广播。 */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory factory, CacheInvalidateListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(listener, new ChannelTopic(CacheInvalidateListener.TOPIC));
        return container;
    }
}

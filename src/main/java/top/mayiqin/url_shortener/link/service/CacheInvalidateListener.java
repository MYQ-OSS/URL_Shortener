package top.mayiqin.url_shortener.link.service;

import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 本地缓存失效广播的接收端。
 *
 * <p><b>为什么需要它</b>：Redis 缓存好清（DEL），但每个应用实例的 Caffeine 是各自独立的。
 * 封禁一个短链后，如果不广播，其他实例最多还会继续跳转 TTL 秒。
 *
 * <p><b>⚠️ 但它不能单独用</b>：Redis Pub/Sub 是 fire-and-forget，<b>不保证送达</b>
 * （订阅端正在重连时消息就丢了，而且没有重试）。所以必须搭配 Caffeine 的短 TTL：
 * <ul>
 *   <li>广播 → 99% 的情况 100ms 内生效</li>
 *   <li>短 TTL(5s) → 最坏情况 5 秒必定生效</li>
 * </ul>
 *
 * <p><b>面试时要把代价量化出来</b>，这是区分"做过"和"背过"的分水岭：
 * <blockquote>
 * "本地缓存 TTL 5 秒意味着封禁最多 5 秒后生效。我评估过：对钓鱼链接，
 * 5 秒内多放过的点击量在可接受范围。如果业务要求 0 延迟，就得放弃本地缓存，
 * 代价是 QPS 从 3.8w 掉到 1.2w。我选了 5 秒。"
 * </blockquote>
 *
 * <p>更强的方案是用 MQ 广播模式（RabbitMQ fanout）替代 Pub/Sub，可持久化可重试，
 * 代价是延迟略高、复杂度上升。见 ADR-008。
 */
@Slf4j
@Component
public class CacheInvalidateListener implements MessageListener {

    public static final String TOPIC = "shortlink:cache:invalidate";

    private final Cache<String, String> localCache;

    public CacheInvalidateListener(Cache<String, String> localCache) {
        this.localCache = localCache;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String shortUri = new String(message.getBody(), StandardCharsets.UTF_8)
                .replace("\"", "");
        localCache.invalidate(shortUri);
        log.debug("本地缓存已失效: {}", shortUri);
    }
}

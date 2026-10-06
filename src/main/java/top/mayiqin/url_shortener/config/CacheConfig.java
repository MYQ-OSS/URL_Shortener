package top.mayiqin.url_shortener.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import top.mayiqin.url_shortener.link.service.CacheInvalidateListener;

import java.time.Duration;

@Configuration
public class CacheConfig {
    /** 空字符串表示无密码，不应向无密码 Redis 发送 AUTH。非空密码必须原样保留。 */
    @Bean
    public RedissonAutoConfigurationCustomizer redisPasswordCustomizer() {
        return config -> {
            if ("".equals(config.getPassword())) {
                config.setPassword(null);
            }
        };
    }

    /** 有界 L1 缓存；短 TTL 兜底漏收失效广播，recordStats 用于观测实际命中率。 */
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

package top.mayiqin.url_shortener.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import top.mayiqin.url_shortener.common.RedisAvailability;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RateLimiterTest {
    @Test void outageUsesSeparateGlobalBudgetsAndSkipsRedisDuringCooldown() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("redis down"));
        var limiter = new RateLimiter(redis, new RedisAvailability(), 1, 1, true, 20, 10, 200, 100);
        assertTrue(limiter.tryAcquireCreate("a"));
        assertFalse(limiter.tryAcquireCreate("b"));
        assertTrue(limiter.tryAcquireRedirect("ip1", "Ab12Cd"));
        assertFalse(limiter.tryAcquireRedirect("ip2", "Ab12Ce"));
        verify(redis, times(1)).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }
    @Test void benchmarkSwitchBypassesAllRateLimiting() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        var limiter = new RateLimiter(redis, new RedisAvailability(), 1, 1, false, 20, 10, 200, 100);
        assertTrue(limiter.tryAcquireCreate("a"));
        assertTrue(limiter.tryAcquireRedirect("ip", "Ab12Cd"));
        verifyNoInteractions(redis);
    }
}

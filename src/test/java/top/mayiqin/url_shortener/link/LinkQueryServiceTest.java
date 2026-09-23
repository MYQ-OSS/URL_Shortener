package top.mayiqin.url_shortener.link;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.web.server.ResponseStatusException;
import top.mayiqin.url_shortener.common.RedisAvailability;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.link.service.DbReadGuard;
import top.mayiqin.url_shortener.link.service.LinkQueryService;
import top.mayiqin.url_shortener.shortcode.ShortCodeGenerator;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LinkQueryServiceTest {
    Cache<String, String> local;
    StringRedisTemplate redis;
    ValueOperations<String, String> values;
    RedissonClient redisson;
    ShortCodeGenerator codes;
    LinkMapper mapper;
    LinkQueryService query;
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        local = Caffeine.newBuilder().build();
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        redisson = mock(RedissonClient.class);
        codes = mock(ShortCodeGenerator.class);
        mapper = mock(LinkMapper.class);
        query = new LinkQueryService(local, redis, redisson, codes, mapper,
                new DbReadGuard(2, 100), new RedisAvailability(), true, true);
    }
    @Test void localHitDoesNotUseRedisOrDatabase() {
        local.put("Ab12Cd", "https://example.com");
        assertEquals("https://example.com", query.resolve("Ab12Cd"));
        verifyNoInteractions(values, mapper, redisson);
    }
    @Test void redisFailureFallsBackAndCachesNegativeResultLocally() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("redis down"));
        assertNull(query.resolve("Ab12Cd"));
        assertNull(query.resolve("Ab12Cd"));
        verify(mapper, times(1)).selectOne(any());
        verify(values, times(1)).get(anyString());
    }
    @Test void databaseFailureDoesNotCauseSecondSqlAttempt() throws Exception {
        when(codes.mightExist("Ab12Cd")).thenReturn(true);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(500, 3000, TimeUnit.MILLISECONDS)).thenReturn(true);
        when(mapper.selectOne(any())).thenThrow(new IllegalStateException("db down"));
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> query.resolve("Ab12Cd"));
        assertEquals(503, error.getStatusCode().value());
        verify(mapper, times(1)).selectOne(any());
    }
    @Test void bloomNegativeDoesNotReachDatabase() {
        assertNull(query.resolve("Ab12Cd"));
        verifyNoInteractions(mapper);
    }
    @Test void invalidationAlwaysClearsLocalEvenIfRedisIsDown() {
        local.put("Ab12Cd", "https://example.com");
        when(redis.delete(anyString())).thenThrow(new IllegalStateException("redis down"));
        query.invalidate("Ab12Cd");
        assertNull(local.getIfPresent("Ab12Cd"));
    }
    @Test void newlyCreatedLinkRemovesLocalNegativeCacheAndBroadcasts() {
        local.put("Ab12Cd", "");
        query.warmUp("Ab12Cd", "https://example.com");
        assertNull(local.getIfPresent("Ab12Cd"));
        verify(redis).delete("link:null:Ab12Cd");
        verify(redis).convertAndSend("shortlink:cache:invalidate", "Ab12Cd");
    }
}

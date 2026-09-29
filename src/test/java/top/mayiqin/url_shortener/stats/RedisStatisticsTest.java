package top.mayiqin.url_shortener.stats;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import top.mayiqin.url_shortener.common.RedisAvailability;
import top.mayiqin.url_shortener.ratelimit.RateLimiter;
import top.mayiqin.url_shortener.stats.mq.StatsConsumer;
import top.mayiqin.url_shortener.stats.mq.StatsKeys;
import top.mayiqin.url_shortener.stats.service.StatsFlushService;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 启动独占随机端口、随机密码的临时 Redis，不连接开发环境实例。 */
@EnabledIfEnvironmentVariable(named = "SHORTLINK_TEST_REDIS_EXECUTABLE", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisStatisticsTest {
    @TempDir static Path directory;
    Process process;
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;

    @BeforeAll void startRedis() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        String password = UUID.randomUUID().toString();
        process = new ProcessBuilder(System.getenv("SHORTLINK_TEST_REDIS_EXECUTABLE"),
                "--bind", "127.0.0.1", "--port", String.valueOf(port),
                "--requirepass", password, "--save", "", "--appendonly", "no",
                "--dir", ".").directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("redis.log").toFile()).start();
        await().atMost(Duration.ofSeconds(10)).until(() -> {
            if (!process.isAlive()) throw new IllegalStateException("临时 Redis 启动失败: "
                    + java.nio.file.Files.readString(directory.resolve("redis.log")));
            try (var socket = new Socket("127.0.0.1", port)) { return true; }
            catch (Exception e) { return false; }
        });
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", port);
        configuration.setPassword(password);
        factory = new LettuceConnectionFactory(configuration);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }
    @AfterAll void stopRedis() throws Exception {
        if (factory != null) factory.destroy();
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        }
    }
    @BeforeEach void clearOwnedTestDatabase() {
        try (var connection = factory.getConnection()) { connection.serverCommands().flushDb(); }
    }
    private void event(String id) {
        new StatsConsumer(redis).onMessage("{\"msgId\":\"" + id + "\",\"shortUri\":\"Ab12Cd\","
                + "\"ip\":\"203.0.113.5\",\"userAgent\":\"test\",\"referer\":null,\"timestamp\":0}");
    }
    @Test void duplicateEventCountsOnlyOnceAndSetsDirtyMarker() {
        event("one"); event("one");
        assertEquals("1", redis.opsForValue().get(StatsKeys.pv("1970-01-01", 8, "Ab12Cd")));
        assertEquals(1, redis.opsForHyperLogLog().size(StatsKeys.uv("1970-01-01", "Ab12Cd")));
        assertTrue(redis.opsForSet().isMember(StatsKeys.DIRTY, "1970-01-01|8|Ab12Cd"));
    }
    @Test void claimSurvivesRetryAndDoesNotStealNewArrivals() {
        event("one");
        var claim = new DefaultRedisScript<String>();
        claim.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/claim_stats.lua")));
        claim.setResultType(String.class);
        List<String> keys = List.of(StatsKeys.DIRTY, StatsKeys.PENDING_BATCH);
        String payload = redis.execute(claim, keys, "batch-a", "500");
        event("two");
        assertEquals(payload, redis.execute(claim, keys, "batch-b", "500"));
        assertEquals("1", redis.opsForValue().get(StatsKeys.pv("1970-01-01", 8, "Ab12Cd")));
        var ack = new DefaultRedisScript<Long>();
        ack.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/ack_stats.lua")));
        ack.setResultType(Long.class);
        assertEquals(0L, redis.execute(ack, List.of(StatsKeys.PENDING_BATCH), "stale batch"));
        assertEquals(payload, redis.opsForValue().get(StatsKeys.PENDING_BATCH));
        assertEquals(1L, redis.execute(ack, List.of(StatsKeys.PENDING_BATCH), payload));
        assertTrue(redis.execute(claim, keys, "batch-b", "500").contains("batch-b"));
    }
    @Test void realLuaRateLimiterHonorsIndependentKeyBudgets() {
        var limiter = new RateLimiter(redis, new RedisAvailability(), 1, 1, true, 1, 1, 1, 1);
        assertTrue(limiter.tryAcquireCreate("one"));
        assertFalse(limiter.tryAcquireCreate("one"));
        assertTrue(limiter.tryAcquireCreate("two"));
        assertTrue(limiter.tryAcquireRedirect("ip", "Ab12Cd"));
        assertFalse(limiter.tryAcquireRedirect("ip", "Ab12Cd"));
    }
    @Test void cacheVersionPreventsLateReaderFromRestoringDisabledLink() {
        var write = new DefaultRedisScript<Long>();
        write.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/write_link_cache.lua")));
        write.setResultType(Long.class);
        var invalidate = new DefaultRedisScript<Long>();
        invalidate.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/invalidate_link_cache.lua")));
        invalidate.setResultType(Long.class);
        List<String> writeKeys = List.of("link:ver:Ab12Cd", "link:uri:Ab12Cd", "link:null:Ab12Cd");
        assertEquals(1L, redis.execute(write, writeKeys, "0", "v2-test", "30000"));
        assertEquals(1L, redis.execute(invalidate, writeKeys, "shortlink:cache:invalidate", "Ab12Cd"));
        assertNull(redis.opsForValue().get("link:uri:Ab12Cd"));
        // 请求从 DB 读到旧值的时刻早于失效脚本；迟到的写入必须被版本拒绝。
        assertEquals(0L, redis.execute(write, writeKeys, "0", "stale-url", "30000"));
        assertNull(redis.opsForValue().get("link:uri:Ab12Cd"));
        assertEquals(1L, redis.execute(write, writeKeys, "1", "fresh-url", "30000"));
        assertEquals("fresh-url", redis.opsForValue().get("link:uri:Ab12Cd"));
    }
    @Test void committedBatchRetryDoesNotRepeatPvAfterClickUpdateFailure() throws Exception {
        event("one");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        doAnswer(call -> {
            Consumer<TransactionStatus> action = call.getArgument(0);
            action.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        when(jdbc.update(startsWith("INSERT IGNORE"), any(Object[].class))).thenReturn(1, 0);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(1L);
        when(jdbc.update(startsWith("UPDATE t_link"), any(Object[].class)))
                .thenThrow(new IllegalStateException("click shard down")).thenReturn(1);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        StatsFlushService flush = new StatsFlushService(redis, redisson, jdbc, tx);
        flush.flush();
        assertNotNull(redis.opsForValue().get(StatsKeys.PENDING_BATCH));
        flush.flush();
        assertNull(redis.opsForValue().get(StatsKeys.PENDING_BATCH));
        verify(jdbc, times(1)).batchUpdate(anyString(), anyList());
        verify(jdbc, times(2)).update(startsWith("UPDATE t_link"), any(Object[].class));
    }
}

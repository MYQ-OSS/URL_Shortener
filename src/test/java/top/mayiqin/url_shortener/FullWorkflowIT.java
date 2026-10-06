package top.mayiqin.url_shortener;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import top.mayiqin.url_shortener.link.LinkValidity;
import top.mayiqin.url_shortener.link.service.CacheInvalidationRetryJob;
import top.mayiqin.url_shortener.stats.service.StatsFlushService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** 独立容器验收：真实 HTTP + MySQL + Redis + RabbitMQ，不接触开发数据库。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "SHORTLINK_INTEGRATION_TESTS", matches = "true")
class FullWorkflowIT {
    private static final String API_TOKEN = "it-api-token-012345678901234567890";
    private static final String ADMIN_TOKEN = "it-admin-token-012345678901234567890";

    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("shortlink").withUsername("shortlink").withPassword("shortlink")
            .withInitScript("schema.sql");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine")
            .withUser("integration", "integration");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "integration");
        registry.add("spring.rabbitmq.password", () -> "integration");
        registry.add("shortlink.admin.token", () -> ADMIN_TOKEN);
        registry.add("shortlink.auth.tokens.default", () -> API_TOKEN);
        registry.add("shortlink.risk.block-private-network", () -> false);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired StatsFlushService statsFlush;
    @Autowired CacheInvalidationRetryJob cacheRetry;
    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> request(String method, String path, String body, boolean admin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15))
                .header(admin ? "X-Admin-Token" : "Authorization", admin ? ADMIN_TOKEN : "Bearer " + API_TOKEN);
        if (body != null) builder.header("Content-Type", "application/json");
        var request = builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String create(boolean expiring) throws Exception {
        String origin = "https://example.com/" + UUID.randomUUID();
        String end = expiring ? ",\"validType\":1,\"validEnd\":\""
                + LocalDateTime.now(LinkValidity.ZONE).plusSeconds(3) + "\"" : "";
        var response = request("POST", "/api/link", "{\"gid\":\"default\",\"originUrl\":\""
                + origin + "\"" + end + "}", false);
        assertEquals(200, response.statusCode(), response.body());
        return URI.create(response.body()).getPath();
    }

    @Test void createRedirectExpireManageAndAggregateStatistics() throws Exception {
        assertTrue(request("GET", "/admin/stats", null, true).body().contains("\"deliveryMode\":\"best-effort\""));
        String expiring = create(true);
        assertEquals(302, request("GET", expiring, null, false).statusCode());
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertEquals(410, request("GET", expiring, null, false).statusCode()));

        String permanent = create(false);
        assertEquals(302, request("GET", permanent, null, false).statusCode());
        String code = permanent.substring(1);
        // 不运行任何同步任务，创建成功后列表立即可读。
        assertTrue(request("GET", "/api/link/list?gid=default", null, false).body().contains(code));
        assertTrue(jdbc.queryForObject("SELECT id FROM t_link WHERE short_uri=?", Long.class, code) > 0);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
            statsFlush.flush();
            Long count = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(pv),0) FROM t_link_stats_daily WHERE short_uri=? AND hour=-1",
                    Long.class, code);
            assertNotNull(count);
            assertTrue(count > 0);
        });

        // Redis 暂停期间状态更新仍把待失效消息与主表写在同一 MySQL 事务。
        DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            assertEquals(204, request("POST", "/api/link/" + code + "/disable", null, false).statusCode());
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_local_message WHERE shard_key=? AND biz_type=? AND status=0",
                    Integer.class, code, CacheInvalidationRetryJob.BIZ_TYPE));
        } finally {
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            cacheRetry.retryPending();
            assertEquals(Boolean.FALSE, redis.hasKey("link:uri:" + code));
        });
        assertEquals(410, request("GET", permanent, null, false).statusCode());
        assertEquals(204, request("POST", "/api/link/" + code + "/enable", null, false).statusCode());
        assertEquals(302, request("GET", permanent, null, false).statusCode());

    }
}

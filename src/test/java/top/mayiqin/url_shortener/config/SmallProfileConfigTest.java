package top.mayiqin.url_shortener.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.*;

class SmallProfileConfigTest {
    @Test
    void smallProfileKeepsPoolsBoundedAndRedissonConfigurationParseable() throws Exception {
        var properties = new YamlPropertySourceLoader()
                .load("small", new ClassPathResource("application-small.yaml")).getFirst();
        assertEquals(8081, properties.getProperty("server.port"));
        assertEquals("127.0.0.1", properties.getProperty("server.address"));
        assertEquals(3, properties.getProperty("spring.datasource.hikari.maximum-pool-size"));
        assertEquals(512, properties.getProperty("shortlink.stats.max-pending"));
        assertEquals(2, properties.getProperty("shortlink.fallback.db-max-concurrent"));

        var yaml = (String) properties.getProperty("spring.redis.redisson.config");
        assertNotNull(yaml);
        var config = Config.fromYAML(yaml.replace("${SPRING_DATA_REDIS_PASSWORD}", "test-password"));
        assertTrue(config.isSingleConfig());
        assertEquals(2, config.getThreads());
        assertEquals(2, config.getNettyThreads());
        assertEquals("test-password", config.getPassword());
    }
}

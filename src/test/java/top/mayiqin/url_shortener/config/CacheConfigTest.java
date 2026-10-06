package top.mayiqin.url_shortener.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;

import static org.junit.jupiter.api.Assertions.*;

class CacheConfigTest {
    @Test void emptyPasswordDisablesAuthButNonEmptyPasswordsRemainUnchanged() {
        var config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        var customizer = new CacheConfig().redisPasswordCustomizer();
        config.setPassword("");
        customizer.customize(config);
        assertNull(config.getPassword());
        for (String password : new String[]{"test-password", " "}) {
            config.setPassword(password);
            customizer.customize(config);
            assertEquals(password, config.getPassword());
        }
    }

    @Test void otherRedisModesAreNotConvertedToSingleServer() {
        var config = new Config();
        config.useClusterServers().addNodeAddress("redis://127.0.0.1:6379");
        config.setPassword("");
        assertDoesNotThrow(() -> new CacheConfig().redisPasswordCustomizer().customize(config));
        assertFalse(config.isSingleConfig());
        assertNull(config.getPassword());
    }
}

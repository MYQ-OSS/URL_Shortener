package top.mayiqin.url_shortener;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring 上下文加载集成测试。
 * <p>测试 Spring 环境下各 Bean 的依赖注入与装配。
 */
@SpringBootTest
@ActiveProfiles("test")
class UrlShortenerApplicationTests {

    @Test
    @DisplayName("上下文测试：验证 Spring 基础 Bean 能正常注入")
    void contextLoads() {
        // 校验 Spring 上下文容器基础组件加载正常
    }
}

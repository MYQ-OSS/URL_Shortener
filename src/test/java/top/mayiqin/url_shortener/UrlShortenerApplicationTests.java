package top.mayiqin.url_shortener;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ⚠️ 暂时禁用。
 *
 * <p>{@code @SpringBootTest} 会启动完整 Spring 上下文，需要 MySQL + Redis + RabbitMQ 都在跑。
 * 现在 {@code mvn test} 会因为连不上中间件而失败，把三个真正有价值的纯逻辑测试
 * （{@code Base62CodecTest} / {@code FeistelCipherTest} / {@code SegmentIdGeneratorTest}）
 * 一起拖红。
 *
 * <p><b>什么时候放开</b>：Week 1 末尾，中间件跑起来、创建 + 跳转两个接口端到端通了之后，
 * 去掉 {@code @Disabled}。届时更好的做法是引入 Testcontainers，
 * 让测试自己拉起 MySQL/Redis 容器——这样 CI 里也能跑，不依赖本地环境。
 *
 * <p><b>顺带一个原则</b>：不要让"需要外部依赖的集成测试"和"纯逻辑单测"混在同一次
 * {@code mvn test} 里。前者慢且易碎，后者快且稳定。用 {@code @Tag} 或 Maven profile 分开，
 * 让"纯逻辑测试永远是绿的"——这样它才真的能当回归网。
 */
@Disabled("需要 MySQL/Redis/RabbitMQ 就绪后再放开，见类注释")
@SpringBootTest
class UrlShortenerApplicationTests {

    @Test
    void contextLoads() {
    }
}

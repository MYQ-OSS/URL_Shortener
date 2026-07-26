package top.mayiqin.url_shortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.mayiqin.url_shortener.idgen.DbSegmentStore;
import top.mayiqin.url_shortener.idgen.SegmentIdGenerator;

@Configuration
public class IdGeneratorConfig {

    /** destroyMethod 关掉预加载线程，避免应用停止时线程泄漏。 */
    @Bean(destroyMethod = "shutdown")
    public SegmentIdGenerator linkIdGenerator(DbSegmentStore store) {
        return new SegmentIdGenerator("short_link", store);
    }
}

package top.mayiqin.url_shortener.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用统计落库、缓存失效重试、过期清理与低频指标采集任务。 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

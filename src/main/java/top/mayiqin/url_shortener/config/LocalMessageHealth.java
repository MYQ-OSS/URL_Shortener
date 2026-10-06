package top.mayiqin.url_shortener.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.mapper.LocalMessageMapper;
import top.mayiqin.url_shortener.link.service.CacheInvalidationRetryJob;

import java.util.concurrent.atomic.AtomicLong;

/** 低频扫描缓存失效待办，避免每次指标采集都查询数据库。 */
@Slf4j
@Component
public class LocalMessageHealth {
    private final LocalMessageMapper mapper;
    private final AtomicLong pendingInvalidations = new AtomicLong();

    public LocalMessageHealth(LocalMessageMapper mapper, MeterRegistry registry) {
        this.mapper = mapper;
        Gauge.builder("shortlink.messages.cache_invalidation_pending", pendingInvalidations, AtomicLong::get)
                .register(registry);
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void refresh() {
        try {
            pendingInvalidations.set(mapper.selectCount(Wrappers.<LocalMessage>lambdaQuery()
                    .eq(LocalMessage::getBizType, CacheInvalidationRetryJob.BIZ_TYPE)
                    .eq(LocalMessage::getStatus, 0)));
        } catch (RuntimeException e) {
            log.warn("本地消息水位查询失败: {}", e.getMessage());
        }
    }
}

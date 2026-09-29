package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.mapper.LocalMessageMapper;

import java.time.LocalDateTime;
import java.util.List;

/** 状态更新与待失效消息同库提交，Redis 故障恢复后最终清除旧 L2 值。 */
@Slf4j
@Component
public class CacheInvalidationRetryJob {
    public static final String BIZ_TYPE = "LINK_CACHE_INVALIDATE";
    private final LocalMessageMapper mapper;
    private final LinkQueryService queryService;

    public CacheInvalidationRetryJob(LocalMessageMapper mapper, LinkQueryService queryService) {
        this.mapper = mapper;
        this.queryService = queryService;
    }

    public static LocalMessage pendingMessage(String shortUri) {
        LocalMessage message = new LocalMessage();
        message.setBizType(BIZ_TYPE);
        message.setShardKey(shortUri);
        message.setPayload(shortUri);
        message.setStatus(0);
        message.setRetryCount(0);
        message.setNextRetry(LocalDateTime.now());
        return message;
    }

    @Scheduled(fixedDelay = 5_000L, initialDelay = 5_000L)
    public void retryPending() {
        // 相同短码的多条状态变更消息均只做 DEL；并发重复执行是安全的。
        List<LocalMessage> pending = mapper.selectList(Wrappers.<LocalMessage>lambdaQuery()
                .eq(LocalMessage::getBizType, BIZ_TYPE)
                .eq(LocalMessage::getStatus, 0)
                .le(LocalMessage::getNextRetry, LocalDateTime.now())
                .last("LIMIT 200"));
        for (LocalMessage message : pending) {
            try {
                if (queryService.invalidate(message.getShardKey())) {
                    mapper.update(null, Wrappers.<LocalMessage>lambdaUpdate()
                            .eq(LocalMessage::getId, message.getId())
                            .eq(LocalMessage::getShardKey, message.getShardKey())
                            .eq(LocalMessage::getStatus, 0)
                            .set(LocalMessage::getStatus, 1));
                } else {
                    scheduleRetry(message);
                }
            } catch (RuntimeException e) {
                log.warn("缓存失效补偿失败，下一轮重试 {}: {}", message.getShardKey(), e.getMessage());
                scheduleRetry(message);
            }
        }
    }

    private void scheduleRetry(LocalMessage message) {
        mapper.update(null, Wrappers.<LocalMessage>lambdaUpdate()
                .eq(LocalMessage::getId, message.getId())
                .eq(LocalMessage::getShardKey, message.getShardKey())
                .eq(LocalMessage::getStatus, 0)
                .set(LocalMessage::getRetryCount, message.getRetryCount() + 1)
                .set(LocalMessage::getNextRetry, LocalDateTime.now().plusSeconds(5)));
    }
}

package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.link.mapper.LocalMessageMapper;

import java.util.List;

/** 状态更新与缓存失效意图同事务提交，Redis 故障后仍可重试清理旧缓存。 */
@Slf4j
@Service
public class LinkManageService {

    /** enable_status：0 启用 / 1 用户禁用 / 2 风控封禁。 */
    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED_BY_USER = 1;
    public static final int STATUS_BLOCKED_BY_RISK = 2;

    private final LinkMapper linkMapper;
    private final LinkQueryService queryService;
    private final LocalMessageMapper messageMapper;
    private final TransactionTemplate tx;

    public LinkManageService(LinkMapper linkMapper,
                             LinkQueryService queryService,
                             LocalMessageMapper messageMapper,
                             TransactionTemplate tx) {
        this.linkMapper = linkMapper;
        this.queryService = queryService;
        this.messageMapper = messageMapper;
        this.tx = tx;
    }

    /**
     * 修改短链的启用状态并让全部层级缓存失效。
     *
     * @return {@code true} 短链存在且已更新；{@code false} 短链不存在
     */
    public boolean updateStatus(String shortUri, String gid, int enableStatus) {
        Boolean updated = tx.execute(status -> {
            int rows = linkMapper.update(null, Wrappers.<Link>lambdaUpdate()
                .eq(Link::getShortUri, shortUri)
                .eq(Link::getGid, gid)
                .apply("CAST(gid AS BINARY) = CAST({0} AS BINARY)", gid)
                // 用户不能通过启用/禁用操作覆盖风控封禁。
                .ne(Link::getEnableStatus, STATUS_BLOCKED_BY_RISK)
                .eq(Link::getDelFlag, 0)
                .set(Link::getEnableStatus, enableStatus));
            if (rows == 0) return false;
            messageMapper.insert(CacheInvalidationRetryJob.pendingMessage(shortUri));
            return true;
        });
        if (!Boolean.TRUE.equals(updated)) {
            return false;
        }
        // DB 与消息已同库提交；实时失效失败时由待办消息在 Redis 恢复后补偿。
        if (queryService.invalidate(shortUri)) {
            try {
                messageMapper.update(null, Wrappers.<LocalMessage>lambdaUpdate()
                        .eq(LocalMessage::getShardKey, shortUri)
                        .eq(LocalMessage::getBizType, CacheInvalidationRetryJob.BIZ_TYPE)
                        .eq(LocalMessage::getStatus, 0)
                        .set(LocalMessage::getStatus, 1));
            } catch (RuntimeException e) {
                log.warn("缓存已失效，但本地消息标记失败，后台会安全重试: {}", e.getMessage());
            }
        }
        log.info("短链 {} 状态已更新为 {}，缓存失效已执行或待重试", shortUri, enableStatus);
        return true;
    }

    /** 单库按 gid 索引读取主表，创建后的记录无需等待异步索引同步。 */
    public List<Link> listByGid(String gid, int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        return linkMapper.selectList(Wrappers.<Link>lambdaQuery()
                .eq(Link::getGid, gid)
                .apply("CAST(gid AS BINARY) = CAST({0} AS BINARY)", gid)
                .eq(Link::getDelFlag, 0)
                .orderByDesc(Link::getId)
                .last("LIMIT " + capped));
    }
}

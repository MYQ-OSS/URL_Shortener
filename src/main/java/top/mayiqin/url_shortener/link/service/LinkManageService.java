package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.entity.LinkGoto;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.mapper.LinkGotoMapper;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.link.mapper.LocalMessageMapper;

import java.util.List;

/**
 * 短链管理操作：禁用 / 恢复。
 *
 * <h3>顺序必须是"先改 DB，再清缓存"（ADR-008）</h3>
 * 反过来（先清缓存后改 DB）有一个竞态窗口：缓存刚清掉，一个跳转请求 miss 后
 * 回源查到<b>还没改的</b> DB 旧值，把"启用"状态重新写回缓存——封禁就被顶掉了。
 * 先改 DB；缓存失效意图与状态更新同事务提交，Redis 失败后由任务补偿。
 *
 * <h3>封禁的生效时延（要能报出数字）</h3>
 * <pre>
 *   Redis L2         DEL 立即生效
 *   本地 L1（本实例） invalidate 立即生效
 *   本地 L1（其他实例）Pub/Sub 广播；广播丢失时靠 Caffeine 5s TTL 兜底
 *   Redis 故障期间     旧 L2 在恢复后由本地消息表重试清理，不能承诺固定秒数
 * </pre>
 */
@Slf4j
@Service
public class LinkManageService {

    /** enable_status：0 启用 / 1 用户禁用 / 2 风控封禁。 */
    public static final int STATUS_ENABLED = 0;
    public static final int STATUS_DISABLED_BY_USER = 1;
    public static final int STATUS_BLOCKED_BY_RISK = 2;

    private final LinkMapper linkMapper;
    private final LinkGotoMapper gotoMapper;
    private final LinkQueryService queryService;
    private final LocalMessageMapper messageMapper;
    private final TransactionTemplate tx;

    public LinkManageService(LinkMapper linkMapper,
                             LinkGotoMapper gotoMapper,
                             LinkQueryService queryService,
                             LocalMessageMapper messageMapper,
                             TransactionTemplate tx) {
        this.linkMapper = linkMapper;
        this.gotoMapper = gotoMapper;
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

    /**
     * 按 gid 列出短链（Week 4 两步查询，ADR-005）。
     *
     * <h3>为什么是两步而不直接查 t_link</h3>
     * t_link 按 short_uri 分片，{@code WHERE gid=?} 没有分片键 → 广播 16 个分片
     * 再归并排序，管理链路一上来就把集群打散。改走异构索引：
     * <pre>
     *   第 1 步：t_link_goto（按 gid 分片）WHERE gid=?  → 单库单表命中
     *   第 2 步：t_link WHERE short_uri IN (...)         → 按分片键路由，逐条单分片
     * </pre>
     * 两步都没有广播。代价是最终一致：刚创建的短链要等补偿任务同步进 goto
     * 才会出现在列表里（秒级延迟，管理场景可接受）。
     *
     * @param limit 最多返回条数，上限 100
     */
    public List<Link> listByGid(String gid, int limit) {
        int capped = Math.max(1, Math.min(limit, 100));
        List<LinkGoto> gotos = gotoMapper.selectList(Wrappers.<LinkGoto>lambdaQuery()
                .eq(LinkGoto::getGid, gid)
                .apply("CAST(gid AS BINARY) = CAST({0} AS BINARY)", gid)
                .orderByDesc(LinkGoto::getId)
                .last("LIMIT " + capped));
        if (gotos.isEmpty()) {
            return List.of();
        }
        List<String> uris = gotos.stream().map(LinkGoto::getShortUri).toList();
        // IN 的是分片键：ShardingSphere 按每个 short_uri 路由，不是广播
        return linkMapper.selectList(Wrappers.<Link>lambdaQuery()
                .in(Link::getShortUri, uris)
                .eq(Link::getGid, gid)
                .apply("CAST(gid AS BINARY) = CAST({0} AS BINARY)", gid)
                .eq(Link::getDelFlag, 0)
                .orderByDesc(Link::getId));
    }
}

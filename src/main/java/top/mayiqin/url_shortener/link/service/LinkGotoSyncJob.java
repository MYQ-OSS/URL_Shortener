package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.mayiqin.url_shortener.link.entity.LinkGoto;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.mapper.LinkGotoMapper;
import top.mayiqin.url_shortener.link.mapper.LocalMessageMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地消息表补偿任务：t_local_message → t_link_goto（ADR-005 的最终一致执行者）。
 *
 * <h3>链路全景</h3>
 * <pre>
 *   创建短链 ──同库事务──► t_link + t_local_message(status=0)
 *                              │
 *   【本任务】每 5s 扫 status=0 且到期的消息 ──► 写 t_link_goto ──► 标记 status=1
 *                              │ 失败
 *                              └──► retry_count++、指数退避，超限置 2 等人工
 * </pre>
 *
 * <h3>三个设计点</h3>
 * <ol>
 *   <li><b>幂等靠 t_link_goto 的唯一索引 uk_gid_uri 兜底</b>：消息重复投递
 *       （"upsert 成功但标记前崩溃"的小概率窗口）再插一次会撞唯一键，
 *       捕获后直接标记成功——重复消费不产生脏数据。</li>
 *   <li><b>标记消息时 WHERE 带 shard_key</b>：UPDATE ... WHERE id=? AND shard_key=?
 *       能路由到单分片；只写 id 会退化成 16 分片广播。</li>
 *   <li><b>扫描本身是广播</b>（按 status/next_retry 查，没有分片键）——
 *       这是管理面低频路径，可接受；换来的是不需要额外的"待办消息指针"。
 *       多实例部署时用 Redisson 锁保证只有一个实例在扫。</li>
 * </ol>
 */
@Slf4j
@Component
public class LinkGotoSyncJob {

    private static final String LOCK_KEY = "sl:msg:sync:lock";
    private static final String BIZ_TYPE = "LINK_GOTO_SYNC";
    /** 每轮最多处理多少条，防止积压时一轮跑到天荒地老。 */
    private static final int BATCH = 200;
    /** 超过这个次数置为失败态（status=2），等人工介入。 */
    private static final int MAX_RETRY = 5;

    private final LocalMessageMapper messageMapper;
    private final LinkGotoMapper gotoMapper;
    private final RedissonClient redisson;
    private final ObjectMapper json = JsonMapper.builder().build();

    public LinkGotoSyncJob(LocalMessageMapper messageMapper,
                           LinkGotoMapper gotoMapper,
                           RedissonClient redisson) {
        this.messageMapper = messageMapper;
        this.gotoMapper = gotoMapper;
        this.redisson = redisson;
    }

    @Scheduled(fixedDelay = 5_000L, initialDelay = 15_000L)
    public void compensate() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked;
        try {
            // waitTime=0：拿不到说明别的实例在跑，本轮直接放弃
            locked = lock.tryLock(0, 25, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            List<LocalMessage> pending = messageMapper.selectList(Wrappers.<LocalMessage>lambdaQuery()
                    .eq(LocalMessage::getBizType, BIZ_TYPE)
                    .eq(LocalMessage::getStatus, 0)
                    .le(LocalMessage::getNextRetry, LocalDateTime.now())
                    .last("LIMIT " + BATCH));
            if (pending.isEmpty()) {
                return;
            }
            int ok = 0;
            for (LocalMessage msg : pending) {
                if (handleOne(msg)) {
                    ok++;
                }
            }
            log.info("本地消息补偿一轮完成：{} 条成功 / {} 条", ok, pending.size());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** @return true 消息已处理完毕（成功或幂等命中），false 进入重试/失败态 */
    private boolean handleOne(LocalMessage msg) {
        try {
            JsonNode payload = json.readTree(msg.getPayload());
            LinkGoto g = new LinkGoto();
            g.setGid(payload.get("gid").asString());
            g.setShortUri(payload.get("shortUri").asString());
            try {
                gotoMapper.insert(g);
            } catch (DuplicateKeyException dup) {
                // uk_gid_uri 兜底：重复投递不产生脏数据，直接当成功
                log.debug("goto 索引已存在（幂等命中）: gid={} uri={}", g.getGid(), g.getShortUri());
            }
            markDone(msg);
            return true;
        } catch (RuntimeException e) {
            markRetry(msg, e);
            return false;
        }
    }

    /** WHERE 带 shard_key：路由到单分片，不广播。 */
    private void markDone(LocalMessage msg) {
        messageMapper.update(null, Wrappers.<LocalMessage>lambdaUpdate()
                .eq(LocalMessage::getId, msg.getId())
                .eq(LocalMessage::getShardKey, msg.getShardKey())
                .set(LocalMessage::getStatus, 1));
    }

    /** 指数退避：5s、10s、20s、40s、80s，之后置失败态等人工。 */
    private void markRetry(LocalMessage msg, RuntimeException cause) {
        int next = msg.getRetryCount() + 1;
        int newStatus = next >= MAX_RETRY ? 2 : 0;
        long backoffSeconds = 5L * (1L << Math.min(msg.getRetryCount(), 10));
        messageMapper.update(null, Wrappers.<LocalMessage>lambdaUpdate()
                .eq(LocalMessage::getId, msg.getId())
                .eq(LocalMessage::getShardKey, msg.getShardKey())
                .set(LocalMessage::getRetryCount, next)
                .set(LocalMessage::getStatus, newStatus)
                .set(LocalMessage::getNextRetry, LocalDateTime.now().plusSeconds(backoffSeconds)));
        log.warn("本地消息补偿失败（第 {} 次）: {} — {}", next, msg.getPayload(), cause.getMessage());
    }
}

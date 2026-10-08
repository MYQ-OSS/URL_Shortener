package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.LinkValidity;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 过期短链接定期清理与缓存清理任务。
 *
 * <p><b>设计考量：</b>
 * 1. 业务短链在 valid_type = 1 且 valid_end < 当前时间 时已失效（302 跳转链路已拦截返回 410）。
 * 2. 避免数据库与索引空间无休止膨胀，采用每日定时轮询逻辑清理已过期的冷短链数据。
 * 3. 基于 Redisson 分布式锁控制多实例单节点运行，并采用分批 LIMIT 机制防止大表锁表。
 * 4. 调用方给定时间预算：人工触发走 HTTP，必须在客户端超时前返回，预算用尽即返回
 *    {@link CleanupStatus#TRUNCATED}；定时任务预算接近锁租期，正常数据量下一次跑完。
 */
@Slf4j
@Component
public class ExpiredLinkCleanupJob {

    private static final String LOCK_KEY = "shortlink:lock:expired-cleanup";
    private static final int BATCH_SIZE = 500;
    /** 锁租期；时间预算必须小于它，否则租期到期后另一实例可能并发进入。 */
    private static final long LOCK_LEASE_MINUTES = 30;
    /** 定时任务预算：略小于锁租期，让凌晨那次调度能一次跑完。 */
    private static final long SCHEDULED_TIME_BUDGET_MILLIS = TimeUnit.MINUTES.toMillis(25);

    private final LinkMapper linkMapper;
    private final RedissonClient redisson;
    private final LinkQueryService queryService;

    public ExpiredLinkCleanupJob(LinkMapper linkMapper,
                                RedissonClient redisson,
                                LinkQueryService queryService) {
        this.linkMapper = linkMapper;
        this.redisson = redisson;
        this.queryService = queryService;
    }

    /** 每日凌晨 02:00 自动跑清理逻辑。测试/人工可触发 cleanupExpiredLinks(预算)。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void runScheduledCleanup() {
        CleanupResult result = cleanupExpiredLinks(SCHEDULED_TIME_BUDGET_MILLIS);
        if (result.moreRemaining()) {
            log.warn("定时清理未跑完（{}），本次处理 {} 条，剩余数据等待下一次调度或人工触发",
                    result.status(), result.cleanedRows());
        }
    }

    /**
     * 清理已过期的短链。
     *
     * @param timeBudgetMillis 本次调用的时间预算。超出后立即停止并返回
     *                         {@link CleanupStatus#TRUNCATED}，使人工触发的 HTTP 请求能在客户端
     *                         超时之前返回——否则客户端先超时报错，服务端却仍在后台继续跑，
     *                         两边对"这次到底做没做"的认知会分叉。
     */
    public CleanupResult cleanupExpiredLinks(long timeBudgetMillis) {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean acquired = false;
        long totalCleaned = 0;
        boolean truncated = false;
        try {
            acquired = lock.tryLock(0, LOCK_LEASE_MINUTES, TimeUnit.MINUTES);
            if (!acquired) {
                log.info("分布式锁被其他实例持有，跳过本次过期短链清理任务");
                return new CleanupResult(CleanupStatus.SKIPPED_LOCK_HELD, 0);
            }

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeBudgetMillis);
            log.info("开始执行过期短链接定期清理任务，时间预算 {} 毫秒", timeBudgetMillis);
            LocalDateTime now = LocalDateTime.now(LinkValidity.ZONE);

            while (true) {
                if (System.nanoTime() - deadline >= 0) {
                    truncated = true;
                    break;
                }
                // 查出批次已过期的短链
                List<Link> expiredList = linkMapper.selectList(Wrappers.<Link>lambdaQuery()
                        .eq(Link::getValidType, 1)
                        .and(condition -> condition.isNull(Link::getValidEnd)
                                .or().le(Link::getValidEnd, now))
                        .eq(Link::getDelFlag, 0)
                        .last("LIMIT " + BATCH_SIZE));

                if (expiredList.isEmpty()) {
                    break;
                }

                for (Link link : expiredList) {
                    // 逐行再查一次预算：每行还要做一次缓存失效与广播，只按批次检查会明显超支。
                    if (System.nanoTime() - deadline >= 0) {
                        truncated = true;
                        break;
                    }
                    // UPDATE 带 short_uri 分片键；命中后统一失效 L1、L2 和空值缓存。
                    int updated = linkMapper.update(null, Wrappers.<Link>lambdaUpdate()
                            .eq(Link::getShortUri, link.getShortUri())
                            .eq(Link::getDelFlag, 0)
                            .set(Link::getDelFlag, 1));
                    if (updated > 0) {
                        queryService.invalidate(link.getShortUri());
                        totalCleaned += updated;
                    }
                }
                log.info("已完成 {} 条过期短链的清理与缓存擦除", totalCleaned);

                if (truncated || expiredList.size() < BATCH_SIZE) {
                    break;
                }
            }

            log.info("过期短链接清理任务{}，累计清理 {} 条记录",
                    truncated ? "因时间预算用尽提前结束" : "完成", totalCleaned);
            return new CleanupResult(truncated ? CleanupStatus.TRUNCATED : CleanupStatus.COMPLETED, totalCleaned);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("过期短链清理任务被中断，已清理 {} 条", totalCleaned);
            return new CleanupResult(CleanupStatus.INTERRUPTED, totalCleaned);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 清理结局：把"跑完了""没抢到锁""被中断""预算用尽仍有剩余"区分开，避免 0 的歧义。 */
    public enum CleanupStatus { COMPLETED, TRUNCATED, SKIPPED_LOCK_HELD, INTERRUPTED }

    /**
     * @param status      本次执行结局
     * @param cleanedRows 实际逻辑删除的行数；提前结束或被中断时是已完成的部分
     */
    public record CleanupResult(CleanupStatus status, long cleanedRows) {
        /** 除完整跑完以外的结局都意味着可能仍有待清理数据，调用方应稍后重试。 */
        public boolean moreRemaining() {
            return status != CleanupStatus.COMPLETED;
        }
    }
}

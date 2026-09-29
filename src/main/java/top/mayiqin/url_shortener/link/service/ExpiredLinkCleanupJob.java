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
 */
@Slf4j
@Component
public class ExpiredLinkCleanupJob {

    private static final String LOCK_KEY = "shortlink:lock:expired-cleanup";
    private static final int BATCH_SIZE = 500;

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

    /** 每日凌晨 02:00 自动跑清理逻辑。测试/人工可触发 cleanupExpiredLinks()。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void runScheduledCleanup() {
        cleanupExpiredLinks();
    }

    public long cleanupExpiredLinks() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, 30, TimeUnit.MINUTES);
            if (!acquired) {
                log.info("分布式锁被其他实例持有，跳过本次过期短链清理任务");
                return 0L;
            }

            log.info("开始执行过期短链接定期清理任务...");
            long totalCleaned = 0;
            LocalDateTime now = LocalDateTime.now(LinkValidity.ZONE);

            while (true) {
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

                if (expiredList.size() < BATCH_SIZE) {
                    break;
                }
            }

            log.info("过期短链接清理任务完成，累计清理 {} 条记录", totalCleaned);
            return totalCleaned;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("过期短链清理任务被中断", e);
            return 0L;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}

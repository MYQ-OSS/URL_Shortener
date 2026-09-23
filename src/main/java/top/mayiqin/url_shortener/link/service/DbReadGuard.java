package top.mayiqin.url_shortener.link.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import top.mayiqin.url_shortener.ratelimit.LocalTokenBucket;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 约束所有跳转回源路径，包括 Redis 故障和分布式锁竞争失败。 */
@Component
public class DbReadGuard {
    private final Semaphore permits;
    private final LocalTokenBucket budget;

    public DbReadGuard(@Value("${shortlink.fallback.db-max-concurrent:8}") int maxConcurrent,
                       @Value("${shortlink.fallback.db-qps:200}") int qps) {
        if (maxConcurrent <= 0) throw new IllegalArgumentException("回源并发数必须为正数");
        permits = new Semaphore(maxConcurrent);
        budget = new LocalTokenBucket(qps, qps);
    }

    public <T> T execute(Supplier<T> query) {
        if (!permits.tryAcquire()) throw unavailable();
        try {
            if (!budget.tryAcquire()) throw unavailable();
            return query.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            // 不把 DB 异常误认成 Redis 故障，再执行一次相同 SQL。
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "数据库暂不可用", e);
        } finally {
            permits.release();
        }
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "回源繁忙，请稍后重试");
    }
}

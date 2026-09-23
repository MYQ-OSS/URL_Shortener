package top.mayiqin.url_shortener.stats.service;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import top.mayiqin.url_shortener.stats.mq.StatsKeys;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 原子认领增量 -> MySQL 批次回执与统计同事务 -> 幂等更新点击数 -> 删除待处理批次。
 * 中途宕机仍有待处理批次；DB 提交后重试由批次主键阻止重复累计。
 */
@Slf4j
@Service
public class StatsFlushService {
    private static final String UPSERT = """
            INSERT INTO t_link_stats_daily (short_uri, stat_date, hour, pv, uv, uip)
            VALUES (?, ?, ?, ?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE pv = t_link_stats_daily.pv + new.pv,
            uv = GREATEST(t_link_stats_daily.uv, new.uv), uip = GREATEST(t_link_stats_daily.uip, new.uip)""";
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DefaultRedisScript<String> claim = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> acknowledge = new DefaultRedisScript<>();
    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicLong flushedRows = new AtomicLong();
    private volatile long lastFlushAt;

    public StatsFlushService(StringRedisTemplate redis, RedissonClient redisson,
                             JdbcTemplate jdbc, TransactionTemplate tx) {
        this.redis = redis;
        this.redisson = redisson;
        this.jdbc = jdbc;
        this.tx = tx;
        claim.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/claim_stats.lua")));
        claim.setResultType(String.class);
        acknowledge.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/ack_stats.lua")));
        acknowledge.setResultType(Long.class);
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void flush() {
        RLock lock = redisson.getLock("sl:st:flush:lock");
        try {
            // 不指定 leaseTime，使用 watchdog，避免慢批次超过 25 秒后并行执行。
            if (!lock.tryLock(0, TimeUnit.SECONDS)) return;
            try {
                for (int i = 0; i < 40; i++) {
                    String payload = redis.execute(claim, List.of(StatsKeys.DIRTY, StatsKeys.PENDING_BATCH),
                            UUID.randomUUID().toString(), "500");
                    if (payload == null) break;
                    flushBatch(json.readValue(payload, Batch.class));
                    redis.execute(acknowledge, List.of(StatsKeys.PENDING_BATCH), payload);
                }
                lastFlushAt = System.currentTimeMillis();
            } finally {
                if (lock.isHeldByCurrentThread()) lock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("统计落库失败，待处理批次保留以便重试", e);
        }
    }

    private void flushBatch(Batch batch) {
        Map<String, long[]> daily = new LinkedHashMap<>();
        List<Object[]> rows = new ArrayList<>();
        Set<String> uris = new LinkedHashSet<>();
        for (Row row : batch.rows()) {
            uris.add(row.uri());
            if (row.pv() <= 0) continue;
            rows.add(new Object[]{row.uri(), row.date(), row.hour(), row.pv(), 0L, 0L});
            daily.computeIfAbsent(row.date() + "|" + row.uri(), key -> new long[1])[0] += row.pv();
        }
        daily.forEach((key, counts) -> {
            int separator = key.indexOf('|');
            String date = key.substring(0, separator), uri = key.substring(separator + 1);
            rows.add(new Object[]{uri, date, -1, counts[0],
                    redis.opsForHyperLogLog().size(StatsKeys.uv(date, uri)),
                    redis.opsForHyperLogLog().size(StatsKeys.uip(date, uri))});
        });
        // 回执和统计表都位于 ds_0；不能把跨 short_uri 分片的 t_link 更新塞进该事务。
        tx.executeWithoutResult(status -> {
            int inserted = jdbc.update("INSERT IGNORE INTO t_stats_flush_batch (batch_id) VALUES (?)", batch.id());
            if (inserted != 0 && !rows.isEmpty()) {
                jdbc.batchUpdate(UPSERT, rows);
                flushedRows.addAndGet(rows.size());
            }
        });
        for (String uri : uris) {
            Long total = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(pv), 0) FROM t_link_stats_daily WHERE short_uri = ? AND hour = -1",
                    Long.class, uri);
            jdbc.update("UPDATE t_link SET click_num = GREATEST(click_num, ?) WHERE short_uri = ?", total, uri);
        }
    }

    public long flushedRows() { return flushedRows.get(); }
    public long lastFlushAt() { return lastFlushAt; }
    public record Row(String date, int hour, String uri, long pv) { }
    public record Batch(String id, List<Row> rows) { }
}

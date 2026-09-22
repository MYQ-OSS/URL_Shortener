package top.mayiqin.url_shortener.stats.service;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import top.mayiqin.url_shortener.stats.mq.StatsKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统计落库：Redis 计数 → MySQL 批量 upsert（最终一致的最后一跳）。
 *
 * <h3>整条链路的位置</h3>
 * <pre>
 *   跳转 → MQ → 消费端(Redis INCR/PFADD + dirty 集合) → 【本任务】30s 批量落库
 * </pre>
 *
 * <h3>三个必须讲清的设计点</h3>
 * <ol>
 *   <li><b>SPOP dirty 集合而不是 SCAN 全库</b>：消费端把"哪些 (日期,小时,短码) 有新数据"
 *       记进一个 Set，落库只处理这些成员。SCAN 在千万级 key 时是灾难。</li>
 *   <li><b>PV 用 GETDEL 取增量</b>：取出即清零，下一轮从 0 累计。DB 侧
 *       {@code pv = pv + 增量} 累加。<b>UV/UIP 相反，是覆盖语义</b>——HyperLogLog
 *       给的是当日全量基数，落库用 {@code uv = new.uv} 覆盖。
 *       这个差别写错的表现是 UV 越统计越大（schema.sql 里也有注释）。</li>
 *   <li><b>DB 写失败要把计数放回去</b>：增量已经 GETDEL 出来了，直接丢就是丢数据。
 *       回滚动作 = 把增量 INCR 回去 + 把成员 SADD 回 dirty 集合，下一轮重试。
 *       （极端情况下"upsert 成功但应用在标记前崩溃"会导致重复累加一轮——
 *       统计场景选择容忍这个小概率重复，代价远低于引入两阶段提交。）</li>
 * </ol>
 *
 * <h3>多实例安全</h3>
 * {@code @Scheduled} 在每个实例都会跑。落库不是幂等操作（pv 累加），
 * 两个实例同时跑会把同一批增量写两遍——所以必须加 Redisson 分布式锁，
 * 拿不到锁的实例直接跳过本轮（SchedulingConfig 的注释里埋的就是这个坑）。
 *
 * <h3>upsert 语法</h3>
 * 用 MySQL 8.0.19+ 的 {@code VALUES ... AS new ON DUPLICATE KEY UPDATE pv = pv + new.pv}。
 * 旧写法 {@code VALUES(pv)} 自 8.0.20 起废弃、9.x 已移除——本地 MySQL 9.4 会直接报错。
 */
@Slf4j
@Service
public class StatsFlushService {

    private static final String LOCK_KEY = "sl:st:flush:lock";
    /** 每轮 SPOP 的批大小。太小则往返多，太大则单批事务过大。 */
    private static final long POP_BATCH = 500L;
    /** 单轮最多处理的批数，防止积压时一轮任务跑到天荒地老、占死调度线程。 */
    private static final int MAX_BATCHES_PER_RUN = 40;

    private static final String UPSERT_SQL = """
            INSERT INTO t_link_stats_daily (short_uri, stat_date, hour, pv, uv, uip)
            VALUES (?, ?, ?, ?, ?, ?) AS new
            ON DUPLICATE KEY UPDATE pv = t_link_stats_daily.pv + new.pv, uv = new.uv, uip = new.uip""";
    // ⚠️ 右侧的 pv 必须用表名限定：有了 AS new 别名后，裸写 pv 在
    // "目标表列"和"new.pv"之间二义，MySQL 8/9 一律报 ambiguous（实测 9.4）。
    // uv/uip 右侧已经带了 new. 前缀，不受影响。

    private static final String CLICK_NUM_SQL =
            "UPDATE t_link SET click_num = click_num + ? WHERE short_uri = ?";

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final JdbcTemplate jdbc;

    /** 供 /admin/stats 观测：累计落库行数与最近一轮时间。 */
    private final AtomicLong flushedRows = new AtomicLong();
    private volatile long lastFlushAt;

    public StatsFlushService(StringRedisTemplate redis, RedissonClient redisson, JdbcTemplate jdbc) {
        this.redis = redis;
        this.redisson = redisson;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void flush() {
        RLock lock = redisson.getLock(LOCK_KEY);
        boolean locked;
        try {
            // waitTime=0：拿不到就说明别的实例在刷，本轮直接放弃，没必要排队
            locked = lock.tryLock(0, 25, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            int batches = 0;
            while (batches++ < MAX_BATCHES_PER_RUN) {
                List<String> members = redis.opsForSet().pop(StatsKeys.DIRTY, POP_BATCH);
                if (members == null || members.isEmpty()) {
                    break;
                }
                flushBatch(members);
            }
            lastFlushAt = System.currentTimeMillis();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 处理一批 dirty 成员。失败时把已取出的增量放回 Redis，下一轮重试。 */
    private void flushBatch(List<String> members) {
        // (date, hour, uri) -> pv 增量
        Map<String[], Long> deltas = new LinkedHashMap<>();
        for (String member : members) {
            String[] parts = StatsKeys.parseDirtyMember(member);
            if (parts == null) {
                log.warn("非法 dirty 成员已丢弃: {}", member);
                continue;
            }
            // GETDEL：取出并清零。null 表示计数已被别人取走或已过期
            String val = redis.opsForValue()
                    .getAndDelete(StatsKeys.pv(parts[0], Integer.parseInt(parts[1]), parts[2]));
            long delta = val == null ? 0L : Long.parseLong(val);
            if (delta > 0) {
                deltas.put(parts, delta);
            }
        }
        if (deltas.isEmpty()) {
            return;
        }

        // 每个 (date, uri) 只 PFCOUNT 一次（同日不同小时共享同一个日粒度 HLL）
        Map<String, Long> uvByDayUri = new HashMap<>();
        Map<String, Long> uipByDayUri = new HashMap<>();
        for (String[] parts : deltas.keySet()) {
            String dayUri = parts[0] + '|' + parts[2];
            uvByDayUri.computeIfAbsent(dayUri,
                    k -> redis.opsForHyperLogLog().size(StatsKeys.uv(parts[0], parts[2])));
            uipByDayUri.computeIfAbsent(dayUri,
                    k -> redis.opsForHyperLogLog().size(StatsKeys.uip(parts[0], parts[2])));
        }

        try {
            List<Object[]> upserts = new ArrayList<>();
            Map<String, Long> clickByUri = new HashMap<>();
            Map<String, long[]> dailyPv = new LinkedHashMap<>();   // date|uri -> [pv合计]

            for (Map.Entry<String[], Long> e : deltas.entrySet()) {
                String date = e.getKey()[0];
                int hour = Integer.parseInt(e.getKey()[1]);
                String uri = e.getKey()[2];
                long pv = e.getValue();
                // 小时行只记 pv（UV 是日粒度 HLL，没有小时口径，写 0 而不是错误地写日值）
                upserts.add(new Object[]{uri, date, hour, pv, 0L, 0L});
                dailyPv.computeIfAbsent(date + '|' + uri, k -> new long[1])[0] += pv;
                clickByUri.merge(uri, pv, Long::sum);
            }
            // 日汇总行（hour = -1）：pv 累加，uv/uip 用 HLL 全量基数覆盖
            for (Map.Entry<String, long[]> e : dailyPv.entrySet()) {
                int sep = e.getKey().indexOf('|');
                String date = e.getKey().substring(0, sep);
                String uri = e.getKey().substring(sep + 1);
                String dayUri = date + '|' + uri;
                upserts.add(new Object[]{uri, date, -1, e.getValue()[0],
                        uvByDayUri.getOrDefault(dayUri, 0L), uipByDayUri.getOrDefault(dayUri, 0L)});
            }

            jdbc.batchUpdate(UPSERT_SQL, upserts);
            jdbc.batchUpdate(CLICK_NUM_SQL, clickByUri.entrySet().stream()
                    .map(e -> new Object[]{e.getValue(), e.getKey()})
                    .toList());

            flushedRows.addAndGet(upserts.size());
            log.info("统计落库: {} 个计数格子, {} 行 upsert", deltas.size(), upserts.size());
        } catch (RuntimeException e) {
            // DB 写失败：把增量放回 Redis（INCR 回去 + 成员回 dirty），下一轮重试
            log.error("统计落库失败，增量已放回 Redis 待重试: {}", e.getMessage());
            for (Map.Entry<String[], Long> entry : deltas.entrySet()) {
                String[] parts = entry.getKey();
                redis.opsForValue().increment(
                        StatsKeys.pv(parts[0], Integer.parseInt(parts[1]), parts[2]), entry.getValue());
                redis.opsForSet().add(StatsKeys.DIRTY,
                        StatsKeys.dirtyMember(parts[0], Integer.parseInt(parts[1]), parts[2]));
            }
        }
    }

    public long flushedRows() {
        return flushedRows.get();
    }

    public long lastFlushAt() {
        return lastFlushAt;
    }
}

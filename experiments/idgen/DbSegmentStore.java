// 历史号段实验，不参与主版本编译和运行。
package top.mayiqin.url_shortener.idgen;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 号段存储的 DB 实现：乐观锁 CAS 推进 {@code max_id}。
 *
 * <h3>为什么用乐观锁而不是 {@code SELECT ... FOR UPDATE}</h3>
 * {@code FOR UPDATE} 会持有行锁直到事务提交。虽然本地 buffer 已经把 DB 访问削掉了 99.99%，
 * 但取号是所有创建请求的公共前置，一旦这行被锁住，整个创建链路都会排队。
 * 乐观锁失败只需重试一次 SQL，期望成本远低于持锁等待。
 *
 * <h3>★ 坑一：为什么必须"每次重试都开一个新事务"（这个 bug 极其隐蔽）</h3>
 * 最初的写法是在方法上加 {@code @Transactional(REQUIRES_NEW)}，然后在方法内部写重试循环。
 * <b>这在 MySQL 默认的 REPEATABLE READ 隔离级别下是错的</b>：
 * <pre>
 *   第 1 次：SELECT 读到 version = 5  →  UPDATE ... WHERE version = 5  →  0 行（别人抢先了）
 *   第 2 次：SELECT 又读到 version = 5   ← 同一个事务里的快照读，看不到别人的提交！
 *           UPDATE ... WHERE version = 5  →  又是 0 行
 *   ...重试到上限，抛异常
 * </pre>
 * REPEATABLE READ 下同一事务内的重复 SELECT 返回同一快照，所以<b>重试永远不可能成功</b>。
 * 表现是"并发一高发号器就报 CAS 失败"，但你去看 SQL 又完全正确——非常难查。
 *
 * <p>正确做法：<b>用 {@link TransactionTemplate} 让每次尝试各自开一个新事务</b>，
 * 这样每次 SELECT 都拿到最新的已提交数据。
 *
 * <p>（另一条路是把本方法的隔离级别降到 READ COMMITTED。但那会让"隔离级别"成为
 * 一个隐式依赖——换个数据库或改了全局配置就会静默失效。每次新事务更显式、更可靠。）
 *
 * <h3>★ 坑二：为什么必须 REQUIRES_NEW 而不是复用调用方事务</h3>
 * 取号必须<b>独立提交</b>。如果被业务事务裹住，业务回滚时会把 {@code max_id} 的推进
 * 一起回滚，而内存 buffer 已经把这些号发出去了 → <b>号段重复分配</b>。
 *
 * <h3>为什么 UPDATE 用相对赋值 {@code max_id = max_id + ?} 是安全的</h3>
 * 乍看有隐患：SELECT 读到 M，UPDATE 时 max_id 可能已经被别人改成 M'，
 * 于是 max_id 变成 M' + step，但我们返回的区间却是基于旧的 M —— 会重叠。
 *
 * <p><b>{@code WHERE version = ?} 这个条件正是为了堵住它</b>：只要别人改过，
 * version 就变了，我们的 UPDATE 影响 0 行，走重试。所以 UPDATE 真正执行时
 * max_id 必然还是 M。<b>版本号不是可选的装饰，它是这段逻辑正确性的地基。</b>
 */
@Slf4j
@Component
public class DbSegmentStore implements SegmentIdGenerator.SegmentStore {

    /** 乐观锁重试上限。冲突只发生在多实例同时预加载时，概率很低，5 次足够。 */
    private static final int MAX_CAS_RETRY = 5;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    /** 记录每个 bizTag 上一次取号的时间，用于 step 动态调整。 */
    private final Map<String, Long> lastFetchAt = new ConcurrentHashMap<>();

    public DbSegmentStore(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        // REQUIRES_NEW：与调用方的业务事务完全隔离，独立提交（见坑二）
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public SegmentIdGenerator.SegmentRange nextRange(String bizTag) {
        // ⚠️ 注意：重试循环在事务【外面】。每次 attemptOnce 各自开一个新事务，
        // 这样每次 SELECT 都能看到别人的最新提交（见坑一）。
        for (int i = 0; i < MAX_CAS_RETRY; i++) {
            SegmentIdGenerator.SegmentRange range =
                    txTemplate.execute(status -> attemptOnce(bizTag));
            if (range != null) {
                return range;
            }
            // CAS 失败：别人抢先推进了 version。稍微退避一下再试，
            // 避免多实例同时预加载时反复对撞。
            try {
                Thread.sleep(1L << i);          // 1, 2, 4, 8, 16 ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("取号被中断", e);
            }
        }
        throw new IllegalStateException("号段乐观锁连续 " + MAX_CAS_RETRY + " 次 CAS 失败: " + bizTag);
    }

    /**
     * 单次尝试，运行在独立事务里。
     *
     * @return 成功则返回分配到的区间；CAS 失败返回 {@code null} 由调用方重试
     */
    private SegmentIdGenerator.SegmentRange attemptOnce(String bizTag) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT max_id, step, version FROM t_id_segment WHERE biz_tag = ?", bizTag);
        long maxId = ((Number) row.get("max_id")).longValue();
        int step = ((Number) row.get("step")).intValue();
        long version = ((Number) row.get("version")).longValue();

        // step 动态调整：段消耗得快就翻倍，慢就减半（美团 Leaf 的思路）
        int newStep = step;
        Long last = lastFetchAt.get(bizTag);
        if (last != null) {
            newStep = SegmentIdGenerator.adjustStep(step, System.currentTimeMillis() - last);
        }

        int updated = jdbc.update(
                "UPDATE t_id_segment SET max_id = max_id + ?, step = ?, version = version + 1 "
                        + "WHERE biz_tag = ? AND version = ?",
                newStep, newStep, bizTag, version);

        if (updated != 1) {
            return null;        // 有并发，version 已被别人推进
        }

        lastFetchAt.put(bizTag, System.currentTimeMillis());
        if (newStep != step) {
            log.info("号段 step 动态调整: {} -> {} (bizTag={})", step, newStep, bizTag);
        }
        // 闭区间 [maxId + 1, maxId + newStep]
        return new SegmentIdGenerator.SegmentRange(maxId + 1, maxId + newStep);
    }
}

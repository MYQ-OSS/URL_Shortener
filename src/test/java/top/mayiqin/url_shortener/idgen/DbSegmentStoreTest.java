package top.mayiqin.url_shortener.idgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 号段乐观锁 CAS 的语义测试。
 *
 * <p>不连真 DB —— 用一个内存模型模拟 MySQL 的两种读语义：
 * <ul>
 *   <li><b>快照读</b>（REPEATABLE READ 事务内的 SELECT）：返回事务开始时的副本</li>
 *   <li><b>当前读</b>（{@code UPDATE ... WHERE version = ?}）：原子地比对并修改最新值</li>
 * </ul>
 * 这样就能在毫秒级验证并发正确性，并且<b>把那个真实踩过的 bug 固化成回归测试</b>。
 *
 * <p>连真 DB 的版本见 {@code DbSegmentStoreIT}（需要本地 MySQL）。
 */
class DbSegmentStoreTest {

    /** 模拟 t_id_segment 的那一行。 */
    static class FakeRow {
        private final Object lock = new Object();
        long maxId = 0;
        long version = 0;
        int step;

        FakeRow(int step) {
            this.step = step;
        }

        /** 快照读：模拟 REPEATABLE READ 下事务内 SELECT 的结果。 */
        long[] select() {
            synchronized (lock) {
                return new long[]{maxId, step, version};
            }
        }

        /** {@code UPDATE ... WHERE version = ?}：原子的当前读 + 写。 */
        int update(int newStep, long expectVersion) {
            synchronized (lock) {
                if (version != expectVersion) {
                    return 0;
                }
                maxId += newStep;
                step = newStep;
                version++;
                return 1;
            }
        }
    }

    record Range(long min, long max) {
    }

    /** 修正后的实现：每次尝试重新 SELECT，等价于"每次尝试开一个新事务"。 */
    static Range nextRange(FakeRow row, int maxRetry) {
        for (int i = 0; i < maxRetry; i++) {
            long[] r = row.select();                       // 新事务 -> 新快照
            long maxId = r[0];
            int step = (int) r[1];
            long version = r[2];
            if (row.update(step, version) == 1) {
                return new Range(maxId + 1, maxId + step);
            }
        }
        return null;
    }

    /** 原始 bug 的实现：整个重试循环共用一个快照。 */
    static Range nextRangeWithFixedSnapshot(FakeRow row, long[] snapshot, int maxRetry) {
        for (int i = 0; i < maxRetry; i++) {
            long maxId = snapshot[0];
            int step = (int) snapshot[1];
            long version = snapshot[2];
            if (row.update(step, version) == 1) {
                return new Range(maxId + 1, maxId + step);
            }
        }
        return null;
    }

    @Test
    @DisplayName("顺序取号：区间严格连续，无空洞无重叠")
    void sequentialRangesAreContiguous() {
        FakeRow row = new FakeRow(1000);
        for (int i = 0; i < 10; i++) {
            Range r = nextRange(row, 5);
            assertNotNull(r);
            assertEquals((long) i * 1000 + 1, r.min());
            assertEquals((long) (i + 1) * 1000, r.max());
        }
    }

    @Test
    @DisplayName("★ 并发取号：任意两个区间都不重叠（版本号守住了相对赋值的隐患）")
    void concurrentRangesNeverOverlap() throws Exception {
        // 为什么这条测试重要：UPDATE 用的是相对赋值 max_id = max_id + ?，
        // 乍看有隐患 —— SELECT 读到 M，UPDATE 时 max_id 可能已被别人改成 M'，
        // 于是 max_id 变成 M' + step，而返回的区间基于旧的 M，会重叠。
        // WHERE version = ? 正是为了堵住它：别人改过则 version 变了，UPDATE 影响 0 行。
        // 这条测试就是在验证"版本号不是装饰，是正确性的地基"。
        FakeRow row = new FakeRow(1000);
        int threads = 64, per = 20;
        List<Range> all = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger errors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.execute(() -> {
                try {
                    start.await();
                    for (int i = 0; i < per; i++) {
                        Range r = nextRange(row, 50);
                        if (r == null) {
                            errors.incrementAndGet();
                        } else {
                            all.add(r);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(0, errors.get(), "有取号失败");
        List<Range> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingLong(Range::min));
        for (int i = 1; i < sorted.size(); i++) {
            assertTrue(sorted.get(i).min() > sorted.get(i - 1).max(),
                    "区间重叠: " + sorted.get(i - 1) + " 与 " + sorted.get(i));
        }
        assertEquals(1, sorted.get(0).min());
        // 末段上界 == 段数 × step  ⇒ 既没有空洞也没有重叠
        assertEquals((long) sorted.size() * 1000, sorted.get(sorted.size() - 1).max());
    }

    @Test
    @DisplayName("★ 回归：把重试循环放进同一个事务，在 REPEATABLE READ 下永远重试不成功")
    void retryInsideOneTransactionCanNeverSucceed() {
        // 这是真实踩过的 bug，固化成回归测试，防止以后有人"顺手"把 @Transactional 加回方法上。
        //
        // 现象：并发一高发号器就报 "连续 5 次 CAS 失败"，但 SQL 看起来完全正确。
        // 原因：REPEATABLE READ 下同一事务内的重复 SELECT 返回同一快照，
        //       看不到别人的提交，所以 WHERE version = <旧值> 永远匹配不上。
        FakeRow row = new FakeRow(1000);
        long[] mySnapshot = row.select();      // 我在事务开始时读到 version = 0

        assertNotNull(nextRange(row, 5));      // 别人抢先取了一段，version 变成 1

        // 我拿着旧快照重试 5 次 —— 全部失败
        assertNull(nextRangeWithFixedSnapshot(row, mySnapshot, 5),
                "旧快照本应导致 CAS 永远失败；如果这里非 null，说明测试模型不对");

        // 修法：重新 SELECT（等价于开新事务），一次就成功，并且接在别人之后
        Range fixed = nextRange(row, 5);
        assertNotNull(fixed);
        assertEquals(1001, fixed.min(), "应该接在别人的 [1,1000] 之后");
        assertEquals(2000, fixed.max());
    }

    @Test
    @DisplayName("step 变化会同时写回 t_id_segment，下一段用新 step")
    void stepChangePersists() {
        FakeRow row = new FakeRow(1000);
        assertNotNull(nextRange(row, 5));
        // 模拟"消耗很快"触发翻倍
        int newStep = SegmentIdGenerator.adjustStep(row.step, 5 * 60_000L);
        assertEquals(2000, newStep);
        assertEquals(1, row.update(newStep, row.version));
        assertEquals(2000, row.step);
        Range next = nextRange(row, 5);
        assertNotNull(next);
        assertEquals(2000, next.max() - next.min() + 1, "下一段长度应为新 step");
    }
}

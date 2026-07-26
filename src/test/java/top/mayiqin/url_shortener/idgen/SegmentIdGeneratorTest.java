package top.mayiqin.url_shortener.idgen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 发号器并发正确性。
 *
 * <p>把 DB 访问抽象成 {@link SegmentIdGenerator.SegmentStore} 的意义就在这里——
 * 并发逻辑可以脱离 DB 单测，跑 100 万次取号只需几十毫秒。
 */
class SegmentIdGeneratorTest {

    /** 内存版 store，模拟 DB 乐观锁 CAS + 一次网络 RT。 */
    static class FakeStore implements SegmentIdGenerator.SegmentStore {
        final int step;
        final long latencyMicros;
        final AtomicLong maxId = new AtomicLong(0);
        final AtomicInteger calls = new AtomicInteger();

        FakeStore(int step, long latencyMicros) {
            this.step = step;
            this.latencyMicros = latencyMicros;
        }

        @Override
        public SegmentIdGenerator.SegmentRange nextRange(String bizTag) {
            calls.incrementAndGet();
            if (latencyMicros > 0) {
                long deadline = System.nanoTime() + latencyMicros * 1000;
                while (System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
            }
            long newMax = maxId.addAndGet(step);
            return new SegmentIdGenerator.SegmentRange(newMax - step + 1, newMax);
        }
    }

    @Test
    @DisplayName("★ 1000 线程取 100 万 ID：无重复、无异常、线程内严格递增")
    void concurrentUniqueAndMonotonic() throws Exception {
        int threads = 1000, perThread = 1000, total = threads * perThread;
        FakeStore store = new FakeStore(100_000, 200);
        SegmentIdGenerator gen = new SegmentIdGenerator("short_link", store);

        long[][] results = new long[threads][];
        AtomicInteger errors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(256);
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int idx = t;
            pool.execute(() -> {
                long[] mine = new long[perThread];
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        mine[i] = gen.nextId();
                    }
                    results[idx] = mine;
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "取号超时");
        pool.shutdownNow();
        gen.shutdown();

        assertEquals(0, errors.get(), "取号过程有异常");

        long[] all = new long[total];
        int n = 0;
        for (long[] arr : results) {
            assertNotNull(arr);
            // 线程内严格递增 —— 这条断言守的是"趋势递增"这个核心性质。
            // 一旦 ID 倒退，用它做 InnoDB 聚簇索引主键的全部意义就没了（会页分裂）。
            for (int i = 1; i < arr.length; i++) {
                assertTrue(arr[i] > arr[i - 1], "ID 倒退: " + arr[i - 1] + " -> " + arr[i]);
            }
            System.arraycopy(arr, 0, all, n, arr.length);
            n += arr.length;
        }

        Arrays.sort(all);
        int distinct = 1;
        for (int i = 1; i < all.length; i++) {
            if (all[i] != all[i - 1]) {
                distinct++;
            }
        }
        assertEquals(total, distinct, "出现重复 ID");
        assertTrue(all[0] >= 1);

        // 本地 buffer 应该削掉几乎全部 DB 访问
        assertTrue(store.calls.get() < total / 1000,
                "DB 取号次数异常偏高: " + store.calls.get());
        System.out.printf("DB 取号 %d 次 | 同步取号 %d 次 | 丢弃倒序号段 %d 个%n",
                store.calls.get(), gen.syncLoadCount(), gen.staleSegmentCount());
    }

    @Test
    @DisplayName("step 太小会让预加载赶不上消耗（同步取号次数飙升），但正确性不受影响")
    void smallStepDegradesButStaysCorrect() throws Exception {
        // 实测：step=1000 时同步取号 800+ 次、丢弃倒序号段 160+ 个；
        //      step=100000 时降到个位数 / 0 个。
        // 这就是 step 必须按创建 QPS 来定、且要动态调整的实证依据。
        for (int step : new int[]{1_000, 100_000}) {
            FakeStore store = new FakeStore(step, 200);
            SegmentIdGenerator gen = new SegmentIdGenerator("t", store);
            int threads = 200, perThread = 500;
            ExecutorService pool = Executors.newFixedThreadPool(64);
            CountDownLatch done = new CountDownLatch(threads);
            java.util.Set<Long> ids = ConcurrentHashMap.newKeySet();
            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            ids.add(gen.nextId());
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
            pool.shutdownNow();
            assertEquals(threads * perThread, ids.size(), "step=" + step + " 出现重复");
            System.out.printf("step=%,d -> 同步取号 %d 次，丢弃倒序 %d 个%n",
                    step, gen.syncLoadCount(), gen.staleSegmentCount());
            gen.shutdown();
        }
    }

    @Test
    @DisplayName("step 动态调整：消耗快则翻倍，慢则减半，有上下限")
    void stepAdjust() {
        assertEquals(2000, SegmentIdGenerator.adjustStep(1000, 5 * 60_000L));
        assertEquals(1000, SegmentIdGenerator.adjustStep(1000, 20 * 60_000L));
        assertEquals(500, SegmentIdGenerator.adjustStep(1000, 40 * 60_000L));
        assertEquals(1_000_000, SegmentIdGenerator.adjustStep(1_000_000, 60_000L));
        assertEquals(100, SegmentIdGenerator.adjustStep(100, 60 * 60_000L));
    }
}

package top.mayiqin.url_shortener.idgen;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 号段模式发号器（美团 Leaf-segment 思路），双 Buffer + 异步预加载。
 *
 * <h3>它在本项目里的用途</h3>
 * <b>不是生成短码，而是生成 {@code t_link} 的主键。</b>
 * InnoDB 主键是聚簇索引，短码是随机字符串，用它做主键等于随机位置插入 →
 * 页分裂 + 写放大 + 索引碎片。发号器 ID 趋势递增，顺序追加写，几乎不分裂。
 *
 * <p>把"唯一性"（发号器）和"不可猜测性"（短码生成）解耦成两套机制，
 * 是这个项目最值得讲的架构判断之一。
 *
 * <h3>为什么要双 Buffer</h3>
 * 单 Buffer 在号段耗尽的那一瞬间，所有并发请求都会阻塞等 DB 返回（一次 DB RT 约 1-5ms），
 * 压测时能看到 <b>P999 出现周期性尖刺</b>。双 Buffer 在当前段消耗到阈值时就后台异步
 * 加载下一段，切换时零等待，把这个毛刺彻底消掉。
 *
 * <h3>为什么用乐观锁而不是 SELECT ... FOR UPDATE</h3>
 * {@code FOR UPDATE} 会持有行锁直到事务提交。取号是高频操作，行锁竞争会成为瓶颈。
 * 而且本地 buffer 已经削掉了 99.9% 的 DB 访问，真正到 DB 的冲突概率很低，
 * 乐观锁失败重试的期望成本远低于悲观锁的持锁成本。
 *
 * <h3>已知取舍</h3>
 * 服务重启会丢掉内存里未用完的号段。<b>本场景完全可接受</b>（短码空间 568 亿，
 * 丢几千个号无所谓）；但如果是订单号，丢号会导致对账问题——
 * 能区分"哪些业务能容忍丢号"是这个方案的关键判断。
 */
public class SegmentIdGenerator {

    /** 当前段剩余比例低于该值时触发下一段的异步预加载。 */
    private static final double PRELOAD_THRESHOLD = 0.2D;

    /** step 动态调整：段消耗过快则扩大，过慢则缩小。 */
    private static final long FAST_CONSUME_MILLIS = 15 * 60 * 1000L;
    private static final long SLOW_CONSUME_MILLIS = 30 * 60 * 1000L;
    private static final int MIN_STEP = 100;
    private static final int MAX_STEP = 1_000_000;

    private final String bizTag;
    private final SegmentStore store;

    /** 双 Buffer。current 用完就和 next 原子交换。 */
    private volatile Segment current;
    private volatile Segment next;

    /** 保护 buffer 切换与首次初始化，粒度极小（只包住指针交换，不包住 DB IO）。 */
    private final ReentrantLock switchLock = new ReentrantLock();

    /** 保证同一时刻只有一个后台预加载在跑。 */
    private final AtomicBoolean preloading = new AtomicBoolean(false);

    private final ExecutorService preloadExecutor;

    /**
     * 同步取号（预加载没赶上）的次数。
     *
     * <p><b>这个指标要暴露到 Prometheus。</b>它接近 0 说明 step 合理；
     * 持续增长说明消耗速度超过了预加载速度，需要调大 step——
     * 双 Buffer 的价值就体现在把这个数压到 0。
     */
    private final AtomicLong syncLoads = new AtomicLong();

    /** 因倒序而被丢弃的预加载号段数（见 {@link #advance}）。正常应接近 0。 */
    private final AtomicLong staleSegments = new AtomicLong();

    public SegmentIdGenerator(String bizTag, SegmentStore store) {
        this.bizTag = bizTag;
        this.store = store;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "id-segment-preload-" + bizTag);
            t.setDaemon(true);
            return t;
        };
        this.preloadExecutor = Executors.newSingleThreadExecutor(tf);
    }

    /**
     * 取号的最大推进次数。
     *
     * <p><b>⚠️ 这里踩过一个坑，值得记进 ADR</b>：最初写的是"最多重试 3 次，超过就抛异常"。
     * 用 1000 线程压 100 万 ID 时大量线程直接抛异常——因为高并发下一个线程完全可能连续
     * 3 次都撞上"号段刚好被别人耗尽"的时刻，重试次数和并发度是正相关的，
     * 固定小上限必然被打穿。
     *
     * <p>正确做法是：<b>循环推进直到成功，只用一个宽松上限兜住"DB 真的挂了"的死循环</b>。
     * 每次 {@code advance} 要么换上一个还有 step 个 ID 的新段（一定能推进），
     * 要么因 DB 不可用直接抛异常向上传播，所以循环必然终止。
     */
    private static final int MAX_ADVANCE = 128;

    /** 取一个 ID。绝大多数调用只是一次 {@code AtomicLong#getAndIncrement}。 */
    public long nextId() {
        for (int i = 0; i < MAX_ADVANCE; i++) {
            Segment seg = current;
            if (seg != null) {
                long id = seg.cursor.getAndIncrement();
                if (id <= seg.max) {
                    // 顺手判断要不要预加载下一段，判断本身是无锁的
                    if (seg.remainRatio() < PRELOAD_THRESHOLD && next == null) {
                        triggerPreload();
                    }
                    return id;
                }
                // 注意：cursor 会冲过 max（超出的那些 ID 被浪费掉）。
                // 这是刻意的取舍——用"浪费几个 ID"换"取号路径完全无锁"。
                // 号码空间 568 亿，浪费是免费的；加锁是不免费的。
            }
            advance(seg);
        }
        throw new IllegalStateException(
                "发号器推进 " + MAX_ADVANCE + " 次仍未取到号，DB 大概率不可用（bizTag=" + bizTag + "）");
    }

    /**
     * 推进到下一个号段。
     *
     * @param stale 调用方看到的（已耗尽的）段；用它做双重检查，
     *              如果 {@code current} 已经不是它了说明别人已经推进过，直接返回让调用方重试
     */
    private void advance(Segment stale) {
        switchLock.lock();
        try {
            if (current != stale) {
                return;                 // 别人已经换好了
            }
            Segment prepared = next;

            // ┌──────────────────────────────────────────────────────────────────────┐
            // │ ⚠️ 这个 min > max 的校验修的是一个非常隐蔽的竞态，务必留着。          │
            // │                                                                      │
            // │ 复现路径：                                                           │
            // │   1. 预加载线程向 DB 申请到号段 [100001, 200000]，还没写进 next      │
            // │   2. current 恰好在此刻耗尽，advance 看到 next == null              │
            // │   3. 于是走同步取号，从 DB 拿到 [200001, 300000] 并装进 current      │
            // │   4. 预加载线程这时才把 [100001, 200000] 写进 next                   │
            // │   5. 等 current 再耗尽，就会切到 [100001, 200000] —— ID 倒退了！     │
            // │                                                                      │
            // │ 后果不是重复（区间互不相交，唯一性没问题），而是**破坏趋势递增**。   │
            // │ 而"趋势递增"正是我们用它做 InnoDB 聚簇索引主键的全部理由：           │
            // │ 一旦 ID 倒退，插入就变成随机位置写 → 页分裂 → 写放大。               │
            // │ 也就是说这个 bug 会静默地把发号器的核心价值抵消掉，且压测不易发现。   │
            // │                                                                      │
            // │ 修法：只接受严格大于当前段上界的号段，否则丢弃并重新同步取。         │
            // │ 因为 DB 侧的 max_id 单调递增，同步取到的一定大于此前分配过的一切。   │
            // │ 丢弃一个号段的代价是浪费 step 个 ID —— 在 568 亿的空间里免费。       │
            // └──────────────────────────────────────────────────────────────────────┘
            if (prepared != null && prepared.min > stale.max) {
                current = prepared;
                next = null;
                triggerPreload();       // 立刻为下一次切换做准备
                return;
            }
            if (prepared != null) {
                staleSegments.incrementAndGet();
                next = null;            // 丢弃倒序的预加载结果
            }

            // 预加载没赶上（step 太小 / DB 慢 / 消耗过猛），只能同步取。
            // 这是唯一会阻塞调用方的分支。
            // 压测时它的出现频率就是"step 定得合不合理"的直接信号。
            syncLoads.incrementAndGet();
            current = loadSegment();
        } finally {
            switchLock.unlock();
        }
    }

    private void triggerPreload() {
        if (!preloading.compareAndSet(false, true)) {
            return;
        }
        preloadExecutor.execute(() -> {
            try {
                if (next == null) {
                    next = loadSegment();
                }
            } catch (RuntimeException e) {
                // 预加载失败不能影响主链路：下次切换时会退化为同步取号
                System.err.println("[SegmentIdGenerator] 预加载号段失败: " + e.getMessage());
            } finally {
                preloading.set(false);
            }
        });
    }

    /** 从存储取一段，内部含乐观锁重试与 step 动态调整。 */
    private Segment loadSegment() {
        SegmentRange range = store.nextRange(bizTag);
        return new Segment(range.min(), range.max());
    }

    /** 供监控暴露：当前段剩余比例。 */
    public double currentRemainRatio() {
        Segment seg = current;
        return seg == null ? 0D : seg.remainRatio();
    }

    /** 供监控暴露：同步取号次数。持续增长 = step 太小。 */
    public long syncLoadCount() {
        return syncLoads.get();
    }

    /** 供监控暴露：被丢弃的倒序号段数。 */
    public long staleSegmentCount() {
        return staleSegments.get();
    }

    /** 关闭预加载线程（Spring 里用 @PreDestroy 调用）。 */
    public void shutdown() {
        preloadExecutor.shutdownNow();
    }

    /** 一个已经分配到本地的号段。 */
    private static final class Segment {
        final long min;
        final long max;
        final AtomicLong cursor;

        Segment(long min, long max) {
            this.min = min;
            this.max = max;
            this.cursor = new AtomicLong(min);
        }

        double remainRatio() {
            long total = max - min + 1;
            if (total <= 0) {
                return 0D;
            }
            long remain = max - cursor.get() + 1;
            return remain <= 0 ? 0D : (double) remain / total;
        }
    }

    /** 号段区间，闭区间 {@code [min, max]}。 */
    public record SegmentRange(long min, long max) {
    }

    /**
     * 号段存储抽象。
     *
     * <p>抽出接口的目的是让发号器的并发逻辑可以脱离 DB 单测——
     * 这也是为什么下面那个 1000 线程取 100 万 ID 的测试能跑得起来。
     */
    public interface SegmentStore {
        /** 原子地把 max_id 推进一个 step，返回本次分配到的闭区间。 */
        SegmentRange nextRange(String bizTag);
    }

    /** step 动态调整策略，供 {@code DbSegmentStore} 复用（也便于单独测试）。 */
    public static int adjustStep(int currentStep, long consumeMillis) {
        int step = currentStep;
        if (consumeMillis < FAST_CONSUME_MILLIS) {
            step = Math.min(MAX_STEP, currentStep * 2);
        } else if (consumeMillis > SLOW_CONSUME_MILLIS) {
            step = Math.max(MIN_STEP, currentStep / 2);
        }
        return step;
    }
}

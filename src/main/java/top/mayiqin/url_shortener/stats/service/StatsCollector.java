package top.mayiqin.url_shortener.stats.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.mayiqin.url_shortener.config.RabbitConfig;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问统计采集（生产端）。
 *
 * <h3>唯一不可动摇的契约：统计绝不能影响跳转</h3>
 * <blockquote>"统计丢一点可以，跳转挂了不行。"</blockquote>
 *
 * <h3>★ 投递模型：请求线程只入队，后台线程批量投递（ADR-023，压测实测的重构）</h3>
 * 最初 record() 在<b>请求线程</b>上同步 convertAndSend。压测实测：
 * <pre>
 *   统计关（纯跳转）                16,117 QPS
 *   统计开 + 同步投递 + confirms     ~1,185 QPS   ← 掉 93%！
 * </pre>
 * 瓶颈不在 MQ 可用性，而在"每请求一次同步网络 publish"这个动作本身
 * （200 线程争抢 AMQP channel + confirm 关联开销）。
 * 修法就是本类现在的模型：
 * <ul>
 *   <li>record()：序列化 + offer 进<b>有界</b>内存队列，O(1)、零网络，绝不阻塞请求线程</li>
 *   <li>后台 publisher 线程组：持续 drain 队列并逐条 publish；
 *       broker 故障时熔断退避（见下），队列积压到上限后开始丢弃</li>
 * </ul>
 * "本地暂存"从异常降级路径变成了<b>常态主路径</b>——这正是 ADR-023 的结论。
 *
 * <h3>熔断（ADR-020 遗产，语义不变）</h3>
 * broker 宕机时一次发送失败即熔断 {@link #OPEN_MILLIS} 毫秒，期间 publisher
 * 不做注定失败的连接尝试（否则每次阻塞到连接超时），只让事件在队列里积压；
 * 到期后试探性恢复。队列是有界的：无界队列在 MQ 长时间不可用时会 OOM，
 * 那就从"统计降级"变成"服务挂掉"，完全违背初衷。
 *
 * <h3>消息格式的取舍</h3>
 * 直接发 JSON 字符串（自己持有 Jackson 3 的 {@link JsonMapper}），
 * 不用 Spring AMQP 的 MessageConverter 体系（Boot 4 Jackson 2→3 迁移期的兼容风险，
 * 见 ADR-001 的教训）。String 载荷在任何版本上行为都一致。
 */
@Slf4j
@Component
public class StatsCollector {

    /**
     * 内存队列上限。达到后新事件直接丢弃（droppedCount 暴露给监控）。
     * 100k 条 × ~200 字节 ≈ 20MB，是"撑过 broker 短暂抖动"与内存占用的平衡点。
     */
    private static final int LOCAL_BUFFER_LIMIT = 100_000;

    /** 后台 publisher 线程数。本地 broker 单线程 publish 约 3-5k/s，4 路足够吃掉跳转峰值。 */
    private static final int PUBLISHER_THREADS = 4;

    /** 队列空时 publisher 的小睡时长（毫秒）。 */
    private static final long IDLE_SLEEP_MS = 50L;

    /** 熔断时长：一次发送失败即熔断这么久，到期试探恢复。 */
    private static final long OPEN_MILLIS = 3_000L;

    /** 暂存队列。生产者 = 请求线程，消费者 = publisher 线程组。 */
    private final Queue<String> localBuffer = new ConcurrentLinkedQueue<>();

    /**
     * 队列长度的独立计数器。
     * <p>⚠️ 不能用 {@link ConcurrentLinkedQueue#size()}——它是 O(n) 遍历。
     * MQ 故障时队列可能积到 10 万条，那时每次跳转都 O(n) 数一遍队列，
     * 等于"统计降级"本身把跳转链路拖垮了，恰好违背第一契约。
     */
    private final AtomicInteger bufferSize = new AtomicInteger();

    /** 超限被丢弃的事件数。要暴露成指标——静默丢数据比丢数据本身更危险。 */
    private final AtomicLong droppedCount = new AtomicLong();

    /** 熔断截止时间戳（0 = 闭合）。 */
    private final AtomicLong mqDownUntil = new AtomicLong(0);

    private final RabbitTemplate rabbit;
    private final ObjectMapper json = JsonMapper.builder().build();

    /**
     * 统计采集总开关。缓存分阶段压测（裸 MySQL / +Redis / +Caffeine）时关闭，
     * 让每一阶段只测缓存这一个变量；"完整链路"阶段和统计链路验证（Week 3）时开启。
     * 生产环境保持默认开启。
     */
    private final boolean enabled;

    private ExecutorService publishers;

    public StatsCollector(RabbitTemplate rabbit,
                          @Value("${shortlink.stats.enabled:true}") boolean enabled) {
        this.rabbit = rabbit;
        this.enabled = enabled;
    }

    @PostConstruct
    public void startPublishers() {
        if (!enabled) {
            return;
        }
        publishers = Executors.newFixedThreadPool(PUBLISHER_THREADS, r -> {
            Thread t = new Thread(r, "stats-publisher");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < PUBLISHER_THREADS; i++) {
            publishers.submit(this::publishLoop);
        }
        log.info("统计 publisher 线程组已启动（{} 线程）", PUBLISHER_THREADS);
    }

    @PreDestroy
    public void stopPublishers() {
        if (publishers != null) {
            publishers.shutdownNow();
        }
    }

    /** 跳转链路调用。任何异常都不外抛。 */
    public void record(String shortUri, HttpServletRequest request) {
        if (!enabled) {
            return;
        }
        try {
            StatsEvent event = new StatsEvent(
                    UUID.randomUUID().toString(),
                    shortUri,
                    clientIp(request),
                    request.getHeader("User-Agent"),
                    request.getHeader("Referer"),
                    System.currentTimeMillis());
            // ★ 请求线程的全部工作到此为止：序列化 + 入队。没有网络调用。
            enqueue(json.writeValueAsString(event));
        } catch (RuntimeException e) {
            // 吞掉。统计的任何问题都不能冒泡到跳转链路。
            log.debug("统计采集失败，已忽略: {}", e.getMessage());
        }
    }

    private void enqueue(String payload) {
        if (bufferSize.get() >= LOCAL_BUFFER_LIMIT) {
            droppedCount.incrementAndGet();
            return;
        }
        localBuffer.offer(payload);
        bufferSize.incrementAndGet();
    }

    /**
     * publisher 主循环：drain 队列 → 逐条 publish。
     *
     * <p>熔断打开时不做注定失败的连接尝试（每次会阻塞到连接超时），
     * 只小睡退避，让事件在队列里积压；到期后第一条消息试探恢复。
     * 队列空时也小睡，避免空转烧 CPU。
     */
    private void publishLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            long waitLeft = mqDownUntil.get() - System.currentTimeMillis();
            if (waitLeft > 0) {
                sleepQuietly(Math.min(waitLeft, 500L));
                continue;
            }
            String payload = localBuffer.poll();
            if (payload == null) {
                sleepQuietly(IDLE_SLEEP_MS);
                continue;
            }
            bufferSize.decrementAndGet();
            try {
                rabbit.convertAndSend(RabbitConfig.EXCHANGE_STATS, RabbitConfig.RK_STATS, payload);
            } catch (RuntimeException e) {
                mqDownUntil.set(System.currentTimeMillis() + OPEN_MILLIS);
                log.warn("统计投递失败，熔断 {}ms，转入积压: {}", OPEN_MILLIS, e.getMessage());
                enqueue(payload);   // 放回队尾等恢复；队列满则按上限丢弃
            }
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 熔断是否打开（供测试/观测）。 */
    public boolean mqBreakerOpen() {
        return System.currentTimeMillis() < mqDownUntil.get();
    }

    /** 供监控暴露：待投递的事件条数。 */
    public int pendingCount() {
        return bufferSize.get();
    }

    /** 供监控暴露：因队列满被丢弃的事件数。 */
    public long droppedCount() {
        return droppedCount.get();
    }

    /** 取真实客户端 IP。X-Forwarded-For 可伪造，生产上只信任网关追加的那一段。 */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 统计事件。
     *
     * @param msgId 消费端幂等去重用（MQ 是至少一次投递，重复消费会让 PV 虚高）
     */
    public record StatsEvent(String msgId, String shortUri, String ip, String userAgent,
                             String referer, long timestamp) {
    }
}

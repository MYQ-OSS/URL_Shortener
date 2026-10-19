package top.mayiqin.url_shortener.stats.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.mayiqin.url_shortener.common.ClientIpResolver;
import top.mayiqin.url_shortener.config.RabbitConfig;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问趋势统计：请求线程只做有界内存入队，后台确认投递并重试。
 * 队列满、关闭或进程崩溃允许丢失事件；不适用于计费、审计等强可靠场景。
 */
@Slf4j
@Component
public class StatsCollector {
    private static final int PUBLISHER_THREADS = 4;
    private static final long OPEN_MILLIS = 3000;
    private final RabbitTemplate rabbit;
    private final ClientIpResolver clientIpResolver;
    private final ArrayBlockingQueue<String> queue;
    // 包括正在等待 broker 确认的事件，使积压总量也受容量上限约束。
    private final AtomicInteger pending = new AtomicInteger();
    private final int capacity;
    private final long confirmTimeoutMillis;
    private final boolean enabled;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicLong mqDownUntil = new AtomicLong();
    private final AtomicLong lastDropWarning = new AtomicLong();
    private volatile boolean stopping;
    private ExecutorService publishers;

    public StatsCollector(RabbitTemplate rabbit, ClientIpResolver clientIpResolver,
                          @Value("${shortlink.stats.max-pending:4096}") int capacity,
                          @Value("${shortlink.stats.confirm-timeout-millis:5000}") long confirmTimeoutMillis,
                          @Value("${shortlink.stats.enabled:true}") boolean enabled) {
        if (capacity <= 0 || confirmTimeoutMillis <= 0) {
            throw new IllegalArgumentException("队列容量和 confirm 超时必须为正数");
        }
        this.rabbit = rabbit;
        this.clientIpResolver = clientIpResolver;
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.enabled = enabled;
        rabbit.setMandatory(true);
    }

    @PostConstruct
    public void startPublishers() {
        if (!enabled || stopping || publishers != null) return;
        publishers = Executors.newFixedThreadPool(PUBLISHER_THREADS, task -> {
            Thread thread = new Thread(task, "stats-publisher");
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < PUBLISHER_THREADS; i++) publishers.submit(this::publishLoop);
    }

    @PreDestroy
    public void stopPublishers() {
        stopping = true;
        if (publishers != null) {
            publishers.shutdownNow();
            try {
                if (!publishers.awaitTermination(confirmTimeoutMillis + 3000, TimeUnit.MILLISECONDS)) {
                    log.warn("统计 publisher 未及时退出，未确认的内存事件不保证保留");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        while (queue.poll() != null) {
            pending.decrementAndGet();
            recordDrop();
        }
    }

    public void record(String shortUri, HttpServletRequest request) {
        if (!enabled) return;
        if (stopping) { recordDrop(); return; }
        try {
            StatsEvent event = new StatsEvent(UUID.randomUUID().toString(), shortUri,
                    clientIpResolver.resolve(request), bounded(request.getHeader("User-Agent")),
                    bounded(request.getHeader("Referer")), System.currentTimeMillis());
            String payload = json.writeValueAsString(event);
            int count;
            do {
                count = pending.get();
                if (count >= capacity) { recordDrop(); return; }
            } while (!pending.compareAndSet(count, count + 1));
            if (stopping || !queue.offer(payload)) {
                pending.decrementAndGet();
                recordDrop();
            }
        } catch (Exception e) {
            recordDrop();
        }
    }

    private void publishLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            String payload = null;
            try {
                if (mqBreakerOpen()) { Thread.sleep(100); continue; }
                payload = queue.poll(1, TimeUnit.SECONDS);
                if (payload == null) continue;
                CorrelationData confirmation = new CorrelationData(UUID.randomUUID().toString());
                rabbit.convertAndSend(RabbitConfig.EXCHANGE_STATS, RabbitConfig.RK_STATS, payload, message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                }, confirmation);
                var result = confirmation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
                if (!result.ack() || confirmation.getReturned() != null) {
                    throw new IllegalStateException("消息未被队列确认接收");
                }
                pending.decrementAndGet();
            } catch (Exception e) {
                if (payload != null) {
                    publishFailures.incrementAndGet();
                    mqDownUntil.set(System.currentTimeMillis() + OPEN_MILLIS);
                    // 重投同一 payload，保留 msgId，消费者可以识别重复投递。
                    if (stopping || !queue.offer(payload)) {
                        pending.decrementAndGet();
                        recordDrop();
                    }
                    log.warn("统计投递未确认，事件将在内存中重试或按容量策略丢弃: {}", e.getMessage());
                }
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }

    private void recordDrop() {
        long count = dropped.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastDropWarning.get();
        if (now - last > 5000 && lastDropWarning.compareAndSet(last, now)) {
            log.warn("访问趋势统计已丢弃 {} 条事件，请检查队列容量和 MQ 状态", count);
        }
    }

    private static String bounded(String header) {
        return header == null ? null : header.substring(0, Math.min(header.length(), 1024));
    }

    public boolean mqBreakerOpen() { return System.currentTimeMillis() < mqDownUntil.get(); }
    public int pendingCount() { return pending.get(); }
    public int bufferCapacity() { return capacity; }
    public long droppedCount() { return dropped.get(); }
    public long publishFailureCount() { return publishFailures.get(); }

    public record StatsEvent(String msgId, String shortUri, String ip, String userAgent,
                             String referer, long timestamp) { }
}

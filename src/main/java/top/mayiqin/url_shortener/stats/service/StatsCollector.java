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

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 先持久化待投递事件，再由后台发送。只有收到 ACK 且未被退回才删除磁盘副本。
 * 请求线程无 MQ 网络等待，但有 fsync 延迟；磁盘故障计入 dropped 并保留跳转可用性。
 */
@Slf4j
@Component
public class StatsCollector {
    private static final int PUBLISHER_THREADS = 4;
    private static final long OPEN_MILLIS = 3000;
    private final RabbitTemplate rabbit;
    private final StatsOutbox outbox;
    private final ClientIpResolver clientIpResolver;
    private final long confirmTimeoutMillis;
    private final boolean enabled;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong publishFailures = new AtomicLong();
    private final AtomicLong mqDownUntil = new AtomicLong();
    private final AtomicLong lastDropWarning = new AtomicLong();
    private ExecutorService publishers;

    public StatsCollector(RabbitTemplate rabbit, StatsOutbox outbox, ClientIpResolver clientIpResolver,
                          @Value("${shortlink.stats.confirm-timeout-millis:5000}") long confirmTimeoutMillis,
                          @Value("${shortlink.stats.enabled:true}") boolean enabled) {
        if (confirmTimeoutMillis <= 0) throw new IllegalArgumentException("confirm 超时必须为正数");
        this.rabbit = rabbit;
        this.outbox = outbox;
        this.clientIpResolver = clientIpResolver;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        this.enabled = enabled;
        rabbit.setMandatory(true);
    }

    @PostConstruct
    public void startPublishers() {
        if (!enabled) return;
        publishers = Executors.newFixedThreadPool(PUBLISHER_THREADS, task -> {
            Thread thread = new Thread(task, "stats-publisher");
            thread.setDaemon(true);
            return thread;
        });
        for (int i = 0; i < PUBLISHER_THREADS; i++) publishers.submit(this::publishLoop);
    }

    @PreDestroy
    public void stopPublishers() {
        if (publishers == null) return;
        publishers.shutdownNow();
        try {
            if (!publishers.awaitTermination(confirmTimeoutMillis + 3000, TimeUnit.MILLISECONDS)) {
                log.warn("统计 publisher 未及时退出；未确认事件保留在磁盘，重启后重放");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void record(String shortUri, HttpServletRequest request) {
        if (!enabled) return;
        try {
            StatsEvent event = new StatsEvent(UUID.randomUUID().toString(), shortUri,
                    clientIpResolver.resolve(request), bounded(request.getHeader("User-Agent")),
                    bounded(request.getHeader("Referer")), System.currentTimeMillis());
            if (!outbox.append(json.writeValueAsString(event))) recordDrop();
        } catch (Exception e) {
            recordDrop();
        }
    }

    private void recordDrop() {
        dropped.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastDropWarning.get();
        if (now - last > 5000 && lastDropWarning.compareAndSet(last, now)) {
            log.error("统计事件未能持久化，请检查磁盘权限、剩余空间和 max-pending；已丢弃 {} 条", dropped.get());
        }
    }

    private static String bounded(String header) {
        return header == null ? null : header.substring(0, Math.min(header.length(), 1024));
    }

    private void publishLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            if (mqBreakerOpen()) {
                sleepQuietly(100);
                continue;
            }
            Path file = outbox.poll();
            if (file == null) {
                sleepQuietly(50);
                continue;
            }
            try {
                String payload = outbox.read(file);
                CorrelationData confirmation = new CorrelationData(UUID.randomUUID().toString());
                rabbit.convertAndSend(RabbitConfig.EXCHANGE_STATS, RabbitConfig.RK_STATS, payload, message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                }, confirmation);
                var result = confirmation.getFuture().get(confirmTimeoutMillis, TimeUnit.MILLISECONDS);
                if (!result.isAck() || confirmation.getReturned() != null) {
                    throw new IllegalStateException("消息未被队列确认接收");
                }
                outbox.acknowledge(file);
            } catch (Exception e) {
                publishFailures.incrementAndGet();
                mqDownUntil.set(System.currentTimeMillis() + OPEN_MILLIS);
                log.warn("统计投递失败，保留磁盘副本并重试: {}", e.getMessage());
                outbox.retry(file);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public boolean mqBreakerOpen() { return System.currentTimeMillis() < mqDownUntil.get(); }
    public int pendingCount() { return outbox.pendingCount(); }
    public long droppedCount() { return dropped.get(); }
    public long publishFailureCount() { return publishFailures.get(); }

    public record StatsEvent(String msgId, String shortUri, String ip, String userAgent,
                             String referer, long timestamp) { }
}

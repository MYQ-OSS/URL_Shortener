package top.mayiqin.url_shortener.stats.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.mayiqin.url_shortener.config.RabbitConfig;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 访问统计采集（生产端）。
 *
 * <h3>唯一不可动摇的契约：统计绝不能影响跳转</h3>
 * <blockquote>"统计丢一点可以，跳转挂了不行。"</blockquote>
 * 所以 MQ 发送失败时<b>降级为本地暂存</b>，由后台定时补投；任何异常都在这里吞掉，
 * 绝不向上抛到跳转链路。
 *
 * <h3>为什么 MQ 用在这里而不是创建链路（ADR-009）</h3>
 * 短链读写比 ≈ 500:1。创建 QPS 本来就低，异步落库反而会造成"拿到短码立刻访问却 404"。
 * 统计链路的 QPS 等于跳转 QPS，才是真正需要削峰的地方，而且统计天然容忍秒级延迟。
 *
 * <h3>消息格式的取舍</h3>
 * 直接发 JSON 字符串（自己持有 Jackson 3 的 {@link JsonMapper}），
 * 不用 Spring AMQP 的 MessageConverter 体系。理由：Boot 4 刚把默认 Jackson 从 2.x
 * 换成 3.x（坐标 {@code tools.jackson}），converter 层在这个迁移期是兼容性风险点
 * （见 ADR-001 的教训：先验证后集成）。String 载荷在任何版本上行为都一致，
 * 消费端用同一个 mapper 反解即可。代价是失去了类型头（type id），
 * 但这条队列只有一种消息，不需要。
 */
@Slf4j
@Component
public class StatsCollector {

    /**
     * MQ 不可用时的本地暂存队列上限。
     *
     * <p>有界很重要：无界队列在 MQ 长时间不可用时会 OOM，
     * 那就从"统计降级"变成"服务挂掉"，完全违背了初衷。
     */
    private static final int LOCAL_BUFFER_LIMIT = 100_000;

    /** 每轮补投的最大条数，避免一次补投占住调度线程太久。 */
    private static final int REPLAY_BATCH = 2_000;

    /** 暂存的是序列化后的 JSON——补投时直接发，不需要再序列化一次。 */
    private final Queue<String> localBuffer = new ConcurrentLinkedQueue<>();

    /**
     * 队列长度的独立计数器。
     *
     * <p>⚠️ 不能用 {@code ConcurrentLinkedQueue#size()}——它是 O(n) 遍历。
     * MQ 故障时 buffer 可能积到 10 万条，那时每次跳转都 O(n) 数一遍队列，
     * 等于"统计降级"本身把跳转链路拖垮了，恰好违背了这里的第一契约。
     */
    private final AtomicInteger bufferSize = new AtomicInteger();

    /** 超限被丢弃的事件数。要暴露成指标——静默丢数据比丢数据本身更危险。 */
    private final AtomicLong droppedCount = new AtomicLong();

    private final RabbitTemplate rabbit;
    private final ObjectMapper json = JsonMapper.builder().build();

    public StatsCollector(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /** 跳转链路调用。任何异常都不外抛。 */
    public void record(String shortUri, HttpServletRequest request) {
        try {
            StatsEvent event = new StatsEvent(
                    UUID.randomUUID().toString(),
                    shortUri,
                    clientIp(request),
                    request.getHeader("User-Agent"),
                    request.getHeader("Referer"),
                    System.currentTimeMillis());
            String payload = json.writeValueAsString(event);
            send(payload);
        } catch (RuntimeException e) {
            // 吞掉。统计的任何问题都不能冒泡到跳转链路。
            log.debug("统计采集失败，已忽略: {}", e.getMessage());
        }
    }

    private void send(String payload) {
        try {
            rabbit.convertAndSend(RabbitConfig.EXCHANGE_STATS, RabbitConfig.RK_STATS, payload);
        } catch (RuntimeException e) {
            bufferLocally(payload);
        }
    }

    private void bufferLocally(String payload) {
        if (bufferSize.get() >= LOCAL_BUFFER_LIMIT) {
            droppedCount.incrementAndGet();
            return;
        }
        localBuffer.offer(payload);
        bufferSize.incrementAndGet();
    }

    /**
     * 补投：MQ 恢复后把暂存的事件发出去。
     *
     * <p>发送再次失败就把消息放回队尾并立即停止本轮——说明 MQ 还没恢复，
     * 继续重试只是空转。
     */
    @Scheduled(fixedDelay = 5_000L, initialDelay = 10_000L)
    public void replayBuffered() {
        for (int i = 0; i < REPLAY_BATCH; i++) {
            String payload = localBuffer.poll();
            if (payload == null) {
                return;
            }
            bufferSize.decrementAndGet();
            try {
                rabbit.convertAndSend(RabbitConfig.EXCHANGE_STATS, RabbitConfig.RK_STATS, payload);
            } catch (RuntimeException e) {
                bufferLocally(payload);
                return;
            }
        }
        log.info("统计补投一轮完成，剩余 {} 条", bufferSize.get());
    }

    /** 供监控暴露：本地暂存的待补投条数。 */
    public int pendingCount() {
        return bufferSize.get();
    }

    /** 供监控暴露：因 buffer 满被丢弃的事件数。 */
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

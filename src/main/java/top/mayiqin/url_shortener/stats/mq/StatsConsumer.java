package top.mayiqin.url_shortener.stats.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.mayiqin.url_shortener.config.RabbitConfig;
import top.mayiqin.url_shortener.stats.service.StatsCollector.StatsEvent;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 统计消费端：MQ 事件 → Redis 计数。
 *
 * <h3>Redis 里的三类计数（key 设计见 {@link StatsKeys}）</h3>
 * <ul>
 *   <li><b>PV</b>：{@code INCR}，按 (日期, 小时, 短码) 维度。落库时按<b>增量</b>累加。</li>
 *   <li><b>UV</b>：HyperLogLog {@code PFADD}，按 (日期, 短码) 维度，访客标识 = IP + UA 指纹。
 *       标准误差 0.81%；低基数时 Redis 用 sparse 编码只占几十字节
 *       （{@code hll-sparse-max-bytes} 默认 3000 控制切换），冷链接不会真占 12KB。</li>
 *   <li><b>UIP</b>：HyperLogLog，按 IP 去重。</li>
 * </ul>
 * 另有一个 dirty 集合记录"哪些 (日期,小时,短码) 有新数据"，
 * 落库任务只处理 dirty 成员——避免 {@code KEYS}/{@code SCAN} 全库扫描。
 *
 * <h3>消费幂等</h3>
 * MQ 是至少一次投递，连接抖动时消息可能重复，重复消费的表现是 PV 虚高。
 * Lua 原子写入去重标记和统计计数，去重窗口为 7 天，覆盖持久待投递文件重放。
 *
 * Lua 同时避免多次网络往返和去重标记、计数之间的失败窗口。
 */
@Slf4j
@Component
public class StatsConsumer {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 计数 key 的 TTL：2 天。落库周期是 30 秒，2 天足够兜住任何积压。 */
    private static final long KEY_TTL_SECONDS = Duration.ofDays(2).toSeconds();

    private static final Duration DEDUP_TTL = Duration.ofDays(7);
    private final DefaultRedisScript<Long> collectScript = new DefaultRedisScript<>();

    private final StringRedisTemplate redis;
    private final ObjectMapper json = JsonMapper.builder().build();

    public StatsConsumer(StringRedisTemplate redis) {
        this.redis = redis;
        collectScript.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/collect_stats.lua")));
        collectScript.setResultType(Long.class);
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_STATS)
    public void onMessage(String payload) {
        // 解析失败直接抛：progression 是 重试 3 次 → 死信队列。
        // 不能吞掉——解析失败说明生产端出了 bug，静默丢弃会掩盖它。
        StatsEvent event = json.readValue(payload, StatsEvent.class);

        ZonedDateTime time = Instant.ofEpochMilli(event.timestamp()).atZone(ZONE);
        String date = DATE_FMT.format(time.toLocalDate());
        int hour = time.getHour();
        String uri = event.shortUri();

        String pvKey = StatsKeys.pv(date, hour, uri);
        String uvKey = StatsKeys.uv(date, uri);
        String uipKey = StatsKeys.uip(date, uri);
        String visitor = visitorId(event);

        // 去重标记和计数原子执行，避免 SETNX 成功、pipeline 失败后重试被误跳过。
        Long result = redis.execute(collectScript,
                List.of(StatsKeys.dedup(event.msgId()), pvKey, uvKey, uipKey, StatsKeys.DIRTY),
                String.valueOf(KEY_TTL_SECONDS), String.valueOf(DEDUP_TTL.toSeconds()),
                visitor, event.ip(), StatsKeys.dirtyMember(date, hour, uri));
        if (result == null) throw new IllegalStateException("统计脚本未返回结果");
    }

    /**
     * 访客标识：IP + User-Agent 指纹。
     *
     * <p>没有登录体系时这是业界通行的近似（同一 NAT 出口的不同设备靠 UA 区分开）。
     * 局限性要能说清：同 IP 同浏览器的多个用户会被合并成 1 个 UV。
     * 有登录体系时换成 userId 即可，key 结构不变。
     */
    private static String visitorId(StatsEvent event) {
        String ua = event.userAgent();
        return event.ip() + "|" + (ua == null ? "-" : Integer.toHexString(ua.hashCode()));
    }
}

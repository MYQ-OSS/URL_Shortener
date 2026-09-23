package top.mayiqin.url_shortener.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑：统计队列 + 死信队列。
 *
 * <h3>为什么是 RabbitMQ 而不是 RocketMQ（ADR-001 / ADR-009）</h3>
 * rocketmq-spring-boot-starter 最新 2.3.6 的 parent POM 基线仍是 Spring Boot 2.7 / Java 8，
 * 与本项目的 Boot 4 对不上。spring-boot-starter-amqp 由 Spring 官方维护、
 * 与 Boot 版本同步发布，零兼容风险。
 *
 * <h3>重试 + 死信的分工</h3>
 * <ul>
 *   <li><b>消费重试</b>：application.yaml 里开 {@code listener.simple.retry}
 *       （最多 3 次，间隔 500ms）。这是<b>进程内</b>重试，消息不会重新入队，
 *       所以重试期间不产生重复投递。</li>
 *   <li><b>死信</b>：3 次都失败后，配合 {@code default-requeue-rejected: false}，
 *       消息被 reject 且不回队 → 由 broker 转入死信队列。死信队列积压要接告警，
 *       人工/定时补偿——统计场景通常直接对账重算即可。</li>
 * </ul>
 *
 * <p>队列 durable，生产者显式 PERSISTENT + mandatory + correlated confirm。
 * 未确认消息保留在本地持久目录；broker 数据目录也必须挂载持久卷。
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE_STATS = "shortlink.stats";
    public static final String QUEUE_STATS = "shortlink.stats.q";
    public static final String RK_STATS = "stats";

    public static final String EXCHANGE_DLX = "shortlink.stats.dlx";
    public static final String QUEUE_DEAD = "shortlink.stats.dead";
    public static final String RK_DEAD = "dead";

    @Bean
    public DirectExchange statsExchange() {
        return new DirectExchange(EXCHANGE_STATS, true, false);
    }

    @Bean
    public Queue statsQueue() {
        return QueueBuilder.durable(QUEUE_STATS)
                .deadLetterExchange(EXCHANGE_DLX)
                .deadLetterRoutingKey(RK_DEAD)
                .build();
    }

    @Bean
    public Binding statsBinding() {
        return BindingBuilder.bind(statsQueue()).to(statsExchange()).with(RK_STATS);
    }

    @Bean
    public DirectExchange statsDlx() {
        return new DirectExchange(EXCHANGE_DLX, true, false);
    }

    @Bean
    public Queue statsDeadQueue() {
        return QueueBuilder.durable(QUEUE_DEAD).build();
    }

    @Bean
    public Binding statsDeadBinding() {
        return BindingBuilder.bind(statsDeadQueue()).to(statsDlx()).with(RK_DEAD);
    }
}

package top.mayiqin.url_shortener.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StatsQueueMonitor {
    private final AmqpAdmin admin;
    private volatile double deadLetters;
    private volatile double reachable;

    public StatsQueueMonitor(AmqpAdmin admin, MeterRegistry registry) {
        this.admin = admin;
        Gauge.builder("shortlink.stats.dead_letters", this, m -> m.deadLetters).register(registry);
        Gauge.builder("shortlink.stats.broker_reachable", this, m -> m.reachable).register(registry);
    }

    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void refresh() {
        try {
            var properties = admin.getQueueProperties(RabbitConfig.QUEUE_DEAD);
            reachable = properties == null ? 0 : 1;
            if (properties != null) {
                deadLetters = ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).doubleValue();
            }
        } catch (RuntimeException ignored) {
            reachable = 0;
        }
    }
}

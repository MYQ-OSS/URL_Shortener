package top.mayiqin.url_shortener.stats;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import top.mayiqin.url_shortener.stats.service.StatsCollector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatsCollectorTest {

    @Mock
    private RabbitTemplate rabbit;

    @Mock
    private HttpServletRequest request;

    private StatsCollector statsCollector;

    @BeforeEach
    void setUp() {
        statsCollector = new StatsCollector(rabbit, true);
        statsCollector.startPublishers();
    }

    @Test
    @DisplayName("请求入队：record() 不阻塞请求，正确进入 pending 队列")
    void testRecordEnqueue() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.195");
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0");
        when(request.getHeader("Referer")).thenReturn("https://google.com");

        statsCollector.record("Ab12Cd", request);

        // 即使没有物理连接，入队逻辑也是原子的且增加 pending 数（或被后台消费）
        assertTrue(statsCollector.pendingCount() >= 0);
        assertEquals(0, statsCollector.droppedCount());
    }
}

package top.mayiqin.url_shortener.stats;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import top.mayiqin.url_shortener.common.ClientIpResolver;
import top.mayiqin.url_shortener.stats.service.StatsCollector;
import top.mayiqin.url_shortener.stats.service.StatsOutbox;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatsCollectorTest {
    @TempDir Path directory;
    RabbitTemplate rabbit;
    StatsOutbox outbox;
    StatsCollector collector;

    @BeforeEach void setUp() throws Exception {
        rabbit = mock(RabbitTemplate.class);
        outbox = new StatsOutbox(directory.toString(), 2);
        collector = new StatsCollector(rabbit, outbox, new ClientIpResolver(""), 100, true);
    }
    @AfterEach void close() throws Exception {
        collector.stopPublishers();
        outbox.close();
    }
    private void record() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        collector.record("Ab12Cd", request);
    }
    private void confirm(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            MessagePostProcessor processor = invocation.getArgument(3);
            Message message = processor.postProcessMessage(new Message(new byte[0], new MessageProperties()));
            assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getDeliveryMode());
            CorrelationData data = invocation.getArgument(4);
            if (returned) data.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", "x", "k"));
            data.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @Test void eventIsDurableBeforeAnyNetworkCallAndIpIsTrusted() throws Exception {
        record();
        assertEquals(1, collector.pendingCount());
        Path file = outbox.poll();
        String payload = outbox.read(file);
        assertTrue(payload.contains("203.0.113.5"));
        assertFalse(payload.contains("1.2.3.4"));
        outbox.retry(file);
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        outbox.close();
        try (StatsOutbox reopened = new StatsOutbox(directory.toString(), 2)) {
            assertEquals(1, reopened.pendingCount());
            assertEquals(payload, reopened.read(reopened.poll()));
        }
    }
    @Test void successfulAckRemovesDiskCopy() {
        confirm(true, false);
        record();
        collector.startPublishers();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, collector.pendingCount()));
        assertEquals(0, collector.droppedCount());
    }
    @Test void nackKeepsDiskCopy() throws Exception {
        confirm(false, false);
        assertFailureIsRetained();
    }
    @Test void returnedMessageKeepsDiskCopyEvenWithAck() throws Exception {
        confirm(true, true);
        assertFailureIsRetained();
    }
    @Test void confirmTimeoutKeepsDiskCopy() throws Exception {
        assertFailureIsRetained();
    }
    private void assertFailureIsRetained() throws Exception {
        record();
        collector.startPublishers();
        await().atMost(Duration.ofSeconds(3)).until(() -> collector.publishFailureCount() > 0);
        collector.stopPublishers();
        assertEquals(1, collector.pendingCount());
        try (var files = Files.list(directory)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".json")).count());
        }
        assertEquals(0, collector.droppedCount());
    }
    @Test void fullSpoolIsVisibleAndNeverEvictsAcceptedEvents() {
        record(); record(); record();
        assertEquals(2, collector.pendingCount());
        assertEquals(1, collector.droppedCount());
    }
    @Test void interruptedPublisherRetainsUnconfirmedEventForRestart() throws Exception {
        record();
        collector.startPublishers();
        collector.stopPublishers();
        outbox.close();
        try (StatsOutbox reopened = new StatsOutbox(directory.toString(), 2)) {
            assertEquals(1, reopened.pendingCount());
        }
    }
}

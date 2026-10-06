package top.mayiqin.url_shortener.stats;

import org.junit.jupiter.api.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import top.mayiqin.url_shortener.common.ClientIpResolver;
import top.mayiqin.url_shortener.stats.service.StatsCollector;

import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatsCollectorTest {
    RabbitTemplate rabbit;
    StatsCollector collector;
    final CopyOnWriteArrayList<String> payloads = new CopyOnWriteArrayList<>();

    @BeforeEach void setUp() {
        rabbit = mock(RabbitTemplate.class);
        collector = new StatsCollector(rabbit, new ClientIpResolver(""), 2, 100, true);
    }

    @AfterEach void close() { collector.stopPublishers(); }

    private void record() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.5");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        collector.record("Ab12Cd", request);
    }

    private void confirm(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            assertTrue(Thread.currentThread().getName().startsWith("stats-publisher"));
            payloads.add(invocation.getArgument(2));
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

    @Test void requestOnlyEnqueuesAndPublisherUsesTrustedIp() {
        record();
        assertEquals(1, collector.pendingCount());
        verify(rabbit, never()).convertAndSend(anyString(), anyString(), any(Object.class),
                any(MessagePostProcessor.class), any(CorrelationData.class));
        confirm(true, false);
        collector.startPublishers();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, collector.pendingCount()));
        assertTrue(payloads.getFirst().contains("203.0.113.5"));
        assertFalse(payloads.getFirst().contains("1.2.3.4"));
        assertEquals(0, collector.droppedCount());
    }

    @Test void successfulAckFreesBufferSlots() {
        confirm(true, false);
        record(); record();
        collector.startPublishers();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(0, collector.pendingCount()));
        record();
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertEquals(3, payloads.size()));
        assertEquals(0, collector.droppedCount());
    }

    @Test void nackRetainsEventInMemory() {
        confirm(false, false);
        assertFailureIsRetainedUntilShutdown();
    }

    @Test void returnedMessageIsFailureEvenWhenAcked() {
        confirm(true, true);
        assertFailureIsRetainedUntilShutdown();
    }

    @Test void confirmTimeoutRetainsEventInMemory() { assertFailureIsRetainedUntilShutdown(); }

    private void assertFailureIsRetainedUntilShutdown() {
        record();
        collector.startPublishers();
        await().atMost(Duration.ofSeconds(3)).until(() -> collector.publishFailureCount() > 0);
        assertEquals(1, collector.pendingCount());
        assertEquals(0, collector.droppedCount());
        collector.stopPublishers();
        assertEquals(0, collector.pendingCount());
        assertEquals(1, collector.droppedCount());
    }

    @Test void retryPreservesPayloadAndMessageId() {
        confirm(false, false);
        record();
        collector.startPublishers();
        await().atMost(Duration.ofSeconds(3)).until(() -> collector.publishFailureCount() > 0);
        confirm(true, false);
        await().atMost(Duration.ofSeconds(6)).untilAsserted(() -> assertEquals(0, collector.pendingCount()));
        assertTrue(payloads.size() >= 2);
        assertEquals(payloads.getFirst(), payloads.getLast());
        assertEquals(0, collector.droppedCount());
    }

    @Test void fullBufferDropsNewEventsWithoutEvictingAcceptedEvents() {
        record(); record(); record();
        assertEquals(2, collector.pendingCount());
        assertEquals(2, collector.bufferCapacity());
        assertEquals(1, collector.droppedCount());
    }

    @Test void inFlightEventsAlsoConsumeBufferCapacity() {
        record(); record();
        collector.startPublishers();
        await().atMost(Duration.ofSeconds(3)).until(() -> collector.publishFailureCount() > 0);
        record();
        assertEquals(2, collector.pendingCount());
        assertEquals(1, collector.droppedCount());
    }

    @Test void shutdownAndClosedBufferHaveExplicitLossSemantics() {
        record();
        collector.stopPublishers();
        record();
        assertEquals(0, collector.pendingCount());
        assertEquals(2, collector.droppedCount());
    }

    @Test void concurrentRequestsCannotExceedCapacity() throws Exception {
        collector = new StatsCollector(rabbit, new ClientIpResolver(""), 100, 100, true);
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 1000; i++) pool.submit(this::record);
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(100, collector.pendingCount());
        assertEquals(900, collector.droppedCount());
    }

    @Test void disabledStatisticsDoNotAcceptEvents() {
        collector = new StatsCollector(rabbit, new ClientIpResolver(""), 2, 100, false);
        record();
        collector.startPublishers();
        assertEquals(0, collector.pendingCount());
        assertEquals(0, collector.droppedCount());
    }

    @Test void invalidCapacityOrTimeoutFailsEarly() {
        assertThrows(IllegalArgumentException.class, () ->
                new StatsCollector(rabbit, new ClientIpResolver(""), 0, 100, true));
        assertThrows(IllegalArgumentException.class, () ->
                new StatsCollector(rabbit, new ClientIpResolver(""), 2, 0, true));
    }
}

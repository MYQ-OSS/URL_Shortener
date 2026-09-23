package top.mayiqin.url_shortener.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.mayiqin.url_shortener.stats.service.StatsOutbox;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StatsOutboxTest {
    @TempDir Path directory;
    @Test void concurrentWritersCannotExceedCapacity() throws Exception {
        try (StatsOutbox outbox = new StatsOutbox(directory.toString(), 5);
             ExecutorService executor = Executors.newFixedThreadPool(10)) {
            var calls = new ArrayList<Callable<Boolean>>();
            for (int i = 0; i < 50; i++) calls.add(() -> outbox.append("{}"));
            int accepted = 0;
            for (var result : executor.invokeAll(calls)) if (result.get()) accepted++;
            assertEquals(5, accepted);
            assertEquals(5, outbox.pendingCount());
        }
        try (StatsOutbox restarted = new StatsOutbox(directory.toString(), 5)) {
            assertEquals(5, restarted.pendingCount());
        }
    }
    @Test void twoInstancesCannotShareDirectory() throws Exception {
        try (var first = new StatsOutbox(directory.toString(), 5)) {
            assertThrows(Exception.class, () -> new StatsOutbox(directory.toString(), 5));
            assertTrue(first.append("{}"));
        }
    }
}

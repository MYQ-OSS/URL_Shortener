package top.mayiqin.url_shortener.link;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import top.mayiqin.url_shortener.link.service.DbReadGuard;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DbReadGuardTest {
    @Test void concurrentLimitRejectsWithoutWaitingAndReleasesPermit() throws Exception {
        DbReadGuard guard = new DbReadGuard(1, 100);
        CountDownLatch entered = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Integer> held = executor.submit(() -> guard.execute(() -> {
                entered.countDown();
                try { finish.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return 1;
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try {
                assertEquals(503, assertThrows(ResponseStatusException.class,
                        () -> guard.execute(() -> 2)).getStatusCode().value());
            } finally { finish.countDown(); }
            assertEquals(1, held.get(2, TimeUnit.SECONDS));
            assertEquals(3, guard.execute(() -> 3));
        }
    }
    @Test void errorReleasesPermitAndQpsLimitStillApplies() {
        DbReadGuard guard = new DbReadGuard(1, 2);
        assertThrows(ResponseStatusException.class, () -> guard.execute(() -> { throw new IllegalStateException(); }));
        assertEquals(1, guard.execute(() -> 1));
        assertThrows(ResponseStatusException.class, () -> guard.execute(() -> 2));
    }
}

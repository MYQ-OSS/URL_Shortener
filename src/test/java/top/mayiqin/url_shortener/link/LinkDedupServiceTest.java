package top.mayiqin.url_shortener.link;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import top.mayiqin.url_shortener.link.entity.LinkDedup;
import top.mayiqin.url_shortener.link.mapper.LinkDedupMapper;
import top.mayiqin.url_shortener.link.service.LinkDedupService;
import top.mayiqin.url_shortener.shortcode.ShortCodeGenerator;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LinkDedupServiceTest {
    @Test void tenantIsPartOfStableDedupKey() {
        assertNotEquals(LinkDedupService.key("a", "https://example.com"), LinkDedupService.key("b", "https://example.com"));
        assertEquals(64, LinkDedupService.key("a", "https://example.com").length());
    }
    @Test void concurrentReservationsAndRestartReuseDatabaseWinner() throws Exception {
        LinkDedupMapper mapper = mock(LinkDedupMapper.class);
        ShortCodeGenerator codes = mock(ShortCodeGenerator.class);
        var table = new ConcurrentHashMap<String, LinkDedup>();
        AtomicInteger sequence = new AtomicInteger();
        when(codes.generate()).thenAnswer(call -> String.format("%06d", sequence.incrementAndGet()));
        when(mapper.selectById(anyString())).thenAnswer(call -> table.get(call.getArgument(0)));
        when(mapper.insert(any(LinkDedup.class))).thenAnswer(call -> {
            LinkDedup row = call.getArgument(0);
            if (table.putIfAbsent(row.getDedupKey(), row) != null) throw new DuplicateKeyException("concurrent reservation");
            return 1;
        });
        var service = new LinkDedupService(mapper, codes);
        String key = LinkDedupService.key("a", "https://example.com");
        Set<String> results = new HashSet<>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 100; i++) calls.add(() -> service.reserve(key));
            for (Future<String> result : executor.invokeAll(calls)) results.add(result.get());
        }
        assertEquals(1, results.size());
        assertEquals(results.iterator().next(), new LinkDedupService(mapper, codes).reserve(key));
        assertEquals(1, table.size());
    }
}

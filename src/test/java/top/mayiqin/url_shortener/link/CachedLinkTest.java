package top.mayiqin.url_shortener.link;

import org.junit.jupiter.api.Test;
import top.mayiqin.url_shortener.link.service.CachedLink;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class CachedLinkTest {
    @Test void permanentValueSurvivesRoundTrip() {
        var entry = CachedLink.of("https://example.com/a?b=2", null);
        assertEquals(entry, CachedLink.parse(entry.encode()));
        assertFalse(entry.expired());
    }
    @Test void pastDeadlineInvalidatesEncodedValue() {
        var entry = CachedLink.of("https://example.com", LocalDateTime.now(LinkValidity.ZONE).minusSeconds(1));
        assertTrue(CachedLink.parse(entry.encode()).expired());
        assertTrue(entry.ttl().isNegative());
    }
    @Test void rawUrlCannotBypassNewExpiryCheck() {
        assertNull(CachedLink.parse("https://example.com"));
    }
    @Test void rejectsIncompleteValidityAtApiAndServiceBoundary() {
        assertFalse(LinkValidity.isValid(1, null));
        assertFalse(LinkValidity.isValid(1, LocalDateTime.now(LinkValidity.ZONE).minusSeconds(1)));
        assertFalse(LinkValidity.isValid(0, LocalDateTime.now(LinkValidity.ZONE).plusDays(1)));
        assertFalse(LinkValidity.isValid(3, null));
        assertTrue(LinkValidity.isValid(1, LocalDateTime.now(LinkValidity.ZONE).plusDays(1)));
        assertThrows(IllegalArgumentException.class, () -> LinkValidity.requireValid(1, null));
    }
    @Test void normalizesExpiryToDatabaseSecondPrecision() {
        LocalDateTime later = LocalDateTime.now(LinkValidity.ZONE).plusMinutes(2);
        assertEquals(0, LinkValidity.normalizedEnd(1, later).getNano());
        assertFalse(LinkValidity.isValid(1, LocalDateTime.now(LinkValidity.ZONE).plusNanos(1000)));
    }
}

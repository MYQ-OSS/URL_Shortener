package top.mayiqin.url_shortener.shortcode;

import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BloomTrustTest {
    @Test void emptyOrUnverifiedFilterCannotDenyOldShortCodes() {
        BloomFilterHolder holder = mock(BloomFilterHolder.class);
        ShortCodeGenerator codes = new ShortCodeGenerator(holder);
        assertTrue(codes.mightExist("Ab12Cd"));
        verify(holder, never()).get();
    }

    @Test @SuppressWarnings("unchecked")
    void completedFilterMayRejectDefinitelyMissingCodes() {
        BloomFilterHolder holder = mock(BloomFilterHolder.class);
        RBloomFilter<String> filter = mock(RBloomFilter.class);
        when(holder.isTrusted()).thenReturn(true);
        when(holder.get()).thenReturn(filter);
        assertFalse(new ShortCodeGenerator(holder).mightExist("Ab12Cd"));
        verify(filter).contains("Ab12Cd");
    }
}

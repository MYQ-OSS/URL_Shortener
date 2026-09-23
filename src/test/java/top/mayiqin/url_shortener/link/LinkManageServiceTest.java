package top.mayiqin.url_shortener.link;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.mapper.*;
import top.mayiqin.url_shortener.link.service.*;
import top.mayiqin.url_shortener.support.MapperMetadata;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LinkManageServiceTest {
    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void ownershipAndRiskStatusArePartOfAtomicUpdateAndCacheClearsAfterCommit() {
        MapperMetadata.initialize(Link.class);
        LinkMapper mapper = mock(LinkMapper.class);
        LinkQueryService query = mock(LinkQueryService.class);
        var service = new LinkManageService(mapper, mock(LinkGotoMapper.class), query);
        when(mapper.update(isNull(), any())).thenReturn(1);
        assertTrue(service.updateStatus("Ab12Cd", "owner", 1));
        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        var order = inOrder(mapper, query);
        order.verify(mapper).update(isNull(), wrapper.capture());
        order.verify(query).invalidate("Ab12Cd");
        String sql = wrapper.getValue().getSqlSegment();
        assertTrue(sql.contains("short_uri"));
        assertTrue(sql.contains("gid"));
        assertTrue(sql.contains("CAST(gid AS BINARY)"));
        assertTrue(sql.contains("enable_status <>"));
        assertTrue(wrapper.getValue().getParamNameValuePairs().containsValue("owner"));
    }
    @Test void missingOrUnauthorizedLinkDoesNotInvalidateCaches() {
        var query = mock(LinkQueryService.class);
        var service = new LinkManageService(mock(LinkMapper.class), mock(LinkGotoMapper.class), query);
        assertFalse(service.updateStatus("Ab12Cd", "attacker", 0));
        verifyNoInteractions(query);
    }
    @Test void pubSubInvalidatesOnlyTheRequestedLocalEntry() {
        var cache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder().<String, String>build();
        cache.put("Ab12Cd", "a"); cache.put("Xy12Zz", "b");
        var listener = new CacheInvalidateListener(cache);
        var message = mock(org.springframework.data.redis.connection.Message.class);
        when(message.getBody()).thenReturn("Ab12Cd".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        listener.onMessage(message, null);
        assertNull(cache.getIfPresent("Ab12Cd"));
        assertEquals("b", cache.getIfPresent("Xy12Zz"));
    }
}

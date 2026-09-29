package top.mayiqin.url_shortener.link;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.mapper.*;
import top.mayiqin.url_shortener.link.service.*;
import top.mayiqin.url_shortener.support.MapperMetadata;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LinkManageServiceTest {
    @SuppressWarnings("unchecked")
    private TransactionTemplate immediateTransaction() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call ->
                ((TransactionCallback<Boolean>) call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        return tx;
    }

    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void ownershipAndRiskStatusArePartOfAtomicUpdateAndCacheClearsAfterCommit() {
        MapperMetadata.initialize(Link.class, LocalMessage.class);
        LinkMapper mapper = mock(LinkMapper.class);
        LocalMessageMapper messages = mock(LocalMessageMapper.class);
        LinkQueryService query = mock(LinkQueryService.class);
        var service = new LinkManageService(mapper, mock(LinkGotoMapper.class), query,
                messages, immediateTransaction());
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(query.invalidate("Ab12Cd")).thenReturn(true);
        assertTrue(service.updateStatus("Ab12Cd", "owner", 1));
        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        var order = inOrder(mapper, messages, query);
        order.verify(mapper).update(isNull(), wrapper.capture());
        order.verify(messages).insert(any(LocalMessage.class));
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
        var messages = mock(LocalMessageMapper.class);
        var service = new LinkManageService(mock(LinkMapper.class), mock(LinkGotoMapper.class), query,
                messages, immediateTransaction());
        assertFalse(service.updateStatus("Ab12Cd", "attacker", 0));
        verifyNoInteractions(query);
        verifyNoInteractions(messages);
    }
    @Test void redisFailureLeavesCommittedInvalidationMessageForRetry() {
        MapperMetadata.initialize(Link.class, LocalMessage.class);
        LinkMapper mapper = mock(LinkMapper.class);
        LocalMessageMapper messages = mock(LocalMessageMapper.class);
        LinkQueryService query = mock(LinkQueryService.class);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(query.invalidate("Ab12Cd")).thenReturn(false);
        var service = new LinkManageService(mapper, mock(LinkGotoMapper.class), query,
                messages, immediateTransaction());
        assertTrue(service.updateStatus("Ab12Cd", "owner", 1));
        verify(messages).insert(any(LocalMessage.class));
        verify(messages, never()).update(isNull(), any());
    }
    @Test void failedOutboxInsertPreventsSuccessResponse() {
        MapperMetadata.initialize(Link.class, LocalMessage.class);
        LinkMapper mapper = mock(LinkMapper.class);
        LocalMessageMapper messages = mock(LocalMessageMapper.class);
        LinkQueryService query = mock(LinkQueryService.class);
        when(mapper.update(isNull(), any())).thenReturn(1);
        when(messages.insert(any(LocalMessage.class))).thenThrow(new IllegalStateException("message table down"));
        var service = new LinkManageService(mapper, mock(LinkGotoMapper.class), query,
                messages, immediateTransaction());
        assertThrows(IllegalStateException.class, () -> service.updateStatus("Ab12Cd", "owner", 1));
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

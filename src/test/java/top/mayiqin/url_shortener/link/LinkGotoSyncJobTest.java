package top.mayiqin.url_shortener.link;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import top.mayiqin.url_shortener.link.entity.*;
import top.mayiqin.url_shortener.link.mapper.*;
import top.mayiqin.url_shortener.link.service.LinkGotoSyncJob;
import top.mayiqin.url_shortener.support.MapperMetadata;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LinkGotoSyncJobTest {
    @Test void duplicateGotoIsAcknowledgedIdempotently() throws Exception { runCase(true, 0, 1); }
    @Test void failureSchedulesRetry() throws Exception { runCase(false, 0, 0); }
    @Test void fifthFailureRequiresManualRecovery() throws Exception { runCase(false, 4, 2); }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void runCase(boolean duplicate, int retryCount, int expectedStatus) throws Exception {
        MapperMetadata.initialize(LocalMessage.class, LinkGoto.class);
        var messages = mock(LocalMessageMapper.class);
        var gotos = mock(LinkGotoMapper.class);
        var redisson = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 25, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        LocalMessage message = new LocalMessage();
        message.setId(1L); message.setShardKey("Ab12Cd"); message.setRetryCount(retryCount);
        message.setPayload("{\"gid\":\"default\",\"shortUri\":\"Ab12Cd\"}");
        when(messages.selectList(any())).thenReturn(List.of(message));
        when(gotos.insert(any(LinkGoto.class))).thenThrow(duplicate
                ? new DuplicateKeyException("already there") : new IllegalStateException("down"));
        new LinkGotoSyncJob(messages, gotos, redisson).compensate();
        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(messages).update(isNull(), wrapper.capture());
        String set = wrapper.getValue().getSqlSet();
        assertTrue(set.contains("status="));
        assertTrue(wrapper.getValue().getSqlSegment().contains("shard_key"));
        var params = wrapper.getValue().getParamNameValuePairs();
        String parameter = set.substring(set.indexOf("status=") + 7).split("[,}]")[0];
        parameter = parameter.substring(parameter.lastIndexOf('.') + 1);
        assertEquals(expectedStatus, params.get(parameter));
        if (!duplicate) assertTrue(params.values().stream().anyMatch(v -> v instanceof LocalDateTime));
        verify(lock).unlock();
    }
}

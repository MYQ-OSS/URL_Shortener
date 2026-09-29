package top.mayiqin.url_shortener.link;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.mapper.LocalMessageMapper;
import top.mayiqin.url_shortener.link.service.CacheInvalidationRetryJob;
import top.mayiqin.url_shortener.link.service.LinkQueryService;
import top.mayiqin.url_shortener.support.MapperMetadata;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheInvalidationRetryJobTest {
    @Test void retriesUntilRedisClearsAndThenMarksMessageDone() {
        MapperMetadata.initialize(LocalMessage.class);
        LocalMessageMapper mapper = mock(LocalMessageMapper.class);
        LinkQueryService query = mock(LinkQueryService.class);
        LocalMessage message = CacheInvalidationRetryJob.pendingMessage("Ab12Cd");
        message.setId(123L);
        when(mapper.selectList(any())).thenReturn(List.of(message));
        when(query.invalidate("Ab12Cd")).thenReturn(false, true);
        var job = new CacheInvalidationRetryJob(mapper, query);

        job.retryPending();
        job.retryPending();

        var updates = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), updates.capture());
        assertTrue(updates.getAllValues().stream().allMatch(u -> u.getSqlSegment().contains("shard_key")));
        assertTrue(updates.getAllValues().get(0).getSqlSet().contains("next_retry"));
        assertTrue(updates.getAllValues().get(1).getSqlSet().contains("status"));
        verify(query, times(2)).invalidate("Ab12Cd");
    }
}

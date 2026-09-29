package top.mayiqin.url_shortener.link;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.link.service.ExpiredLinkCleanupJob;
import top.mayiqin.url_shortener.link.service.LinkQueryService;
import top.mayiqin.url_shortener.support.MapperMetadata;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExpiredLinkCleanupJobTest {
    @Test void cleanupUsesShardKeyAndInvalidatesCurrentCacheKey() throws Exception {
        MapperMetadata.initialize(Link.class);
        LinkMapper mapper = mock(LinkMapper.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        LinkQueryService query = mock(LinkQueryService.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, 30, TimeUnit.MINUTES)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        Link expired = new Link();
        expired.setShortUri("Ab12Cd");
        when(mapper.selectList(any())).thenReturn(List.of(expired), List.of());
        when(mapper.update(isNull(), any())).thenReturn(1);

        assertEquals(1L, new ExpiredLinkCleanupJob(mapper, redisson, query).cleanupExpiredLinks());

        ArgumentCaptor<LambdaUpdateWrapper> wrapper = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSegment().contains("short_uri"));
        verify(query).invalidate("Ab12Cd");
        verify(lock).unlock();
    }
}

package top.mayiqin.url_shortener.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import top.mayiqin.url_shortener.idgen.SegmentIdGenerator;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.link.mapper.LocalMessageMapper;
import top.mayiqin.url_shortener.link.service.LinkCreateService;
import top.mayiqin.url_shortener.link.service.LinkQueryService;
import top.mayiqin.url_shortener.link.service.LinkDedupService;
import top.mayiqin.url_shortener.risk.UrlRiskChecker;
import top.mayiqin.url_shortener.shortcode.ShortCodeGenerator;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LinkCreateServiceTest {

    @Mock
    private SegmentIdGenerator idGenerator;
    @Mock
    private ShortCodeGenerator codeGenerator;
    @Mock
    private LinkMapper linkMapper;
    @Mock
    private LinkQueryService queryService;
    @Mock
    private UrlRiskChecker riskChecker;
    @Mock
    private TransactionTemplate tx;
    @Mock
    private LocalMessageMapper messageMapper;
    @Mock
    private LinkDedupService dedupService;

    private LinkCreateService linkCreateService;

    @BeforeEach
    void setUp() {
        linkCreateService = new LinkCreateService(
                idGenerator,
                codeGenerator,
                linkMapper,
                queryService,
                riskChecker,
                tx,
                messageMapper,
                dedupService,
                "s.hicode.top"
        );

        // 模拟 TransactionTemplate.executeWithoutResult
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
    }

    @Test
    @DisplayName("正常创建短链：完成发号、落库与缓存预热")
    void testCreateSuccess() {
        when(dedupService.reserve(any())).thenReturn("Ab12Cd");
        when(idGenerator.nextId()).thenReturn(10086L);
        when(linkMapper.selectOne(any())).thenReturn(null);

        String originUrl = "https://example.com/item/123";
        String fullUrl = linkCreateService.create(originUrl, "default", false, 0, null, "test");

        assertEquals("http://s.hicode.top/Ab12Cd", fullUrl);
        verify(riskChecker).check(originUrl);
        verify(linkMapper).insert((Link) any());
        verify(messageMapper).insert((LocalMessage) any());
        verify(queryService).warmUp("Ab12Cd", originUrl);
    }

    @Test
    @DisplayName("风控拦截：未通过风控校验时抛出异常")
    void testCreateRiskFail() {
        String riskyUrl = "http://127.0.0.1/admin";
        doThrow(new IllegalArgumentException("SSRF 风险 URL")).when(riskChecker).check(riskyUrl);

        assertThrows(IllegalArgumentException.class, () ->
                linkCreateService.create(riskyUrl, "default", false, 0, null, "test")
        );

        verify(linkMapper, never()).insert((Link) any());
    }

    @Test
    @DisplayName("幂等场景：非 forceNew 且已存在同长链时返回已有短码")
    void testCreateIdempotent() {
        String originUrl = "https://example.com/item/123";
        Link existingLink = new Link();
        existingLink.setDomain("s.hicode.top");
        existingLink.setShortUri("Exist6");
        existingLink.setGid("default");
        existingLink.setOriginUrl(originUrl);
        when(dedupService.reserve(any())).thenReturn("Exist6");

        when(linkMapper.selectOne(any())).thenReturn(existingLink);

        String fullUrl = linkCreateService.create(originUrl, "default", false, 0, null, "test");

        assertEquals("http://s.hicode.top/Exist6", fullUrl);
        verify(linkMapper, never()).insert((Link) any());
        verify(codeGenerator, never()).generate();
    }

    @Test
    void forceNewSkipsCanonicalIndex() {
        when(codeGenerator.generate()).thenReturn("New123");
        when(idGenerator.nextId()).thenReturn(123L);
        assertEquals("http://s.hicode.top/New123", linkCreateService.create(
                "https://example.com", "default", true, 0, null, null));
        verifyNoInteractions(dedupService);
        verify(linkMapper, never()).selectOne(any());
    }

    @Test
    void duplicateInsertReturnsConcurrentWinner() {
        when(dedupService.reserve(any())).thenReturn("Winner");
        Link winner = new Link();
        winner.setShortUri("Winner");
        winner.setDomain("s.hicode.top");
        winner.setGid("default");
        winner.setOriginUrl("https://example.com");
        when(linkMapper.selectOne(any())).thenReturn(null, winner);
        doThrow(new org.springframework.dao.DuplicateKeyException("race"))
                .when(linkMapper).insert(any(Link.class));
        assertEquals("http://s.hicode.top/Winner", linkCreateService.create(
                "https://example.com", "default", false, 0, null, null));
        verify(dedupService, never()).replace(any(), any());
        verifyNoInteractions(messageMapper);
    }

    @Test
    void failedTransactionDoesNotWarmCache() {
        when(dedupService.reserve(any())).thenReturn("Ab12Cd");
        doThrow(new IllegalStateException("database unavailable")).when(messageMapper).insert(any(LocalMessage.class));
        assertThrows(IllegalStateException.class, () -> linkCreateService.create(
                "https://example.com", "default", false, 0, null, null));
        verifyNoInteractions(queryService);
    }
}

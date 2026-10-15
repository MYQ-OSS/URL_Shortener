package top.mayiqin.url_shortener.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.link.service.LinkCreateService;
import top.mayiqin.url_shortener.link.service.LinkQueryService;
import top.mayiqin.url_shortener.link.service.LinkDedupService;
import top.mayiqin.url_shortener.link.service.LinkManageService;
import top.mayiqin.url_shortener.risk.UrlRiskChecker;
import top.mayiqin.url_shortener.shortcode.ShortCodeGenerator;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class LinkCreateServiceTest {

    @Mock
    private ShortCodeGenerator codeGenerator;
    @Mock
    private LinkMapper linkMapper;
    @Mock
    private LinkQueryService queryService;
    @Mock
    private UrlRiskChecker riskChecker;
    @Mock
    private LinkDedupService dedupService;

    private LinkCreateService linkCreateService;

    @BeforeEach
    void setUp() {
        linkCreateService = new LinkCreateService(
                codeGenerator,
                linkMapper,
                queryService,
                riskChecker,
                dedupService,
                "s.hicode.top"
        );

    }

    @Test
    @DisplayName("正常创建短链：同步落库与缓存预热")
    void testCreateSuccess() {
        when(dedupService.reserve(any())).thenReturn("Ab12Cd");
        when(linkMapper.selectOne(any())).thenReturn(null);

        String originUrl = "https://example.com/item/123";
        String fullUrl = linkCreateService.create(originUrl, "default", false, 0, null, "test");

        assertEquals("http://s.hicode.top/Ab12Cd", fullUrl);
        verify(riskChecker).check(originUrl);
        verify(linkMapper).insert((Link) any());
        verify(queryService).warmUp("Ab12Cd", originUrl, null);
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
    void configuredHttpsOriginKeepsSchemeAndRemovesTrailingSlash() {
        linkCreateService = new LinkCreateService(codeGenerator, linkMapper, queryService,
                riskChecker, dedupService, "https://s.mayiqin.top/");
        when(codeGenerator.generate()).thenReturn("New123");

        assertEquals("https://s.mayiqin.top/New123", linkCreateService.create(
                "https://example.com", "default", true, 0, null, null));
        verify(linkMapper).insert(argThat((Link link) ->
                "https://s.mayiqin.top/".equals(link.getDomain())));
    }

    @Test
    void idempotentHttpsLinkKeepsStoredOrigin() {
        Link existing = new Link();
        existing.setShortUri("Exist6");
        existing.setDomain("https://s.mayiqin.top");
        existing.setGid("default");
        existing.setOriginUrl("https://example.com");
        when(dedupService.reserve(any())).thenReturn("Exist6");
        when(linkMapper.selectOne(any())).thenReturn(existing);

        assertEquals("https://s.mayiqin.top/Exist6", linkCreateService.create(
                "https://example.com", "default", false, 0, null, null));
        verify(linkMapper, never()).insert(any(Link.class));
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
    void forceNewRetriesDatabaseCollisionWithAnotherRandomCode() {
        when(codeGenerator.generate()).thenReturn("Ab12Cd", "New123");
        doThrow(new org.springframework.dao.DuplicateKeyException("collision"))
                .doReturn(1).when(linkMapper).insert(any(Link.class));
        assertEquals("http://s.hicode.top/New123", linkCreateService.create(
                "https://example.com", "default", true, 0, null, null));
        verify(codeGenerator, times(2)).generate();
        verify(queryService).warmUp("New123", "https://example.com", null);
        verifyNoInteractions(dedupService);
    }

    @Test
    void collisionsAreBoundedAndDoNotWarmCache() {
        when(codeGenerator.generate()).thenReturn("Ab12Cd");
        doThrow(new org.springframework.dao.DuplicateKeyException("collision"))
                .when(linkMapper).insert(any(Link.class));
        assertThrows(IllegalStateException.class, () -> linkCreateService.create(
                "https://example.com", "default", true, 0, null, null));
        verify(linkMapper, times(5)).insert(any(Link.class));
        verifyNoInteractions(queryService);
    }

    @Test
    void forceNewSkipsCanonicalIndex() {
        when(codeGenerator.generate()).thenReturn("New123");
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
    }

    @Test
    void failedInsertDoesNotWarmCache() {
        when(dedupService.reserve(any())).thenReturn("Ab12Cd");
        doThrow(new IllegalStateException("database unavailable")).when(linkMapper).insert(any(Link.class));
        assertThrows(IllegalStateException.class, () -> linkCreateService.create(
                "https://example.com", "default", false, 0, null, null));
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("规范短码已逻辑删除时换新码，不返回永久 410 的链接")
    void deletedCanonicalCodeIsReplacedWithNewCode() {
        String originUrl = "https://example.com/item/123";
        when(dedupService.reserve(any())).thenReturn("Old001", "New002");
        Link deleted = new Link();
        deleted.setShortUri("Old001");
        deleted.setDomain("s.hicode.top");
        deleted.setGid("default");
        deleted.setOriginUrl(originUrl);
        deleted.setDelFlag(1);
        when(linkMapper.selectOne(any())).thenReturn(deleted, null);

        assertEquals("http://s.hicode.top/New002",
                linkCreateService.create(originUrl, "default", false, 0, null, null));

        verify(dedupService).replace(any(), eq("Old001"));
        verify(linkMapper).insert(any(Link.class));
        verify(queryService).warmUp("New002", originUrl, null);
    }

    @Test
    @DisplayName("规范短码已过期时换新码，让同一条 URL 能重新建出可用短链")
    void expiredCanonicalCodeIsReplacedWithNewCode() {
        String originUrl = "https://example.com/item/456";
        LocalDateTime newEnd = LocalDateTime.now().plusDays(7);
        when(dedupService.reserve(any())).thenReturn("Exp001", "New003");
        Link expired = new Link();
        expired.setShortUri("Exp001");
        expired.setDomain("s.hicode.top");
        expired.setGid("default");
        expired.setOriginUrl(originUrl);
        expired.setValidType(1);
        expired.setValidEnd(LocalDateTime.now().minusDays(7));
        when(linkMapper.selectOne(any())).thenReturn(expired, null);

        assertEquals("http://s.hicode.top/New003",
                linkCreateService.create(originUrl, "default", false, 1, newEnd, null));

        verify(dedupService).replace(any(), eq("Exp001"));
        verify(linkMapper).insert(any(Link.class));
        verify(queryService).warmUp(eq("New003"), eq(originUrl), any());
    }

    @Test
    @DisplayName("并发赢家已逻辑删除时不复用其短码，改换预约重建")
    void deletedConcurrentWinnerIsNotReturned() {
        String originUrl = "https://example.com";
        when(dedupService.reserve(any())).thenReturn("Winner", "Fresh1");
        Link winner = new Link();
        winner.setShortUri("Winner");
        winner.setDomain("s.hicode.top");
        winner.setGid("default");
        winner.setOriginUrl(originUrl);
        winner.setDelFlag(1);
        when(linkMapper.selectOne(any())).thenReturn(null, winner, null);
        doThrow(new org.springframework.dao.DuplicateKeyException("race"))
                .doReturn(1).when(linkMapper).insert(any(Link.class));

        assertEquals("http://s.hicode.top/Fresh1",
                linkCreateService.create(originUrl, "default", false, 0, null, null));

        verify(dedupService).replace(any(), eq("Winner"));
        verify(linkMapper, times(2)).insert(any(Link.class));
        verify(queryService).warmUp("Fresh1", originUrl, null);
    }

    @Test
    @DisplayName("用户禁用的既有短码仍然复用，不另发新码")
    void disabledCanonicalCodeIsStillReused() {
        String originUrl = "https://example.com/item/789";
        when(dedupService.reserve(any())).thenReturn("Off001");
        Link disabled = new Link();
        disabled.setShortUri("Off001");
        disabled.setDomain("s.hicode.top");
        disabled.setGid("default");
        disabled.setOriginUrl(originUrl);
        disabled.setEnableStatus(LinkManageService.STATUS_DISABLED_BY_USER);
        when(linkMapper.selectOne(any())).thenReturn(disabled);

        assertEquals("http://s.hicode.top/Off001",
                linkCreateService.create(originUrl, "default", false, 0, null, null));

        verify(dedupService, never()).replace(any(), any());
        verify(linkMapper, never()).insert(any(Link.class));
    }
}

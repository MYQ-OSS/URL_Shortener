package top.mayiqin.url_shortener.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import top.mayiqin.url_shortener.admin.AdminInterceptor;
import top.mayiqin.url_shortener.common.ClientIpResolver;
import top.mayiqin.url_shortener.config.WebConfig;
import top.mayiqin.url_shortener.link.controller.LinkController;
import top.mayiqin.url_shortener.link.controller.RedirectController;
import top.mayiqin.url_shortener.link.service.LinkCreateService;
import top.mayiqin.url_shortener.link.service.LinkManageService;
import top.mayiqin.url_shortener.link.service.LinkQueryService;
import top.mayiqin.url_shortener.ratelimit.RateLimiter;
import top.mayiqin.url_shortener.stats.service.StatsCollector;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = {LinkController.class, RedirectController.class}, properties = {
        "shortlink.auth.tokens.default=test-api-token-012345678901234567890",
        "shortlink.admin.token=test-admin-token-012345678901234567890"})
@Import({WebConfig.class, ApiInterceptor.class, ApiCredentials.class, AdminInterceptor.class, ClientIpResolver.class})
class ApiSecurityTest {
    private static final String TOKEN = "Bearer test-api-token-012345678901234567890";
    @Autowired MockMvc mvc;
    @MockitoBean LinkCreateService create;
    @MockitoBean LinkManageService manage;
    @MockitoBean LinkQueryService query;
    @MockitoBean RateLimiter limiter;
    @MockitoBean StatsCollector stats;

    @Test void missingAndInvalidCredentialsAreRejected() throws Exception {
        mvc.perform(get("/api/link/list").param("gid", "default")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/link/Ab12Cd/disable").header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(manage);
    }
    @Test void tokenCannotSelectAnotherGid() throws Exception {
        mvc.perform(get("/api/link/list").param("gid", "victim").header("Authorization", TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/link").header("Authorization", TOKEN).contentType("application/json")
                .content("{\"originUrl\":\"https://example.com\",\"gid\":\"victim\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(create, manage, limiter);
    }
    @Test void authorizedMutationPassesServerIdentity() throws Exception {
        when(manage.updateStatus("Ab12Cd", "default", 1)).thenReturn(true);
        mvc.perform(post("/api/link/Ab12Cd/disable").header("Authorization", TOKEN))
                .andExpect(status().isNoContent());
        verify(manage).updateStatus("Ab12Cd", "default", 1);
    }
    @Test void createUsesAuthenticatedGidForRateLimitAndService() throws Exception {
        when(limiter.tryAcquireCreate("default")).thenReturn(true);
        when(create.create("https://example.com", "default", false, null, null, null))
                .thenReturn("http://localhost:8080/Ab12Cd");
        mvc.perform(post("/api/link").header("Authorization", TOKEN).contentType("application/json")
                .content("{\"originUrl\":\"https://example.com\",\"gid\":\"default\"}"))
                .andExpect(status().isOk()).andExpect(content().string("http://localhost:8080/Ab12Cd"));
        verify(limiter).tryAcquireCreate("default");
    }
    @Test void invalidExpiryCombosAreRejectedBeforeCreation() throws Exception {
        mvc.perform(post("/api/link").header("Authorization", TOKEN).contentType("application/json")
                .content("{\"originUrl\":\"https://example.com\",\"gid\":\"default\",\"validType\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/link").header("Authorization", TOKEN).contentType("application/json")
                .content("{\"originUrl\":\"https://example.com\",\"gid\":\"default\",\"validType\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/link").header("Authorization", TOKEN).contentType("application/json")
                .content("{\"originUrl\":\"https://example.com\",\"gid\":\"default\",\"validType\":1,\"validEnd\":\"bad-date\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(create);
    }
    @Test void publicRedirectNeedsNoTokenAndReturnsExpectedStatus() throws Exception {
        when(limiter.tryAcquireRedirect(any(), eq("Ab12Cd"))).thenReturn(true);
        when(query.resolve("Ab12Cd")).thenReturn("https://example.com");
        mvc.perform(get("/Ab12Cd")).andExpect(status().isFound()).andExpect(header().string("Location", "https://example.com"));
        when(query.resolve("Ab12Cd")).thenReturn(null);
        mvc.perform(get("/Ab12Cd")).andExpect(status().isGone());
        when(limiter.tryAcquireRedirect(any(), eq("Ab12Cd"))).thenReturn(false);
        mvc.perform(get("/Ab12Cd")).andExpect(status().isTooManyRequests());
    }
    @Test void overloadRemains503InsteadOf500() throws Exception {
        when(limiter.tryAcquireRedirect(any(), any())).thenReturn(true);
        when(query.resolve("Ab12Cd")).thenThrow(new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "busy"));
        mvc.perform(get("/Ab12Cd")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"));
    }
    @Test void unsafeOrReusedTokenConfigurationFailsEarly() {
        assertThrows(IllegalArgumentException.class, () -> new AdminInterceptor("AdminSecret123"));
        ApiCredentials credentials = new ApiCredentials();
        assertThrows(IllegalArgumentException.class, () -> new ApiInterceptor(credentials));
        credentials.setTokens(Map.of("a", TOKEN, "b", TOKEN));
        assertThrows(IllegalArgumentException.class, () -> new ApiInterceptor(credentials));
    }
    @Test void adminQueryTokenIsNeverAccepted() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        String secret = "test-admin-token-012345678901234567890";
        request.addParameter("token", secret);
        var interceptor = new AdminInterceptor(secret);
        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
        request.addHeader("X-Admin-Token", secret);
        assertTrue(interceptor.preHandle(request, response, new Object()));
    }
}

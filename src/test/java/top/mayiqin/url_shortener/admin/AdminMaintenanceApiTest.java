package top.mayiqin.url_shortener.admin;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import top.mayiqin.url_shortener.auth.ApiCredentials;
import top.mayiqin.url_shortener.auth.ApiInterceptor;
import top.mayiqin.url_shortener.config.WebConfig;
import top.mayiqin.url_shortener.link.service.ExpiredLinkCleanupJob;
import top.mayiqin.url_shortener.risk.UrlRiskChecker;
import top.mayiqin.url_shortener.stats.service.StatsCollector;
import top.mayiqin.url_shortener.stats.service.StatsFlushService;

import java.util.List;
import java.util.Locale;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AdminController.class, properties = {
        "shortlink.auth.tokens.default=test-api-token-012345678901234567890",
        "shortlink.admin.token=test-admin-token-012345678901234567890"})
@Import({WebConfig.class, ApiInterceptor.class, ApiCredentials.class, AdminInterceptor.class})
class AdminMaintenanceApiTest {
    private static final String TOKEN = "test-admin-token-012345678901234567890";
    @Autowired MockMvc mvc;
    @MockitoBean Cache<String, String> localCache;
    @MockitoBean StatsCollector statsCollector;
    @MockitoBean StatsFlushService statsFlushService;
    @MockitoBean UrlRiskChecker riskChecker;
    @MockitoBean ExpiredLinkCleanupJob cleanupJob;

    @Test void maintenanceRequiresAdministratorHeader() throws Exception {
        mvc.perform(post("/admin/cache/local/clear")).andExpect(status().isForbidden());
        when(localCache.estimatedSize()).thenReturn(12L);
        mvc.perform(post("/admin/cache/local/clear").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cleared").value(12));
        verify(localCache).invalidateAll();
        verify(localCache).cleanUp();
    }

    @Test void statsOnlyDescribeActiveComponentsAndTheBestEffortContract() throws Exception {
        when(localCache.stats()).thenReturn(CacheStats.empty());
        when(statsCollector.bufferCapacity()).thenReturn(4096);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            mvc.perform(get("/admin/stats").header("X-Admin-Token", TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.localCache.hitRate").value("1.0000"))
                    .andExpect(jsonPath("$.stats.deliveryMode").value("best-effort"))
                    .andExpect(jsonPath("$.stats.bufferCapacity").value(4096))
                    .andExpect(jsonPath("$.idGenerator").doesNotExist())
                    .andExpect(jsonPath("$.bloomVersion").doesNotExist());
        } finally { Locale.setDefault(previous); }
    }

    @Test void retiredMaintenanceEndpointsAreNotAvailable() throws Exception {
        mvc.perform(post("/admin/bloom/rebuild").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound());
        mvc.perform(get("/admin/messages/failed").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound());
        mvc.perform(post("/admin/messages/Ab12Cd/42/retry").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test void expiredCleanupAndSelectiveRiskUpdatesRemainAvailable() throws Exception {
        when(cleanupJob.cleanupExpiredLinks()).thenReturn(3L);
        mvc.perform(post("/admin/expired/clean").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cleanedRows").value(3));
        mvc.perform(post("/admin/risk/reload").header("X-Admin-Token", TOKEN)
                .contentType("application/json").content("{\"blockedDomains\":[\"evil.example\"]}"))
                .andExpect(status().isOk());
        verify(riskChecker).reloadRules(List.of("evil.example"), null);
    }

    @Test void administratorHeaderPreflightAndRequestRemainSeparate() throws Exception {
        mvc.perform(options("/admin/cache/local/clear")
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "x-admin-token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(get("/admin/stats").header("Origin", "http://localhost:5173"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }
}

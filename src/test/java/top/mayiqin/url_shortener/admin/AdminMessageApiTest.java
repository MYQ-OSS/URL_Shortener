package top.mayiqin.url_shortener.admin;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import top.mayiqin.url_shortener.auth.ApiCredentials;
import top.mayiqin.url_shortener.auth.ApiInterceptor;
import top.mayiqin.url_shortener.config.WebConfig;
import top.mayiqin.url_shortener.idgen.SegmentIdGenerator;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.service.ExpiredLinkCleanupJob;
import top.mayiqin.url_shortener.link.service.LinkGotoSyncJob;
import top.mayiqin.url_shortener.risk.UrlRiskChecker;
import top.mayiqin.url_shortener.shortcode.BloomFilterHolder;
import top.mayiqin.url_shortener.shortcode.BloomFilterRebuilder;
import top.mayiqin.url_shortener.stats.service.StatsCollector;
import top.mayiqin.url_shortener.stats.service.StatsFlushService;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(value = AdminController.class, properties = {
        "shortlink.auth.tokens.default=test-api-token-012345678901234567890",
        "shortlink.admin.token=test-admin-token-012345678901234567890"})
@Import({WebConfig.class, ApiInterceptor.class, ApiCredentials.class, AdminInterceptor.class})
class AdminMessageApiTest {
    private static final String TOKEN = "test-admin-token-012345678901234567890";
    @Autowired MockMvc mvc;
    @MockitoBean BloomFilterRebuilder rebuilder;
    @MockitoBean BloomFilterHolder bloomHolder;
    @MockitoBean Cache<String, String> localCache;
    @MockitoBean SegmentIdGenerator idGenerator;
    @MockitoBean StatsCollector statsCollector;
    @MockitoBean StatsFlushService statsFlushService;
    @MockitoBean UrlRiskChecker riskChecker;
    @MockitoBean ExpiredLinkCleanupJob cleanupJob;
    @MockitoBean LinkGotoSyncJob gotoSyncJob;

    @Test void onlyAdminCanListAndRetryFailedGotoMessages() throws Exception {
        LocalMessage failed = new LocalMessage();
        failed.setId(42L); failed.setShardKey("Ab12Cd"); failed.setStatus(2);
        when(gotoSyncJob.listFailed(20)).thenReturn(List.of(failed));
        when(gotoSyncJob.retryFailed(42L, "Ab12Cd")).thenReturn(true);

        mvc.perform(get("/admin/messages/failed")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/messages/failed").header("X-Admin-Token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(42));
        mvc.perform(post("/admin/messages/Ab12Cd/42/retry").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNoContent());
        verify(gotoSyncJob).retryFailed(42L, "Ab12Cd");
    }

    @Test void wrongIdIsClientErrorAndMissingMessageIs404() throws Exception {
        mvc.perform(post("/admin/messages/Ab12Cd/not-a-number/retry").header("X-Admin-Token", TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/messages/Ab12Cd/42/retry").header("X-Admin-Token", TOKEN))
                .andExpect(status().isNotFound());
    }
}

package top.mayiqin.url_shortener.link.controller;

import top.mayiqin.url_shortener.common.ClientIpResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import top.mayiqin.url_shortener.link.service.LinkQueryService;
import top.mayiqin.url_shortener.ratelimit.RateLimiter;
import top.mayiqin.url_shortener.stats.service.StatsCollector;

import java.net.URI;

/** 跳转使用 302，便于每次请求检查禁用与过期状态；访问趋势统计仅异步入队。 */
@RestController
public class RedirectController {

    private final LinkQueryService queryService;
    private final RateLimiter rateLimiter;
    private final StatsCollector statsCollector;
    private final ClientIpResolver clientIpResolver;

    public RedirectController(LinkQueryService queryService,
                              RateLimiter rateLimiter,
                              StatsCollector statsCollector,
                              ClientIpResolver clientIpResolver) {
        this.queryService = queryService;
        this.rateLimiter = rateLimiter;
        this.statsCollector = statsCollector;
        this.clientIpResolver = clientIpResolver;
    }

    @GetMapping("/{shortUri:[0-9a-zA-Z]{6}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortUri,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        // 跳转接口的限流要"宽松"：正常用户可能高频访问同一个短链（刷新、多设备）。
        // 和创建接口的严格限流是两套完全不同的策略。
        if (!rateLimiter.tryAcquireRedirect(clientIpResolver.resolve(request), shortUri)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        String target = queryService.resolve(shortUri);
        if (target == null) {
            // 失效/不存在 → 410 Gone，而不是 302 到首页（302 到首页会让用户困惑）
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        // 统计走异步，且绝不能影响跳转：内部已做"发送失败降级本地暂存"
        statsCollector.record(shortUri, request);

        return ResponseEntity.status(HttpStatus.FOUND)     // 302
                .location(URI.create(target))
                .build();
    }

}

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

/**
 * 跳转入口。<b>全系统 QPS 最高的接口，必须最瘦。</b>
 *
 * <h3>为什么用 302 而不是 301（这题的答法很关键）</h3>
 * 大多数人答"因为要统计"。<b>更有说服力的答案是风控</b>：
 * <blockquote>
 * 短链被举报为钓鱼后必须能立即断掉。用 301 的话，浏览器已经强缓存了，
 * 后续请求根本不到服务器，<b>你永远拦不住已经缓存过的用户</b>。
 * 把风控优先级放在服务器压力之上，是这里的决定性理由。
 * </blockquote>
 *
 * <p>补充两个能加深度的点：
 * <ul>
 *   <li>商业平台常用 <b>{@code 301 + Cache-Control: private, max-age=90}</b> 的组合拳，
 *       既拿到 301 的减压效果，又把缓存窗口压到 90 秒、风控最多 90 秒生效。</li>
 *   <li><b>307/308</b> 严格保留请求方法和 body（301/302 在历史实现中会把 POST 降级成 GET）。
 *       短链 99% 是 GET 所以 302 够用；但如果短链要用在 API 回调 / Webhook 上，307 才对。</li>
 * </ul>
 *
 * <h3>这条链路的三个"瘦身"措施</h3>
 * <ol>
 *   <li>不走 Spring Security 过滤器链（在 SecurityConfig 里 permitAll + 排除）</li>
 *   <li>不开事务、不做 Bean 校验</li>
 *   <li><b>不打同步 INFO 日志</b> —— 这是压测时用 async-profiler 抓火焰图最常发现的瓶颈，
 *       改异步 Appender 通常能提升 15-30% QPS</li>
 * </ol>
 *
 * <p>另外：绝对不要用 HTML {@code meta refresh} 或 JS {@code location.href} 跳转
 * （多一个完整 RT + 页面渲染，延迟从 ~10ms 变 ~300ms，SEO 差，部分 App 内嵌 WebView 不跟随）。
 */
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

package top.mayiqin.url_shortener.link.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.mayiqin.url_shortener.link.service.LinkCreateService;
import top.mayiqin.url_shortener.link.service.LinkQueryService;
import top.mayiqin.url_shortener.ratelimit.RateLimiter;

import java.time.LocalDateTime;

/** 管理接口。QPS 低，可以"重"。 */
@RestController
@RequestMapping("/api/link")
public class LinkController {

    private final LinkCreateService createService;
    private final LinkQueryService queryService;
    private final RateLimiter rateLimiter;

    public LinkController(LinkCreateService createService,
                          LinkQueryService queryService,
                          RateLimiter rateLimiter) {
        this.createService = createService;
        this.queryService = queryService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateRequest req, HttpServletRequest http) {
        // 创建接口的限流要"严格"：按用户维度，防刷量
        if (!rateLimiter.tryAcquireCreate(req.getGid())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("创建过于频繁，请稍后再试");
        }
        String shortUrl = createService.create(
                req.getOriginUrl(), req.getGid(), req.isForceNew(),
                req.getValidType(), req.getValidEnd(), req.getDescribe());
        return ResponseEntity.ok(shortUrl);
    }

    /** 禁用短链。要求秒级生效，所以必须清多级缓存 + 广播。 */
    @PostMapping("/{shortUri}/disable")
    public ResponseEntity<Void> disable(@PathVariable String shortUri) {
        // TODO Week 4：先更新 DB enable_status，再走 invalidate
        queryService.invalidate(shortUri);
        return ResponseEntity.noContent().build();
    }

    @Data
    public static class CreateRequest {
        /**
         * URL 长度上限 2048。
         *
         * <p>URL 理论上无长度限制，但浏览器实践在 2048~8192 之间，
         * 而且 {@code VARCHAR(2048)} utf8mb4 已经是 8192 字节。太长的直接拒绝，
         * 不要让脏数据进库。
         */
        @NotBlank
        @Size(max = 2048)
        private String originUrl;

        @NotBlank
        @Size(max = 32)
        private String gid;

        /**
         * true 时跳过幂等强制生成新短码。
         *
         * <p>营销场景需要它：同一落地页生成多个短码分投不同渠道做归因。
         */
        private boolean forceNew = false;

        private Integer validType;
        private LocalDateTime validEnd;

        @Size(max = 128)
        private String describe;
    }
}

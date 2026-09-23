package top.mayiqin.url_shortener.link.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.service.LinkCreateService;
import top.mayiqin.url_shortener.link.service.LinkManageService;
import top.mayiqin.url_shortener.ratelimit.RateLimiter;
import top.mayiqin.url_shortener.auth.ApiInterceptor;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/** 管理接口。QPS 低，可以"重"。 */
@Data
@RestController
@RequestMapping("/api/link")
public class LinkController {

    private final LinkCreateService createService;
    private final LinkManageService manageService;
    private final RateLimiter rateLimiter;

    /** 创建短链。 */
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateRequest req,
                                    @RequestAttribute(ApiInterceptor.GID_ATTRIBUTE) String gid) {
        requireOwner(req.getGid(), gid);
        // 创建接口的限流要"严格"：按用户维度，防刷量
        if (!rateLimiter.tryAcquireCreate(gid)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body("创建过于频繁，请稍后再试");
        }
        String shortUrl = createService.create(
                req.getOriginUrl(), gid, req.isForceNew(),
                req.getValidType(), req.getValidEnd(), req.getDescribe());
        return ResponseEntity.ok(shortUrl);
    }

    /** 禁用短链。要求秒级生效：先改 DB，再清多级缓存 + 广播（顺序理由见 LinkManageService）。 */
    @PostMapping("/{shortUri}/disable")
    public ResponseEntity<Void> disable(@PathVariable String shortUri,
                                        @RequestAttribute(ApiInterceptor.GID_ATTRIBUTE) String gid) {
        boolean ok = manageService.updateStatus(shortUri, gid, LinkManageService.STATUS_DISABLED_BY_USER);
        return ok ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** 恢复被禁用的短链。 */
    @PostMapping("/{shortUri}/enable")
    public ResponseEntity<Void> enable(@PathVariable String shortUri,
                                       @RequestAttribute(ApiInterceptor.GID_ATTRIBUTE) String gid) {
        boolean ok = manageService.updateStatus(shortUri, gid, LinkManageService.STATUS_ENABLED);
        return ok ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * 按 gid 列出短链。走 t_link_goto 异构索引两步查询（ADR-005）：
     * 单分片拿短码 → 按分片键回查主表，全程无广播。
     */
    @GetMapping("/list")
    public ResponseEntity<List<Link>> list(@RequestParam @NotBlank String gid,
                                           @RequestParam(defaultValue = "20") int limit,
                                           @RequestAttribute(ApiInterceptor.GID_ATTRIBUTE) String authenticatedGid) {
        requireOwner(gid, authenticatedGid);
        return ResponseEntity.ok(manageService.listByGid(authenticatedGid, limit));
    }

    private static void requireOwner(String requestedGid, String authenticatedGid) {
        if (!authenticatedGid.equals(requestedGid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "不能操作其他 gid 的短链");
        }
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

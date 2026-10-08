package top.mayiqin.url_shortener.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 全局异常 → 干净的 HTTP 响应。
 *
 * <p>没有它时任何未捕获异常都会以 500 + 完整堆栈返回给客户端——
 * 堆栈里有表名、索引名、框架版本号，等于把系统内部结构送给攻击者当侦察情报。
 *
 * <p>约定：
 * <ul>
 *   <li>{@link IllegalArgumentException} —— 业务校验失败（风控拒绝、URL 非法等），
 *       是调用方的问题 → 400 + 原因</li>
 *   <li>其余异常 —— 服务端的问题 → 500 + 通用文案，细节只进日志</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        var builder = ResponseEntity.status(e.getStatusCode());
        if (e.getStatusCode().value() == 503) {
            builder.header("Retry-After", "1");
        }
        return builder.body(Map.of("error", e.getReason() == null ? "请求失败" : e.getReason()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalidBody(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("请求参数非法");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> malformedBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求体格式或字段类型非法"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> invalidParameter(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "请求参数类型非法"));
    }

    /**
     * 无匹配路由（如非 6 位的路径 {@code /abc} 不满足跳转接口的正则约束）→ 404。
     *
     * <p>⚠️ 踩过的坑：没有这个方法时它会掉进下面的兜底 Exception 处理返回 500——
     * catch-all 异常处理会把"正常的 404 语义"也吞掉，必须把这类异常显式放行。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> notFound(NoResourceFoundException e) {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> internal(Exception e) {
        // 细节进日志，不进响应
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "服务内部错误，请稍后再试"));
    }
}

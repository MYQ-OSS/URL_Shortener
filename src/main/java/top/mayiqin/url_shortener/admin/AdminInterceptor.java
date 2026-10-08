package top.mayiqin.url_shortener.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.cors.CorsUtils;
import top.mayiqin.url_shortener.auth.TokenSupport;

/**
 * 运维/管理端接口安全拦截器。
 *
 * <p>拦截 /admin/** 请求，仅接受 Header 'X-Admin-Token'。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final String adminToken;

    public AdminInterceptor(@Value("${shortlink.admin.token:}") String adminToken) {
        TokenSupport.validate(adminToken);
        this.adminToken = adminToken;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) throws Exception {
        if (CorsUtils.isPreFlightRequest(request)) {
            return true;
        }
        String token = request.getHeader("X-Admin-Token");
        if (TokenSupport.matches(adminToken, token)) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\": \"Forbidden: Invalid admin token\"}");
        return false;
    }
}

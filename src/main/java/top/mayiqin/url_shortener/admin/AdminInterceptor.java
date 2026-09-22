package top.mayiqin.url_shortener.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 运维/管理端接口安全拦截器。
 *
 * <p>拦截 /admin/** 请求，校验 Header 'X-Admin-Token' 或 Query 参数 'token'。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final String adminToken;

    public AdminInterceptor(@Value("${shortlink.admin.token:AdminSecret123}") String adminToken) {
        this.adminToken = adminToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("X-Admin-Token");
        if (token == null || token.isBlank()) {
            token = request.getParameter("token");
        }

        if (adminToken.equals(token)) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\": \"Forbidden: Invalid admin token\"}");
        return false;
    }
}

package top.mayiqin.url_shortener.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.cors.CorsUtils;

import java.util.HashSet;
import java.util.Map;

/** 服务端 Token -> gid 绑定；请求体里的 gid 不能充当身份凭据。 */
@Component
public class ApiInterceptor implements HandlerInterceptor {
    public static final String GID_ATTRIBUTE = "authenticatedGid";
    private final Map<String, String> tokens;

    public ApiInterceptor(ApiCredentials credentials) {
        tokens = Map.copyOf(credentials.getTokens());
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("必须配置 shortlink.auth.tokens");
        }
        var seen = new HashSet<String>();
        tokens.forEach((gid, token) -> {
            if (gid.isBlank() || gid.length() > 32 || !gid.equals(gid.trim())) {
                throw new IllegalArgumentException("认证 gid 必须为 1~32 字符且无首尾空格");
            }
            TokenSupport.validate(token);
            if (!seen.add(token)) {
                throw new IllegalArgumentException("不同 gid 不能共用同一个 API Token");
            }
        });
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 浏览器预检只询问跨域权限，不携带业务 Token；来源由 MVC CORS 规则限制。
        if (CorsUtils.isPreFlightRequest(request)) return true;
        String header = request.getHeader("Authorization");
        String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                ? header.substring(7) : null;
        String gid = null;
        for (var entry : tokens.entrySet()) {
            if (TokenSupport.matches(entry.getValue(), token)) {
                gid = entry.getKey();
            }
        }
        if (gid != null) {
            request.setAttribute(GID_ATTRIBUTE, gid);
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Invalid API token\"}");
        return false;
    }
}

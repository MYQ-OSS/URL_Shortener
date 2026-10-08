package top.mayiqin.url_shortener.config;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.mayiqin.url_shortener.admin.AdminInterceptor;
import top.mayiqin.url_shortener.auth.ApiInterceptor;
import java.util.Arrays;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminInterceptor adminInterceptor;
    private final ApiInterceptor apiInterceptor;
    private final String[] allowedOrigins;

    public WebConfig(AdminInterceptor adminInterceptor, ApiInterceptor apiInterceptor,
                     @Value("${shortlink.cors.allowed-origins:http://127.0.0.1:5173,http://localhost:5173}") String origins) {
        this.adminInterceptor = adminInterceptor;
        this.apiInterceptor = apiInterceptor;
        this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim)
                .filter(origin -> !origin.isEmpty()).toArray(String[]::new);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**");
        registry.addInterceptor(apiInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(@NonNull CorsRegistry registry) {
        // MVC 校验来源，鉴权拦截器只跳过合法形态的预检；正式请求仍必须携带 Token。
        for (String path : new String[]{"/api/**", "/admin/**"}) {
            registry.addMapping(path)
                    .allowedOrigins(allowedOrigins)
                    .allowedMethods("GET", "POST", "OPTIONS")
                    .allowedHeaders("Authorization", "X-Admin-Token", "Content-Type", "Accept")
                    .exposedHeaders("Retry-After", "WWW-Authenticate")
                    .allowCredentials(false)
                    .maxAge(600);
        }
    }
}

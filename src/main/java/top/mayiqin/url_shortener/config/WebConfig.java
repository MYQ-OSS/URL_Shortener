package top.mayiqin.url_shortener.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import top.mayiqin.url_shortener.admin.AdminInterceptor;
import top.mayiqin.url_shortener.auth.ApiInterceptor;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AdminInterceptor adminInterceptor;
    private final ApiInterceptor apiInterceptor;

    public WebConfig(AdminInterceptor adminInterceptor, ApiInterceptor apiInterceptor) {
        this.adminInterceptor = adminInterceptor;
        this.apiInterceptor = apiInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**");
        registry.addInterceptor(apiInterceptor).addPathPatterns("/api/**");
    }
}

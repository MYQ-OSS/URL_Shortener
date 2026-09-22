package top.mayiqin.url_shortener.risk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UrlRiskCheckerTest {

    @Test
    @DisplayName("正常公网 URL：合法 http/https 正常放行")
    void testCheckValidPublicUrl() {
        UrlRiskChecker checker = new UrlRiskChecker(true, List.of("evil.com"), List.of());
        assertDoesNotThrow(() -> checker.check("https://www.baidu.com"));
        assertDoesNotThrow(() -> checker.check("http://github.com/index"));
    }

    @Test
    @DisplayName("Protocol 风控：拒绝 javascript:/file:/data: 等协议")
    void testCheckForbiddenSchemes() {
        UrlRiskChecker checker = new UrlRiskChecker(true, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> checker.check("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> checker.check("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> checker.check("data:text/html,test"));
    }

    @Test
    @DisplayName("SSRF 防护：拦截 127.0.0.1 及 localhost 等内网地址")
    void testCheckSSRF() {
        UrlRiskChecker checker = new UrlRiskChecker(true, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> checker.check("http://127.0.0.1:8080/actuator"));
        assertThrows(IllegalArgumentException.class, () -> checker.check("http://localhost/admin"));
    }

    @Test
    @DisplayName("黑名单域名：拒绝配置在 blocked-domains 列表及其子域名")
    void testCheckBlockedDomains() {
        UrlRiskChecker checker = new UrlRiskChecker(false, List.of("evil.com"), List.of());
        assertThrows(IllegalArgumentException.class, () -> checker.check("http://evil.com/login"));
        assertThrows(IllegalArgumentException.class, () -> checker.check("http://phish.evil.com/login"));
        // 确保不会误伤 note evil.com
        assertDoesNotThrow(() -> checker.check("https://noteevil.com"));
    }
}

package top.mayiqin.url_shortener.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Token 不提供默认值，并使用恒定时间比较，避免逐字符比较泄露信息。 */
public final class TokenSupport {
    private TokenSupport() { }

    public static void validate(String token) {
        if (token == null || token.isBlank() || token.length() < 32 || !token.equals(token.trim())) {
            throw new IllegalArgumentException("请配置至少 32 字符、无首尾空格的独立随机 Token");
        }
    }

    public static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}

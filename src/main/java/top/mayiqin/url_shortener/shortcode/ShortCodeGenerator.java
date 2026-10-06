package top.mayiqin.url_shortener.shortcode;

import org.springframework.stereotype.Component;
import top.mayiqin.url_shortener.common.ShortCodeFormat;

import java.security.SecureRandom;

/** 随机短码不需要 Bloom 预约；并发碰撞由数据库唯一索引及创建重试兜底。 */
@Component
public class ShortCodeGenerator {
    private final SecureRandom random = new SecureRandom();
    private final char[] alphabet = ShortCodeFormat.alphabet();

    public String generate() {
        char[] code = new char[ShortCodeFormat.CODE_LENGTH];
        for (int i = 0; i < code.length; i++) {
            code[i] = alphabet[random.nextInt(alphabet.length)];
        }
        return new String(code);
    }
}

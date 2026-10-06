package top.mayiqin.url_shortener.shortcode;

import org.junit.jupiter.api.Test;
import top.mayiqin.url_shortener.common.ShortCodeFormat;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class ShortCodeGeneratorTest {
    @Test void randomCodesUseTheEntireSixCharacterAlphabet() {
        var generator = new ShortCodeGenerator();
        var codes = new HashSet<String>();
        for (int i = 0; i < 10000; i++) {
            String code = generator.generate();
            assertTrue(ShortCodeFormat.isWellFormed(code));
            codes.add(code);
        }
        // 随机生成可以碰撞，数据库才是最终唯一性保障；这里仅检查生成器没有退化。
        assertTrue(codes.size() > 9000);
    }

    @Test void formatAcceptsOldCodesAndRejectsInvalidInput() {
        assertTrue(ShortCodeFormat.isWellFormed("Ab12Cd"));
        assertTrue(ShortCodeFormat.isWellFormed("000000"));
        assertTrue(ShortCodeFormat.isWellFormed("ZZzz99"));
        for (String code : new String[]{"", "abcde", "abcdefg", "Ab12_C", "Ａb12Cd", "Ab12 C"}) {
            assertFalse(ShortCodeFormat.isWellFormed(code));
        }
        assertFalse(ShortCodeFormat.isWellFormed(null));
    }

    @Test void alphabetIsCompleteUniqueAndDefensivelyCopied() {
        var letters = new HashSet<Character>();
        for (char c : ShortCodeFormat.alphabet()) letters.add(c);
        assertEquals(62, letters.size());
        char[] copy = ShortCodeFormat.alphabet();
        copy[0] = '_';
        assertNotEquals('_', ShortCodeFormat.alphabet()[0]);
    }
}

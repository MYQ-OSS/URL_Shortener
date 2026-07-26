package top.mayiqin.url_shortener.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 这些断言全部在本地跑通过（见 docs/adr.md ADR-003 的验算记录）。
 * 保留它们的意义不只是回归，而是面试时可以说："这些结论我都写了测试。"
 */
class Base62CodecTest {

    @Test
    @DisplayName("DOMAIN 就是 62^6 = 56,800,235,584（568 亿）")
    void domainIs62Pow6() {
        long pow = 1;
        for (int i = 0; i < 6; i++) {
            pow *= 62;
        }
        assertEquals(56_800_235_584L, Base62Codec.DOMAIN);
        assertEquals(pow, Base62Codec.DOMAIN);
    }

    @Test
    @DisplayName("短码恒为 6 位，含 0 和 62^6-1 两个边界")
    void encodeAlwaysSixChars() {
        long[] samples = {0, 1, 61, 62, 3843, 3844, 916_132_831L, 916_132_832L,
                Base62Codec.DOMAIN - 1};
        for (long v : samples) {
            assertEquals(6, Base62Codec.encodeFixed(v).length(), "value=" + v);
        }
    }

    @Test
    @DisplayName("编解码往返一致（20 万次随机）")
    void roundTrip() {
        SecureRandom rnd = new SecureRandom();
        for (int i = 0; i < 200_000; i++) {
            long v = Math.floorMod(rnd.nextLong(), Base62Codec.DOMAIN);
            assertEquals(v, Base62Codec.decodeFixed(Base62Codec.encodeFixed(v)));
        }
    }

    @Test
    @DisplayName("前 30 万连续值编码无冲突")
    void noCollision() {
        Set<String> seen = new HashSet<>(400_000);
        for (long v = 0; v < 300_000; v++) {
            assertTrue(seen.add(Base62Codec.encodeFixed(v)), "冲突于 " + v);
        }
    }

    @Test
    @DisplayName("越界必须抛异常，绝不静默截断")
    void overflowThrows() {
        // 静默截断会让不同输入编出相同短码——那是一个只有数据量上来后才暴露的隐藏冲突 bug
        assertThrows(IllegalArgumentException.class, () -> Base62Codec.encodeFixed(Base62Codec.DOMAIN));
        assertThrows(IllegalArgumentException.class, () -> Base62Codec.encodeFixed(-1));
    }

    @Test
    @DisplayName("非法短码返回 -1（跳转链路的第 0 道防线，不查任何存储）")
    void malformedReturnsMinusOne() {
        assertEquals(-1, Base62Codec.decodeFixed(null));
        assertEquals(-1, Base62Codec.decodeFixed("abc"));       // 长度不足
        assertEquals(-1, Base62Codec.decodeFixed("abcdefg"));   // 长度超出
        assertEquals(-1, Base62Codec.decodeFixed("!!!!!!"));    // 非法字符
        assertEquals(-1, Base62Codec.decodeFixed("中中中中中中")); // 非 ASCII
        assertFalse(Base62Codec.isWellFormed("aaaaaaa"));
    }
}

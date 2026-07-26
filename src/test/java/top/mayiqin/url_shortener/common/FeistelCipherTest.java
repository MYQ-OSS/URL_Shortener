package top.mayiqin.url_shortener.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FeistelCipherTest {

    private final FeistelCipher cipher = new FeistelCipher("url-shortener-demo-key-2026");

    @Test
    @DisplayName("加解密往返一致（2 万次随机）")
    void roundTrip() {
        SecureRandom rnd = new SecureRandom();
        for (int i = 0; i < 20_000; i++) {
            long v = Math.floorMod(rnd.nextLong(), Base62Codec.DOMAIN);
            assertEquals(v, cipher.decrypt(cipher.encrypt(v)));
        }
    }

    @Test
    @DisplayName("cycle-walking 保证结果恒落在 [0, 62^6) 内，期望迭代 ≈ 1.21 次")
    void stayInDomain() {
        for (long v = 0; v < 20_000; v++) {
            long c = cipher.encrypt(v);
            assertTrue(c >= 0 && c < Base62Codec.DOMAIN, "越界: " + c);
        }
        assertEquals(1.21, FeistelCipher.expectedCycleWalks(), 0.01);
    }

    @Test
    @DisplayName("前 20 万连续 id 加密无冲突（双射，所以不需要判重）")
    void bijective() {
        Set<Long> seen = new HashSet<>(300_000);
        for (long v = 0; v < 200_000; v++) {
            assertTrue(seen.add(cipher.encrypt(v)), "冲突于 " + v);
        }
    }

    @Test
    @DisplayName("★ 关键对照：Feistel 的连续密文差值不恒定，而模乘置换恒等于 P")
    void feistelIsNotLinearButModularMultiplicationIs() {
        // Feistel：差值随机
        long f0 = cipher.encrypt(1) - cipher.encrypt(0);
        long f1 = cipher.encrypt(2) - cipher.encrypt(1);
        long f2 = cipher.encrypt(3) - cipher.encrypt(2);
        assertFalse(f0 == f1 && f1 == f2, "Feistel 的差值不该恒定");

        // 模乘置换：差值恒等于 P —— 这就是它只算"混淆"不算"安全"的原因。
        // 攻击者连续调两次创建接口，拿到两个短码解码相减就直接得到 P，
        // 再用任意一个已知 (id, code) 对反推出 K，整个置换在两次 API 调用内被破解。
        long n = Base62Codec.DOMAIN, p = 1_000_000_007L, k = 20_260_726L;
        long m0 = (0 * p + k) % n, m1 = (1 * p + k) % n, m2 = (2 * p + k) % n;
        assertEquals(p, m1 - m0);
        assertEquals(p, m2 - m1);
    }
}

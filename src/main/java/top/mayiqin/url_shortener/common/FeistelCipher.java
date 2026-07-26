package top.mayiqin.url_shortener.common;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * 4 轮 Feistel 网络，实现 {@code [0, 62^6)} 上的格式保留加密（FPE）。
 *
 * <p><b>这个类在本项目里是"备选方案 + 面试素材"，主链路默认走 ShortCodeGenerator 的随机生成。</b>
 * 保留它是因为它能回答一个很有深度的问题：如果要求短码既由自增 ID 派生（便于反解、便于基因法分片）
 * 又不可枚举，正确答案是什么。
 *
 * <h3>为什么不用模乘置换 {@code (id*P + K) mod N}</h3>
 * 模乘置换看起来很美（双射、可逆、两次整数运算），但它有一个致命弱点：
 * <pre>
 *   enc(id+1) - enc(id) ≡ P  (mod N)
 * </pre>
 * 攻击者只要<b>连续调用两次创建接口</b>，拿到两个短码解码相减，就直接得到 P；
 * 再用任意一个已知 (id, code) 对反推出 K。<b>整个置换在两次 API 调用内被完全破解。</b>
 * 所以它只是 obfuscation（混淆），不是 security（安全）。
 *
 * <p>Feistel 的轮函数带密钥且非线性，不知道 key 就无法从任意数量的样本推出映射。
 *
 * <h3>Cycle-walking</h3>
 * Feistel 天然工作在 {@code 2^n} 的域上。这里取 36 bit（左右各 18 bit），
 * 而 {@code 2^36 = 68,719,476,736 > 62^6 = 56,800,235,584}。
 * 加密结果可能落在 {@code [62^6, 2^36)} 这段"域外区间"，此时<b>反复加密直到落回域内</b>。
 * 因为 Feistel 是双射，这个游走必然终止且仍是 {@code [0, 62^6)} 上的双射。
 * 期望迭代次数 = {@code 2^36 / 62^6 ≈ 1.21} 次。
 */
public final class FeistelCipher {

    private static final int HALF_BITS = 18;
    private static final int HALF_MASK = (1 << HALF_BITS) - 1;   // 0x3FFFF
    private static final long FULL_DOMAIN = 1L << (HALF_BITS * 2); // 2^36
    private static final int ROUNDS = 4;

    private final byte[] key;

    public FeistelCipher(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("Feistel 密钥至少 16 个字符");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 加密：{@code [0, 62^6) -> [0, 62^6)}，双射。 */
    public long encrypt(long plain) {
        checkDomain(plain);
        long v = plain;
        do {
            v = feistelForward(v);
        } while (v >= Base62Codec.DOMAIN);   // cycle-walking
        return v;
    }

    /** 解密：{@code encrypt} 的逆。 */
    public long decrypt(long cipher) {
        checkDomain(cipher);
        long v = cipher;
        do {
            v = feistelBackward(v);
        } while (v >= Base62Codec.DOMAIN);   // 逆向游走，路径与正向严格对称
        return v;
    }

    private static void checkDomain(long v) {
        if (v < 0 || v >= Base62Codec.DOMAIN) {
            throw new IllegalArgumentException("超出 [0, 62^6) 值域: " + v);
        }
    }

    /** 一次完整的 4 轮 Feistel：(L,R) -> (R, L ^ F(R,i))。 */
    private long feistelForward(long x) {
        int l = (int) (x >>> HALF_BITS);
        int r = (int) (x & HALF_MASK);
        for (int i = 0; i < ROUNDS; i++) {
            int next = l ^ prf(r, i);
            l = r;
            r = next;
        }
        return ((long) l << HALF_BITS) | r;
    }

    /** 逆向 4 轮：(L,R) -> (R ^ F(L, i), L)，轮序倒转。 */
    private long feistelBackward(long x) {
        int l = (int) (x >>> HALF_BITS);
        int r = (int) (x & HALF_MASK);
        for (int i = ROUNDS - 1; i >= 0; i--) {
            int prev = r ^ prf(l, i);
            r = l;
            l = prev;
        }
        return ((long) l << HALF_BITS) | r;
    }

    /** 轮函数：HMAC-SHA256(key, round‖half) 截断到 18 bit。轮函数不需要可逆。 */
    private int prf(int half, int round) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] out = mac.doFinal(ByteBuffer.allocate(8).putInt(round).putInt(half).array());
            int v = ((out[0] & 0xFF) << 16) | ((out[1] & 0xFF) << 8) | (out[2] & 0xFF);
            return v & HALF_MASK;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC 初始化失败", e);
        }
    }

    /** 期望的 cycle-walking 迭代次数，用于文档/面试引用。 */
    public static double expectedCycleWalks() {
        return (double) FULL_DOMAIN / Base62Codec.DOMAIN;
    }
}

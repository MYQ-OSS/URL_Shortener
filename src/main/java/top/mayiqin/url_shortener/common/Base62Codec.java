package top.mayiqin.url_shortener.common;

import java.util.Arrays;

/**
 * 定长 Base62 编解码。
 *
 * <p><b>为什么必须"定长"</b>：短码长度要稳定为 6 位。不补齐的话小数字会编出 1-2 位短码，
 * 既不美观，也让早期短码极易被猜到。
 *
 * <p><b>容量参考</b>（面试可直接报）：
 * <pre>
 *   62^5 =            916,132,832   （9.16 亿）
 *   62^6 =         56,800,235,584   （568 亿）  ← 本项目采用 6 位
 *   62^7 =      3,521,614,606,208   （3.5 万亿）
 * </pre>
 * 按 1 亿条/年生成，6 位够用 568 年。
 *
 * <p><b>为什么雪花算法不能直接做短码</b>：雪花 ID 是 64 bit，量级约 9.22e18，
 * {@code ceil(log62(9.22e18)) = 11} 位。根因是 41 bit 时间戳让 ID 空间极度稀疏——
 * 绝大多数 ID 值永远不会被使用，纯粹浪费编码空间。短码要短，就要求 ID 空间被密集利用。
 */
public final class Base62Codec {

    /**
     * 打乱过的固定字符表。
     *
     * <p><b>⚠️ 一旦上线绝对不能改</b>，否则所有历史短码都会解错。
     *
     * <p><b>⚠️ 这只是一层极弱的混淆</b>（单表替换密码，频率分析即可破），不是安全措施。
     * 本项目的抗枚举依赖 {@code ShortCodeGenerator} 的随机生成，而不是这张表。
     * 打乱它只是"不额外送分给攻击者"。
     */
    private static final char[] TABLE =
            "kb27yYTIZxKSQuVGwcFspJ01DfnLmdltOzCqAgr9oX3aP8W5MU4HhBjvEeNiR6".toCharArray();

    /** ASCII 反查表：TABLE[i] -> i，O(1) 查表，避免 indexOf 的线性扫描。 */
    private static final int[] REVERSE = new int[128];

    /** 短码固定长度。 */
    public static final int CODE_LENGTH = 6;

    /** 62^6，短码值域大小。 */
    public static final long DOMAIN = 56_800_235_584L;

    static {
        if (TABLE.length != 62) {
            throw new IllegalStateException("Base62 字符表长度必须是 62，当前 " + TABLE.length);
        }
        Arrays.fill(REVERSE, -1);
        for (int i = 0; i < TABLE.length; i++) {
            char c = TABLE[i];
            if (c > 127) {
                throw new IllegalStateException("字符表含非 ASCII 字符: " + c);
            }
            if (REVERSE[c] >= 0) {
                throw new IllegalStateException("字符表存在重复字符: " + c);
            }
            REVERSE[c] = i;
        }
    }

    private Base62Codec() {
    }

    /**
     * 把 {@code [0, 62^6)} 的数编码成固定 6 位短码，高位用 {@code TABLE[0]} 补齐。
     *
     * @throws IllegalArgumentException value 越界时抛出。
     *         <b>刻意不做静默截断</b>——静默截断会让不同的输入编出相同短码，
     *         那是一个只有在数据量上来后才会暴露的隐藏冲突 bug。
     */
    public static String encodeFixed(long value) {
        if (value < 0 || value >= DOMAIN) {
            throw new IllegalArgumentException("value 超出 [0, 62^6) 值域: " + value);
        }
        char[] buf = new char[CODE_LENGTH];
        for (int i = CODE_LENGTH - 1; i >= 0; i--) {
            buf[i] = TABLE[(int) (value % 62)];
            value /= 62;
        }
        return new String(buf);
    }

    /**
     * 解码固定 6 位短码。
     *
     * @return 对应数值；短码格式非法时返回 {@code -1}。
     *         <p><b>这是跳转链路的第 0 道防线</b>：长度不对、含非法字符的请求，
     *         不查缓存也不查库直接判 404，能极低成本地拦掉大量乱扫流量。
     */
    public static long decodeFixed(String code) {
        if (code == null || code.length() != CODE_LENGTH) {
            return -1L;
        }
        long value = 0L;
        for (int i = 0; i < CODE_LENGTH; i++) {
            char c = code.charAt(i);
            if (c > 127) {
                return -1L;
            }
            int d = REVERSE[c];
            if (d < 0) {
                return -1L;
            }
            value = value * 62 + d;
        }
        return value;
    }

    /** 判断字符串是否是格式合法的短码（不查存储）。 */
    public static boolean isWellFormed(String code) {
        return decodeFixed(code) >= 0;
    }

    /** 变长编码，用于不要求定长的场景。 */
    public static String encode(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("value 不能为负: " + value);
        }
        if (value == 0) {
            return String.valueOf(TABLE[0]);
        }
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            sb.append(TABLE[(int) (value % 62)]);
            value /= 62;
        }
        return sb.reverse().toString();
    }

    /** 暴露字符表副本，供 {@code ShortCodeGenerator} 做随机采样。 */
    public static char[] alphabet() {
        return TABLE.clone();
    }
}

package top.mayiqin.url_shortener.shortcode;

import org.springframework.stereotype.Component;
import top.mayiqin.url_shortener.common.Base62Codec;

import java.security.SecureRandom;

/**
 * 短码生成器：随机 6 位 Base62 + 布隆过滤器判重 + DB 唯一索引兜底。
 *
 * <h3>为什么是随机而不是"自增 ID 编码"</h3>
 * 自增 ID 直接 Base62 编码 → 短码可被顺序枚举 → 爬虫能遍历系统里全部短链。
 * 这不是理论风险：bit.ly / goo.gl 早年就被批量爬出过大量本以为"只有拿到链接的人才能看"的
 * 私密分享链。<b>短链的"不可猜测性"本身就是一层访问控制。</b>
 *
 * <p>考虑过的替代方案见 {@code docs/adr.md} ADR-003。一句话总结：
 * 模乘置换 {@code (id*P+K) mod N} 看起来很美但两次 API 调用就能被反解
 * （连续密文之差恒等于 P，已实测验证）；Feistel FPE 是正确答案但实现量是本方案的 4 倍。
 *
 * <h3>碰撞概率（面试可直接报）</h3>
 * <pre>
 *   短码空间 62^6 = 568 亿
 *   已有 1000 万条时，单次生成撞车概率 = 1e7 / 5.68e10 = 0.018%
 *   连续重试 5 次全部撞车的概率 ≈ 1.8e-19
 * </pre>
 *
 * <h3>⚠️ 三个必须讲清的点</h3>
 * <ol>
 *   <li><b>写 BF 必须早于落库。</b>BF 的误差是单向的（可能误报"存在"，绝不误报"不存在"）。
 *       若先落库后写 BF，一旦写 BF 失败就是 <b>BF 漏位 → 合法短链永久 404</b>；
 *       反过来先写 BF 后落库，最坏只是 <b>BF 多位 → 那个不存在的短码多穿透一次 DB</b>，
 *       然后被空值缓存兜住。让失败方向与 BF 本身的误差方向一致。</li>
 *   <li><b>BF 判重防不住并发竞态。</b>两个请求可能同时 {@code contains} 都返回 false，
 *       同时 {@code add}，同时插入。所以 <b>DB 唯一索引是必需的兜底，不是冗余</b>。</li>
 *   <li><b>BF 不支持删除。</b>短链删除后位无法清除 → 假阳性率单调上升。
 *       对策是双 BF 滚动重建（定时用 DB 全量构建新 BF 后切流）。
 *       顺着这个还能推出："短码不应该回收复用" —— 见 ADR-006。</li>
 * </ol>
 */
@Component
public class ShortCodeGenerator {

    /** 重试上限。超过说明短码空间真的告急（或 BF 假阳性率异常），应该告警并考虑扩到 7 位。 */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * 通过 Holder 取 BF 而不是直接注入 RBloomFilter。
     *
     * <p>因为 BF 会被全量重建并切换版本（见 {@link BloomFilterRebuilder}）。
     * 直接注入实例的话，重建后这里还握着旧版本，新写入会进错的 BF。
     */
    private final BloomFilterHolder holder;
    private final char[] alphabet = Base62Codec.alphabet();

    /**
     * 用 {@link SecureRandom} 而不是 {@link java.util.Random}。
     *
     * <p>理由：Random 是线性同余，从几个输出就能推出内部 state，进而预测后续短码——
     * 那等于把辛苦换来的"抗枚举"又送回去了。SecureRandom 每次生成约几百纳秒，
     * 而创建链路 QPS 本来就低，这个开销完全无感。
     */
    private final SecureRandom random = new SecureRandom();

    public ShortCodeGenerator(BloomFilterHolder holder) {
        this.holder = holder;
    }

    /**
     * 生成一个当前未被占用的短码，并已写入布隆过滤器。
     *
     * <p><b>调用方约定</b>：拿到短码后必须紧接着落库。如果落库失败，
     * BF 里会残留这个短码（多位），这是可接受的——它只会让这个不存在的短码
     * 多穿透一次 DB，随后被空值缓存拦住。
     *
     * @throws IllegalStateException 连续 {@value #MAX_ATTEMPTS} 次都撞车
     */
    public String generate() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            String code = randomCode();
            // BF 说"不存在" ⇒ 一定不存在（BF 无假阴性），可以安全使用
            if (!holder.get().contains(code)) {
                holder.get().add(code);  // 必须早于落库，见类注释第 1 点
                return code;
            }
        }
        throw new IllegalStateException(
                "连续 " + MAX_ATTEMPTS + " 次生成短码均冲突，短码空间可能告急或 BF 假阳性率异常");
    }

    /** 创建预约恢复时确保 BF 仍包含该短码。 */
    public void remember(String shortUri) {
        holder.get().add(shortUri);
    }

    /** 生成一个 6 位随机 Base62 串。 */
    private String randomCode() {
        char[] buf = new char[Base62Codec.CODE_LENGTH];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = alphabet[random.nextInt(alphabet.length)];
        }
        return new String(buf);
    }

    /** 供跳转链路使用：短码是否"可能存在"。false 表示<b>一定不存在</b>，可直接 404。 */
    public boolean mightExist(String code) {
        return Base62Codec.isWellFormed(code) && (!holder.isTrusted() || holder.get().contains(code));
    }
}

package top.mayiqin.url_shortener.shortcode;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 持有"当前生效的布隆过滤器"，并负责在版本切换后跟上。
 *
 * <h3>为什么要单独拆一个 Holder 出来</h3>
 * 如果每次判断短码都去 Redis 读一次"当前版本号"，跳转链路就<b>凭空多了一次网络往返</b>——
 * 而这条链路的全部优化都是在省这次往返。
 *
 * <p>所以版本号必须缓存在本地（{@code volatile} 字段），只在两种时机更新：
 * <ol>
 *   <li><b>本实例触发重建后</b>：立刻更新，零延迟</li>
 *   <li><b>其他实例触发了重建</b>：靠定时任务每 30 秒对一次版本号</li>
 * </ol>
 *
 * <p><b>30 秒的滞后可以接受吗？</b>可以，而且要能说清为什么：
 * 重建后的新 BF 是旧 BF 的<b>超集</b>（包含 DB 里全部短码），
 * 旧 BF 只是少了重建期间新建的那几条。少的那几条会被判"不存在"→ 404。
 * 也就是说滞后的后果是<b>极少量新建短链在 30 秒内可能 404</b>。
 *
 * <p>而这个后果已经被创建链路消掉了：{@code ShortCodeGenerator.generate()} 在生成短码时
 * 就往<b>当前生效的</b> BF 里写了一份（见 ADR-004 的"先写 BF 后落库"），
 * 所以新建短链在任何版本的 BF 里都存在。
 * <b>两处设计正好互补，这不是巧合——是"先写 BF"这个顺序带来的额外收益。</b>
 */
@Slf4j
@Component
public class BloomFilterHolder {

    private final RedissonClient redisson;
    private final long expectedInsertions;
    private final double falseProbability;

    private volatile RBloomFilter<String> active;
    private volatile int activeVersion = -1;

    public BloomFilterHolder(RedissonClient redisson,
                             @Value("${shortlink.bloom.expected-insertions:10000000}") long expectedInsertions,
                             @Value("${shortlink.bloom.false-probability:0.0001}") double falseProbability) {
        this.redisson = redisson;
        this.expectedInsertions = expectedInsertions;
        this.falseProbability = falseProbability;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    /** 热路径调用：只读本地 volatile 字段，无网络开销。 */
    public RBloomFilter<String> get() {
        RBloomFilter<String> bf = active;
        if (bf == null) {
            synchronized (this) {
                refresh();
                bf = active;
            }
        }
        return bf;
    }

    public int version() {
        return activeVersion;
    }

    /** 重建完成后由 {@link BloomFilterRebuilder} 调用，零延迟切换本实例。 */
    void switchTo(int version) {
        this.active = openOrInit(version);
        this.activeVersion = version;
        log.info("布隆过滤器已切换到 v{}", version);
    }

    /** 定时对版本号，让其他实例触发的重建在 30 秒内被本实例感知。 */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 30_000L)
    public void refresh() {
        Object v = redisson.getBucket(BloomFilterRebuilder.KEY_VERSION).get();
        int remote = v == null ? 1 : Integer.parseInt(v.toString());
        if (remote != activeVersion) {
            log.info("检测到布隆过滤器版本变化: v{} -> v{}", activeVersion, remote);
            switchTo(remote);
        }
    }

    private RBloomFilter<String> openOrInit(int version) {
        RBloomFilter<String> bf = redisson.getBloomFilter(BloomFilterRebuilder.KEY_PREFIX + version);
        if (!bf.isExists()) {
            // tryInit 是幂等的：多实例同时调用只有一个真正初始化
            bf.tryInit(expectedInsertions, falseProbability);
            redisson.getBucket(BloomFilterRebuilder.KEY_VERSION).set(String.valueOf(version));
        }
        return bf;
    }
}

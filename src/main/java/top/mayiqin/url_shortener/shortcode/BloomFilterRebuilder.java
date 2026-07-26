package top.mayiqin.url_shortener.shortcode;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 布隆过滤器全量重建（双 BF 滚动切换）。
 *
 * <h3>它解决两个问题，一个是压测的，一个是生产的</h3>
 *
 * <p><b>压测</b>：灌完种子数据后，BF 里并没有这些短码，
 * 于是<b>所有压测请求都会被 BF 判定为"一定不存在"直接 404</b>。
 * 这个坑非常隐蔽——QPS 会高得离谱（因为根本没查缓存和 DB），很容易误以为优化成功。
 * <b>灌完数据必须调一次 {@code POST /admin/bloom/rebuild}。</b>
 *
 * <p><b>生产</b>：BF 不支持删除位（见 ADR-004），短链删除后位无法清除，
 * 假阳性率会随时间单调上升。定时全量重建是唯一干净的解法。
 *
 * <h3>为什么必须"双 BF 滚动"而不是原地清空重建</h3>
 * 原地 {@code delete()} 后重建，会出现一个<b>致命窗口</b>：
 * 重建期间 BF 是空的或不完整的，此时所有跳转请求都会被判"不存在"→
 * <b>全站短链在重建期间集体 404</b>。
 *
 * <p>正确做法是<b>写新的、切指针、删旧的</b>：
 * <pre>
 *   1. 往 bloom:uri:v{N+1} 里灌 DB 全量数据（此时线上仍在读 v{N}）
 *   2. 灌完，把"当前版本号"指向 v{N+1}（一次原子的 SET）
 *   3. 延迟一段时间后删除 v{N}（延迟是为了让正在处理中的请求走完）
 * </pre>
 * <b>任何"清空后重建"的缓存/索引都有这个问题，不只是布隆过滤器。</b>
 * 能主动指出这一点，比会背"BF 不支持删除"高一个档次。
 */
@Slf4j
@Component
public class BloomFilterRebuilder {

    /** 当前生效的 BF 版本号存在这个 key 里。 */
    public static final String KEY_VERSION = "shortlink:bloom:version";
    /** BF 本体的 key 前缀，后面拼版本号。 */
    public static final String KEY_PREFIX = "shortlink:bloom:uri:v";

    /** 每批从 DB 拉多少行。太小则往返次数多，太大则单批内存占用高。 */
    private static final int PAGE_SIZE = 50_000;

    private final JdbcTemplate jdbc;
    private final RedissonClient redisson;
    private final BloomFilterHolder holder;
    private final long expectedInsertions;
    private final double falseProbability;

    /** 防并发重建：同时跑两个重建会互相踩版本号。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public BloomFilterRebuilder(JdbcTemplate jdbc, RedissonClient redisson, BloomFilterHolder holder,
                                @Value("${shortlink.bloom.expected-insertions:10000000}") long expectedInsertions,
                                @Value("${shortlink.bloom.false-probability:0.0001}") double falseProbability) {
        this.jdbc = jdbc;
        this.redisson = redisson;
        this.holder = holder;
        this.expectedInsertions = expectedInsertions;
        this.falseProbability = falseProbability;
    }

    /**
     * 全量重建。
     *
     * @return 写入的短码条数
     */
    public long rebuild() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("已有重建任务在跑，请勿并发触发");
        }
        long t0 = System.currentTimeMillis();
        try {
            int oldVersion = currentVersion();
            int newVersion = oldVersion + 1;
            String newKey = KEY_PREFIX + newVersion;

            RBloomFilter<String> next = redisson.getBloomFilter(newKey);
            // 万一上次重建中途挂了留下了残留，先清掉
            next.delete();
            next.tryInit(expectedInsertions, falseProbability);

            long total = 0;
            long lastId = 0;
            long nextLogAt = 500_000;
            while (true) {
                // ⚠️ 用 id > lastId 的"游标分页"而不是 LIMIT offset。
                // offset 分页在 offset 很大时 MySQL 要扫描并丢弃前 offset 行，
                // 1000 万行时最后几页会慢到不可接受（这是经典的深分页问题）。
                //
                // ⚠️ 而且 id 和 short_uri 必须一次查出来。
                // 如果分成"查 short_uri" + "再查 MAX(id) 定位游标"两条 SQL，
                // 等于把整表扫了两遍 —— 1000 万行时白白多花一倍时间。
                final long cursor = lastId;
                List<long[]> ids = new java.util.ArrayList<>(PAGE_SIZE);
                int added = jdbc.query(
                        "SELECT id, short_uri FROM t_link WHERE id > ? ORDER BY id LIMIT " + PAGE_SIZE,
                        rs -> {
                            int c = 0;
                            long maxId = cursor;
                            while (rs.next()) {
                                next.add(rs.getString(2));
                                maxId = Math.max(maxId, rs.getLong(1));
                                c++;
                            }
                            ids.add(new long[]{maxId});
                            return c;
                        }, cursor);

                if (added == 0) {
                    break;
                }
                total += added;
                lastId = ids.get(0)[0];
                if (total >= nextLogAt) {
                    log.info("布隆过滤器重建中: {} 条", total);
                    nextLogAt = total + 500_000;
                }
            }

            // 切指针。这一步之前线上一直在读旧 BF，所以没有 404 窗口。
            redisson.getBucket(KEY_VERSION).set(String.valueOf(newVersion));
            holder.switchTo(newVersion);        // 本实例零延迟切换；其他实例靠 30s 定时对版本

            // 旧版本不立即删——让正在处理中的请求走完。
            // 生产上应该延迟几分钟再删（这里简化为下一次重建时清理）。
            if (oldVersion > 0) {
                log.info("旧布隆过滤器 v{} 保留待清理（下次重建时删除）", oldVersion);
                redisson.getBloomFilter(KEY_PREFIX + (oldVersion - 1)).delete();
            }

            log.info("布隆过滤器重建完成: v{} -> v{}, {} 条, 耗时 {} ms",
                    oldVersion, newVersion, total, System.currentTimeMillis() - t0);
            return total;
        } finally {
            running.set(false);
        }
    }

    /** 当前生效的版本号；未初始化时返回 0。 */
    public int currentVersion() {
        Object v = redisson.getBucket(KEY_VERSION).get();
        return v == null ? 0 : Integer.parseInt(v.toString());
    }

}

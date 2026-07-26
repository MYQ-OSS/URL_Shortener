package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.mayiqin.url_shortener.idgen.SegmentIdGenerator;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.risk.UrlRiskChecker;
import top.mayiqin.url_shortener.shortcode.ShortCodeGenerator;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/**
 * 创建链路：长链 → 短码。
 *
 * <h3>⚠️ 这条链路刻意做成"重"的、全同步的，这是一个有意识的取舍</h3>
 * 原方案里写的是"用 MQ 异步落库削峰"。<b>那是错的，会引入一个必然的 bug</b>：
 * <pre>
 *   创建 → 发 MQ → 立即返回短码 → 用户 100ms 内就访问 → 缓存和 DB 都还没有 → 404
 * </pre>
 * 而"生成完立刻群发"正是营销短链的典型用法，所以这个 404 是<b>必然</b>会发生的。
 *
 * <p>更根本的原因是<b>读写比</b>：短链的创建 : 访问 ≈ 1 : 500 甚至 1 : 1000。
 * 创建 QPS 本来就低（几百到几千），<b>根本不需要削峰</b>。
 * 真正需要 MQ 削峰的是访问统计——那里的 QPS 等于跳转 QPS。
 *
 * <p>所以这里的策略是：<b>用创建时的几毫秒，换跳转时的确定性</b>。
 * 同步落库 + 同步写 BF + 同步预热缓存都做完才返回，返回即可读。
 *
 * <h3>执行顺序（每一步的位置都是有理由的）</h3>
 * <pre>
 *  1. 风控校验目标 URL     防钓鱼 / 防 SSRF / 域名黑白名单。放最前面，脏数据不进系统
 *  2. 幂等判断（urlHash）  同 gid + 同长链直接复用已有短码
 *  3. 生成短码             随机 + BF 判重 + BF.add （BF 必须早于落库）
 *  4. 落库                 唯一索引兜底并发竞态；同事务写本地消息表
 *  5. 预热缓存             删空值缓存 + 写长链缓存 → 返回即可访问
 *  6. 返回
 * </pre>
 */
@Slf4j
@Service
public class LinkCreateService {

    private final SegmentIdGenerator idGenerator;
    private final ShortCodeGenerator codeGenerator;
    private final LinkMapper linkMapper;
    private final LinkQueryService queryService;
    private final UrlRiskChecker riskChecker;
    private final String defaultDomain;

    public LinkCreateService(SegmentIdGenerator idGenerator,
                             ShortCodeGenerator codeGenerator,
                             LinkMapper linkMapper,
                             LinkQueryService queryService,
                             UrlRiskChecker riskChecker,
                             @org.springframework.beans.factory.annotation.Value("${shortlink.domain}") String defaultDomain) {
        this.idGenerator = idGenerator;
        this.codeGenerator = codeGenerator;
        this.linkMapper = linkMapper;
        this.queryService = queryService;
        this.riskChecker = riskChecker;
        this.defaultDomain = defaultDomain;
    }

    /**
     * 创建短链。
     *
     * @param forceNew {@code true} 时跳过幂等，强制生成新短码。
     *                 <p><b>为什么需要这个开关（这是一道考产品思维的题）</b>：
     *                 bit.ly 的行为是"同账号同长链返回同一短码"，
     *                 但<b>营销场景恰恰相反</b>——同一个落地页要生成 20 个不同短码，
     *                 分别投放到微信/微博/抖音/短信，用于<b>渠道归因</b>。
     *                 所以"要不要幂等"取决于业务语义，没有唯一正确答案。
     */
    @Transactional(rollbackFor = Exception.class)
    public String create(String originUrl, String gid, boolean forceNew,
                         Integer validType, LocalDateTime validEnd, String describe) {

        // ── 1. 风控：脏数据不进系统 ──────────────────────────────────
        riskChecker.check(originUrl);

        byte[] hash = md5(originUrl);

        // ── 2. 幂等 ───────────────────────────────────────────────
        if (!forceNew) {
            Link exist = linkMapper.selectOne(Wrappers.<Link>lambdaQuery()
                    .eq(Link::getGid, gid)
                    .eq(Link::getUrlHash, hash)
                    .eq(Link::getDelFlag, 0)
                    .last("LIMIT 1"));
            if (exist != null) {
                return fullUrl(exist.getDomain(), exist.getShortUri());
            }
            // ⚠️ 分库分表下的坑：t_link 按 short_uri 分片，而这里按 urlHash 查，
            // 是一次广播查询。生产上应该单独建一张按 urlHash 分片的去重表，
            // 或者用 Redis SETNX(urlHash -> shortUri) 做幂等。见 ADR-007。
        }

        // ── 3. 生成短码（内部已完成 BF 判重 + BF.add）────────────────
        String shortUri = codeGenerator.generate();

        Link link = new Link();
        link.setId(idGenerator.nextId());        // 趋势递增主键，避免 InnoDB 页分裂
        link.setShortUri(shortUri);
        link.setDomain(defaultDomain);
        link.setOriginUrl(originUrl);
        link.setUrlHash(hash);
        link.setGid(gid);
        link.setEnableStatus(0);
        link.setValidType(validType == null ? 0 : validType);
        link.setValidEnd(validEnd);
        link.setClickNum(0);
        link.setDescribe(describe);
        link.setDelFlag(0);

        // ── 4. 落库。唯一索引兜住"两个请求同时通过 BF 检查"的竞态 ──────
        try {
            linkMapper.insert(link);
        } catch (DuplicateKeyException e) {
            // 极小概率（BF 判重之后仍然撞车）。重试一次即可。
            log.warn("短码 {} 落库冲突，重试一次", shortUri);
            shortUri = codeGenerator.generate();
            link.setShortUri(shortUri);
            link.setId(idGenerator.nextId());
            linkMapper.insert(link);
        }

        // TODO Week 4：同事务写 t_local_message，由定时任务补偿双写 t_link_goto

        // ── 5. 预热缓存：删空值缓存 + 写长链缓存 → 返回即可访问，不会 404 ──
        queryService.warmUp(shortUri, originUrl);

        return fullUrl(defaultDomain, shortUri);
    }

    private String fullUrl(String domain, String shortUri) {
        return "http://" + domain + "/" + shortUri;
    }

    /** MD5 取 16 字节，用于 {@code url_hash BINARY(16)} 幂等索引。 */
    private static byte[] md5(String s) {
        try {
            return MessageDigest.getInstance("MD5")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

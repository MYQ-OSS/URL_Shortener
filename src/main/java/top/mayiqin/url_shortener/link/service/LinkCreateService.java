package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import top.mayiqin.url_shortener.idgen.SegmentIdGenerator;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.LinkValidity;
import top.mayiqin.url_shortener.link.entity.LocalMessage;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.link.mapper.LocalMessageMapper;
import top.mayiqin.url_shortener.risk.UrlRiskChecker;
import top.mayiqin.url_shortener.shortcode.ShortCodeGenerator;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Map;

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
    private final TransactionTemplate tx;
    private final LocalMessageMapper messageMapper;
    private final LinkDedupService dedupService;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final String defaultDomain;

    public LinkCreateService(SegmentIdGenerator idGenerator,
                             ShortCodeGenerator codeGenerator,
                             LinkMapper linkMapper,
                             LinkQueryService queryService,
                             UrlRiskChecker riskChecker,
                             TransactionTemplate tx,
                             LocalMessageMapper messageMapper,
                             LinkDedupService dedupService,
                             @org.springframework.beans.factory.annotation.Value("${shortlink.domain}") String defaultDomain) {
        this.idGenerator = idGenerator;
        this.codeGenerator = codeGenerator;
        this.linkMapper = linkMapper;
        this.queryService = queryService;
        this.riskChecker = riskChecker;
        this.tx = tx;
        this.messageMapper = messageMapper;
        this.dedupService = dedupService;
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
     * <h3>★ 事务边界为什么只包"落库"这一步（2026-08-03 压测实测的教训）</h3>
     * 最初整个方法挂 {@code @Transactional}。而 {@code idGenerator.nextId()} 内部是
     * REQUIRES_NEW（见 ADR-011：取号必须独立提交）——它会<b>挂起外层事务、再从同一个
     * 连接池拿第二条连接</b>。于是每个在途请求同时占 2 条连接：50 并发需要 100 条，
     * 池里只有 20 → 所有持连接者都在等一条永远腾不出的连接 → 集体 3s 超时 5xx。
     * 这是经典的<b>连接池自饿</b>。低并发冒烟永远发现不了它，一压就炸。
     * 修法：风控/幂等/取号/生成短码都不需要事务，事务只包落库（Week 4 会扩成
     * "落库 + 本地消息表"同一事务）。任一时刻每请求最多占 1 条连接。
     */
    public String create(String originUrl, String gid, boolean forceNew,
                         Integer validType, LocalDateTime validEnd, String describe) {
        LocalDateTime normalizedEnd = LinkValidity.normalizedEnd(validType, validEnd);

        // ── 1. 风控：脏数据不进系统 ──────────────────────────────────
        riskChecker.check(originUrl);

        byte[] hash = md5(originUrl);

        String dedupKey = forceNew ? null : LinkDedupService.key(gid, originUrl);
        for (int attempt = 0; attempt < 5; attempt++) {
            // 所有查询都携带分片键；并发请求共享已持久化的规范短码。
            String shortUri = forceNew ? codeGenerator.generate() : dedupService.reserve(dedupKey);
            if (!forceNew) {
                Link existing = findByCode(shortUri);
                if (existing != null) {
                    if (sameTarget(existing, gid, originUrl)) return fullUrl(existing.getDomain(), shortUri);
                    dedupService.replace(dedupKey, shortUri);
                    continue;
                }
                // 预约可能经历过进程重启或 BF 重建，落库前重新登记。
                codeGenerator.remember(shortUri);
            }
            Link link = new Link();
            link.setId(idGenerator.nextId());        // 趋势递增主键，避免 InnoDB 页分裂（独立短事务）
            link.setShortUri(shortUri);
            link.setDomain(defaultDomain);
            link.setOriginUrl(originUrl);
            link.setUrlHash(hash);
            link.setGid(gid);
            link.setEnableStatus(0);
            link.setValidType(validType == null ? 0 : validType);
            link.setValidEnd(normalizedEnd);
            link.setClickNum(0);
            link.setDescribe(describe);
            link.setDelFlag(0);

            // ── 4. 落库 + 本地消息表（同一个事务，ADR-005）──────────────────
            // 事务只包这两步写。t_link 和 t_local_message 按同一个 short_uri 分片表达式
            // 路由（sharding.yaml 的 uri_* 与 msg_*），两条 INSERT 必然落同一个库——
            // 所以一个本地事务就能同时写两张表，不需要分布式事务框架。
            // 消息由 LinkGotoSyncJob 定时补偿投递到 t_link_goto（按 gid 分片的异构索引）。
            try {
                tx.executeWithoutResult(status -> {
                    linkMapper.insert(link);
                    messageMapper.insert(gotoSyncMessage(link));
                });
            } catch (DuplicateKeyException e) {
                if (!forceNew) {
                    Link winner = findByCode(shortUri);
                    if (winner != null && sameTarget(winner, gid, originUrl)) {
                        return fullUrl(winner.getDomain(), shortUri);
                    }
                    dedupService.replace(dedupKey, shortUri);
                }
                continue;
            }

            // ── 5. 预热缓存：删空值缓存 + 写长链缓存 → 返回即可访问，不会 404 ──
            queryService.warmUp(shortUri, originUrl, normalizedEnd);

            return fullUrl(defaultDomain, shortUri);
        }
        throw new IllegalStateException("短码冲突重试次数已用尽");
    }

    private Link findByCode(String code) {
        return linkMapper.selectOne(Wrappers.<Link>lambdaQuery().eq(Link::getShortUri, code));
    }

    private static boolean sameTarget(Link link, String gid, String url) {
        return gid.equals(link.getGid()) && url.equals(link.getOriginUrl());
    }

    /**
     * 构造"同步 gid 异构索引"的本地消息。
     *
     * <p>shardKey = shortUri：与 t_link 同一分片表达式 → 同库 → 可同事务。
     * payload 只放补偿需要的两个字段，不放整个 Link（消息表要瘦，补偿任务也解耦）。
     */
    private LocalMessage gotoSyncMessage(Link link) {
        LocalMessage msg = new LocalMessage();
        msg.setBizType("LINK_GOTO_SYNC");
        msg.setShardKey(link.getShortUri());
        msg.setPayload(json.writeValueAsString(Map.of(
                "gid", link.getGid(), "shortUri", link.getShortUri())));
        msg.setStatus(0);
        msg.setRetryCount(0);
        msg.setNextRetry(LocalDateTime.now());
        return msg;
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

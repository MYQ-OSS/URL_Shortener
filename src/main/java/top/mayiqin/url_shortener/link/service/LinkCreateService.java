package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import top.mayiqin.url_shortener.link.LinkValidity;
import top.mayiqin.url_shortener.link.entity.Link;
import top.mayiqin.url_shortener.link.mapper.LinkMapper;
import top.mayiqin.url_shortener.risk.UrlRiskChecker;
import top.mayiqin.url_shortener.shortcode.ShortCodeGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;

/** 同步创建并落库，成功返回后即可跳转、查询列表；统计另走异步链路。 */
@Service
public class LinkCreateService {
    private static final int MAX_ATTEMPTS = 5;
    private final ShortCodeGenerator codes;
    private final LinkMapper links;
    private final LinkQueryService query;
    private final UrlRiskChecker risk;
    private final LinkDedupService dedup;
    private final String domain;

    public LinkCreateService(ShortCodeGenerator codes, LinkMapper links, LinkQueryService query,
                             UrlRiskChecker risk, LinkDedupService dedup,
                             @Value("${shortlink.domain}") String domain) {
        this.codes = codes;
        this.links = links;
        this.query = query;
        this.risk = risk;
        this.dedup = dedup;
        this.domain = domain;
    }

    public String create(String originUrl, String gid, boolean forceNew,
                         Integer validType, LocalDateTime validEnd, String describe) {
        LocalDateTime end = LinkValidity.normalizedEnd(validType, validEnd);
        risk.check(originUrl);
        String key = forceNew ? null : LinkDedupService.key(gid, originUrl);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String code = forceNew ? codes.generate() : dedup.reserve(key);
            if (!forceNew) {
                Link existing = findByCode(code);
                if (existing != null) {
                    if (reusable(existing, gid, originUrl)) {
                        return fullUrl(existing.getDomain(), code);
                    }
                    dedup.replace(key, code);
                    continue;
                }
            }
            Link link = new Link();
            // 主键由 MyBatis-Plus ASSIGN_ID 分配；短码独立随机生成。
            link.setShortUri(code);
            link.setDomain(domain);
            link.setOriginUrl(originUrl);
            link.setUrlHash(legacyHash(originUrl));
            link.setGid(gid);
            link.setEnableStatus(0);
            link.setValidType(validType == null ? 0 : validType);
            link.setValidEnd(end);
            link.setClickNum(0);
            link.setDescribe(describe);
            link.setDelFlag(0);
            try {
                // 单条 INSERT 本身原子提交，不再维护异构索引和同步消息。
                links.insert(link);
            } catch (DuplicateKeyException collision) {
                if (!forceNew) {
                    Link winner = findByCode(code);
                    if (winner != null && reusable(winner, gid, originUrl)) {
                        return fullUrl(winner.getDomain(), code);
                    }
                    dedup.replace(key, code);
                }
                continue;
            }
            query.warmUp(code, originUrl, end);
            return fullUrl(domain, code);
        }
        throw new IllegalStateException("短码冲突重试次数已用尽");
    }

    private Link findByCode(String code) {
        return links.selectOne(Wrappers.<Link>lambdaQuery().eq(Link::getShortUri, code));
    }

    private static boolean sameTarget(Link link, String gid, String url) {
        return gid.equals(link.getGid()) && url.equals(link.getOriginUrl());
    }

    /**
     * 幂等复用既有短码的前提：同目标<b>且记录当前仍可用</b>。
     *
     * <p>已逻辑删除（清理任务对过期短链置 {@code del_flag=1}，行仍占用 {@code uk_short_uri}）
     * 或已过期的记录不能复用：否则调用方拿到一个永久 410 的链接，且预约始终指向这个死码，
     * 同一条 URL 再也建不出可用短链。此时换预约、用新码重新落库。
     *
     * <p><b>刻意不把 {@code enable_status} 计入不可复用</b>：用户禁用（1）的记录应由用户自己恢复，
     * 另发新码只会让同一 URL 产生重复记录；风控封禁（2）若换码，等于允许被封 URL 靠
     * "再创建一次"绕过封禁。两种情况都返回既有短码。
     */
    private static boolean reusable(Link link, String gid, String url) {
        return sameTarget(link, gid, url)
                && (link.getDelFlag() == null || link.getDelFlag() == 0)
                && !link.expired();
    }

    private static String fullUrl(String domain, String code) {
        return "http://" + domain + "/" + code;
    }

    /** 保留旧表 NOT NULL 列的兼容写入；实际防重使用 SHA-256 预约表。 */
    private static byte[] legacyHash(String url) {
        try {
            return MessageDigest.getInstance("MD5").digest(url.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

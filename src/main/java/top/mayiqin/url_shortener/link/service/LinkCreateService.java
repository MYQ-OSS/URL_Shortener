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
                    if (sameTarget(existing, gid, originUrl)) return fullUrl(existing.getDomain(), code);
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
                    if (winner != null && sameTarget(winner, gid, originUrl)) {
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

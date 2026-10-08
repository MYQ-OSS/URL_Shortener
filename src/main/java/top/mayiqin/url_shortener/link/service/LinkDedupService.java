package top.mayiqin.url_shortener.link.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import top.mayiqin.url_shortener.link.entity.LinkDedup;
import top.mayiqin.url_shortener.link.mapper.LinkDedupMapper;
import top.mayiqin.url_shortener.shortcode.ShortCodeGenerator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class LinkDedupService {
    private final LinkDedupMapper mapper;
    private final ShortCodeGenerator codes;

    public LinkDedupService(LinkDedupMapper mapper, ShortCodeGenerator codes) {
        this.mapper = mapper;
        this.codes = codes;
    }

    public static String key(String gid, String url) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((gid + "\0" + url).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 预约独立提交，进程在落库前退出时，下次请求仍复用同一个规范短码。 */
    public String reserve(String key) {
        LinkDedup existing = mapper.selectById(key);
        if (existing != null) {
            return existing.getShortUri();
        }
        LinkDedup reservation = new LinkDedup();
        reservation.setDedupKey(key);
        reservation.setShortUri(codes.generate());
        try {
            mapper.insert(reservation);
            return reservation.getShortUri();
        } catch (DuplicateKeyException concurrent) {
            return mapper.selectById(key).getShortUri();
        }
    }

    /** 短码被其他 URL 占用或已逻辑删除时，CAS 更换预约，不能覆盖并发赢家。 */
    public void replace(String key, String oldCode) {
        mapper.update(null, Wrappers.<LinkDedup>lambdaUpdate()
                .eq(LinkDedup::getDedupKey, key)
                .eq(LinkDedup::getShortUri, oldCode)
                .set(LinkDedup::getShortUri, codes.generate()));
    }
}

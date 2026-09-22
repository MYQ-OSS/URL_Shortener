package top.mayiqin.url_shortener.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * 目标 URL 风控。
 *
 * <p><b>这是本项目的差异化加分项——几乎没人在短链项目里做，但它是这个业务的头号真实风险。</b>
 *
 * <h3>1. 开放重定向（Open Redirect）</h3>
 * <b>短链本质上就是一个开放重定向。</b>攻击者可以把钓鱼站包装成你的可信短域名发出去，
 * 用户看到的是 {@code s.example.cn/aB3xY9}，点进去是伪装的登录页。
 * 对策：域名黑白名单、风险中间页（"你即将离开本站前往 xxx.com"）、接入 URL 信誉库。
 *
 * <h3>2. SSRF —— 这个点答出来面试官会印象很深</h3>
 * 如果你做了"抓取目标页 title / favicon 做预览"（很多短链平台都有这个功能），
 * 就引入了 SSRF：攻击者创建一个指向内网的短链，<b>你的服务器会替他去请求内网</b>。
 * <pre>
 *   http://127.0.0.1:8080/actuator/env        → 泄露配置和密码
 *   http://169.254.169.254/latest/meta-data/  → 云厂商元数据，可能拿到临时凭证
 *   http://10.0.0.1/admin                     → 内网管理后台
 * </pre>
 * 对策：<b>解析后的 IP 必须校验</b>（拒绝私有网段、回环、链路本地），
 * 并且要防 <b>DNS Rebinding</b>——校验时和真正发请求时的 DNS 解析结果可能不同，
 * 所以必须"解析一次 IP，然后直连这个 IP"，不能解析两次。
 */
@Component
public class UrlRiskChecker {

    /** 只允许 http/https。javascript:、data:、file: 都要拒。 */
    private static final List<String> ALLOWED_SCHEMES = List.of("http", "https");

    private final boolean blockPrivateNetwork;

    /**
     * 域名黑名单（Week 5）。命中的是<b>域名后缀</b>：配 {@code evil.com} 会同时拦
     * {@code evil.com} 和 {@code phish.evil.com}。小写匹配。
     * <p>为什么是后缀而不是精确匹配：钓鱼者惯用子域名换皮
     * （{@code login.evil.com}、{@code evil.com.attacker.cn} 这类要格外小心——
     * 所以匹配规则必须是"等于 domain 或以 .domain 结尾"，不能用 contains/endsWith 裸写）。
     */
    private volatile List<String> blockedDomains;

    /**
     * 域名白名单（可选）。<b>非空时进入白名单模式</b>：只有命中的域名才允许创建，
     * 其余一律拒。适合私有化部署只服务自家域名的场景。留空 = 不启用。
     */
    private volatile List<String> allowedDomains;

    public UrlRiskChecker(
            @Value("${shortlink.risk.block-private-network:true}") boolean blockPrivateNetwork,
            @Value("${shortlink.risk.blocked-domains:}") List<String> blockedDomains,
            @Value("${shortlink.risk.allowed-domains:}") List<String> allowedDomains) {
        this.blockPrivateNetwork = blockPrivateNetwork;
        this.blockedDomains = blockedDomains.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
        this.allowedDomains = allowedDomains.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
    }

    /** 动态重载黑白名单规则。 */
    public synchronized void reloadRules(List<String> newBlocked, List<String> newAllowed) {
        if (newBlocked != null) {
            this.blockedDomains = newBlocked.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
        }
        if (newAllowed != null) {
            this.allowedDomains = newAllowed.stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
        }
    }

    public void check(String originUrl) {
        URI uri;
        try {
            uri = URI.create(originUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("URL 格式非法");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("只支持 http/https，拒绝: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL 缺少 host");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        // ★ 黑白名单放在 DNS 解析之前：纯字符串匹配、零网络开销，
        // 而且被拉黑的域名常常已经解析不了——先查名单才能给出准确的拒绝原因。
        if (!allowedDomains.isEmpty() && !matchesAny(normalizedHost, allowedDomains)) {
            throw new IllegalArgumentException("目标域名不在白名单内: " + host);
        }
        if (matchesAny(normalizedHost, blockedDomains)) {
            throw new IllegalArgumentException("目标域名已被风控封禁: " + host);
        }
        if (blockPrivateNetwork && resolvesToPrivateAddress(host)) {
            throw new IllegalArgumentException("拒绝指向内网/回环地址的目标: " + host);
        }
    }

    /**
     * 域名后缀匹配：host == domain 或 host 以 ".domain" 结尾。
     * 不用裸 endsWith——{@code notevil.com} 会被 {@code evil.com} 的裸 endsWith 误伤。
     */
    private static boolean matchesAny(String host, List<String> domains) {
        for (String d : domains) {
            if (d.isBlank()) {
                continue;
            }
            if (host.equals(d) || host.endsWith("." + d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 host 是否解析到内网地址。
     *
     * <p>⚠️ 这里只做"创建时拦截"。如果后续要做页面抓取，必须把这里解析出的 IP
     * 直接传给 HTTP 客户端使用（而不是让客户端再解析一次域名），否则 DNS Rebinding
     * 可以让两次解析返回不同结果，绕过校验。
     */
    private boolean resolvesToPrivateAddress(String host) {
        try {
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress()          // 127.0.0.0/8, ::1
                        || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                        || addr.isLinkLocalAddress()  // 169.254/16 (含云元数据地址)
                        || addr.isAnyLocalAddress()   // 0.0.0.0
                        || addr.isMulticastAddress()) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            // 域名解析不了：拒绝创建。宁可误杀也不要让不可达的短链进库。
            throw new IllegalArgumentException("目标域名无法解析: " + host);
        }
    }
}

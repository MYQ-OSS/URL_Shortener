package top.mayiqin.url_shortener.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/** 仅信任显式配置的代理，从 XFF 右侧剥离可信代理，遇到首个不可信地址即停止。 */
@Component
public class ClientIpResolver {
    private final List<Network> trusted;

    public ClientIpResolver(@Value("${shortlink.proxy.trusted-cidrs:}") String cidrs) {
        trusted = Arrays.stream(cidrs.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(Network::parse).toList();
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        byte[] address = literal(peer);
        if (address == null || !isTrusted(address)) {
            return peer;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.length() > 8192) {
            return peer;
        }
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 32) {
            return peer;
        }
        for (int i = hops.length - 1; i >= 0 && isTrusted(address); i--) {
            address = literal(hops[i].trim());
            if (address == null) {
                return peer;
            }
        }
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isTrusted(byte[] address) {
        return trusted.stream().anyMatch(network -> network.contains(address));
    }

    /** 只解析 IP 字面量；不会为请求头内容发起 DNS 查询。 */
    private static byte[] literal(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
            try { return InetAddress.getByName(value).getAddress(); }
            catch (UnknownHostException e) { return null; }
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return null;
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return null;
            int n = Integer.parseInt(parts[i]);
            if (n > 255) return null;
            bytes[i] = (byte) n;
        }
        return bytes;
    }

    private record Network(byte[] address, int prefix) {
        static Network parse(String cidr) {
            String[] parts = cidr.split("/", -1);
            byte[] address = literal(parts[0]);
            if (address == null || parts.length > 2) throw new IllegalArgumentException("非法代理 CIDR: " + cidr);
            int bits = parts.length == 1 ? address.length * 8 : Integer.parseInt(parts[1]);
            if (bits < 0 || bits > address.length * 8) throw new IllegalArgumentException("非法代理 CIDR: " + cidr);
            return new Network(address, bits);
        }

        boolean contains(byte[] other) {
            if (address.length != other.length) return false;
            for (int i = 0; i < prefix; i++) {
                int mask = 1 << (7 - i % 8);
                if ((address[i / 8] & mask) != (other[i / 8] & mask)) return false;
            }
            return true;
        }
    }
}

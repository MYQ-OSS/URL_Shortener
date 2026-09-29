package top.mayiqin.url_shortener.link.service;

import top.mayiqin.url_shortener.link.LinkValidity;

import java.time.Duration;
import java.time.LocalDateTime;

/** 缓存内容携带绝对截止时间，L1/L2 命中也必须检查是否已过期。 */
public record CachedLink(String url, long expiresAtMillis) {
    private static final String PREFIX = "v2\0";
    private static final Duration MAX_TTL = Duration.ofDays(30);

    public static CachedLink of(String url, LocalDateTime validEnd) {
        long expires = validEnd == null ? Long.MAX_VALUE
                : validEnd.atZone(LinkValidity.ZONE).toInstant().toEpochMilli();
        return new CachedLink(url, expires);
    }

    public static CachedLink parse(String raw) {
        if (raw == null || !raw.startsWith(PREFIX)) return null;
        int separator = raw.indexOf('\0', PREFIX.length());
        if (separator < 0 || separator == raw.length() - 1) return null;
        try {
            return new CachedLink(raw.substring(separator + 1),
                    Long.parseLong(raw.substring(PREFIX.length(), separator)));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    public String encode() { return PREFIX + expiresAtMillis + "\0" + url; }
    public boolean expired() { return expiresAtMillis <= System.currentTimeMillis(); }

    public Duration ttl() {
        if (expiresAtMillis == Long.MAX_VALUE) return MAX_TTL;
        return Duration.ofMillis(Math.min(MAX_TTL.toMillis(), expiresAtMillis - System.currentTimeMillis()));
    }
}

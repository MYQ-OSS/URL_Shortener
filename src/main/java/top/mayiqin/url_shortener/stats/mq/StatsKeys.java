package top.mayiqin.url_shortener.stats.mq;

/**
 * 统计相关 Redis key 的唯一出处。
 *
 * <p>消费端（写计数）和落库任务（读计数）必须用同一套 key，
 * 拼 key 的逻辑散在两处早晚会改岔，所以集中到这里。
 *
 * <p>dirty 成员格式：{@code date|hour|uri}，例如 {@code 2026-07-26|13|aB3xY9}。
 * 短码是 Base62 不含 {@code |}，日期是 ISO 格式，所以这个分隔符是安全的。
 */
public final class StatsKeys {

    /** 有新计数待落库的 (日期,小时,短码) 集合。落库任务 SPOP 它而不是扫全库。 */
    public static final String DIRTY = "sl:st:dirty";

    private StatsKeys() {
    }

    public static String pv(String date, int hour, String uri) {
        return "sl:st:pv:" + date + ":" + hour + ":" + uri;
    }

    public static String uv(String date, String uri) {
        return "sl:st:uv:" + date + ":" + uri;
    }

    public static String uip(String date, String uri) {
        return "sl:st:uip:" + date + ":" + uri;
    }

    public static String dedup(String msgId) {
        return "sl:st:dedup:" + msgId;
    }

    public static String dirtyMember(String date, int hour, String uri) {
        return date + "|" + hour + "|" + uri;
    }

    /** 解析 dirty 成员，返回 [date, hour, uri]；格式非法返回 null。 */
    public static String[] parseDirtyMember(String member) {
        if (member == null) {
            return null;
        }
        int p1 = member.indexOf('|');
        int p2 = member.indexOf('|', p1 + 1);
        if (p1 <= 0 || p2 <= p1 + 1 || p2 == member.length() - 1) {
            return null;
        }
        return new String[]{member.substring(0, p1), member.substring(p1 + 1, p2), member.substring(p2 + 1)};
    }
}

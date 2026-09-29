package top.mayiqin.url_shortener.link;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** 统一短链有效期语义：API 的 LocalDateTime 按 Asia/Shanghai 解释。 */
public final class LinkValidity {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private LinkValidity() { }

    public static boolean isValid(Integer validType, LocalDateTime validEnd) {
        int type = validType == null ? 0 : validType;
        return switch (type) {
            case 0 -> validEnd == null;
            case 1 -> validEnd != null
                    && validEnd.truncatedTo(ChronoUnit.SECONDS).isAfter(LocalDateTime.now(ZONE));
            default -> false;
        };
    }

    public static void requireValid(Integer validType, LocalDateTime validEnd) {
        if (!isValid(validType, validEnd)) {
            throw new IllegalArgumentException("永久短链不能设置 validEnd；自定义有效期必须晚于当前时间（Asia/Shanghai）");
        }
    }

    /** MySQL DATETIME 列没有小数秒；缓存和 DB 必须使用相同的截止时间。 */
    public static LocalDateTime normalizedEnd(Integer validType, LocalDateTime validEnd) {
        requireValid(validType, validEnd);
        return validEnd == null ? null : validEnd.truncatedTo(ChronoUnit.SECONDS);
    }
}

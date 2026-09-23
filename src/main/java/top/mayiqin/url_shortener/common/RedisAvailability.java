package top.mayiqin.url_shortener.common;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/** Redis 故障后短暂跳过网络访问，限流与查询共享状态，减少重复超时。 */
@Component
public class RedisAvailability {
    private final AtomicLong downUntil = new AtomicLong();
    public boolean isOpen() {
        long deadline = downUntil.get();
        return deadline != 0 && System.nanoTime() - deadline < 0;
    }
    public void failed() { downUntil.set(System.nanoTime() + 2_000_000_000L); }
}

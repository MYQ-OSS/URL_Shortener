package top.mayiqin.url_shortener.ratelimit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis + Lua 令牌桶限流。
 *
 * <h3>为什么必须用 Lua 而不是"多条 Redis 命令"</h3>
 * 令牌桶要做三件事：读当前令牌数、按时间差补发令牌、扣减。
 * 拆成多条命令的话，两个请求可能<b>同时读到同一个余量</b>然后各自扣减 → 超发。
 * Redis 执行 Lua 脚本是<b>原子</b>的（单线程执行期间不会插入其他命令），
 * 这才是选 Lua 的理由——不是为了少几次网络往返，是为了原子性。
 *
 * <h3>为什么两个接口用两套策略（这题考的是有没有想过业务）</h3>
 * <ul>
 *   <li><b>创建接口</b>：按 {@code gid}/{@code userId} <b>严格</b>限流（如 10 QPS）。
 *       创建是写操作、成本高、且刷量没有正当理由。</li>
 *   <li><b>跳转接口</b>：按 {@code IP + 短码} <b>宽松</b>限流（如 100 QPS）。
 *       正常用户完全可能高频访问同一个短链（刷新、多设备、页面预加载），
 *       限得太死会误杀真实用户。</li>
 * </ul>
 * 用一套阈值套两个接口，是最常见的想当然。
 */
@Component
public class RateLimiter {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> tokenBucketScript;

    private final int createCapacity;
    private final int createRefillPerSec;
    private final int redirectCapacity;
    private final int redirectRefillPerSec;

    public RateLimiter(StringRedisTemplate redis,
                       @Value("${shortlink.rate-limit.create.capacity:20}") int createCapacity,
                       @Value("${shortlink.rate-limit.create.refill-per-sec:10}") int createRefill,
                       @Value("${shortlink.rate-limit.redirect.capacity:200}") int redirectCapacity,
                       @Value("${shortlink.rate-limit.redirect.refill-per-sec:100}") int redirectRefill) {
        this.redis = redis;
        this.createCapacity = createCapacity;
        this.createRefillPerSec = createRefill;
        this.redirectCapacity = redirectCapacity;
        this.redirectRefillPerSec = redirectRefill;

        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("lua/token_bucket.lua")));
        script.setResultType(Long.class);
        this.tokenBucketScript = script;
    }

    public boolean tryAcquireCreate(String gid) {
        return acquire("rl:create:" + gid, createCapacity, createRefillPerSec);
    }

    public boolean tryAcquireRedirect(String ip, String shortUri) {
        return acquire("rl:rd:" + ip + ":" + shortUri, redirectCapacity, redirectRefillPerSec);
    }

    private boolean acquire(String key, int capacity, int refillPerSec) {
        try {
            Long allowed = redis.execute(tokenBucketScript, List.of(key),
                    String.valueOf(capacity),
                    String.valueOf(refillPerSec),
                    String.valueOf(System.currentTimeMillis()),
                    "1");
            return allowed != null && allowed == 1L;
        } catch (RuntimeException e) {
            // ⚠️ Redis 挂了时限流器要"失败放行"还是"失败拒绝"？
            // 这里选放行：限流是保护措施，不该在依赖故障时变成新的故障源。
            // 但要配合下游的熔断/信号量隔离，否则 Redis 挂 + 限流放行 = DB 被打死。
            // 这个取舍要在面试里主动说出来。
            return true;
        }
    }
}

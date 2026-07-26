package top.mayiqin.url_shortener.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 开启 @Scheduled。
 *
 * <p>当前依赖它的地方：
 * <ul>
 *   <li>{@code BloomFilterHolder#refresh} —— 每 30 秒对一次 BF 版本号，
 *       让其他实例触发的重建被本实例感知</li>
 * </ul>
 *
 * <p>Week 3 还会加：统计数据定时批量落库、MQ 发送失败的本地暂存补投。
 *
 * <p>⚠️ 多实例部署时定时任务会<b>每个实例都跑一遍</b>。
 * 对"对版本号"这种幂等操作无所谓，但对"批量落库"就必须加分布式锁
 * （Redisson 的 {@code RLock}）或者用 ShedLock，否则统计数据会被重复累加。
 * 这是一个很容易在多实例上线后才炸出来的问题。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}

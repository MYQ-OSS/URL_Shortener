package top.mayiqin.url_shortener.ratelimit;

/** 单实例兜底令牌桶；单调时钟避免系统时间回拨影响补充速率。 */
public final class LocalTokenBucket {
    private final int capacity;
    private final int refillPerSecond;
    private double tokens;
    private long lastNanos = System.nanoTime();

    public LocalTokenBucket(int capacity, int refillPerSecond) {
        if (capacity <= 0 || refillPerSecond <= 0) throw new IllegalArgumentException("限流参数必须为正数");
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
    }

    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        tokens = Math.min(capacity, tokens + (now - lastNanos) / 1_000_000_000D * refillPerSecond);
        lastNanos = now;
        if (tokens < 1) return false;
        tokens -= 1;
        return true;
    }
}

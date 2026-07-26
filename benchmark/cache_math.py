#!/usr/bin/env python3
"""算出 Zipf 分布下"本地缓存容量 → L1 命中率 → 期望延迟"的对应关系。

    python3 cache_math.py                       # 默认 N=1000万
    python3 cache_math.py --rows 1000000        # 你实际 seed 了多少条就填多少

★ 为什么要先算再压

压测之前先把理论值算出来，压完拿实测对照。两者接近说明系统行为符合预期；
差得远说明有问题（比如序列化开销、锁竞争、GC），这时候才知道该去抓火焰图。

反过来，如果不先算，压出来一个数字你根本不知道它是好是坏——
"3.8 万 QPS"到底是打满了还是差得远？没有理论值就无法判断。

数学：Zipf 下第 k 热门的 key 被访问的概率 p(k) ∝ 1/k^α，
前 C 个 key 覆盖的请求比例 = H(C, α) / H(N, α)，H 为广义调和数。
"""
import argparse
from math import log

# 每条缓存项内存估算（Caffeine + 64 位 JVM，开启指针压缩）
#   key   String(6 chars)  : ~40 B  (对象头 16 + coder/hash 8 + byte[6] 24 → 对齐后)
#   value String(~80 chars): ~120 B
#   Caffeine BoundedLocalCache 节点（含 frequency sketch 摊销）: ~90 B
BYTES_PER_ENTRY = 250

L1_NS = 100           # 本地缓存命中
L2_NS = 500_000       # Redis 一次 RT（0.5 ms，本机 Docker 的典型值）
L3_NS = 5_000_000     # MySQL 一次查询（5 ms）


def harmonic(n: int, a: float, exact_upto: int = 2_000_000) -> float:
    """H(n, a) = Σ 1/k^a。n 很大时对尾部用积分近似，避免上千万次循环。"""
    if n <= exact_upto:
        return sum(k ** -a for k in range(1, n + 1))
    s = sum(k ** -a for k in range(1, exact_upto + 1))
    m = exact_upto
    s += (log(n) - log(m)) if a == 1.0 else (n ** (1 - a) - m ** (1 - a)) / (1 - a)
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rows', type=int, default=10_000_000)
    ap.add_argument('--alphas', type=float, nargs='+', default=[1.0, 1.2])
    a = ap.parse_args()
    N = a.rows

    print(f"数据规模 N = {N:,} 条；每条缓存项按 {BYTES_PER_ENTRY} B 估算\n")

    for alpha in a.alphas:
        HN = harmonic(N, alpha)
        print(f"── Zipf α={alpha} " + "─" * 52)
        print(f"{'maximumSize':>12} {'L1命中率':>10} {'内存':>10} {'期望延迟':>11} {'相对10k':>9}")
        base = None
        for c in (1_000, 10_000, 50_000, 100_000, 200_000, 500_000, 1_000_000):
            if c > N:
                break
            h = harmonic(c, alpha) / HN
            mem = c * BYTES_PER_ENTRY / 1024 / 1024
            lat = (h * L1_NS + (1 - h) * L2_NS) / 1000     # 微秒
            if c == 10_000:
                base = lat
            rel = f"{base / lat:.2f}x" if base else "-"
            print(f"{c:>12,} {h * 100:>9.2f}% {mem:>8.1f}MB {lat:>9.1f}us {rel:>9}")
        print()

    print("── 对照：均匀随机（很多人默认用的分布）" + "─" * 30)
    for c in (10_000, 100_000, 1_000_000):
        if c <= N:
            print(f"{c:>12,} {c / N * 100:>9.4f}%   <-- 这就是均匀随机压测命中率上不去的原因")

    alpha = a.alphas[0]
    HN = harmonic(N, alpha)
    h10, h100 = harmonic(10_000, alpha) / HN, harmonic(min(100_000, N), alpha) / HN
    print(f"\n{'=' * 70}")
    print("结论")
    print(f"  用均匀随机压测会把 L1 命中率低估约 {h10 / (10_000 / N):.0f} 倍")
    print(f"  10,000 -> {h10 * 100:.1f}% ；100,000 -> {h100 * 100:.1f}%（多 21MB 内存）")
    print(f"\n  ⚠️ 必须分清两个数：")
    print(f"     L1 命中率   = Caffeine 命中 / 总请求      -> 约 {h100 * 100:.0f}%")
    print(f"     整体命中率  = (L1 + Redis) / 总请求       -> 99.9%+（Redis 装得下全部数据）")
    print(f"     简历上写哪个都行，但要标清楚是哪个。混着说会被面试官问穿。")


if __name__ == '__main__':
    main()

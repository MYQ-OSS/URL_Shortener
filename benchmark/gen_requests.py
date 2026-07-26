#!/usr/bin/env python3
"""生成 Zipf 分布的压测请求日志。只用标准库，无需 pip install。

    python3 gen_requests.py --rows 10000000 --requests 2000000 --alpha 1.0 -o wrk/requests.txt

★ 为什么必须用 Zipf 而不是均匀随机（这是压测最关键的一个决定）

真实短链流量是极度长尾的：少数热门链接占绝大部分点击。用均匀随机压测，
每个请求都打一个几乎没被访问过的短码，本地缓存永远不命中——
你测的是一个现实中不存在的场景，并且会系统性地低估缓存效果。

实测（N=1000万，本地缓存 10000 条）：
    均匀随机  ->  L1 覆盖 0.10%
    Zipf α=1.0 -> L1 覆盖 58.62%       低估了 586 倍
    Zipf α=1.2 -> L1 覆盖 89.00%

α 的取值必须在报告里写清楚，因为它显著改变结论：
    α=0.8 较平（长尾更重）  α=1.0 经典 Zipf  α=1.2~1.5 爆款效应明显
建议主报告用 α=1.0（保守、经典、易解释），另跑一组 α=1.2 作为"有爆款时"的对照。
"""
import argparse
import bisect
import random
from seedcode import short_uri


def build_cdf(n: int, alpha: float):
    """构造 Zipf 的累积分布。O(n) 时间、O(n) 空间，n=1000万 时约 80MB，可接受。"""
    cdf = []
    total = 0.0
    for k in range(1, n + 1):
        total += k ** -alpha
        cdf.append(total)
    # 归一化省掉：直接在 [0, total) 上取随机数二分即可
    return cdf, total


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--rows', type=int, default=10_000_000, help='库里的短链总数')
    ap.add_argument('--requests', type=int, default=2_000_000, help='生成多少条请求')
    ap.add_argument('--alpha', type=float, default=1.0, help='Zipf 指数')
    ap.add_argument('--seed', type=int, default=20260726,
                    help='固定随机种子 —— 优化前后必须用同一个种子，否则数据不可比')
    ap.add_argument('--miss-ratio', type=float, default=0.0,
                    help='掺入多少比例的"不存在的短码"，用来同时压测防穿透链路')
    ap.add_argument('-o', '--out', default='wrk/requests.txt')
    a = ap.parse_args()

    random.seed(a.seed)      # 可复现是压测报告可信的前提
    print(f"构造 Zipf CDF (n={a.rows:,}, alpha={a.alpha}) ...")
    cdf, total = build_cdf(a.rows, a.alpha)

    print(f"生成 {a.requests:,} 条请求 -> {a.out}")
    n_miss = 0
    with open(a.out, 'w', encoding='utf-8') as f:
        for i in range(a.requests):
            if a.miss_ratio > 0 and random.random() < a.miss_ratio:
                # 取一个远超种子范围的 row，其短码一定不在库里 -> 走 BF/空值缓存路径
                f.write(short_uri(a.rows + 1 + random.randrange(10 ** 9)) + '\n')
                n_miss += 1
            else:
                rank = bisect.bisect_left(cdf, random.random() * total) + 1
                f.write(short_uri(min(rank, a.rows)) + '\n')
            if (i + 1) % 500_000 == 0:
                print(f"  {i + 1:,} / {a.requests:,}")

    print(f"完成。其中不存在的短码 {n_miss:,} 条（{n_miss / a.requests * 100:.2f}%）")
    print(f"\n⚠️ 记住两件事：")
    print(f"   1. seed 完数据后必须重建布隆过滤器，否则全部 404 —— ")
    print(f"      POST http://localhost:8080/admin/bloom/rebuild")
    print(f"   2. 优化前后必须用同一个 --seed 和同一份 requests.txt，否则数据不可比")


if __name__ == '__main__':
    main()

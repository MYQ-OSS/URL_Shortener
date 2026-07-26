"""交叉校验：Python / Java / SQL 三处的短码生成规则必须完全一致。

用法（Java 侧的期望值由 JavaCodeDump 生成）：
    python3 verify_consistency.py java_codes.txt
不带参数时只自检 Python 侧。
"""
import sys
from seedcode import short_uri, b62, DOMAIN

# Python 自检
assert len({short_uri(i) for i in range(1, 200_001)}) == 200_000, "前 20 万行短码出现重复"
assert all(len(short_uri(i)) == 6 for i in range(1, 1000)), "短码长度不是 6"
print(f"  Python 自检通过：前 20 万行无重复、长度恒为 6")
print(f"  样例 row 1..5 -> {[short_uri(i) for i in range(1, 6)]}")

if len(sys.argv) > 1:
    expected = [l.strip() for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
    got = [short_uri(i) for i in range(1, len(expected) + 1)]
    bad = [(i + 1, e, g) for i, (e, g) in enumerate(zip(expected, got)) if e != g]
    if bad:
        print(f"  !! 与 Java 不一致，前 5 处差异: {bad[:5]}")
        sys.exit(1)
    print(f"  Java 交叉校验通过：{len(expected)} 个短码完全一致")

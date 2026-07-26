"""压测种子短码的生成规则（Python 参照实现）。

必须与三处保持一致：
  1) Java  Base62Codec.TABLE
  2) SQL   benchmark/seed.sql 里的 fn_b62()
  3) 本文件

不一致的后果是压测请求全部 404，而你会误以为是缓存或 BF 的问题——
非常难查，所以这三处必须有一个交叉校验（见 verify_consistency.py）。

注意：种子数据用"模乘置换"生成短码，是因为它保证唯一、无需判重、可批量算出。
生产环境的短码走 ShortCodeGenerator 的随机方案（模乘置换会被两次 API 调用反解，
见 ADR-003）。测试数据不需要抗枚举，所以这里用它完全没问题。
"""
TABLE = "kb27yYTIZxKSQuVGwcFspJ01DfnLmdltOzCqAgr9oX3aP8W5MU4HhBjvEeNiR6"
DOMAIN = 62 ** 6          # 56,800,235,584
P = 1_000_000_007
K = 20_260_726

assert len(TABLE) == 62 and len(set(TABLE)) == 62


def b62(value: int) -> str:
    """定长 6 位 Base62 编码，与 Java Base62Codec.encodeFixed 等价。"""
    if not (0 <= value < DOMAIN):
        raise ValueError(f"value 超出 [0, 62^6): {value}")
    out = []
    for _ in range(6):
        out.append(TABLE[value % 62])
        value //= 62
    return "".join(reversed(out))


def short_uri(row: int) -> str:
    """第 row 行（从 1 开始）种子数据对应的短码。"""
    return b62((row * P + K) % DOMAIN)

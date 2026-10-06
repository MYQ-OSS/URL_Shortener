// k6 脚本：按预生成的 Zipf 请求日志压跳转链路（对应 wrk/redirect.lua 的等价实现）
//
//   ~/bin/k6 run -e REQ_FILE=/mnt/d/Java/Allproject/URL_Shortener/benchmark/wrk/requests.txt \
//       -e VUS=200 -e DURATION=60s benchmark/k6/redirect.js
//
// 与 lua 版保持完全一致的两个关键设计：
//
// 1) 请求序列来自预生成文件而不是运行时随机——
//    "优化前/优化后"打的是完全相同的请求序列，数据才可比（ADR-012）。
// 2) 每个 VU 从文件的不同确定性偏移开始（(vuId-1)*9973 % n，9973 是质数），
//    否则所有 VU 同时打同一个短码，会人为制造出一个不存在的超级热点，命中率虚高。
//
// ★ k6 默认会跟随重定向（和 wrk 不同！），必须显式 redirects: 0——
// 我们要测的是"服务端产出 302 的能力"，跟随重定向会把 example.com 的
// 响应时间掺进来，测的就不是自己的系统了。

import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { vu } from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const codes = new SharedArray('codes', function () {
    const path = __ENV.REQ_FILE || './wrk/requests.txt';
    const text = open(path);
    if (!text) {
        throw new Error('读不到请求日志: ' + path + '\n先运行: python gen_requests.py');
    }
    // trim 必须做：Windows 下 Python 文本模式写出的文件是 CRLF 行尾，
    // split('\n') 后每行尾部带 '\r'，不剥掉会拼出非法 URL（wrk 的 f:lines() 会自动剥）
    return text.split('\n').map((l) => l.trim()).filter((l) => l.length > 0);
});

// ── 状态码分布（对应 lua 版的 response/done 统计）──────────────────
// 302 之外的都是问题：
//   410 -> 短码不存在/已失效。大面积出现 = seed 数据和请求日志不匹配，
//          或者链接已禁用/过期，需检查目标库的主表记录
//   429 -> 被限流打到了。隔离压测时可提高 shortlink.rate-limit.redirect 的容量与补充速率
//   5xx -> 真问题
const st302 = new Counter('st_302');
const st404 = new Counter('st_404');
const st410 = new Counter('st_410');
const st429 = new Counter('st_429');
const st5xx = new Counter('st_5xx');
const stOther = new Counter('st_other');
const httpDuration = new Trend('redirect_duration', true);

export const options = {
    vus: parseInt(__ENV.VUS || '200'),
    duration: __ENV.DURATION || '60s',
    // k6 默认只算 avg/min/med/max/p90/p95，p99/p99.9 必须显式声明才有
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],
    // 注意：不能用内置的 http_req_failed——它把 302 也算失败，而 302 正是我们要的。
    // 用自己的状态码计数器做门槛：5xx 必须为 0。
    thresholds: {
        st_5xx: ['count==0'],
    },
};

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const n = codes.length;

let idx = -1;   // 每个 VU 独立的模块级游标（k6 每个 VU 有独立的 JS 运行时）

export default function () {
    if (idx < 0) {
        // 首个迭代初始化确定性起点（vu.idInTest 从 1 开始）
        idx = ((vu.idInTest - 1) * 9973) % n;
    }
    const code = codes[idx];
    idx = (idx + 1) % n;

    const res = http.get(BASE + '/' + code, { redirects: 0, tags: { name: 'redirect' } });
    httpDuration.add(res.timings.duration);

    const st = res.status;
    if (st === 302) st302.add(1);
    else if (st === 404) st404.add(1);
    else if (st === 410) st410.add(1);
    else if (st === 429) st429.add(1);
    else if (st >= 500) st5xx.add(1);
    else stOther.add(1);
}

// 收尾报告：对齐 lua 版 done() 的输出格式，方便抄进 results/baseline.md
export function handleSummary(data) {
    // ★ k6 会把"从未被 add 过"的自定义指标从 summary 里整个省略掉（比如全程没有
    //   404 时 st_404 不存在），引用前必须判空，否则 handleSummary 直接抛异常。
    const cnt = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);

    const total = cnt('st_302') + cnt('st_404') + cnt('st_410') + cnt('st_429') + cnt('st_5xx') + cnt('st_other');
    const d = data.metrics.redirect_duration.values;
    const rps = data.metrics.http_reqs.values.rate;

    let out = '';
    out += '\n--- 状态码分布 ---\n';
    const dist = [
        [302, cnt('st_302')],
        [404, cnt('st_404')],
        [410, cnt('st_410')],
        [429, cnt('st_429')],
        ['5xx', cnt('st_5xx')],
        ['other', cnt('st_other')],
    ];
    for (const [st, c] of dist) {
        if (c > 0) out += `  ${st} : ${c} (${((c / total) * 100).toFixed(2)}%)\n`;
    }
    out += '\n--- 关键指标（抄进 results/baseline.md）---\n';
    out += `  QPS        : ${Math.round(rps)}\n`;
    out += `  P50        : ${d['p(50)'].toFixed(2)} ms\n`;
    out += `  P99        : ${d['p(99)'].toFixed(2)} ms\n`;
    out += `  P99.9      : ${d['p(99.9)'].toFixed(2)} ms\n`;
    out += `  总请求数    : ${data.metrics.http_reqs.values.count}\n`;

    const ratio302 = total > 0 ? cnt('st_302') / total : 0;
    if (ratio302 < 0.95) {
        out += '\n  !! 302 占比低于 95%，这次压测数据不可用。检查：\n';
        out += '     1. seed 数据是否灌进去了\n';
        out += '     2. 主表记录是否有效、连接的是否为预期测试库\n';
        out += '     3. 限流是否把请求挡了（看 429 数量）\n';
    }
    return { stdout: out };
}

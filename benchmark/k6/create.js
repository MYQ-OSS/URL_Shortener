// k6 脚本：压创建接口（对应 wrk/create.lua 的等价实现）
//
//   ~/bin/k6 run -e VUS=50 -e DURATION=30s benchmark/k6/create.js
//
// ⚠️ 创建接口的并发要开小（50 而不是 200）。理由不是它扛不住，
// 而是短链的读写比是 1:500 —— 用跳转的并发量去压创建，测的是一个不存在的场景。
// 真实的创建 QPS 大概在几百到几千，把它压到几万没有参考价值。
//
// 压测时限流必须关闭（shortlink.rate-limit.enabled=false），
// 否则创建默认 10 QPS/gid，绝大部分请求会变成 429。

import http from 'k6/http';
import { vu } from 'k6/execution';
import { Counter } from 'k6/metrics';

const st200 = new Counter('st_200');
const st429 = new Counter('st_429');
const st5xx = new Counter('st_5xx');
const stOther = new Counter('st_other');

export const options = {
    vus: parseInt(__ENV.VUS || '50'),
    duration: __ENV.DURATION || '30s',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'p(99.9)'],
};

const BASE = __ENV.BASE_URL || 'http://127.0.0.1:8080';

// 每个 VU 用自己的计数器 + vu.idInTest 前缀，保证 URL 全局唯一（和 lua 版 counter+os.time 等价）
let counter = 0;

export default function () {
    counter++;
    // forceNew=true 跳过幂等，保证每次都真的走完整的创建链路（发号 + 生成短码 + 落库）。
    // 不加这个的话第二次起就命中幂等直接返回，测的是查询而不是创建。
    const gidNum = (counter + vu.idInTest * 100000) % 100;
    const body = JSON.stringify({
        originUrl: `https://example.com/bench/${vu.idInTest}-${counter}-${Date.now()}`,
        gid: 'bench' + String(gidNum).padStart(3, '0'),
        forceNew: true,
    });
    const res = http.post(BASE + '/api/link', body, {
        headers: { 'Content-Type': 'application/json' },
        tags: { name: 'create' },
    });

    const st = res.status;
    if (st === 200) st200.add(1);
    else if (st === 429) st429.add(1);
    else if (st >= 500) st5xx.add(1);
    else stOther.add(1);
}

export function handleSummary(data) {
    // k6 会省略从未 add 过的指标，引用必须判空
    const cnt = (name) => (data.metrics[name] ? data.metrics[name].values.count : 0);
    // 带 tag 的指标在 handleSummary 里的键名是 "http_req_duration{name:create}"
    const d = data.metrics['http_req_duration{name:create}']
        ? data.metrics['http_req_duration{name:create}'].values
        : null;
    let out = '\n--- 状态码分布 ---\n';
    out += `  200 : ${cnt('st_200')}\n`;
    out += `  429 : ${cnt('st_429')}\n`;
    out += `  5xx : ${cnt('st_5xx')}\n`;
    out += `  other: ${cnt('st_other')}\n`;
    out += `\n  创建 QPS : ${Math.round(data.metrics.http_reqs.values.rate)}\n`;
    if (d) out += `  P99      : ${d['p(99)'].toFixed(2)} ms\n`;
    out += '\n  对照检查：看 /admin/stats 里发号器的 syncLoadCount。\n';
    out += '  它持续增长说明 step 太小、预加载赶不上消耗（见 ADR-002）。\n';
    return { stdout: out };
}

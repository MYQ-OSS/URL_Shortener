import fs from 'node:fs';
import http from 'node:http';

const base = new URL(process.env.BASE_URL ?? 'http://127.0.0.1:8080');
const codesPath = process.env.REQ_FILE ?? 'benchmark/wrk/requests.txt';
const stages = (process.env.STAGES ?? '1,4,8,16,32,64').split(',').map(Number);
const durationMs = Number(process.env.STAGE_SECONDS ?? 30) * 1000;
const latencyStopMs = Number(process.env.P95_STOP_MS ?? 250);
const codes = fs.readFileSync(codesPath, 'utf8').split(/\r?\n/).filter(Boolean);

if (base.protocol !== 'http:' || !['127.0.0.1', 'localhost', '::1', '[::1]'].includes(base.hostname)) {
    throw new Error('This local benchmark runner only permits loopback destinations');
}
if (codes.length === 0 || stages.some((n) => !Number.isInteger(n) || n < 1 || n > 64)) {
    throw new Error('REQ_FILE must contain short codes and STAGES must be integers from 1 to 64');
}
if (!Number.isFinite(durationMs) || durationMs < 1000 || durationMs > 120000) {
    throw new Error('STAGE_SECONDS must be between 1 and 120');
}

const agent = new http.Agent({ keepAlive: true, maxSockets: Math.max(...stages) });
const results = [];
let stopRequested = false;

function request(code) {
    return new Promise((resolve) => {
        const start = performance.now();
        const req = http.get(new URL(`/${code}`, base), { agent }, (res) => {
            res.resume();
            res.once('end', () => resolve({
                status: res.statusCode,
                location: res.headers.location ?? '',
                durationMs: performance.now() - start,
            }));
        });
        req.setTimeout(5000, () => req.destroy(new Error('request timeout')));
        req.once('error', (error) => resolve({ error: error.message, durationMs: performance.now() - start }));
    });
}

function percentile(values, p) {
    if (values.length === 0) return null;
    const sorted = [...values].sort((a, b) => a - b);
    return Number(sorted[Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)].toFixed(2));
}

for (const vus of stages) {
    if (stopRequested) break;
    const start = performance.now();
    const cpuStart = process.cpuUsage();
    const deadline = start + durationMs;
    const latencies = [];
    const statuses = {};
    const errors = [];
    let nextCode = Number(process.env.START_OFFSET ?? 0) % codes.length;

    const workers = Array.from({ length: vus }, async () => {
        while (!stopRequested && performance.now() < deadline) {
            const code = codes[nextCode++ % codes.length];
            const result = await request(code);
            latencies.push(result.durationMs);
            if (result.error) {
                errors.push(result.error);
                stopRequested = true;
                continue;
            }
            statuses[result.status] = (statuses[result.status] ?? 0) + 1;
            if (result.status !== 302 || !result.location.startsWith('https://example.com/seed/')) {
                errors.push(`unexpected response: ${result.status}`);
                stopRequested = true;
            }
        }
    });

    const progress = setInterval(() => {
        const elapsed = Math.max(1, (performance.now() - start) / 1000);
        process.stdout.write(`\r${vus} VU  ${elapsed.toFixed(0)}s/${(durationMs / 1000).toFixed(0)}s  ${latencies.length} responses`);
    }, 5000);
    await Promise.all(workers);
    clearInterval(progress);

    const elapsedSec = (performance.now() - start) / 1000;
    const cpuUsed = process.cpuUsage(cpuStart);
    const p95 = percentile(latencies, 0.95);
    const row = {
        vus,
        durationSec: Number(elapsedSec.toFixed(2)),
        requests: latencies.length,
        rps: Number((latencies.length / elapsedSec).toFixed(2)),
        generatorCpuPercent: Number((((cpuUsed.user + cpuUsed.system) / 1000) / (elapsedSec * 10)).toFixed(1)),
        statusCodes: statuses,
        errors,
        latencyMs: {
            p50: percentile(latencies, 0.50),
            p95,
            p99: percentile(latencies, 0.99),
            max: latencies.length
                ? Number(latencies.reduce((max, value) => Math.max(max, value), 0).toFixed(2))
                : null,
        },
    };
    results.push(row);
    const outputPath = process.env.RESULT_FILE;
    if (outputPath) {
        const saved = results.map((item, index) => process.env.RAW_LATENCIES === '1'
            ? { ...item, latencySamples: index === results.length - 1 ? latencies : undefined }
            : item);
        fs.writeFileSync(outputPath, JSON.stringify(saved, null, 2) + '\n');
    }
    process.stdout.write(`\r${JSON.stringify(row)}\n`);

    if (errors.length > 0 || (p95 !== null && p95 > latencyStopMs)) {
        stopRequested = true;
        process.stdout.write(`Stop guard: error or P95 > ${latencyStopMs} ms; higher VU stages skipped.\n`);
    }
}

agent.destroy();

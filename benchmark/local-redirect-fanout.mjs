import fs from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const script = path.join(path.dirname(fileURLToPath(import.meta.url)), 'local-redirect.mjs');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const clients = Number(process.env.CLIENTS ?? 4);
const vus = Number(process.env.VUS_PER_CLIENT ?? 64);
const duration = Number(process.env.STAGE_SECONDS ?? 30);
const codesPath = path.resolve(process.env.REQ_FILE ?? path.join(root, '.local/loadtest/requests.txt'));
const resultDir = path.resolve(process.env.RESULT_DIR ?? path.join(root, '.local/loadtest/fanout'));
const codes = fs.readFileSync(codesPath, 'utf8').split(/\r?\n/).filter(Boolean);

if (!Number.isInteger(clients) || clients < 1 || clients > 8 || !Number.isInteger(vus) || vus < 1 || vus > 64) {
    throw new Error('CLIENTS must be 1..8 and VUS_PER_CLIENT must be 1..64');
}
fs.mkdirSync(resultDir, { recursive: true });

const children = [];
const start = performance.now();
const exits = Array.from({ length: clients }, (_, index) => new Promise((resolve, reject) => {
    const resultFile = path.join(resultDir, `client-${index + 1}.json`);
    const child = spawn(process.execPath, [script], {
        cwd: root,
        env: {
            ...process.env,
            BASE_URL: process.env.BASE_URL ?? 'http://127.0.0.1:18080',
            REQ_FILE: codesPath,
            STAGES: String(vus),
            STAGE_SECONDS: String(duration),
            RESULT_FILE: resultFile,
            RAW_LATENCIES: '1',
            START_OFFSET: String(Math.floor(codes.length * index / clients)),
        },
        stdio: ['ignore', 'pipe', 'inherit'],
    });
    children.push(child);
    child.stdout.pipe(process.stdout, { end: false });
    child.once('error', reject);
    child.once('exit', (code) => code === 0 ? resolve(resultFile) : reject(new Error(`client ${index + 1} exited with ${code}`)));
}));

for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, () => children.forEach((child) => child.kill('SIGTERM')));
}

const files = await Promise.all(exits);
const clientResults = files.map((file) => JSON.parse(fs.readFileSync(file, 'utf8'))[0]);
const samples = clientResults.flatMap((item) => item.latencySamples ?? []);
const sorted = [...samples].sort((a, b) => a - b);
const percentile = (p) => sorted.length === 0 ? null
    : Number(sorted[Math.min(sorted.length - 1, Math.ceil(p * sorted.length) - 1)].toFixed(2));
const statusCodes = {};
for (const result of clientResults) {
    for (const [status, count] of Object.entries(result.statusCodes)) {
        statusCodes[status] = (statusCodes[status] ?? 0) + count;
    }
}

const elapsedSec = (performance.now() - start) / 1000;
const summary = {
    clients,
    vusPerClient: vus,
    totalVus: clients * vus,
    elapsedSec: Number(elapsedSec.toFixed(2)),
    requests: samples.length,
    rps: Number((samples.length / elapsedSec).toFixed(2)),
    statusCodes,
    errors: clientResults.flatMap((item) => item.errors),
    aggregateGeneratorCpuPercent: Number(clientResults.reduce((sum, item) => sum + item.generatorCpuPercent, 0).toFixed(1)),
    latencyMs: { p50: percentile(0.5), p95: percentile(0.95), p99: percentile(0.99), max: sorted.at(-1) ?? null },
    clientsResult: clientResults.map(({ latencySamples, ...item }) => item),
};
const output = process.env.RESULT_FILE ?? path.join(resultDir, 'aggregate.json');
fs.writeFileSync(output, JSON.stringify(summary, null, 2) + '\n');
process.stdout.write(`Aggregate: ${JSON.stringify(summary)}\nSaved: ${output}\n`);

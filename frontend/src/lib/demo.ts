import type { LinkRecord, RuntimeStats } from '../types'
import { backendDate } from './format'

export function demoLinks(): LinkRecord[] {
  const entries = [
    ['k7M2pQ', '十月产品更新', 'https://example.com/product/updates/october?utm_source=newsletter', 4826, 0, 0],
    ['dQ8mY3', '团队设计规范', 'https://www.figma.com/community', 2318, 0, 0],
    ['xM4aR2', '开发文档 · 快速开始', 'https://vuejs.org/guide/introduction.html', 1752, 0, 0],
    ['vN6sL9', '秋季活动报名', 'https://example.com/events/autumn?ref=poster', 964, 0, 1],
    ['bR2eT8', '品牌素材下载', 'https://example.com/brand/assets/download', 632, 1, 0],
    ['p5CwZ1', '每周阅读清单', 'https://github.com/vuejs/core', 418, 0, 0],
    ['hY3kF6', '九月招募入口', 'https://example.com/jobs/september', 287, 0, 2],
    ['aT9nB4', '开源组件索引', 'https://vite.dev/guide/', 156, 0, 0],
  ] as const
  return entries.map(([code, title, origin, clicks, disabled, expiry], index) => ({
    id: `demo-${index}`, shortUri: code, domain: 'fold.example', originUrl: origin, gid: 'default',
    enableStatus: disabled, validType: expiry ? 1 : 0,
    validEnd: expiry ? backendDate(expiry === 1 ? 2 * 86_400_000 : -86_400_000) : null,
    clickNum: clicks, describe: title, createTime: backendDate(-(index + 1) * 86_400_000),
  }))
}

export function demoStats(): RuntimeStats {
  return {
    localCache: { size: 1462, hitCount: 18340, missCount: 1296, hitRate: '0.9340', evictionCount: 76 },
    stats: { pending: 12, bufferCapacity: 4096, dropped: 0, publishFailures: 0, flushedRows: 1274, lastFlushAt: Date.now() - 12_000, deliveryMode: 'best-effort' },
  }
}

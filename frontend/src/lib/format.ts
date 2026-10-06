import type { LinkRecord, LinkStatus } from '../types'

export const statusNames: Record<LinkStatus, string> = {
  active: '正常', disabled: '已停用', expired: '已过期', blocked: '风控封禁',
}

// 后端 DATETIME 不带时区，统一按北京时间解释，避免客户端时区改变有效期。
export function backendTime(value: string | null): number {
  if (!value) return NaN
  return Date.parse(/[zZ]|[+-]\d{2}:\d{2}$/.test(value) ? value : `${value}+08:00`)
}

export function linkStatus(link: LinkRecord, now = Date.now()): LinkStatus {
  if (link.enableStatus === 2) return 'blocked'
  if (link.validType === 1 && (!link.validEnd || !(backendTime(link.validEnd) > now))) return 'expired'
  return link.enableStatus === 1 ? 'disabled' : 'active'
}

export function shortUrl(link: Pick<LinkRecord, 'domain' | 'shortUri'>): string {
  const domain = /^https?:\/\//i.test(link.domain) ? link.domain : `http://${link.domain}`
  return `${domain.replace(/\/$/, '')}/${link.shortUri}`
}

export function safeHttpUrl(value: string): string | null {
  try {
    const parsed = new URL(value)
    return ['http:', 'https:'].includes(parsed.protocol) ? parsed.href : null
  } catch { return null }
}

export function hostname(value: string): string {
  try { return new URL(value).hostname.replace(/^www\./, '') } catch { return value }
}

export function number(value: number): string {
  return new Intl.NumberFormat('zh-CN').format(value)
}

export function dateLabel(value: string | null, full = false): string {
  const timestamp = backendTime(value)
  if (!Number.isFinite(timestamp)) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', month: '2-digit', day: '2-digit',
    ...(full ? { year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' as const } : {}),
  }).format(timestamp)
}

export function localDateInput(offset = 60_000): string {
  return new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).format(new Date(Date.now() + offset)).replace(' ', 'T')
}

export function backendDate(offset: number): string {
  return `${localDateInput(offset)}:00`
}

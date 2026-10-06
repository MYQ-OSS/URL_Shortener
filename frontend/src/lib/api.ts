import JSONbigFactory from 'json-bigint'
import type { Connection, CreateLinkInput, LinkRecord, MaintenanceAction, RuntimeStats } from '../types'
import { safeHttpUrl } from './format'

// 雪花 ID 大于 JS 的安全整数范围，必须在 JSON 解析阶段保留原始十进制字符串。
const JSONbig = JSONbigFactory({ storeAsString: true })
type JsonObject = Record<string, unknown>
const object = (value: unknown): JsonObject => value && typeof value === 'object' ? value as JsonObject : {}

export class ApiError extends Error {
  constructor(message: string, public readonly status = 0) { super(message); this.name = 'ApiError' }
}

export function validateConnection(input: Connection): Connection {
  const baseUrl = input.baseUrl.trim().replace(/\/$/, '')
  if (baseUrl) {
    const safe = safeHttpUrl(baseUrl)
    if (!safe || new URL(safe).origin !== baseUrl) throw new ApiError('服务地址请填写完整的 http/https 源地址，不包含路径。')
  }
  const gid = input.gid.trim()
  const apiToken = input.apiToken.trim().replace(/^Bearer\s+/i, '')
  if (!gid || gid.length > 32) throw new ApiError('工作空间 gid 需要 1–32 个字符。')
  if (!apiToken) throw new ApiError('请填写绑定此 gid 的 API Token。')
  return { baseUrl, gid, apiToken, adminToken: input.adminToken.trim() }
}

function linkRecord(raw: unknown): LinkRecord {
  const row = object(raw)
  if (typeof row.shortUri !== 'string' || typeof row.originUrl !== 'string') throw new ApiError('服务返回的链接数据不完整。')
  return {
    id: String(row.id ?? row.shortUri), shortUri: row.shortUri,
    domain: String(row.domain || ''), originUrl: row.originUrl, gid: String(row.gid || ''),
    enableStatus: Number(row.enableStatus ?? 0), validType: Number(row.validType ?? 0),
    validEnd: typeof row.validEnd === 'string' ? row.validEnd : null,
    clickNum: Number(row.clickNum ?? 0), describe: typeof row.describe === 'string' ? row.describe : null,
    createTime: typeof row.createTime === 'string' ? row.createTime : null,
  }
}

export function createApi(connection: Connection) {
  async function request<T>(path: string, method = 'GET', body?: unknown, admin = false, timeoutMillis = 12_000): Promise<T> {
    const token = admin ? connection.adminToken : connection.apiToken
    if (!token) throw new ApiError(admin ? '需要管理员 Token 才能访问运行观察。' : '请先连接工作空间。')
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), timeoutMillis)
    try {
      const response = await fetch(`${connection.baseUrl}${path}`, {
        method, signal: controller.signal,
        headers: { ...(admin ? { 'X-Admin-Token': token } : { Authorization: `Bearer ${token}` }),
          ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      })
      const text = await response.text()
      let parsed: unknown = text
      if (text && response.headers.get('content-type')?.includes('json')) {
        try { parsed = JSONbig.parse(text) } catch { throw new ApiError('服务返回了无法解析的数据。', response.status) }
      }
      if (!response.ok) {
        const data = object(parsed)
        let detail = typeof data.error === 'string' ? data.error : typeof parsed === 'string' ? parsed : ''
        const fallback: Record<number, string> = {
          401: 'API Token 未通过验证，请检查连接设置。', 403: '凭据权限不足，或 gid 与 Token 不匹配。',
          404: '链接或消息不存在，可能已被处理。', 429: '操作过于频繁，请稍后再试。', 503: '服务暂时繁忙，请稍后再试。',
        }
        if (response.status === 401 && detail === 'Invalid API token') detail = fallback[401]
        if (admin && response.status === 403 && detail.includes('Invalid admin token')) {
          detail = '管理员 Token 未通过验证，请检查管理员凭据。'
        }
        throw new ApiError(detail.slice(0, 240) || fallback[response.status] || `请求未完成（${response.status}）。`, response.status)
      }
      return parsed as T
    } catch (error) {
      if (error instanceof ApiError) throw error
      if (error instanceof Error && error.name === 'AbortError') throw new ApiError(method === 'GET'
        ? '服务响应超时，请检查后端是否运行。' : '等待操作返回超时，服务可能仍在执行，请先刷新列表或状态确认。')
      const origin = typeof location === 'undefined' ? '' : location.origin
      if (connection.baseUrl && origin && new URL(connection.baseUrl).origin !== origin) {
        throw new ApiError(`浏览器无法连接 ${connection.baseUrl}。本地开发可将“服务地址”留空使用代理；直接连接需要后端允许前端来源 ${origin}。`)
      }
      throw new ApiError('无法连接服务，请确认后端已启动，以及开发代理指向了正确的端口。')
    } finally { clearTimeout(timeout) }
  }

  return {
    async links(): Promise<LinkRecord[]> {
      const data = await request<unknown>(`/api/link/list?gid=${encodeURIComponent(connection.gid)}&limit=100`)
      if (!Array.isArray(data)) throw new ApiError('服务返回的链接列表格式不正确。')
      return data.map(linkRecord)
    },
    async create(input: CreateLinkInput): Promise<string> {
      const value = await request<unknown>('/api/link', 'POST', input)
      if (typeof value !== 'string' || !safeHttpUrl(value)) throw new ApiError('服务未返回有效的短链接。')
      return value
    },
    toggle: (code: string, enabled: boolean) => request<void>(`/api/link/${encodeURIComponent(code)}/${enabled ? 'enable' : 'disable'}`, 'POST'),
    async stats(): Promise<RuntimeStats> {
      const data = await request<RuntimeStats>('/admin/stats', 'GET', undefined, true)
      if (!data?.localCache || !data?.stats) throw new ApiError('运行状态数据不完整。')
      return data
    },
    maintenance(action: MaintenanceAction) {
      const paths = { cache: '/admin/cache/local/clear', expired: '/admin/expired/clean' }
      return request<Record<string, unknown>>(paths[action], 'POST', undefined, true, 30_000)
    },
    risk: (rules: { blockedDomains?: string[]; allowedDomains?: string[] }) => request<void>('/admin/risk/reload', 'POST', rules, true),
  }
}

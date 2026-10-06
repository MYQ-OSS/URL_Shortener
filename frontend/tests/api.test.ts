import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createApi, validateConnection } from '../src/lib/api'

const config = { baseUrl: '', gid: 'default', apiToken: 'api-token', adminToken: 'admin-token' }
afterEach(() => vi.unstubAllGlobals())

describe('后端请求契约', () => {
  it('将链接主键无损保留为字符串并使用 Bearer 请求头', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('[{"id":2065100100012345678,"shortUri":"Ab12Cd","domain":"localhost:8080","originUrl":"https://example.com","gid":"default"}]',
      { headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const rows = await createApi(config).links()
    expect(rows[0].id).toBe('2065100100012345678')
    expect(fetchMock.mock.calls[0][1].headers).toEqual({ Authorization: 'Bearer api-token' })
  })
  it('管理员运行状态不依赖已移除的实验组件', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{"localCache":{},"stats":{"deliveryMode":"best-effort"}}',
      { headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const stats = await createApi(config).stats()
    expect(stats.stats.deliveryMode).toBe('best-effort')
    expect(fetchMock.mock.calls[0][1].headers).toEqual({ 'X-Admin-Token': 'admin-token' })
  })
  it('创建接口接受纯文本短 URL，凭据不会进入请求体', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('http://localhost:8080/Ab12Cd'))
    vi.stubGlobal('fetch', fetchMock)
    const result = await createApi(config).create({ originUrl: 'https://example.com', gid: 'default', forceNew: false, validType: 0, validEnd: null, describe: null })
    expect(result).toBe('http://localhost:8080/Ab12Cd')
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer api-token')
    expect(fetchMock.mock.calls[0][1].body).not.toContain('api-token')
  })
  it('拒绝错误的凭据并保留服务端校验信息', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"error":"gid 不匹配"}',
      { status: 403, headers: { 'Content-Type': 'application/json' } })))
    await expect(createApi(config).links()).rejects.toThrow('gid 不匹配')
  })
  it('服务地址必须是源地址，不允许夹带密码或路径', () => {
    expect(() => validateConnection({ ...config, baseUrl: 'https://user:password@example.com' })).toThrow(ApiError)
    expect(() => validateConnection({ ...config, baseUrl: 'https://example.com/api' })).toThrow(ApiError)
    expect(validateConnection({ ...config, apiToken: 'Bearer abc' }).apiToken).toBe('abc')
  })
  it('跨域网络失败给出代理与允许来源的明确指引', async () => {
    vi.stubGlobal('location', { origin: 'http://localhost:5173' })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))
    await expect(createApi({ ...config, baseUrl: 'http://localhost:8080' }).links())
      .rejects.toThrow('服务地址”留空使用代理')
  })
})

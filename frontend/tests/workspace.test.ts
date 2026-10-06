import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useWorkspaceStore } from '../src/stores/workspace'

const config = { baseUrl: '', gid: 'default', apiToken: 'api-token', adminToken: '' }
const json = (body: string) => new Response(body, { headers: { 'Content-Type': 'application/json' } })
const input = { originUrl: 'https://example.com', gid: 'default', forceNew: false, validType: 0 as const, validEnd: null, describe: '新链接' }
beforeEach(() => { vi.useFakeTimers(); setActivePinia(createPinia()) })
afterEach(() => { vi.clearAllTimers(); vi.useRealTimers(); vi.unstubAllGlobals() })

it('创建成功后直接刷新主表列表，不再轮询索引同步状态', async () => {
  const fetchMock = vi.fn().mockResolvedValueOnce(json('[]'))
    .mockResolvedValueOnce(new Response('http://localhost:8080/Ab12Cd'))
    .mockResolvedValueOnce(json('[{"id":2065100100012345678,"shortUri":"Ab12Cd","domain":"localhost:8080","originUrl":"https://example.com","gid":"default","enableStatus":0,"validType":0,"clickNum":0}]'))
  vi.stubGlobal('fetch', fetchMock)
  const store = useWorkspaceStore()
  await store.connect(config)
  const created = await store.createLink(input)
  expect(created.id).toBe('2065100100012345678')
  expect(store.links).toHaveLength(1)
  expect(store.links[0]).not.toHaveProperty('pendingSync')
  expect(fetchMock).toHaveBeenCalledTimes(3)
})

it('列表刷新失败不把已经创建的短链接误报为失败', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json('[]'))
    .mockResolvedValueOnce(new Response('http://localhost:8080/Ab12Cd'))
    .mockRejectedValueOnce(new TypeError('Failed to fetch')))
  const store = useWorkspaceStore()
  await store.connect(config)
  expect((await store.createLink(input)).shortUri).toBe('Ab12Cd')
  expect(store.links).toHaveLength(1)
  expect(store.loadError).not.toBe('')
  expect(store.toasts.some(item => item.message.includes('短链接已创建'))).toBe(true)
})

it('切换回演示后，迟到的真实连接响应不能覆盖空间', async () => {
  let resolve: (value: Response) => void = () => {}
  vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>(done => { resolve = done })))
  const store = useWorkspaceStore()
  const pending = store.connect(config)
  store.useDemo()
  resolve(json('[]'))
  await expect(pending).rejects.toThrow('连接过程已取消')
  expect(store.isDemo).toBe(true)
  expect(store.links.length).toBeGreaterThan(0)
})

it('演示缓存清理后刷新保持维护结果，运行状态不再包含实验组件', async () => {
  const store = useWorkspaceStore()
  await store.refreshOperations()
  await store.maintain('cache')
  await store.refreshOperations()
  expect(store.stats?.localCache.size).toBe(0)
  expect(store.stats?.stats.deliveryMode).toBe('best-effort')
  expect(store.stats).not.toHaveProperty('bloomVersion')
  expect(store.stats).not.toHaveProperty('idGenerator')
})

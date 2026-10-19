import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useClock } from '../lib/use-clock'
import { ApiError, createApi, validateConnection } from '../lib/api'
import { demoLinks, demoStats } from '../lib/demo'
import { backendDate, linkStatus, number, shortUrl } from '../lib/format'
import type { Connection, CreateLinkInput, LinkRecord, MaintenanceAction, MaintenanceResult, RuntimeStats } from '../types'

const STORAGE_KEY = 'shortlink:connection'

function readStoredConnection(): { mode: 'demo' | 'live'; connection: Connection } | null {
  if (typeof localStorage === 'undefined') return null
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    if (parsed.mode === 'live' || parsed.mode === 'demo') {
      return { mode: parsed.mode, connection: parsed.connection }
    }
  } catch {
    // 本地存储损坏或内容非法，忽略并回退到默认状态。
  }
  return null
}

function writeStoredConnection(mode: 'demo' | 'live', connection: Connection) {
  if (typeof localStorage === 'undefined') return
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ mode, connection }))
  } catch {
    // 隐私模式或存储配额已满，不阻断业务。
  }
}

function clearStoredConnection() {
  if (typeof localStorage === 'undefined') return
  try { localStorage.removeItem(STORAGE_KEY) } catch { /* ignore */ }
}

export const useWorkspaceStore = defineStore('workspace', () => {
  const stored = readStoredConnection()
  const mode = ref<'demo' | 'live'>(stored?.mode ?? 'demo')
  const connection = ref<Connection>(stored?.connection ?? { baseUrl: '', gid: 'default', apiToken: '', adminToken: '' })
  const links = ref<LinkRecord[]>(demoLinks())
  const stats = ref<RuntimeStats | null>(null)
  const loading = ref(false)
  const creating = ref(false)
  const loadingOperations = ref(false)
  const loadError = ref('')
  const operationsError = ref('')
  const busyCodes = ref<string[]>([])
  const toasts = ref<{ id: number; message: string; tone: 'success' | 'error' | 'info' }[]>([])
  const lastLoaded = ref<number | null>(null)
  let revision = 0
  let toastId = 0
  const now = useClock()
  const isDemo = computed(() => mode.value === 'demo')
  const hasAdmin = computed(() => isDemo.value || Boolean(connection.value.adminToken))
  const loadedSummary = computed(() => ({
    total: links.value.length,
    active: links.value.filter(link => linkStatus(link, now.value.getTime()) === 'active').length,
    clicks: links.value.reduce((sum, link) => sum + link.clickNum, 0),
    expiring: links.value.filter(link => linkStatus(link, now.value.getTime()) === 'active' && link.validType === 1).length,
  }))

  function notify(message: string, tone: 'success' | 'error' | 'info' = 'success') {
    const id = ++toastId
    toasts.value.push({ id, message, tone })
    setTimeout(() => { toasts.value = toasts.value.filter(item => item.id !== id) }, 4500)
  }

  function errorText(error: unknown): string { return error instanceof Error ? error.message : '操作未完成，请稍后重试。' }

  async function connect(input: Connection) {
    const validated = validateConnection(input)
    const current = ++revision
    let records: LinkRecord[]
    try { records = await createApi(validated).links() }
    finally { if (current === revision) loading.value = false }
    if (current !== revision) throw new ApiError('连接过程已取消，请重新选择工作空间。')
    connection.value = validated
    mode.value = 'live'
    writeStoredConnection('live', validated)
    links.value = records
    stats.value = null
    loadingOperations.value = false
    loadError.value = ''
    operationsError.value = ''
    lastLoaded.value = Date.now()
    notify('工作空间已连接')
  }

  function useDemo() {
    revision++
    mode.value = 'demo'
    connection.value = { baseUrl: '', gid: 'default', apiToken: '', adminToken: '' }
    clearStoredConnection()
    links.value = demoLinks()
    stats.value = null
    loading.value = false
    loadingOperations.value = false
    loadError.value = ''
    operationsError.value = ''
    notify('已切换到演示空间，操作只影响本页面', 'info')
  }

  async function refreshLinks() {
    if (isDemo.value) { lastLoaded.value = Date.now(); return }
    const current = revision
    loading.value = true
    try {
      const records = await createApi(connection.value).links()
      if (current !== revision) return
      links.value = records
      lastLoaded.value = Date.now()
      loadError.value = ''
    } catch (error) {
      if (current === revision) loadError.value = errorText(error)
      throw error
    } finally { if (current === revision) loading.value = false }
  }

  async function createLink(input: CreateLinkInput): Promise<LinkRecord> {
    const current = revision
    creating.value = true
    try {
      let url: string
      if (isDemo.value) {
        const existing = !input.forceNew && links.value.find(link => link.originUrl === input.originUrl)
        if (existing) { notify('已复用现有短链接'); return existing }
        const alphabet = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ'
        let code: string
        do { code = Array.from(crypto.getRandomValues(new Uint8Array(6)), n => alphabet[n % 62]).join('') }
        while (links.value.some(link => link.shortUri === code))
        url = `https://fold.example/${code}`
      } else { url = await createApi(connection.value).create(input) }
      if (current !== revision) throw new ApiError('工作空间已变更，请在原空间确认创建结果。')
      const parsed = new URL(url)
      const code = parsed.pathname.split('/').filter(Boolean).pop() || ''
      const existing = links.value.find(link => link.shortUri === code)
      if (existing) { notify('短链接已就绪'); return existing }
      const link: LinkRecord = {
        id: `new-${code}`, shortUri: code, domain: parsed.origin, originUrl: input.originUrl,
        gid: input.gid, enableStatus: 0, validType: input.validType, validEnd: input.validEnd,
        clickNum: 0, describe: input.describe, createTime: backendDate(0),
      }
      links.value = [link, ...links.value]
      if (!isDemo.value) {
        // 单库列表立即可读；刷新失败不能把已创建成功误报为失败。
        try { await refreshLinks() }
        catch { notify('短链接已创建，列表暂时未能刷新，可稍后手动刷新。', 'info') }
        if (current !== revision) throw new ApiError('工作空间已变更，请在原空间确认创建结果。')
      }
      notify('短链接已生成，可以复制分享')
      return links.value.find(item => item.shortUri === code) || link
    } finally { creating.value = false }
  }

  async function toggleLink(link: LinkRecord) {
    const status = linkStatus(link)
    if (status !== 'active' && status !== 'disabled') throw new ApiError('此链接当前不能切换启用状态。')
    const current = revision
    const enabled = status === 'disabled'
    busyCodes.value.push(link.shortUri)
    try {
      if (!isDemo.value) await createApi(connection.value).toggle(link.shortUri, enabled)
      if (current !== revision) return
      const record = links.value.find(item => item.shortUri === link.shortUri)
      if (record) record.enableStatus = enabled ? 0 : 1
      notify(enabled ? '链接已恢复' : '链接已停用')
    } finally { busyCodes.value = busyCodes.value.filter(code => code !== link.shortUri) }
  }

  async function refreshOperations() {
    const current = revision
    loadingOperations.value = true
    try {
      if (isDemo.value) {
        stats.value ??= demoStats()
      } else {
        const api = createApi(connection.value)
        const runtime = await api.stats()
        if (current !== revision) return
        stats.value = runtime
      }
      operationsError.value = ''
    } catch (error) {
      if (current === revision) operationsError.value = errorText(error)
      throw error
    } finally { if (current === revision) loadingOperations.value = false }
  }

  async function maintain(action: MaintenanceAction) {
    if (isDemo.value) {
      stats.value ??= demoStats()
      if (action === 'cache') { stats.value.localCache.size = 0; stats.value.localCache.hitRate = '0.0000' }
      if (action === 'expired') links.value = links.value.filter(link => linkStatus(link) !== 'expired')
      notify({ cache: '本机缓存已清理', expired: '过期链接清理已完成' }[action])
      return
    }
    const result = await createApi(connection.value).maintenance(action)
    await refreshOperations()
    const summary = maintenanceSummary(action, result)
    notify(summary.message, summary.tone)
  }

  // 后端会区分“跑完 / 时间预算用尽 / 没抢到锁 / 被中断”，必须如实转述：
  // 否则 cleanedRows=0 会被读成“没有过期数据”，而实际可能是什么都没做。
  function maintenanceSummary(action: MaintenanceAction, result: MaintenanceResult):
      { message: string; tone: 'success' | 'info' } {
    if (action === 'cache') return { message: '本机缓存已清理', tone: 'success' }
    const rows = number(result.cleanedRows ?? 0)
    switch (result.status) {
      case 'truncated':
        return { message: `本次已清理 ${rows} 条，时间预算用尽仍有剩余，请稍后再次执行`, tone: 'info' }
      case 'skipped_lock_held':
        return { message: '定时任务或其他实例正在清理，本次未执行，请稍后重试', tone: 'info' }
      case 'interrupted':
        return { message: `清理被中断，已处理 ${rows} 条，请稍后重试`, tone: 'info' }
      default:
        return { message: `过期链接清理完成，共 ${rows} 条`, tone: 'success' }
    }
  }

  async function reloadRisk(rules: { blockedDomains?: string[]; allowedDomains?: string[] }) {
    if (!isDemo.value) await createApi(connection.value).risk(rules)
    notify('风控名单已应用')
  }

  return { mode, connection, links, stats, loading, creating, loadingOperations,
    loadError, operationsError, busyCodes, toasts, lastLoaded, isDemo, hasAdmin, loadedSummary,
    notify, errorText, connect, useDemo, refreshLinks, createLink, toggleLink, refreshOperations,
    maintain, reloadRisk, shortUrl }
})

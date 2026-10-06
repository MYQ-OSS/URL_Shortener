<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useMediaQuery, onKeyStroke } from '@vueuse/core'
import { Plus, Search, RefreshCw, ChevronDown, AlertCircle, Pause } from '@lucide/vue'
import { useWorkspaceStore } from '../stores/workspace'
import { linkStatus, number } from '../lib/format'
import type { LinkRecord } from '../types'
import CreateLinkForm from '../components/CreateLinkForm.vue'
import LinkTable from '../components/LinkTable.vue'
import LinkDetail from '../components/LinkDetail.vue'
import AppModal from '../components/AppModal.vue'

const workspace = useWorkspaceStore()
const search = ref('')
const filter = ref('all')
const sort = ref('newest')
const wide = useMediaQuery('(min-width: 1280px)')
const createModal = ref(false)
const detail = ref<LinkRecord | null>(null)
const pauseLink = ref<LinkRecord | null>(null)
const pausing = ref(false)
const form = ref<InstanceType<typeof CreateLinkForm>>()
const searchInput = ref<HTMLInputElement>()
const rows = computed(() => {
  const query = search.value.trim().toLowerCase()
  const values = workspace.links.filter(link => (filter.value === 'all' || linkStatus(link) === filter.value)
    && (!query || [link.describe, link.shortUri, link.originUrl].some(value => value?.toLowerCase().includes(query))))
  return sort.value === 'clicks' ? [...values].sort((a, b) => b.clickNum - a.clickNum) : values
})
const metrics = computed(() => [
  { label: '已加载链接', value: workspace.loadedSummary.total },
  { label: '正常启用', value: workspace.loadedSummary.active },
  { label: '访问合计', value: workspace.loadedSummary.clicks },
  { label: '有期限的链接', value: workspace.loadedSummary.expiring },
])

function create() { if (wide.value) form.value?.focus(); else createModal.value = true }
async function refresh() {
  try { await workspace.refreshLinks(); workspace.notify('链接列表已更新') }
  catch (error) { workspace.notify(workspace.errorText(error), 'error') }
}
async function toggle(link: LinkRecord) {
  detail.value = null
  if (linkStatus(link) === 'active') { pauseLink.value = link; return }
  try { await workspace.toggleLink(link) } catch (error) { workspace.notify(workspace.errorText(error), 'error') }
}
async function confirmPause() {
  if (!pauseLink.value) return
  pausing.value = true
  try { await workspace.toggleLink(pauseLink.value); pauseLink.value = null }
  catch (error) { workspace.notify(workspace.errorText(error), 'error') }
  finally { pausing.value = false }
}
onMounted(() => { if (!workspace.isDemo) workspace.refreshLinks().catch(() => {}) })
onKeyStroke('/', event => {
  if (!['INPUT', 'TEXTAREA'].includes((event.target as HTMLElement)?.tagName)) { event.preventDefault(); searchInput.value?.focus() }
})
</script>

<template>
  <div class="page links-page">
    <header class="page-heading"><div><h1>我的链接</h1><p>创建和管理当前工作空间的短链接。</p></div>
      <button class="button button-accent" @click="create"><Plus :size="17" />新建短链</button>
    </header>
    <section class="summary-strip" aria-label="当前列表概览">
      <div v-for="metric in metrics" :key="metric.label" class="summary-item"><span>{{ metric.label }}</span><strong>{{ number(metric.value) }}</strong></div>
    </section>
    <div class="desk-grid">
      <section class="collection-panel" aria-labelledby="collection-heading">
        <div class="collection-header"><h2 id="collection-heading">链接列表</h2>
          <button class="icon-button refresh-button" :disabled="workspace.loading" aria-label="刷新链接列表" title="刷新列表" @click="refresh"><RefreshCw :size="17" :class="{ spin: workspace.loading }" /></button>
        </div>
        <div class="collection-toolbar">
          <div class="search-field"><Search :size="17" /><input ref="searchInput" v-model="search" aria-label="搜索链接" placeholder="搜索名称、短码或网址" /><kbd>/</kbd></div>
          <label class="sort-select"><span class="sr-only">排序方式</span><select v-model="sort"><option value="newest">最近创建</option><option value="clicks">访问最多</option></select><ChevronDown :size="14" /></label>
        </div>
        <div class="filter-row" aria-label="按链接状态筛选"><div class="filter-tabs">
          <button v-for="item in [{ value: 'all', label: '全部链接' }, { value: 'active', label: '正常' }, { value: 'disabled', label: '已停用' }, { value: 'expired', label: '已过期' }]" :key="item.value"
            :class="{ selected: filter === item.value }" :aria-pressed="filter === item.value" @click="filter = item.value">{{ item.label }}</button>
        </div><span class="filter-result">{{ rows.length }} 条</span></div>
        <div v-if="workspace.loadError" class="inline-alert"><AlertCircle :size="17" /><span>{{ workspace.loadError }}</span><button @click="refresh">重试</button></div>
        <LinkTable :rows="rows" :loading="workspace.loading" :filtered="Boolean(search) || filter !== 'all'" @open="detail = $event" @toggle="toggle" @create="create" />
        <div class="collection-footer"><span>{{ workspace.isDemo ? '示例数据' : '仅统计当前已加载的链接，最多 100 条' }}</span><span>{{ rows.length }} 条 · {{ workspace.connection.gid }}</span></div>
      </section>
      <aside v-if="wide" class="compose-aside">
        <section class="create-panel"><div class="compose-heading"><h2>创建短链接</h2></div><div class="compose-body"><CreateLinkForm ref="form" /></div></section>
      </aside>
    </div>
    <AppModal :open="createModal" title="新建短链接" @close="createModal = false"><CreateLinkForm v-if="createModal" /></AppModal>
    <LinkDetail :link="detail" @close="detail = null" @toggle="toggle" />
    <AppModal :open="Boolean(pauseLink)" title="停用这条链接？" @close="!pausing && (pauseLink = null)"><div class="confirmation-body"><span class="confirmation-icon"><Pause :size="23" /></span><p><strong>{{ pauseLink?.describe || pauseLink?.shortUri }}</strong> 将停止跳转。你可以随时在列表中恢复它。</p><div class="confirmation-actions"><button class="button button-outline" :disabled="pausing" @click="pauseLink = null">暂不操作</button><button class="button button-accent" :disabled="pausing" @click="confirmPause">{{ pausing ? '正在停用' : '确认停用' }}</button></div></div></AppModal>
  </div>
</template>

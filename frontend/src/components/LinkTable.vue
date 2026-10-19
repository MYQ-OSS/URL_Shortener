<script setup lang="ts">
import { ref } from 'vue'
import { useClipboard } from '@vueuse/core'
import { useClock } from '../lib/use-clock'
import { Copy, Check, Pause, Play, Link2, Plus, LoaderCircle } from '@lucide/vue'
import { dateLabel, hostname, linkStatus, number, shortUrl, statusNames } from '../lib/format'
import { useWorkspaceStore } from '../stores/workspace'
import type { LinkRecord } from '../types'

defineProps<{ rows: LinkRecord[]; loading: boolean; filtered: boolean }>()
const emit = defineEmits<{ open: [link: LinkRecord]; toggle: [link: LinkRecord]; create: [] }>()
const workspace = useWorkspaceStore()
const now = useClock()
const copiedCode = ref('')
const { copy } = useClipboard({ legacy: true })
async function copyLink(link: LinkRecord) {
  try {
    await copy(shortUrl(link)); copiedCode.value = link.shortUri
    workspace.notify('短链接已复制')
    setTimeout(() => { if (copiedCode.value === link.shortUri) copiedCode.value = '' }, 2000)
  } catch { workspace.notify('复制失败，可以在详情里手动选择链接', 'error') }
}
const status = (link: LinkRecord) => linkStatus(link, now.value.getTime())
</script>

<template>
  <div class="link-table-wrap" :aria-busy="loading">
    <div v-if="loading" class="table-progress"></div>
    <table v-if="rows.length" class="link-table">
      <thead><tr><th scope="col">链接</th><th scope="col" class="numeric">访问</th><th scope="col">状态</th><th scope="col" class="date-col">创建日期</th><th scope="col" class="actions-col"><span class="sr-only">操作</span></th></tr></thead>
      <tbody>
        <tr v-for="link in rows" :key="link.shortUri" :class="{ 'row-muted': ['disabled', 'expired', 'blocked'].includes(status(link)) }">
          <td class="link-cell"><div class="link-cell-inner">
            <span class="link-monogram"><Link2 :size="17" :stroke-width="1.6" /></span>
            <div class="link-information"><button class="link-title" @click="emit('open', link)">{{ link.describe || hostname(link.originUrl) }}</button>
              <div class="link-addresses"><a class="short-code" :href="shortUrl(link)" target="_blank" rel="noopener noreferrer" :aria-label="`打开短链接 ${shortUrl(link)}`" @click.stop>{{ link.domain.replace(/^https?:\/\//, '') }}/<strong>{{ link.shortUri }}</strong></a><span class="origin-host">{{ hostname(link.originUrl) }}</span></div>
            </div>
          </div></td>
          <td class="numeric visit-cell"><span class="mobile-label">访问</span>{{ number(link.clickNum) }}</td>
          <td class="status-cell"><span class="status-badge" :class="`status-${status(link)}`"><span></span>{{ statusNames[status(link)] }}</span></td>
          <td class="date-col">{{ dateLabel(link.createTime) }}</td>
          <td class="actions-cell"><div class="row-actions">
            <button class="icon-button" :aria-label="`复制 ${link.describe || link.shortUri} 的短链接`" title="复制短链接" @click="copyLink(link)"><Check v-if="copiedCode === link.shortUri" :size="17" /><Copy v-else :size="17" /></button>
            <button class="icon-button" :disabled="!['active', 'disabled'].includes(status(link)) || workspace.busyCodes.includes(link.shortUri)"
              :aria-label="`${status(link) === 'disabled' ? '恢复' : '停用'} ${link.describe || link.shortUri}`"
              :title="status(link) === 'disabled' ? '恢复链接' : status(link) === 'active' ? '停用链接' : '当前状态不可切换'" @click="emit('toggle', link)">
              <LoaderCircle v-if="workspace.busyCodes.includes(link.shortUri)" :size="17" class="spin" /><Play v-else-if="status(link) === 'disabled'" :size="17" /><Pause v-else :size="17" />
            </button>
          </div></td>
        </tr>
      </tbody>
    </table>
    <div v-else-if="loading" class="table-skeleton" aria-label="正在载入链接"><div v-for="index in 5" :key="index"></div></div>
    <div v-else class="empty-state"><span class="empty-symbol"><Link2 :size="29" :stroke-width="1.4" /></span>
      <h3>{{ filtered ? '没有找到匹配的链接' : '从第一条链接开始' }}</h3>
      <p>{{ filtered ? '试试其他关键词，或切换状态筛选。' : '粘贴一个长网址，给它一个更容易分享的入口。' }}</p>
      <button v-if="!filtered" class="button button-dark" @click="emit('create')"><Plus :size="16" />新建短链</button>
    </div>
  </div>
</template>

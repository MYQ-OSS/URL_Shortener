<script setup lang="ts">
import { ref, watch } from 'vue'
import { useClipboard } from '@vueuse/core'
import { useClock } from '../lib/use-clock'
import QRCode from 'qrcode'
import { Copy, Check, QrCode, Download, ArrowUpRight, Pause, Play } from '@lucide/vue'
import AppModal from './AppModal.vue'
import { useWorkspaceStore } from '../stores/workspace'
import { dateLabel, hostname, linkStatus, number, safeHttpUrl, shortUrl, statusNames } from '../lib/format'
import type { LinkRecord } from '../types'

const props = defineProps<{ link: LinkRecord | null }>()
const emit = defineEmits<{ close: []; toggle: [link: LinkRecord] }>()
const workspace = useWorkspaceStore()
const { copy, copied } = useClipboard({ legacy: true })
const qr = ref('')
const qrBusy = ref(false)
const now = useClock()
watch(() => props.link?.shortUri, () => { qr.value = '' })
async function copyLink() {
  if (!props.link) return
  try { await copy(shortUrl(props.link)); workspace.notify('短链接已复制') }
  catch { workspace.notify('复制失败，请手动选择链接', 'error') }
}
async function showQr() {
  if (!props.link) return
  qrBusy.value = true
  try { qr.value = await QRCode.toDataURL(shortUrl(props.link), { width: 240, margin: 2, color: { dark: '#202827', light: '#fffdf7' } }) }
  catch { workspace.notify('二维码生成失败，请重试', 'error') }
  finally { qrBusy.value = false }
}
</script>

<template>
  <AppModal :open="Boolean(link)" title="链接详情" @close="emit('close')">
    <template v-if="link">
      <div class="detail-heading"><h3>{{ link.describe || hostname(link.originUrl) }}</h3>
        <span class="status-badge" :class="`status-${linkStatus(link, now.getTime())}`"><span></span>{{ statusNames[linkStatus(link, now.getTime())] }}</span></div>
      <div class="detail-short-url"><code>{{ shortUrl(link) }}</code><button class="icon-button" aria-label="复制短链接" @click="copyLink"><Check v-if="copied" :size="18" /><Copy v-else :size="18" /></button></div>
      <dl class="detail-facts">
        <div class="fact-full"><dt>目标网址</dt><dd><a v-if="safeHttpUrl(link.originUrl)" :href="safeHttpUrl(link.originUrl)!" target="_blank" rel="noopener noreferrer">{{ link.originUrl }} <ArrowUpRight :size="13" /></a><span v-else>{{ link.originUrl }}</span></dd></div>
        <div><dt>累计访问</dt><dd>{{ number(link.clickNum) }}</dd></div>
        <div><dt>工作空间</dt><dd class="mono">{{ link.gid }}</dd></div>
        <div><dt>创建时间</dt><dd>{{ dateLabel(link.createTime, true) }}</dd></div>
        <div><dt>有效期</dt><dd>{{ link.validType === 0 ? '永久有效' : dateLabel(link.validEnd, true) }}</dd></div>
      </dl>
      <div class="detail-actions">
        <button class="button button-outline" :disabled="qrBusy" @click="showQr"><QrCode :size="17" />{{ qrBusy ? '正在生成' : '分享二维码' }}</button>
        <button v-if="['active', 'disabled'].includes(linkStatus(link))" class="button button-outline" @click="emit('toggle', link)">
          <component :is="linkStatus(link) === 'disabled' ? Play : Pause" :size="17" />{{ linkStatus(link) === 'disabled' ? '恢复链接' : '停用链接' }}
        </button>
      </div>
      <div v-if="qr" class="qr-share"><img :src="qr" :alt="`短链接 ${link.shortUri} 的二维码`" width="180" height="180" /><div><strong>分享二维码</strong><p>扫码访问短链接。</p><a :href="qr" :download="`fold-${link.shortUri}.png`" class="button button-outline"><Download :size="15" />下载 PNG</a></div></div>
      <p v-if="workspace.isDemo" class="detail-note">此为示例短链接，不提供真实跳转服务。</p>
    </template>
  </AppModal>
</template>

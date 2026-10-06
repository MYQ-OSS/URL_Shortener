<script setup lang="ts">
import { reactive, ref, useId } from 'vue'
import { useClipboard } from '@vueuse/core'
import { ArrowUpRight, Copy, Check, LoaderCircle, Link2, ChevronDown } from '@lucide/vue'
import { useWorkspaceStore } from '../stores/workspace'
import { backendTime, localDateInput, safeHttpUrl, shortUrl } from '../lib/format'
import type { LinkRecord } from '../types'

const emit = defineEmits<{ created: [link: LinkRecord] }>()
const workspace = useWorkspaceStore()
const form = reactive({ originUrl: '', describe: '', forceNew: false, expiring: false, validEnd: '' })
const error = ref('')
const created = ref<LinkRecord | null>(null)
const originInput = ref<HTMLTextAreaElement>()
const fieldId = useId()
const { copy, copied } = useClipboard({ legacy: true })
defineExpose({ focus: () => originInput.value?.focus() })

async function submit() {
  error.value = ''
  const url = form.originUrl.trim()
  if (!safeHttpUrl(url)) { error.value = '请输入完整的 http:// 或 https:// 网址。'; originInput.value?.focus(); return }
  const end = form.expiring ? `${form.validEnd}${form.validEnd.length === 16 ? ':00' : ''}` : null
  if (form.expiring && (!end || !(backendTime(end) > Date.now() + 1000))) {
    error.value = '请设置晚于当前时间的截止日期（北京时间）。'; return
  }
  try {
    const link = await workspace.createLink({ originUrl: url, describe: form.describe.trim() || null,
      gid: workspace.connection.gid, forceNew: form.forceNew, validType: form.expiring ? 1 : 0, validEnd: end })
    created.value = link
    form.originUrl = ''; form.describe = ''; form.forceNew = false; form.expiring = false; form.validEnd = ''
    emit('created', link)
  } catch (reason) { error.value = workspace.errorText(reason) }
}

async function copyResult() {
  if (!created.value) return
  try { await copy(shortUrl(created.value)); workspace.notify('短链接已复制') }
  catch { workspace.notify('复制失败，请手动选择链接复制', 'error') }
}
</script>

<template>
  <form class="create-form" @submit.prevent="submit">
    <label class="field-label" :for="`${fieldId}-origin`">目标网址 <span>必填</span></label>
    <div class="url-input-wrap"><Link2 :size="17" /><textarea :id="`${fieldId}-origin`" ref="originInput" v-model="form.originUrl"
      aria-label="目标网址" :aria-invalid="Boolean(error)" maxlength="2048" rows="3" required
      placeholder="https://example.com/一个很长的网址" :disabled="workspace.creating"></textarea></div>
    <label class="field-label" :for="`${fieldId}-description`">备注名称 <span>方便下次找到</span></label>
    <input :id="`${fieldId}-description`" v-model="form.describe" maxlength="128" placeholder="例如：十月活动报名" :disabled="workspace.creating" />
    <details class="create-options">
      <summary>更多设置 <ChevronDown :size="15" /></summary>
      <label class="checkbox-row"><input v-model="form.forceNew" type="checkbox" /><span>生成独立短码<small>同一网址也生成新入口，适合区分渠道。</small></span></label>
      <label class="checkbox-row"><input v-model="form.expiring" type="checkbox" /><span>设置有效期<small>到期后，短链接将停止跳转。</small></span></label>
      <div v-if="form.expiring" class="date-field"><label :for="`${fieldId}-end`">截止时间 · 北京时间</label>
        <input :id="`${fieldId}-end`" v-model="form.validEnd" type="datetime-local" :min="localDateInput()" required /></div>
    </details>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <button class="button button-dark button-full create-submit" :disabled="workspace.creating">
      <LoaderCircle v-if="workspace.creating" :size="17" class="spin" /><span>{{ workspace.creating ? '正在生成' : '生成短链接' }}</span><ArrowUpRight v-if="!workspace.creating" :size="18" />
    </button>
    <p class="form-footnote">默认永久有效 · 同一网址自动复用短码</p>
    <div v-if="created" class="created-result">
      <div><strong>链接已就绪</strong></div>
      <div class="created-url"><code>{{ shortUrl(created) }}</code><button type="button" class="icon-button" aria-label="复制刚创建的短链接" @click="copyResult"><Check v-if="copied" :size="18" /><Copy v-else :size="18" /></button></div>
    </div>
  </form>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RefreshCw, ShieldCheck, Layers, Trash2, ArrowRight, ChevronDown, AlertCircle, LoaderCircle, LockKeyhole } from '@lucide/vue'
import { useWorkspaceStore } from '../stores/workspace'
import { number } from '../lib/format'
import type { MaintenanceAction } from '../types'
import AppModal from '../components/AppModal.vue'

const workspace = useWorkspaceStore()
const actionBusy = ref(false)
const confirmation = ref<{ title: string; message: string; run: () => Promise<void> } | null>(null)
const risk = reactive({ changeBlocked: false, changeAllowed: false, blocked: '', allowed: '' })
const riskError = ref('')
const hitRate = computed(() => Math.max(0, Math.min(100, Number(workspace.stats?.localCache.hitRate || 0) * 100)))
const lastFlush = computed(() => workspace.stats?.stats.lastFlushAt ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).format(workspace.stats.stats.lastFlushAt) : '尚无成功落库')
const maintenance = [
  { action: 'cache' as const, icon: Layers, title: '清理本机缓存', description: '清空当前服务实例的 L1 缓存。' },
  { action: 'expired' as const, icon: Trash2, title: '清理过期链接', description: '逻辑删除已过期入口，并清理缓存。' },
]
async function refresh() {
  try { await workspace.refreshOperations() }
  catch (error) { workspace.notify(workspace.errorText(error), 'error') }
}
function confirmMaintenance(action: MaintenanceAction) {
  const labels = { cache: '清理本机缓存', expired: '清理过期链接' }
  const descriptions = {
    cache: '当前服务实例的本地缓存会被清空，后续请求将逐步预热。',
    expired: '已过期的短链会被逻辑删除，这些记录将不再出现在链接集合中。单次执行有时间预算，没清完或被定时任务占用时会提示重试。',
  }
  confirmation.value = { title: `${labels[action]}？`, message: descriptions[action], run: () => workspace.maintain(action) }
}
async function confirm() {
  if (!confirmation.value) return
  actionBusy.value = true
  try { await confirmation.value.run(); confirmation.value = null }
  catch (error) { workspace.notify(workspace.errorText(error), 'error') }
  finally { actionBusy.value = false }
}
function applyRisk() {
  riskError.value = ''
  if (!risk.changeBlocked && !risk.changeAllowed) { riskError.value = '先选择需要更新的名单。'; return }
  const domains = (value: string) => [...new Set(value.split(/[\n,，]/).map(item => item.trim().toLowerCase()).filter(Boolean))]
  const blocked = domains(risk.blocked), allowed = domains(risk.allowed)
  if ([...(risk.changeBlocked ? blocked : []), ...(risk.changeAllowed ? allowed : [])].some(value => !/^[a-z0-9.-]+$/.test(value))) {
    riskError.value = '请填写域名，不包含协议、路径或端口，例如 evil.example。'; return
  }
  const rules = { ...(risk.changeBlocked ? { blockedDomains: blocked } : {}), ...(risk.changeAllowed ? { allowedDomains: allowed } : {}) }
  confirmation.value = { title: '应用新的风控名单？',
    message: `只更新勾选的名单。${risk.changeAllowed && !allowed.length ? '白名单为空时，会允许所有未被黑名单拦截的域名。' : '规则对后续创建请求生效，服务重启后恢复配置文件中的规则。'}`,
    run: () => workspace.reloadRisk(rules) }
}
onMounted(() => { if (workspace.hasAdmin) workspace.refreshOperations().catch(() => {}) })
</script>

<template>
  <div class="page operations-page">
    <header class="page-heading">
      <div><h1>运行观察</h1><p>缓存命中与访问趋势统计。</p></div>
      <button class="button button-outline" :disabled="!workspace.hasAdmin || workspace.loadingOperations" @click="refresh">
        <RefreshCw :size="16" :class="{ spin: workspace.loadingOperations }" />刷新状态
      </button>
    </header>
    <div v-if="!workspace.hasAdmin" class="admin-locked">
      <LockKeyhole :size="28" :stroke-width="1.5" />
      <h2>需要管理员凭据</h2>
      <p>添加管理员 Token 后，可查看运行状态和执行维护操作。</p>
      <RouterLink to="/settings" class="button button-dark">前往连接设置<ArrowRight :size="16" /></RouterLink>
    </div>
    <template v-else>
      <div v-if="workspace.operationsError" class="inline-alert operations-alert">
        <AlertCircle :size="18" /><span>{{ workspace.operationsError }}</span><button @click="refresh">重试</button>
      </div>
      <div v-if="!workspace.stats" class="operations-skeleton" aria-label="正在读取运行状态"><div></div><div></div></div>
      <template v-else>
        <section class="operations-summary" aria-label="运行概览">
          <div><span>待投递事件</span><strong>{{ number(workspace.stats.stats.pending) }}<small>条</small></strong></div>
          <div :class="{ 'danger-reading': workspace.stats.stats.dropped > 0 }">
            <span>丢弃事件</span><strong>{{ number(workspace.stats.stats.dropped) }}<small>条</small></strong>
          </div>
          <div><span>投递失败次数</span><strong>{{ number(workspace.stats.stats.publishFailures) }}<small>次</small></strong></div>
        </section>
        <div class="observatory-grid">
          <section class="cache-instrument">
            <h2>本地缓存</h2>
            <div class="cache-reading"><strong>{{ hitRate.toFixed(1) }}<small>%</small></strong><span>命中率</span></div>
            <dl class="cache-instrument-footer">
              <div><dt>命中</dt><dd>{{ number(workspace.stats.localCache.hitCount) }}</dd></div>
              <div><dt>未命中</dt><dd>{{ number(workspace.stats.localCache.missCount) }}</dd></div>
            </dl>
          </section>
          <section class="runtime-ledger">
            <h2>服务指标</h2>
            <dl class="runtime-facts">
              <div><dt>本地缓存条目</dt><dd>{{ number(workspace.stats.localCache.size) }}<small>条</small></dd></div>
              <div><dt>累计淘汰次数</dt><dd>{{ number(workspace.stats.localCache.evictionCount) }}</dd></div>
              <div><dt>统计落库行数</dt><dd>{{ number(workspace.stats.stats.flushedRows) }}</dd></div>
              <div><dt>最近成功落库</dt><dd>{{ lastFlush }}</dd></div>
            </dl>
          </section>
        </div>
        <p class="field-help">访问趋势统计使用有界内存队列（{{ number(workspace.stats.stats.bufferCapacity) }} 条）。队列满或进程崩溃可能丢失事件，不用于计费或审计。</p>
        <section class="maintenance-panel">
          <header><h2>维护操作</h2><p>执行前会提示影响范围。</p></header>
          <div class="maintenance-list">
            <button v-for="item in maintenance" :key="item.action" :title="item.description" @click="confirmMaintenance(item.action)">
              <component :is="item.icon" :size="17" :stroke-width="1.7" /><span>{{ item.title }}</span>
            </button>
          </div>
        </section>
        <details class="risk-panel">
          <summary><ShieldCheck :size="18" /><span>风控名单</span><small>更新后续创建请求的域名规则</small><ChevronDown :size="16" /></summary>
          <form @submit.prevent="applyRisk">
            <p class="field-help">只更新明确勾选的名单，未勾选的保持原规则。后端未提供当前名单查询。</p>
            <div class="risk-fields">
              <div><label class="checkbox-row"><input v-model="risk.changeBlocked" type="checkbox" /><span>更新域名黑名单</span></label><textarea v-model="risk.blocked" :disabled="!risk.changeBlocked" aria-label="新的域名黑名单" rows="3" placeholder="evil.example&#10;phishing.test"></textarea></div>
              <div><label class="checkbox-row"><input v-model="risk.changeAllowed" type="checkbox" /><span>更新域名白名单</span></label><textarea v-model="risk.allowed" :disabled="!risk.changeAllowed" aria-label="新的域名白名单" rows="3" placeholder="每行一个域名，也可以用逗号分隔"></textarea></div>
            </div>
            <p v-if="riskError" class="form-error" role="alert">{{ riskError }}</p>
            <button class="button button-dark" :disabled="actionBusy">应用名单<ArrowRight :size="16" /></button>
          </form>
        </details>
      </template>
    </template>
    <AppModal :open="Boolean(confirmation)" :title="confirmation?.title || '确认操作'" @close="!actionBusy && (confirmation = null)">
      <div class="confirmation-body">
        <p>{{ confirmation?.message }}</p><p v-if="workspace.isDemo" class="field-help">演示模式下只更新页面示例。</p>
        <div class="confirmation-actions">
          <button class="button button-outline" :disabled="actionBusy" @click="confirmation = null">取消</button>
          <button class="button button-accent" :disabled="actionBusy" @click="confirm"><LoaderCircle v-if="actionBusy" :size="16" class="spin" />{{ actionBusy ? '正在执行' : '确认执行' }}</button>
        </div>
      </div>
    </AppModal>
  </div>
</template>

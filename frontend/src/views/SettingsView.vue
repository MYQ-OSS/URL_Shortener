<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowUpRight, Eye, EyeOff, LockKeyhole, Plug, Check, LoaderCircle, BookOpen } from '@lucide/vue'
import { useWorkspaceStore } from '../stores/workspace'
const workspace = useWorkspaceStore()
const router = useRouter()
const form = reactive({ ...workspace.connection })
const includeAdmin = ref(Boolean(form.adminToken))
const showApi = ref(false)
const showAdmin = ref(false)
const busy = ref(false)
const error = ref('')
const docsUrl = computed(() => `${form.baseUrl.trim().replace(/\/$/, '')}/swagger-ui/index.html`)
const browserOrigin = location.origin
const directConnection = computed(() => {
  try { return Boolean(form.baseUrl.trim()) && new URL(form.baseUrl.trim()).origin !== browserOrigin }
  catch { return false }
})
async function connect() {
  busy.value = true; error.value = ''
  try { await workspace.connect({ ...form, adminToken: includeAdmin.value ? form.adminToken : '' }); await router.push('/') }
  catch (reason) { error.value = workspace.errorText(reason) }
  finally { busy.value = false }
}
function demo() { workspace.useDemo(); router.push('/') }
</script>

<template>
  <div class="page settings-page">
    <header class="page-heading"><div><h1>连接设置</h1><p>填写服务地址与工作空间凭据。</p></div></header>
    <div class="settings-grid">
      <section class="settings-panel"><header class="panel-heading"><span class="panel-icon"><Plug :size="20" /></span><div><h2>服务连接</h2><p>保存前会验证 API Token 并载入链接列表。</p></div><span v-if="!workspace.isDemo" class="status-badge status-active"><span></span>已连接</span></header>
        <form class="connection-form" @submit.prevent="connect">
          <label class="field-label" for="service-url">服务地址 <span>本地开发推荐留空</span></label>
          <input id="service-url" v-model="form.baseUrl" placeholder="留空使用代理，或填写后端完整源地址" :disabled="busy" autocomplete="url" />
          <p class="field-help">前端 5173、后端 8080 的本地开发场景留空即可。填写地址会直接跨域访问后端。</p>
          <p v-if="directConnection" class="field-help">当前选择了跨域直连。后端需允许来源 {{ browserOrigin }}；本地默认已支持 localhost/127.0.0.1:5173，修改后需重启后端。</p>
          <label class="field-label" for="workspace-gid">工作空间 gid</label><input id="workspace-gid" v-model="form.gid" required maxlength="32" placeholder="default" :disabled="busy" />
          <label class="field-label" for="api-token">API Token</label><div class="secret-field"><input id="api-token" v-model="form.apiToken" :type="showApi ? 'text' : 'password'" required placeholder="粘贴绑定此 gid 的 Token" autocomplete="off" :disabled="busy" /><button type="button" class="icon-button" :aria-label="showApi ? '隐藏 API Token' : '显示 API Token'" @click="showApi = !showApi"><EyeOff v-if="showApi" :size="17" /><Eye v-else :size="17" /></button></div>
          <p class="field-help">Token 与 gid 必须对应，前端不能自行分配工作空间。</p>
          <div class="admin-credential"><label class="checkbox-row"><input v-model="includeAdmin" type="checkbox" /><span>同时连接运行观察<small>可选，需额外的管理员凭据。</small></span></label>
            <template v-if="includeAdmin"><label class="field-label" for="admin-token">管理员 Token</label><div class="secret-field"><input id="admin-token" v-model="form.adminToken" :type="showAdmin ? 'text' : 'password'" required autocomplete="off" placeholder="X-Admin-Token" :disabled="busy" /><button type="button" class="icon-button" :aria-label="showAdmin ? '隐藏管理员 Token' : '显示管理员 Token'" @click="showAdmin = !showAdmin"><EyeOff v-if="showAdmin" :size="17" /><Eye v-else :size="17" /></button></div></template>
          </div>
          <p v-if="error" class="form-error" role="alert">{{ error }}</p>
          <div class="connection-buttons"><button class="button button-dark" :disabled="busy"><LoaderCircle v-if="busy" :size="17" class="spin" /><Check v-else :size="17" />{{ busy ? '正在验证连接' : '连接并载入' }}</button><button type="button" class="button button-quiet" :disabled="busy" @click="demo">{{ workspace.isDemo ? '返回演示空间' : '断开连接' }}</button></div>
          <p class="credential-note"><LockKeyhole :size="14" />凭据保存在浏览器本地存储，刷新后保持登录；点击“断开连接”可清除。</p>
        </form>
      </section>
      <aside class="connection-guide"><div class="guide-top"><h2>连接说明</h2></div>
        <ol><li><span>01</span><div><strong>启动后端服务</strong><p>确认服务已配置 API 和管理员 Token。</p></div></li><li><span>02</span><div><strong>填写对应的 gid</strong><p>默认工作空间通常是 default。</p></div></li><li><span>03</span><div><strong>验证连接</strong><p>成功后，示例数据会替换为真实链接。</p></div></li></ol>
        <a :href="docsUrl" target="_blank" rel="noopener noreferrer" class="guide-document"><BookOpen :size="16" />查看后端接口文档<ArrowUpRight :size="16" /></a>
      </aside>
    </div>
  </div>
</template>

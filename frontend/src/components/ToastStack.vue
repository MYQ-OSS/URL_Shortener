<script setup lang="ts">
import { Check, Info, AlertCircle, X } from '@lucide/vue'
import { useWorkspaceStore } from '../stores/workspace'
const workspace = useWorkspaceStore()
</script>
<template>
  <div class="toast-stack" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="toast">
      <div v-for="toast in workspace.toasts" :key="toast.id" class="toast" :class="`toast-${toast.tone}`">
        <component :is="toast.tone === 'error' ? AlertCircle : toast.tone === 'info' ? Info : Check" :size="18" />
        <span>{{ toast.message }}</span>
        <button class="icon-button toast-close" aria-label="关闭提示"
          @click="workspace.toasts = workspace.toasts.filter(item => item.id !== toast.id)"><X :size="16" /></button>
      </div>
    </TransitionGroup>
  </div>
</template>

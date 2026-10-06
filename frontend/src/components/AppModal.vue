<script setup lang="ts">
import { nextTick, onMounted, onBeforeUnmount, ref, useId, watch } from 'vue'
import { X } from '@lucide/vue'

const props = defineProps<{ open: boolean; title: string; wide?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const element = ref<HTMLDialogElement>()
const titleId = useId()
async function sync() {
  await nextTick()
  if (!element.value) return
  if (props.open && !element.value.open) element.value.showModal()
  if (!props.open && element.value.open) element.value.close()
}
watch(() => props.open, sync)
onMounted(sync)
onBeforeUnmount(() => element.value?.close())
</script>

<template>
  <dialog ref="element" class="modal" :class="{ 'modal-wide': wide }" :aria-labelledby="titleId"
    @cancel.prevent="emit('close')" @click="event => { if (event.target === element) emit('close') }">
    <div class="modal-content">
      <header class="modal-header">
        <h2 :id="titleId">{{ title }}</h2>
        <button class="icon-button" aria-label="关闭对话框" @click="emit('close')"><X :size="20" /></button>
      </header>
      <slot />
    </div>
  </dialog>
</template>

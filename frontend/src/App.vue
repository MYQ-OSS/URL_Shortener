<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { Link2, Activity, SlidersHorizontal, Menu, X, ChevronRight, Plug } from '@lucide/vue'
import { useWorkspaceStore } from './stores/workspace'
import ToastStack from './components/ToastStack.vue'

const route = useRoute()
const workspace = useWorkspaceStore()
const sidebarOpen = ref(false)
const logoUrl = `${import.meta.env.BASE_URL}fold.svg`
const navigation = [
  { path: '/', label: '链接工作台', icon: Link2 },
  { path: '/operations', label: '运行观察', icon: Activity },
  { path: '/settings', label: '连接设置', icon: SlidersHorizontal },
]
</script>

<template>
  <a class="skip-link" href="#main-content">跳至主要内容</a>
  <div class="app-shell">
    <button v-if="sidebarOpen" class="sidebar-scrim" aria-label="关闭导航" @click="sidebarOpen = false"></button>
    <aside class="sidebar" :class="{ 'sidebar-open': sidebarOpen }">
      <div class="sidebar-brand-row">
        <RouterLink to="/" class="brand" aria-label="折页首页" @click="sidebarOpen = false">
          <img :src="logoUrl" alt="" width="28" height="28" />
          <span>折页</span>
        </RouterLink>
        <button class="icon-button mobile-only sidebar-close" aria-label="关闭导航" @click="sidebarOpen = false"><X :size="20" /></button>
      </div>
      <RouterLink to="/settings" class="workspace-picker" @click="sidebarOpen = false">
        <span><small>工作空间</small><strong>{{ workspace.isDemo ? '演示空间' : workspace.connection.gid }}</strong></span>
        <ChevronRight :size="15" />
      </RouterLink>
      <nav aria-label="主导航">
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path" class="nav-item"
          :class="{ active: route.path === item.path }" @click="sidebarOpen = false">
          <component :is="item.icon" :size="18" :stroke-width="1.7" /><span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-footer">短链接工作台 <span>v1.0</span></div>
    </aside>

    <div class="main-shell">
      <header class="topbar">
        <button class="icon-button mobile-only" aria-label="展开导航" @click="sidebarOpen = true"><Menu :size="21" /></button>
        <div class="breadcrumbs"><strong>{{ route.meta.title }}</strong></div>
        <div class="topbar-actions">
          <span class="connection-chip" :class="{ 'chip-live': !workspace.isDemo }"><span></span>{{ workspace.isDemo ? '演示模式' : '服务已连接' }}</span>
          <RouterLink to="/settings" class="topbar-link"><Plug :size="15" /><span>{{ workspace.isDemo ? '连接服务' : '连接设置' }}</span></RouterLink>
        </div>
      </header>
      <div v-if="workspace.isDemo" class="demo-strip">
        <span>演示模式，操作只影响当前页面的示例数据。</span>
        <RouterLink to="/settings">连接后端</RouterLink>
      </div>
      <main id="main-content" tabindex="-1"><RouterView /></main>
    </div>
    <ToastStack />
  </div>
</template>

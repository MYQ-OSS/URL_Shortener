import { createRouter, createWebHashHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', name: 'links', component: () => import('./views/LinksView.vue'), meta: { title: '链接工作台' } },
    { path: '/operations', name: 'operations', component: () => import('./views/OperationsView.vue'), meta: { title: '运行观察' } },
    { path: '/settings', name: 'settings', component: () => import('./views/SettingsView.vue'), meta: { title: '连接设置' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach((to) => { document.title = `${to.meta.title || '工作台'} · 折页 FOLD` })

import { createRouter, createWebHashHistory } from 'vue-router'
import { useSessionStore } from '@/stores/session.js'

// 一功能一条路由。带 requiresAuth 的进业务壳,登录页独立。
const routes = [
  { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layouts/AppShell.vue'),
    meta: { requiresAuth: true },
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '工作台', icon: 'HomeFilled' } },
      { path: 'single', name: 'single', component: () => import('@/views/SingleProduct.vue'), meta: { title: '单品上新', icon: 'Goods' } },
      { path: 'batch', name: 'batch', component: () => import('@/views/BatchListing.vue'), meta: { title: '批量上新', icon: 'Files' } },
      { path: 'studio', name: 'studio', component: () => import('@/views/ImageStudio.vue'), meta: { title: '生图工作室', icon: 'Picture' } },
      { path: 'import', name: 'import', component: () => import('@/views/ImportProduct.vue'), meta: { title: '导入建品', icon: 'Upload' } },
      { path: 'selection', name: 'selection', component: () => import('@/views/Selection.vue'), meta: { title: '选品', icon: 'Search' } },
      { path: 'stores', name: 'stores', component: () => import('@/views/Stores.vue'), meta: { title: '店铺管理', icon: 'Shop' } },
      { path: 'settings', name: 'settings', component: () => import('@/views/Settings.vue'), meta: { title: '设置', icon: 'Setting' } },
    ],
  },
]

const router = createRouter({
  history: createWebHashHistory(),
  routes,
})

// 守卫接缝:未登录访问业务页 → 跳登录。DEV_BYPASS 期 isLoggedIn 恒真。
router.beforeEach((to) => {
  const session = useSessionStore()
  if (to.meta.requiresAuth && !session.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (to.name === 'login' && session.isLoggedIn) {
    return { name: 'dashboard' }
  }
})

export default router

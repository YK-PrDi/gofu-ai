import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router.js'

// 一次性清理这四个"干活中间态"的残留键(08.03)。
// 它们经历过两次错误的持久化尝试:先 localStorage(新开应用就自动铺出上次商品),
// 再 sessionStorage(以为 F5 会清,其实 sessionStorage 在同一标签页内跨 F5 存活 → F5 后照旧残留)。
// 现在这四个 store 都不持久化了,两处残留键都读不到但会一直占着 —— 启动时一并删掉。
;['context', 'productReplace', 'kaipin', 'batch'].forEach((k) => {
  try { localStorage.removeItem(k) } catch (_) {}
  try { sessionStorage.removeItem(k) } catch (_) {}
})

const app = createApp(App)
const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')

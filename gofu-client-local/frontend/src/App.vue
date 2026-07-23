<script setup>
// 根组件:仅承载路由出口。业务壳在 AppShell.vue,登录页独立。
import { onMounted, onBeforeUnmount } from 'vue'

// 生命周期心跳:让打包态"关掉工作台浏览器→自动退出所有服务(托盘消失)"。
// 页面开着就每 5s 报活;关闭(pagehide)用 sendBeacon 发关闭信号。后端 LifecycleService 看门狗据此退出。
// 源码态后端不启用看门狗(app.resources-path 为空),这些请求打了也无副作用。
let hbTimer = null
function heartbeat() {
  // 轻量、无需 token、失败静默(后端没起/切走都不该报错)。
  fetch('/api/lifecycle/heartbeat', { method: 'POST', keepalive: true }).catch(() => {})
}
function onPageHide() {
  // 页面卸载期 fetch 不可靠,用 sendBeacon 确保关闭信号发出。
  try { navigator.sendBeacon('/api/lifecycle/closing') } catch (_) {}
}
onMounted(() => {
  heartbeat()
  hbTimer = setInterval(heartbeat, 5000)
  window.addEventListener('pagehide', onPageHide)
})
onBeforeUnmount(() => {
  if (hbTimer) clearInterval(hbTimer)
  window.removeEventListener('pagehide', onPageHide)
})
</script>

<template>
  <router-view />
</template>

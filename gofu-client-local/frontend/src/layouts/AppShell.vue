<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session.js'
import { useContextStore } from '@/stores/context.js'

const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const ctx = useContextStore()

// 侧边导航按业务分区(zone)分组:生产/运营/售后/系统。zone 顺序固定,内按路由声明序。
const ZONE_ORDER = ['生产', '运营', '售后', '系统']
const navZones = computed(() => {
  const byZone = {}
  router.getRoutes()
    .filter((r) => r.meta?.title && r.name !== 'login')
    .forEach((r) => {
      const z = r.meta.zone || '其他'
      ;(byZone[z] ||= []).push({ name: r.name, title: r.meta.title, icon: r.meta.icon })
    })
  const zones = ZONE_ORDER.filter((z) => byZone[z]).map((z) => ({ zone: z, items: byZone[z] }))
  // 未列入 ZONE_ORDER 的兜底追加
  Object.keys(byZone).filter((z) => !ZONE_ORDER.includes(z)).forEach((z) => zones.push({ zone: z, items: byZone[z] }))
  return zones
})

function logout() {
  session.logout()
  ctx.clear()
  router.push({ name: 'login' })
}
</script>

<template>
  <el-container class="shell">
    <el-header class="topbar">
      <div class="brand">GOFU <span>电商自动化中枢</span></div>
      <!-- 顶部上下文切换器:全程可见的"当前商品",跨页互通的核心 -->
      <div class="ctx-switcher">
        <el-tag v-if="ctx.hasContext" type="success" size="large" effect="dark">
          当前商品：{{ ctx.title }}
        </el-tag>
        <el-tag v-else type="info" size="large">未选择商品</el-tag>
      </div>
      <div class="user">
        <span v-if="session.user">{{ session.user.name }}</span>
        <el-button text @click="logout">退出</el-button>
      </div>
    </el-header>
    <el-container>
      <el-aside width="200px" class="sidebar">
        <el-menu :default-active="route.name" router>
          <template v-for="z in navZones" :key="z.zone">
            <div class="zone-title">{{ z.zone }}</div>
            <el-menu-item v-for="it in z.items" :key="it.name" :index="it.name" :route="{ name: it.name }">
              <el-icon v-if="it.icon"><component :is="it.icon" /></el-icon>
              <span>{{ it.title }}</span>
            </el-menu-item>
          </template>
        </el-menu>
      </el-aside>
      <el-main class="content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.shell { height: 100vh; }
.topbar {
  display: flex; align-items: center; gap: 24px;
  background: #1f2329; color: #fff; padding: 0 20px;
}
.brand { font-weight: 700; font-size: 18px; }
.brand span { font-weight: 400; font-size: 13px; opacity: .7; margin-left: 6px; }
.ctx-switcher { flex: 1; }
.user { display: flex; align-items: center; gap: 8px; }
.user :deep(.el-button) { color: #fff; }
.sidebar { border-right: 1px solid #e4e7ed; }
.zone-title { padding: 12px 20px 4px; font-size: 12px; color: #909399; font-weight: 600; letter-spacing: 1px; }
.content { background: #f5f7fa; padding: 20px; }
</style>

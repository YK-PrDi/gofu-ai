<script setup>
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useSessionStore, DEV_BYPASS_AUTH } from '@/stores/session.js'

// B1:登录向云端认证拿 token。云端鉴权端点未就绪前走 DEV_BYPASS。
const router = useRouter()
const route = useRoute()
const session = useSessionStore()
const name = ref('')
const password = ref('')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    if (DEV_BYPASS_AUTH) {
      session.setAuth('dev-token', { name: name.value || '开发者', role: 'admin' })
    } else {
      // TODO: 接云端 POST /api/auth/login → { token, user }
    }
    router.push(route.query.redirect || { name: 'dashboard' })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h1>GOFU</h1>
      <p class="sub">电商自动化中枢</p>
      <el-form @submit.prevent="submit">
        <el-form-item>
          <el-input v-model="name" placeholder="用户名" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="password" type="password" placeholder="密码" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" style="width:100%" @click="submit">
          登录
        </el-button>
      </el-form>
      <p v-if="DEV_BYPASS_AUTH" class="dev-hint">开发模式：任意账号直接进入（云端鉴权未接）</p>
    </el-card>
  </div>
</template>

<style scoped>
.login-wrap { height: 100vh; display: flex; align-items: center; justify-content: center; background: #1f2329; }
.login-card { width: 360px; text-align: center; padding: 20px; }
h1 { margin: 0; font-size: 32px; }
.sub { color: #909399; margin: 4px 0 24px; }
.dev-hint { color: #e6a23c; font-size: 12px; margin-top: 16px; }
</style>

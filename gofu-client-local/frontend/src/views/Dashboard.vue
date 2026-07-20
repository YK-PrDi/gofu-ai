<script setup>
import { ref, onMounted } from 'vue'
import { useContextStore } from '@/stores/context.js'

// P0 占位:验证路由 + Pinia + /api 代理连通。P2 填充真实概览。
const ctx = useContextStore()
const contexts = ref([])
const err = ref('')

onMounted(async () => {
  try {
    contexts.value = await ctx.listAll()
  } catch (e) {
    err.value = String(e.message || e)
  }
})
</script>

<template>
  <div>
    <h2>工作台</h2>
    <el-alert v-if="err" :title="'读取 /api/context 失败：' + err" type="warning" :closable="false" />
    <el-alert v-else title="骨架已跑通：路由 / Pinia / Element Plus / API 代理均正常" type="success" :closable="false" />
    <el-card style="margin-top:16px">
      <template #header>已有商品上下文（来自 /api/context）</template>
      <el-empty v-if="!contexts.length" description="暂无数据或后端未启动" />
      <pre v-else style="max-height:300px;overflow:auto">{{ contexts }}</pre>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useSettingsStore } from '@/stores/settings.js'

// P2-a:快麦ERP配置 + 行为开关 + Agent列表 + 防比价模板库。
const store = useSettingsStore()

// 行为开关直接双向绑 store.settings(persist 自动存)
const flags = [
  { key: 'reviewImages', label: '生图后必须人工过图' },
  { key: 'confirmBeforeListing', label: '上新前二次确认' },
  { key: 'genAllPlansSku', label: '补生全部方案的 SKU 图（默认只生当前方案，省额度）' },
  { key: 'batchAutoList', label: '批量流「AI生成SKU图」后自动上新（默认关：只生图+定价，人工确认后上）' },
  { key: 'batchAutoPreview', label: '批量流预检后自动预览首个商品（默认开：轻量读本地图，零额度）' },
  { key: 'batchAutoGen', label: '批量流预检后自动为缺图商品AI补生SKU图（默认开：串行走云端，费额度）' },
]

// 快麦 ERP 配置表单
const erp = reactive({ appKey: '', appSecret: '', accessToken: '', refreshToken: '', companyId: '', appTitle: '' })
const erpBusy = ref(false)

async function loadErp() {
  try {
    const c = await store.loadErpConfig()
    Object.keys(erp).forEach((k) => { if (c?.[k] != null) erp[k] = c[k] })
  } catch (_) {}
}
async function saveErp() {
  erpBusy.value = true
  try {
    await store.saveErpConfig({ ...erp })
    ElMessage.success('已保存（写入 kuaimai-config.json，重启仍生效）')
  } catch (e) {
    ElMessage.error('保存失败：' + e.message)
  } finally { erpBusy.value = false }
}
async function refreshToken() {
  erpBusy.value = true
  try {
    const c = await store.refreshErpToken()
    Object.keys(erp).forEach((k) => { if (c?.[k] != null) erp[k] = c[k] })
    ElMessage.success('Token 已刷新')
  } catch (e) {
    ElMessage.error('刷新失败（refreshToken 可能也过期，需在快麦后台重新获取）：' + e.message)
  } finally { erpBusy.value = false }
}

onMounted(() => { store.init(); loadErp() })
</script>

<template>
  <div class="settings">
    <h2>设置</h2>

    <el-card class="sec">
      <template #header>行为开关</template>
      <div class="flags">
        <el-checkbox v-for="f in flags" :key="f.key" v-model="store.settings[f.key]" :label="f.label" />
      </div>
    </el-card>

    <el-card class="sec">
      <template #header>快麦 ERP 配置<span class="hint">会话约30天过期，过期后刷新 Token 或在快麦后台重取</span></template>
      <el-form label-width="110px" class="erp-form">
        <el-form-item label="appKey"><el-input v-model="erp.appKey" /></el-form-item>
        <el-form-item label="appSecret"><el-input v-model="erp.appSecret" type="password" show-password /></el-form-item>
        <el-form-item label="accessToken"><el-input v-model="erp.accessToken" type="password" show-password /></el-form-item>
        <el-form-item label="refreshToken"><el-input v-model="erp.refreshToken" type="password" show-password /></el-form-item>
        <el-form-item label="companyId"><el-input v-model="erp.companyId" /></el-form-item>
        <el-form-item label="appTitle（公司名）"><el-input v-model="erp.appTitle" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="erpBusy" @click="saveErp">保存</el-button>
          <el-button :loading="erpBusy" @click="refreshToken">刷新 Token</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <div class="row">
      <el-card class="sec half">
        <template #header>可用生图 Agent</template>
        <el-tag v-for="a in store.agents" :key="a.id" class="tag">{{ a.name }}</el-tag>
      </el-card>
      <el-card class="sec half">
        <template #header>防比价模板库</template>
        <el-empty v-if="!store.antipriceTemplates.length" description="暂无（非花洒品类或云端未配）" :image-size="60" />
        <el-tag v-for="t in store.antipriceTemplates" :key="t.id" type="warning" class="tag">{{ t.name }}</el-tag>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.settings { max-width: 900px; }
.sec { margin-bottom: 16px; }
.hint { font-size: 12px; color: #909399; margin-left: 12px; font-weight: 400; }
.flags { display: flex; flex-direction: column; gap: 8px; }
.erp-form { max-width: 560px; }
.row { display: flex; gap: 16px; }
.half { flex: 1; }
.tag { margin: 0 6px 6px 0; }
</style>

<script setup>
import { onMounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useStoresStore } from '@/stores/stores-mgmt.js'

// P2-b:多店管理(平移 stores.js)。反风控逻辑一行不改,仅形态转 SFC。
const store = useStoresStore()

async function rename(s) {
  try {
    const { value } = await ElMessageBox.prompt('修改店铺名', '改名', {
      inputValue: s.name || '',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
    })
    await store.renameStore(s, value)
  } catch (_) { /* 取消 */ }
}

onMounted(() => store.loadStores())
</script>

<template>
  <div class="stores">
    <div class="head">
      <h2>店铺管理</h2>
      <div class="actions">
        <el-button @click="store.loadStores()">刷新</el-button>
        <el-button type="primary" :loading="store.adding" @click="store.addAndLogin()">
          + 加店铺并扫码登录
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="store.msg"
      :title="store.msg"
      :type="store.msgType === 'ok' ? 'success' : store.msgType === 'err' ? 'error' : 'info'"
      :closable="false"
      style="margin-bottom:12px"
    />

    <!-- 目标店铺选择器:跨页共享(上新第4步透传此 profile) -->
    <el-card class="sec">
      <template #header>目标店铺（上新时使用，默认顺位继承第一个已登录店）</template>
      <el-select v-model="store.targetProfile" placeholder="选择目标店铺" style="width:320px" clearable>
        <el-option
          v-for="s in store.list"
          :key="s.profile"
          :label="(s.name || s.profile) + '（' + store.storeLoginText(s) + '）'"
          :value="s.profile"
          :disabled="!s.loggedIn"
        />
      </el-select>
      <span v-if="!store.list.some(s => s.loggedIn)" class="warn">暂无已登录店铺，请先加店铺扫码登录</span>
    </el-card>

    <el-card class="sec">
      <template #header>店铺列表（每店独立 profile / cookie 隔离）</template>
      <el-table :data="store.list" empty-text="暂无店铺，点右上「加店铺」">
        <el-table-column label="店铺名">
          <template #default="{ row }">
            <span>{{ row.name || row.profile }}</span>
            <el-button link size="small" @click="rename(row)">✏️</el-button>
          </template>
        </el-table-column>
        <el-table-column label="profile" prop="profile" width="140" />
        <el-table-column label="登录态" width="120">
          <template #default="{ row }">
            <el-tag :type="!row.loggedIn ? 'info' : store.storeLoginStale(row) ? 'warning' : 'success'">
              {{ store.storeLoginText(row) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" @click="store.loginStore(row)">
              {{ row.loggedIn ? '重新登录' : '扫码登录' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.stores { max-width: 900px; }
.head { display: flex; align-items: center; justify-content: space-between; }
.actions { display: flex; gap: 8px; }
.sec { margin-top: 16px; }
.warn { color: #e6a23c; font-size: 12px; margin-left: 12px; }
</style>

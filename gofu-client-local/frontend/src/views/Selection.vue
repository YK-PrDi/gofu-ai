<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api.js'
import { useEntryStore } from '@/stores/entry.js'

// P2-c:ERP选品(旧 picker 弹窗升独立页)。选中写入 entryStore,跨页带给单品页。
const entry = useEntryStore()
const kw = ref('')
const loading = ref(false)
const all = ref([])            // 全量单品缓存
const roleFor = reactive({})   // outerId → role(临时选择态)

// 本地过滤(源 searchPick):打开即全量,输入实时过滤,展示上限200
const results = computed(() => {
  const k = kw.value.trim().toLowerCase()
  const base = !k ? all.value : all.value.filter((r) => {
    const t = String(r.title || '').toLowerCase(), o = String(r.outerId || '').toLowerCase()
    return t.includes(k) || o.includes(k)
  })
  return base.slice(0, 200)
})

async function loadAll() {
  loading.value = true
  Object.keys(roleFor).forEach((k) => delete roleFor[k])
  try {
    const d = await api.get('/api/erp/sku-items?keyword=') // 空关键词=全量(首次约6-10s建缓存)
    all.value = d.items || []
  } catch (e) {
    all.value = []
    ElMessage.error('加载单品失败：' + e.message)
  } finally {
    loading.value = false
  }
}

// 点行切换:未选→默认主件;已选→取消(源 togglePick)
function toggle(r) {
  const id = r.outerId
  if (roleFor[id]) delete roleFor[id]
  else roleFor[id] = 'main'
}

// 加入选中→写 entryStore(源 confirmPick 的写入部分)
function confirm() {
  const picks = results.value.filter((r) => roleFor[r.outerId]).map((r) => ({ ...r, role: roleFor[r.outerId] }))
  const n = entry.addPicks(picks)
  if (n > 0 && entry.hasMain) ElMessage.success(`已加入 ${n} 个单品`)
  else if (n > 0) ElMessage.warning(`已加入 ${n} 个单品，但还没选「主件」——标一个主件才会自动拉白底图/SKU`)
  else ElMessage.error('未选择任何单品')
  Object.keys(roleFor).forEach((k) => delete roleFor[k])
}

onMounted(loadAll)
</script>

<template>
  <div class="selection">
    <div class="head">
      <h2>选品</h2>
      <el-input v-model="kw" placeholder="搜索名称/编码（打开即显示全部，输入实时过滤）" clearable style="max-width:340px" />
      <el-button @click="loadAll" :loading="loading">🔄 刷新缓存</el-button>
    </div>

    <!-- 已选草稿(entryStore,跨页共享) -->
    <el-card v-if="entry.skus.length" class="picked-bar">
      <span class="lbl">已选 {{ entry.skus.length }} 件：</span>
      <el-tag
        v-for="(s, i) in entry.skus" :key="s.itemCode"
        :type="s.role === 'main' ? 'success' : 'info'"
        closable @close="entry.removeSku(i)" class="ptag"
      >{{ entry.roleLabel(s.role) }} · {{ s.name }}</el-tag>
    </el-card>

    <el-card>
      <el-table :data="results" v-loading="loading" height="calc(100vh - 300px)"
        empty-text="无单品（快麦凭证未配或缓存为空，去设置检查/刷新缓存）">
        <el-table-column width="50">
          <template #default="{ row }">
            <el-checkbox :model-value="!!roleFor[row.outerId]" @change="toggle(row)" />
          </template>
        </el-table-column>
        <el-table-column label="名称" prop="title" show-overflow-tooltip />
        <el-table-column label="编码" prop="outerId" width="160" />
        <el-table-column label="进货价" width="100">
          <template #default="{ row }">{{ row.purchasePrice || 0 }}</template>
        </el-table-column>
        <el-table-column label="角色" width="130">
          <template #default="{ row }">
            <el-select v-if="roleFor[row.outerId]" v-model="roleFor[row.outerId]" size="small" @click.stop>
              <el-option label="主件" value="main" />
              <el-option label="配件" value="accessory" />
              <el-option label="批量件" value="batch" />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
      <div class="foot">
        <span v-if="results.length >= 200" class="hint">仅显示前 200 条，用搜索缩小范围</span>
        <el-button type="primary" @click="confirm">加入选中</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.head h2 { margin: 0; margin-right: auto; }
.picked-bar { margin-bottom: 12px; }
.picked-bar .lbl { font-weight: 600; margin-right: 8px; }
.ptag { margin: 0 6px 6px 0; }
.foot { display: flex; align-items: center; justify-content: flex-end; gap: 12px; margin-top: 12px; }
.hint { color: #909399; font-size: 12px; margin-right: auto; }
</style>

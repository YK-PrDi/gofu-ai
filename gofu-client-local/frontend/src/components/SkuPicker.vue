<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api.js'
import { useEntryStore } from '@/stores/entry.js'

// 选品抽屉(做法A:选品收回单品页)。选中写 entryStore.skus。源:openPicker/loadPickerAll/searchPick/togglePick/confirmPick。
const open = defineModel({ type: Boolean, default: false })
const emit = defineEmits(['picked']) // 加入主件后通知父组件(触发拉白底图)
const entry = useEntryStore()

const kw = ref('')
const loading = ref(false)
const all = ref([])
const roleFor = reactive({})

const results = computed(() => {
  const k = kw.value.trim().toLowerCase()
  const base = !k ? all.value : all.value.filter((r) => {
    const t = String(r.title || '').toLowerCase(), o = String(r.outerId || '').toLowerCase()
    return t.includes(k) || o.includes(k)
  })
  // 有白底图预览的编码排前面(省得往下翻找),稳定排序(仅按有无图分组、组内原序)
  const sorted = base.slice().sort((a, b) => (rowImg(b) ? 1 : 0) - (rowImg(a) ? 1 : 0))
  return sorted.slice(0, 200)
})

async function loadAll() {
  loading.value = true
  Object.keys(roleFor).forEach((k) => delete roleFor[k])
  try {
    const d = await api.get('/api/erp/sku-items?keyword=')
    all.value = d.items || []
  } catch (e) {
    all.value = []; ElMessage.error('加载单品失败：' + e.message)
  } finally {
    loading.value = false
  }
}

async function refreshCache() {
  loading.value = true
  try {
    const d = await api.post('/api/erp/sku-items/refresh')
    if (d.error) throw new Error(d.error)
    all.value = []
    ElMessage.success('缓存已刷新，共 ' + (d.total || 0) + ' 个单品')
    await loadAll()
  } catch (e) {
    ElMessage.error('刷新失败：' + e.message)
  } finally {
    loading.value = false
  }
}

// 选品行白底图预览:picPath 为真实图(http 且非 no_pic 占位)才显示(源 rowImg)
function rowImg(r) {
  const p = r?.picPath
  return (p && p.startsWith('http') && !p.includes('no_pic')) ? p : ''
}

function toggle(r) {
  const id = r.outerId
  if (roleFor[id]) delete roleFor[id]
  else roleFor[id] = 'main'
}

function confirm() {
  const picks = results.value.filter((r) => roleFor[r.outerId]).map((r) => ({ ...r, role: roleFor[r.outerId] }))
  const n = entry.addPicks(picks)
  if (n > 0 && entry.hasMain) {
    ElMessage.success(`已加入 ${n} 个单品`)
    emit('picked') // 触发父组件 autoFetchWhite
    open.value = false
  } else if (n > 0) {
    ElMessage.warning(`已加入 ${n} 个单品，但还没选「主件」——标一个主件才会自动拉白底图/SKU`)
  } else {
    ElMessage.error('未选择任何单品')
  }
  Object.keys(roleFor).forEach((k) => delete roleFor[k])
}

// 抽屉首次打开时加载
watch(open, (v) => { if (v && !all.value.length) loadAll() })
</script>

<template>
  <el-drawer v-model="open" title="选品（ERP 单品）" size="640px" append-to-body>
    <div class="picker-head">
      <el-input v-model="kw" placeholder="搜索名称/编码（显示全部，输入实时过滤）" clearable style="flex:1" />
      <el-button @click="refreshCache" :loading="loading">🔄 刷新缓存</el-button>
    </div>
    <el-table :data="results" v-loading="loading" height="calc(100vh - 220px)"
      empty-text="无单品（快麦凭证未配或缓存为空，去设置检查/刷新缓存）">
      <el-table-column width="46">
        <template #default="{ row }">
          <el-checkbox :model-value="!!roleFor[row.outerId]" @change="toggle(row)" />
        </template>
      </el-table-column>
      <el-table-column label="图" width="56">
        <template #default="{ row }">
          <img v-if="rowImg(row)" :src="rowImg(row)" class="row-thumb" title="白底图预览" />
          <span v-else class="no-img">无图</span>
        </template>
      </el-table-column>
      <el-table-column label="名称" prop="title" show-overflow-tooltip />
      <el-table-column label="编码" prop="outerId" width="150" />
      <el-table-column label="角色" width="120">
        <template #default="{ row }">
          <el-select v-if="roleFor[row.outerId]" v-model="roleFor[row.outerId]" size="small" @click.stop>
            <el-option label="主件" value="main" />
            <el-option label="配件" value="accessory" />
            <el-option label="批量件" value="batch" />
          </el-select>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <span v-if="results.length >= 200" class="hint">仅显示前 200 条，用搜索缩小</span>
      <el-button @click="open = false">取消</el-button>
      <el-button type="primary" @click="confirm">加入选中</el-button>
    </template>
  </el-drawer>
</template>

<style scoped>
.picker-head { display: flex; gap: 12px; margin-bottom: 12px; }
.hint { color: #909399; font-size: 12px; margin-right: auto; }
.row-thumb { width: 40px; height: 40px; object-fit: contain; border: 1px solid #ebeef5; border-radius: 4px; }
.no-img { color: #c0c4cc; font-size: 11px; }
</style>

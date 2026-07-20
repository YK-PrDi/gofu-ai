<script setup>
import { ref, computed } from 'vue'
import { EC_CATEGORY_TREE } from '@/lib/categories.js'

// 品类列式级联 + 搜索(源 index.html cascCols/cascSearch/pickCasc)。
// v-model 绑品类路径数组。
const model = defineModel({ type: Array, default: () => [] })
const open = ref(false)
const kw = ref('')
const sel = ref([]) // 级联临时选择路径

// 按已选路径逐级展开列(源 cascCols)
const cols = computed(() => {
  const tree = EC_CATEGORY_TREE
  const out = [tree]
  let cur = tree
  for (const disp of sel.value) {
    const node = cur.find((n) => n.display === disp)
    if (node?.children) { out.push(node.children); cur = node.children }
    else break
  }
  return out
})

// 搜索:平铺所有叶子路径(源 cascSearch)
const searchResults = computed(() => {
  const k = kw.value.trim()
  if (!k) return []
  const out = []
  const walk = (nodes, path) => {
    for (const n of nodes) {
      const p = [...path, n.display]
      if (n.children) walk(n.children, p)
      else if (p.join('>').includes(k)) out.push(p)
    }
  }
  walk(EC_CATEGORY_TREE, [])
  return out.slice(0, 50)
})

function pickCol(colIdx, node) {
  sel.value = sel.value.slice(0, colIdx)
  sel.value.push(node.display)
  if (!node.children) { model.value = [...sel.value]; open.value = false } // 叶子=选定
}
function pickPath(path) {
  model.value = [...path]; sel.value = [...path]; open.value = false; kw.value = ''
}
function toggle() {
  open.value = !open.value
  if (open.value) sel.value = [...model.value]
}
</script>

<template>
  <div class="casc-wrap">
    <div class="casc-box" :class="{ placeholder: !model.length }" @click="toggle">
      {{ model.length ? model.join(' › ') : '点击选择品类（如 家装主材 › 卫浴配件 › 花洒配件 › 花洒喷头）' }}
    </div>
    <el-dialog v-model="open" title="选择品类" width="720px" append-to-body>
      <el-input v-model="kw" placeholder="搜索品类关键词（如 花洒/锅盖架）" clearable style="margin-bottom:12px" />
      <!-- 搜索结果 -->
      <div v-if="kw.trim()" class="search-list">
        <div v-if="!searchResults.length" class="empty">无匹配品类</div>
        <div v-for="(p, i) in searchResults" :key="i" class="search-row" @click="pickPath(p)">
          {{ p.join(' › ') }}
        </div>
      </div>
      <!-- 列式级联 -->
      <div v-else class="cols">
        <div v-for="(col, ci) in cols" :key="ci" class="col">
          <div
            v-for="node in col" :key="node.display"
            class="node" :class="{ active: sel[ci] === node.display, leaf: !node.children }"
            @click="pickCol(ci, node)"
          >
            {{ node.display }}<span v-if="node.children" class="arr">›</span>
          </div>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.casc-box { border: 1px solid #dcdfe6; border-radius: 4px; padding: 8px 12px; cursor: pointer; background: #fff; min-height: 20px; }
.casc-box:hover { border-color: #c0c4cc; }
.casc-box.placeholder { color: #c0c4cc; }
.cols { display: flex; gap: 8px; max-height: 50vh; }
.col { flex: 1; overflow-y: auto; border: 1px solid #ebeef5; border-radius: 4px; min-width: 0; }
.node { padding: 7px 10px; cursor: pointer; font-size: 13px; display: flex; justify-content: space-between; align-items: center; }
.node:hover { background: #f5f7fa; }
.node.active { background: #ecf5ff; color: #409eff; }
.node .arr { color: #c0c4cc; }
.search-list { max-height: 50vh; overflow-y: auto; }
.search-row { padding: 8px 10px; cursor: pointer; font-size: 13px; border-radius: 4px; }
.search-row:hover { background: #ecf5ff; color: #409eff; }
.empty { color: #909399; text-align: center; padding: 24px; }
</style>

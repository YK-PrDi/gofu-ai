<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api.js'
import { useContextStore } from '@/stores/context.js'

// P2-d:导入外部成品图→建商品。只做"选文件夹→上传→建context→载入",
// 导入后自动链(补图/风格迁移/自动上新)依赖生图页(P2-f)/单品页(P2-e),暂留桩,那时再接。
const router = useRouter()
const ctxStore = useContextStore()

const imp = reactive({
  folderName: '', groups: null,
  counts: { main: 0, detail: 0, white: 0, sku: 0 },
  running: false, progress: 0, msg: '', msgType: '', done: false,
})
let lastImportedFolder = ''

// SkuItem.role 是后端枚举(MAIN/ACCESSORY/BATCH,大写),兼容大小写
function roleLabel(r) {
  return { main: '主件', accessory: '配件', batch: '批量件' }[String(r || '').toLowerCase()] || r || ''
}

// 选文件夹(webkitdirectory):程序化建 input(源 pickImportFolder)
function pickFolder() {
  const inp = document.createElement('input')
  inp.type = 'file'; inp.multiple = true; inp.webkitdirectory = true
  inp.onchange = () => processFiles([...inp.files])
  inp.click()
}

function fileToB64(f) {
  return new Promise((resolve) => {
    const rd = new FileReader()
    rd.onload = () => resolve(String(rd.result).split(',')[1] || '')
    rd.readAsDataURL(f)
  })
}

// 按子目录名分类 + 命名/结构校验(源 _processImportFiles)
async function processFiles(files) {
  const imgs = files.filter((f) => f.type && f.type.startsWith('image/'))
  if (!imgs.length) { imp.msg = '该文件夹没有图片'; imp.msgType = 'err'; return }
  imp.folderName = (imgs[0].webkitRelativePath || '').split('/')[0] || ''
  const groups = { main: [], detail: [], white: [], sku: [] }
  const roleOf = (seg) => {
    const s = (seg || '').toLowerCase()
    if (s.includes('主图') || s.includes('main')) return 'main'
    if (s.includes('详情') || s.includes('detail')) return 'detail'
    if (s.includes('白底') || s.includes('white')) return 'white'
    if (s.includes('sku') || s.includes('款式')) return 'sku'
    return null
  }
  for (const f of imgs) {
    const parts = (f.webkitRelativePath || f.name).split('/')
    const role = roleOf(parts.length >= 2 ? parts[parts.length - 2] : '')
    if (!role) continue
    const b64 = await fileToB64(f)
    const ext = f.name.toLowerCase().endsWith('.png') ? 'png' : 'jpg'
    groups[role].push({ name: parts[parts.length - 1], b64, ext })
  }
  imp.groups = groups
  imp.counts = { main: groups.main.length, detail: groups.detail.length, white: groups.white.length, sku: groups.sku.length }
  const total = groups.main.length + groups.detail.length + groups.white.length + groups.sku.length
  if (total === 0) {
    imp.folderName = ''; imp.groups = null
    imp.msg = '❌ 没识别到 主图/详情/白底图/sku 子目录的图。结构：商品文件夹内含名字含「主图」「详情」「白底」「sku」的子目录，图放子目录里。'
    imp.msgType = 'err'; return
  }
  const warns = [], notes = []
  if (!imp.folderName.includes('-')) warns.push(`文件夹名「${imp.folderName}」没按「品类-主件名」命名（缺"-"，如 锅盖架-圣诞树收纳架），否则识别不出品类，属性/标题/方案会不准。`)
  if (groups.white.length === 0) notes.push('无「白底图」子目录 → 反推命中快麦编码后自动从快麦拉取，无需另放。')
  if (groups.main.length === 0) warns.push('没有「主图」——上新缺主图会被拦。')
  const head = `已读「${imp.folderName}」（主图${groups.main.length}·详情${groups.detail.length}·白底${groups.white.length}·sku${groups.sku.length}）。`
  if (warns.length) { imp.msg = '⚠ ' + head + '注意：' + warns.concat(notes).join(' '); imp.msgType = 'err' }
  else if (notes.length) { imp.msg = '✓ ' + head + notes.join(' ') + ' 点「导入并建商品」上传。'; imp.msgType = 'ok' }
  else { imp.msg = '✓ ' + head + '命名/结构正常，点「导入并建商品」上传。'; imp.msgType = 'ok' }
}

// 轮询导入进度(源 _pollImport)
async function pollImport(importId) {
  while (true) {
    await new Promise((r) => setTimeout(r, 1200))
    let t
    try { t = await api.get('/api/semi-auto/import-progress/' + importId) } catch (_) { continue }
    imp.progress = t.pct || 0
    imp.msg = `导入中 ${t.pct || 0}% · ${t.phase || '处理中…'}`; imp.msgType = ''
    if (t.done) { if (t.error) throw new Error(t.error); return t.result }
  }
}

// 上传建商品(源 runImport 的本体,不含 autoAfterImport 自动链)
async function runImport() {
  if (!imp.folderName || !imp.groups) return
  if (!imp.folderName.includes('-') && !confirm(`文件夹名「${imp.folderName}」没按「品类-主件名」命名，识别不出品类，方案/属性/标题会不准。仍要继续吗？`)) return
  if (lastImportedFolder === imp.folderName && !confirm(`文件夹「${imp.folderName}」刚导入过，再次导入会新建重复商品。确认再导吗？`)) return
  imp.running = true; imp.progress = 0; imp.done = false
  imp.msg = '导入中（上传云端→反推SKU→出方案+AI标题）…'; imp.msgType = ''
  try {
    const g = imp.groups
    const started = await api.post('/api/semi-auto/import-to-context', { folderName: imp.folderName, main: g.main, detail: g.detail, white: g.white, sku: g.sku })
    if (started.error) throw new Error(started.error)
    if (!started.importId) throw new Error('未返回 importId')
    const d = await pollImport(started.importId)
    if (!d || !d.contextId) throw new Error('未返回 contextId')
    imp.progress = 100
    imp.msg = `✓ 已导入「${d.productName || imp.folderName}」：主图${d.mainCount}·详情${d.detailCount}·白底${d.whiteCount || 0}，SKU方案${d.skuPlanCount || 0}个。`
    imp.msgType = 'ok'; imp.done = true
    if (d.warnings?.length) d.warnings.forEach((w) => console.warn('[导入]', w))
    lastImportedFolder = imp.folderName
    await ctxStore.load(d.contextId) // 载入新建商品→顶部上下文切换器立即显示,跨页可用
    // TODO(P2-f/P2-e后接): autoAfterImport 自动链(补SKU图→风格迁移→自动上新)
  } catch (e) {
    imp.msg = '导入失败：' + e.message; imp.msgType = 'err'
  } finally {
    imp.running = false
  }
}
</script>
<template>
  <div class="import">
    <h2>导入建品</h2>
    <el-card>
      <template #header>导入外部成品图 → 建商品（可接着换风格/上新）</template>
      <p class="desc">
        选一个商品文件夹，内含名字带「主图 / 详情 / 白底 / sku」的子目录。文件夹名建议「品类-主件名」，如
        <code>锅盖架-圣诞树收纳架</code>，否则识别不出品类。
      </p>
      <div class="actions">
        <el-button :disabled="imp.running" @click="pickFolder">📁 选择文件夹</el-button>
        <span v-if="imp.folderName" class="picked">
          已选：<b>{{ imp.folderName }}</b>
          <span class="counts">（主图{{ imp.counts.main }}·详情{{ imp.counts.detail }}·白底{{ imp.counts.white }}·sku{{ imp.counts.sku }}）</span>
        </span>
        <el-button type="primary" :disabled="!imp.folderName || imp.running" :loading="imp.running" @click="runImport">
          {{ imp.running ? '导入中…' : '导入并建商品' }}
        </el-button>
      </div>

      <el-progress v-if="imp.running" :percentage="imp.progress" style="margin-top:12px" />

      <el-alert
        v-if="imp.msg"
        :title="imp.msg"
        :type="imp.msgType === 'ok' ? 'success' : imp.msgType === 'err' ? 'error' : 'info'"
        :closable="false" style="margin-top:12px"
      />

      <!-- 导入结果概览:后端反推的品类 + SKU(识别不出则置空) -->
      <div v-if="imp.done" class="result">
        <div class="field">
          <label>品类</label>
          <div class="box" :class="{ empty: !ctxStore.category }">
            {{ ctxStore.category || '未识别（文件夹名未按「品类-主件名」命名）' }}
          </div>
        </div>
        <div class="field">
          <label>选品（SKU）</label>
          <div class="box" :class="{ empty: !ctxStore.skuItems.length }">
            <template v-if="ctxStore.skuItems.length">
              <el-tag
                v-for="(s, i) in ctxStore.skuItems" :key="i"
                :type="String(s.role).toLowerCase() === 'main' ? 'success' : 'info'" class="stag"
              >{{ roleLabel(s.role) }} · {{ s.name }}</el-tag>
            </template>
            <template v-else>未识别到 SKU 单品</template>
          </div>
        </div>
      </div>

      <!-- 下一步引导:导入成功后 -->
      <div v-if="imp.done" class="next">
        <span>商品已建好并载入（见顶部当前商品）。下一步：</span>
        <el-button type="primary" @click="router.push({ name: 'single' })">去单品上新继续</el-button>
        <el-button @click="router.push({ name: 'studio' })">去生图工作室换风格</el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.import { max-width: 800px; }
.desc { color: #606266; font-size: 13px; line-height: 1.7; }
.desc code { background: #f0f2f5; padding: 1px 6px; border-radius: 4px; }
.actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.picked { font-size: 13px; }
.counts { color: #909399; }
.next { margin-top: 16px; padding-top: 16px; border-top: 1px solid #ebeef5; display: flex; align-items: center; gap: 12px; }
.next span { color: #606266; font-size: 14px; }
.result { margin-top: 16px; display: flex; flex-direction: column; gap: 12px; }
.field { display: flex; align-items: flex-start; gap: 12px; }
.field label { width: 84px; flex-shrink: 0; color: #606266; font-size: 14px; padding-top: 6px; }
.box { flex: 1; min-height: 34px; padding: 6px 10px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; }
.box.empty { color: #c0c4cc; background: #fafafa; }
.stag { margin: 2px 6px 2px 0; }
</style>

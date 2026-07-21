<script setup>
import { ref, computed, onMounted } from 'vue'
import { api } from '@/api.js'
import { useContextStore } from '@/stores/context.js'
import { useImportStore } from '@/stores/import-flow.js'
import { useSettingsStore } from '@/stores/settings.js'
import { useStoresStore } from '@/stores/stores-mgmt.js'
import { useGen } from '@/composables/useGen.js'

// 导入建品:完整自动链(选文件夹符合要求→建商品→补SKU图→自动风格迁移→定价→上新)。
// 不符合(缺"-")提示改名。页内含手动风格迁移(旧版🎨,测哪种风格销量好)。
const ctxStore = useContextStore()
const imp = useImportStore() // 态放store,切页不丢
const settings = useSettingsStore()
const storesStore = useStoresStore()
const { gen, style, runSkuImages, runStyleTransfer, fillCostAndPrice } = useGen()

const styleOptions = [
  { id: 'tech-blue', name: '科技蓝' }, { id: 'girl-pink', name: '少女粉' }, { id: 'premium-gray', name: '高级灰' },
  { id: 'natural-green', name: '自然绿' }, { id: 'sunset-orange', name: '暖阳橙' }, { id: 'khaki', name: '卡其色' },
  { id: 'light-yellow', name: '淡黄色' }, { id: 'beige', name: '米黄色' },
]
const imgUrl = (r) => '/api/gen/img?ref=' + encodeURIComponent(r)

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
  if (warns.length) {
    // 命名/结构有硬问题 → 拦下,等用户看提示决定(不自动导)
    imp.msg = '⚠ ' + head + '注意：' + warns.concat(notes).join(' '); imp.msgType = 'err'
    return
  }
  // 命名正确、结构正常 → 直接导入,不需人工再点「导入并建商品」
  imp.msg = '✓ ' + head + (notes.length ? notes.join(' ') + ' ' : '') + '命名正常，开始导入…'; imp.msgType = 'ok'
  runImport()
}

// 轮询导入进度(源 _pollImport)
async function pollImport(importId) {
  while (true) {
    await new Promise((r) => setTimeout(r, 1200))
    let t
    try { t = await api.get('/api/semi-auto/import-progress/' + importId) } catch (_) { continue }
    imp.progress = t.pct || 0
    imp.msg = `导入中 ${t.pct || 0}% · ${t.phase || '处理中…'}`; imp.msgType = ''
    // 建完context即中途载入并持续刷新,右侧预览实时出图(主图/详情先显示,方案/SKU随后陆续刷入),不必等100%
    if (t.contextId) { try { await ctxStore.load(t.contextId) } catch (_) {} }
    if (t.done) { if (t.error) throw new Error(t.error); return t.result }
  }
}

// 上传建商品(源 runImport 的本体,不含 autoAfterImport 自动链)
async function runImport() {
  if (!imp.folderName || !imp.groups) return
  // 名字正确(含"-")直接导入,不弹确认;只有格式不对才拦
  if (!imp.folderName.includes('-') && !confirm(`文件夹名「${imp.folderName}」没按「品类-主件名」命名，识别不出品类，方案/属性/标题会不准。仍要继续吗？`)) return
  // 重复导入防呆:同名刚导过会建重复商品,提醒(名字正确但重复也该提醒)
  if (imp.lastImportedFolder === imp.folderName && !confirm(`文件夹「${imp.folderName}」刚导入过，再次导入会新建重复商品。确认再导吗？`)) return
  imp.running = true; imp.progress = 0; imp.done = false
  imp.msg = '导入中（上传云端→反推SKU→出方案+AI标题）…'; imp.msgType = ''
  try {
    const g = imp.groups
    const started = await api.post('/api/semi-auto/import-to-context', { folderName: imp.folderName, main: g.main, detail: g.detail, white: g.white, sku: g.sku, planCount: settings.settings.defaultPlanCount || 1 })
    if (started.error) throw new Error(started.error)
    if (!started.importId) throw new Error('未返回 importId')
    const d = await pollImport(started.importId)
    if (!d || !d.contextId) throw new Error('未返回 contextId')
    // 导入本体完成≠整条自动链完成:不在此置100%/done,交给autoAfterImport走完各步后再终态
    imp.msg = `已导入「${d.productName || imp.folderName}」，进入自动链…`; imp.msgType = ''
    if (d.warnings?.length) d.warnings.forEach((w) => console.warn('[导入]', w))
    imp.lastImportedFolder = imp.folderName
    await ctxStore.load(d.contextId)
    // 建好后进自动链:补SKU图→风格迁移→定价→上新
    await autoAfterImport()
  } catch (e) {
    imp.msg = '导入失败：' + e.message; imp.msgType = 'err'
  } finally {
    imp.running = false
  }
}

// 导入后自动链(源 autoAfterImport):补缺SKU图→自动随机风格迁移→定价→按设置上新/停下等人工。
async function autoAfterImport() {
  const plans = () => ctxStore.current?.structure?.plans || []
  imp.running = true
  try {
    // 1/4 补生缺失SKU图(方案有item但无成品图),需白底图参考
    const hasWhite = (ctxStore.current?.visual?.whiteImages || []).length > 0
    const missing = plans().reduce((n, p) => n + (p.items || []).filter((it) => !it.imgDir).length, 0)
    if (missing > 0 && hasWhite) {
      imp.msg = `步骤1/4 补生缺失的 SKU 图中…（${missing} 张）`; imp.msgType = ''
      await runSkuImages(settings.antipriceTemplates) // step2 生选定方案SKU图+详情图
    } else if (missing > 0 && !hasWhite) {
      imp.msg = `⚠ 有 ${missing} 个 SKU 缺成品图但无白底图可参考,无法自动补生,请手动补白底图。`; imp.msgType = 'err'; return
    }
    // 2/4 自动随机风格迁移(定基调)
    const pickable = styleOptions
    style.styleId = pickable[Math.floor(Math.random() * pickable.length)].id
    imp.msg = '步骤2/4 自动风格迁移中（不满意可在下方🎨手动重换）…'
    await runStyleTransfer(style.styleId, { auto: true })
    if (style.msgType === 'err') { imp.msg = '⚠ 自动风格迁移失败,已中断:' + style.msg + '（可在下方🎨手动重试）'; imp.msgType = 'err'; return }
    // 3/4 定价(导入方案价为0,上新前必须回填)
    imp.msg = '步骤3/4 自动定价中…'
    await fillCostAndPrice()
    const zero = plans().reduce((n, p) => n + (p.items || []).filter((it) => !(it.groupPrice > 0)).length, 0)
    if (zero > 0) { imp.msg = `⚠ 有 ${zero} 个 SKU 定不出价(快麦缺进价),已中断上新,请手动定价后上新。`; imp.msgType = 'err'; return }
    // 4/4 直接进上新:是否弹二次确认/是否要主图,由全局设置(confirmBeforeListing/reviewImages)在 submitListing 内统一管,不再额外停一次
    imp.progress = 100; imp.done = true
    imp.msg = '步骤4/4 上新中…'
    await submitListing(false)
  } catch (e) {
    imp.msg = '自动链失败：' + (e.message || e); imp.msgType = 'err'
  } finally {
    imp.running = false
  }
}

// 手动换风格(旧版🎨):测哪种风格销量好
async function manualStyle() {
  if (!style.styleId) return
  await runStyleTransfer(style.styleId)
}

// 统一进度:导入阶段用 imp.progress,补图/风格迁移阶段用 gen.progress(它才是真实生图进度)
const busy = computed(() => imp.running || gen.running || style.running || listing.value.running)
const liveProgress = computed(() => (gen.running || style.running) ? gen.progress : imp.progress)
// SKU 方案(15套):展示用
const plans = computed(() => ctxStore.current?.structure?.plans || [])
const selPlan = computed({
  get: () => ctxStore.current?.structure?.selectedPlanIndex || 0,
  set: (i) => { if (ctxStore.current?.structure) ctxStore.current.structure.selectedPlanIndex = i },
})
const curItems = computed(() => plans.value[selPlan.value]?.items || [])
const detailImages = computed(() => ctxStore.current?.visual?.detailImages || [])
function skuImg(it) { return it.imgDir ? imgUrl(it.imgDir) : '' }

// 上新(dryRun=false 正式)。源 submitListing。二次确认由全局设置 confirmBeforeListing 统一管。
const listing = ref({ running: false, log: '' })
async function submitListing(dryRun) {
  if (!ctxStore.current) return
  // reviewImages(生图后必须人工过图):无主图不让上
  if (settings.settings.reviewImages && !dryRun && !(ctxStore.current.visual?.mainImages || []).length) {
    imp.msg = '设置要求生图后人工过图:当前无主图,不允许上新'; imp.msgType = 'err'; return
  }
  // 上新前二次确认(全局设置):勾了才弹。导入自动链也走这里,不再额外停一次。
  if (!dryRun && settings.settings.confirmBeforeListing && !confirm('将真实提交上新到拼多多,确认继续?')) return
  listing.value.running = true
  try {
    const d = await api.post('/api/listing/from-context', {
      contextId: ctxStore.contextId, planIndex: ctxStore.current?.structure?.selectedPlanIndex || 0,
      dryRun, brand: '', storeProfile: storesStore.targetProfile || '',
    })
    if (d.error) throw new Error(d.error)
    if (!d.taskId) throw new Error('未返回 taskId')
    await pollListing(d.taskId)
  } catch (e) { imp.msg = '上新启动失败：' + e.message; imp.msgType = 'err'; listing.value.running = false }
}
async function pollListing(taskId, tries = 0) {
  if (tries > 1200) { imp.msg = '上新轮询超时'; imp.msgType = 'err'; listing.value.running = false; return }
  try {
    const t = await api.get('/api/task/' + taskId)
    listing.value.log = (t.results || []).map((x) => x.message || '').join('\n')
    const last = [...(t.results || [])].reverse().find((x) => ['captcha', 'done', 'error'].includes(x.type))
    if (t.status === 'running' && last?.type === 'captcha') { imp.msg = '🛑 需人工完成滑块验证,脚本会自动继续'; imp.msgType = 'err' }
    else imp.msg = `上新中… ${t.status} ${t.progress}/${t.total}`
    if (t.status === 'running') { setTimeout(() => pollListing(taskId, tries + 1), 1500); return }
    listing.value.running = false
    const ok = t.status === 'done'
    imp.msg = ok ? '✓ 全自动完成:导入→补图→风格迁移→定价→上新成功。' : '✗ 上新未成功:' + t.status; imp.msgType = ok ? 'ok' : 'err'
  } catch (e) { imp.msg = '上新轮询失败：' + e.message; imp.msgType = 'err'; listing.value.running = false }
}

onMounted(() => { settings.init(); storesStore.loadStores() })
</script>
<template>
  <div class="import">
    <h2>导入建品</h2>
    <div class="cols">
      <!-- 左栏:导入 + 自动链 + 手动风格 + 上新 -->
      <div class="left">
        <el-card>
          <template #header>导入外部成品图 → 全自动建品</template>
          <p class="desc">
            选商品文件夹，内含名字带「主图 / 详情」的子目录（白底/sku 可选）。文件夹名须「品类-主件名」，如
            <code>锅盖架-圣诞树收纳架</code>。命名正确即全自动：建品→补SKU图→换风格→定价→上新（上不上新看设置）。
          </p>
          <div class="actions">
            <el-button :disabled="busy" @click="pickFolder">📁 选择文件夹</el-button>
            <el-button v-if="imp.folderName && !imp.done" type="primary" :disabled="!imp.folderName || busy" :loading="imp.running" @click="runImport">
              {{ imp.running ? '进行中…' : '导入并建商品' }}
            </el-button>
          </div>
          <div v-if="imp.folderName" class="picked">
            已选：<b>{{ imp.folderName }}</b>
            <span class="counts">（主图{{ imp.counts.main }}·详情{{ imp.counts.detail }}·白底{{ imp.counts.white }}·sku{{ imp.counts.sku }}）</span>
          </div>
          <el-progress v-if="busy" :percentage="Math.round(liveProgress)" style="margin-top:12px" />
          <el-alert v-if="imp.msg" :title="imp.msg" :closable="false" style="margin-top:12px"
            :type="imp.msgType === 'ok' ? 'success' : imp.msgType === 'err' ? 'error' : 'info'" />
        </el-card>

        <!-- 手动风格迁移(旧版🎨) -->
        <el-card v-if="ctxStore.current" class="sec">
          <template #header>🎨 风格迁移（测哪种风格销量好，可反复换）</template>
          <p class="desc">对当前商品整套换视觉基调（产品/构图/文案不变，只换风格），换完覆盖回写，可接着上新。</p>
          <div class="style-bar">
            <el-select v-model="style.styleId" placeholder="选择目标风格…" style="width:180px">
              <el-option v-for="s in styleOptions" :key="s.id" :label="s.name" :value="s.id" />
            </el-select>
            <el-button type="primary" :disabled="!style.styleId || busy" :loading="style.running" @click="manualStyle">整套换风格</el-button>
          </div>
          <el-alert v-if="style.msg" :title="style.msg" :closable="false" style="margin-top:10px"
            :type="style.msgType === 'ok' ? 'success' : style.msgType === 'err' ? 'error' : 'info'" />
        </el-card>

        <!-- 手动上新 -->
        <el-card v-if="ctxStore.current" class="sec">
          <template #header>上新</template>
          <el-button type="primary" :disabled="busy" :loading="listing.running" @click="submitListing(false)">上新到平台</el-button>
          <pre v-if="listing.log" class="log">{{ listing.log }}</pre>
        </el-card>
      </div>

      <!-- 右栏:预览(品类/SKU概览 + 主图 + 详情 + SKU方案) -->
      <div class="right">
        <el-card class="preview">
          <template #header>预览{{ ctxStore.current ? '：' + ctxStore.title : '' }}</template>
          <el-empty v-if="!ctxStore.current" description="选文件夹导入后在此预览" :image-size="60" />
          <template v-else>
            <!-- 品类 + 选品概览 -->
            <div class="ov">
              <span class="ov-l">品类</span>
              <span class="ov-v" :class="{ empty: !ctxStore.category }">{{ ctxStore.category || '未识别（文件夹名未按品类-主件名）' }}</span>
            </div>
            <div class="ov">
              <span class="ov-l">选品</span>
              <span class="ov-v">
                <el-tag v-for="(s, i) in ctxStore.skuItems" :key="i" :type="String(s.role).toLowerCase() === 'main' ? 'success' : 'info'" class="stag">
                  {{ roleLabel(s.role) }} · {{ s.name }}
                </el-tag>
                <span v-if="!ctxStore.skuItems.length" class="empty">未识别到 SKU 单品</span>
              </span>
            </div>

            <!-- 主图 -->
            <div v-if="(ctxStore.current.visual?.mainImages || []).length" class="psec">
              <div class="psec-t">主图（{{ ctxStore.current.visual.mainImages.length }}）</div>
              <div class="pgrid"><img v-for="(m, i) in ctxStore.current.visual.mainImages" :key="i" :src="imgUrl(m)" /></div>
            </div>
            <!-- 详情图 -->
            <div v-if="detailImages.length" class="psec">
              <div class="psec-t">详情图（{{ detailImages.length }}）</div>
              <div class="pgrid"><img v-for="(d, i) in detailImages" :key="i" :src="imgUrl(d)" /></div>
            </div>
            <!-- SKU 方案(多套,可切) -->
            <div v-if="plans.length" class="psec">
              <div class="psec-t">SKU 方案（{{ plans.length }} 套）</div>
              <el-tabs v-model="selPlan">
                <el-tab-pane v-for="(p, i) in plans" :key="i" :label="`方案${i + 1}`" :name="i" />
              </el-tabs>
              <el-table :data="curItems" size="small" max-height="300">
                <el-table-column label="SKU图" width="70">
                  <template #default="{ row }">
                    <img v-if="skuImg(row)" :src="skuImg(row)" class="sku-thumb" />
                    <span v-else class="empty">缺</span>
                  </template>
                </el-table-column>
                <el-table-column label="SKU" prop="skuDisplayName" show-overflow-tooltip />
                <el-table-column label="成本" width="70"><template #default="{ row }">{{ (row.cost || 0).toFixed(2) }}</template></el-table-column>
                <el-table-column label="拼单价" width="80"><template #default="{ row }">{{ (row.groupPrice || 0).toFixed(2) }}</template></el-table-column>
              </el-table>
            </div>
          </template>
        </el-card>
      </div>
    </div>
  </div>
</template>

<style scoped>
.import { max-width: 1400px; }
.cols { display: flex; gap: 16px; align-items: flex-start; }
.left { flex: 0 0 480px; display: flex; flex-direction: column; gap: 12px; }
.right { flex: 1; position: sticky; top: 16px; }
.desc { color: #606266; font-size: 13px; line-height: 1.7; }
.desc code { background: #f0f2f5; padding: 1px 6px; border-radius: 4px; }
.actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.picked { font-size: 13px; margin-top: 8px; }
.counts { color: #909399; }
.stag { margin: 2px 6px 2px 0; }
.style-bar { display: flex; gap: 12px; align-items: center; }
.ov { display: flex; gap: 10px; margin-bottom: 10px; font-size: 13px; }
.ov-l { width: 42px; flex-shrink: 0; color: #909399; }
.ov-v { flex: 1; }
.ov-v.empty, .empty { color: #c0c4cc; }
.psec { margin-top: 16px; }
.psec-t { font-size: 13px; color: #909399; margin-bottom: 8px; }
.pgrid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; }
.pgrid img { width: 100%; border: 1px solid #ebeef5; border-radius: 4px; }
.sku-thumb { width: 44px; height: 44px; object-fit: contain; border: 1px solid #ebeef5; border-radius: 4px; }
.log { background: #111827; color: #d1d5db; padding: 10px; border-radius: 4px; font-size: 12px; max-height: 200px; overflow: auto; white-space: pre-wrap; margin-top: 12px; }
</style>

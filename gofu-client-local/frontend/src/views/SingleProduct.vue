<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api.js'
import { useEntryStore } from '@/stores/entry.js'
import { useContextStore } from '@/stores/context.js'
import { useSettingsStore } from '@/stores/settings.js'
import { useStoresStore } from '@/stores/stores-mgmt.js'
import { useGen } from '@/composables/useGen.js'
import CascadeCategory from '@/components/CascadeCategory.vue'
import SkuPicker from '@/components/SkuPicker.vue'

// P2-e:单品上新工作台。整合品类/选品/白底图/生成/SKU定价/标题/上新全流程。
const entry = useEntryStore()
const ctxStore = useContextStore()
const settings = useSettingsStore()
const storesStore = useStoresStore()
const { gen, runLayout, runSkuImages, runOneClick, stopGen, recalcPrice } = useGen()

const pickerOpen = ref(false)
const styleOptions = [
  { id: 'random', name: '随机' }, { id: 'original', name: '原图延展' }, { id: 'tech-blue', name: '科技蓝' },
  { id: 'girl-pink', name: '少女粉' }, { id: 'premium-gray', name: '高级灰' }, { id: 'natural-green', name: '自然绿' },
  { id: 'sunset-orange', name: '暖阳橙' }, { id: 'khaki', name: '卡其色' }, { id: 'light-yellow', name: '淡黄色' }, { id: 'beige', name: '米黄色' },
]

// ── 上新态 ──
const listing = ref({ dryRun: true, running: false, msg: '', msgType: '', log: '' })

// 白底图缩略图 URL(源 whiteThumb)
function whiteThumb(w) {
  if (!w) return ''
  if (w.startsWith('data:') || w.startsWith('http')) return w
  return '/api/erp/local-image?path=' + encodeURIComponent(w)
}

// 拖拽/选文件加白底图(源 _addWhiteFiles)
function addWhiteFiles(files) {
  const imgs = [...files].filter((f) => f.type && f.type.startsWith('image/'))
  imgs.forEach((f) => {
    const rd = new FileReader()
    rd.onload = () => entry.whites.push(rd.result)
    rd.readAsDataURL(f)
  })
  const skipped = files.length - imgs.length
  ElMessage[imgs.length ? 'success' : 'error'](`已加 ${imgs.length} 张白底图${skipped ? `（跳过 ${skipped} 个非图片）` : ''}`)
}
function pickWhiteFiles() {
  const inp = document.createElement('input')
  inp.type = 'file'; inp.multiple = true; inp.accept = 'image/*'
  inp.onchange = () => addWhiteFiles([...inp.files])
  inp.click()
}
const dropOver = ref(false)
function onDrop(e) {
  dropOver.value = false
  addWhiteFiles([...(e.dataTransfer?.files || [])].filter((f) => f.type.startsWith('image/')))
}

// 选完主件→自动拉白底图(SkuPicker picked 事件触发)
async function afterPick() {
  if (!entry.mainCodes.length) return
  try {
    const { matched, missing } = await entry.autoFetchWhite()
    // 配件白底图也当场查(如052滤芯)→缺则当场进missing、当场拦住生成;
    // 否则原来配件缺图要等runLayout里fetchAccWhites才发现,主图已在生了(主图也会用到配件)
    await entry.fetchAccWhites()
    const totalMiss = entry.whiteCheck.missing.length
    ElMessage[totalMiss ? 'warning' : 'success'](`自动取白底图：主件${matched}张${totalMiss ? `，缺 ${totalMiss} 个编码待导入（含配件）` : '（齐全）'}`)
  } catch (e) { ElMessage.error('自动取白底图失败：' + e.message) }
}

// 为缺图编码导入白底图(源 importForCode)
function importForCode(code) {
  const inp = document.createElement('input')
  inp.type = 'file'; inp.accept = 'image/*'
  inp.onchange = () => {
    const f = inp.files?.[0]; if (!f) return
    const rd = new FileReader()
    rd.onload = () => {
      entry.importedFor[code] = rd.result
      const isMain = entry.mainCodes.includes(code)
      if (isMain) { if (!entry.whites.includes(rd.result)) entry.whites.push(rd.result) }
      else if (!entry.accWhites.includes(rd.result)) entry.accWhites.push(rd.result)
      entry.recordWhiteCode(code, rd.result)
      entry.resolveMissing(code) // 补图后从缺图列表移除,解硬拦
      ElMessage.success(`已为 ${code} 导入白底图`)
    }
    rd.readAsDataURL(f)
  }
  inp.click()
}

// 回传快麦:把导入的白底图写回该编码快麦档案(会改线上,先二次确认)。源 pushWhiteToKuaimai,P2-e漏迁补回。
const pushing = ref('')
async function pushWhite(code) {
  if (!confirm(`确认把这张白底图回传到快麦单品【${code}】？这会修改快麦线上商品档案的图片。`)) return
  pushing.value = code
  try {
    await entry.pushWhiteToKuaimai(code)
    ElMessage.success('✓ 已回传快麦：' + code)
  } catch (e) { ElMessage.error('回传快麦失败：' + (e.message || e)) }
  finally { pushing.value = '' }
}

// 单品上新与导入建品是两条独立流程,单品页直接读当前商品,不做导入接管逻辑。
const pageCtx = computed(() => ctxStore.current)

// ── 方案/定价 ──
const plans = computed(() => pageCtx.value?.structure?.plans || [])
const selectedPlan = computed({
  get: () => pageCtx.value?.structure?.selectedPlanIndex || 0,
  set: (i) => { if (pageCtx.value?.structure) pageCtx.value.structure.selectedPlanIndex = i },
})
const currentItems = computed(() => plans.value[selectedPlan.value]?.items || [])

function onPriceEdit(it, idx) {
  it.__edited = true
  if (!pageCtx.value.lockedFields) pageCtx.value.lockedFields = []
  const f = `plans[${selectedPlan.value}].items[${idx}].groupPrice`
  if (!pageCtx.value.lockedFields.includes(f)) pageCtx.value.lockedFields.push(f)
}

async function exportPricing() {
  if (!currentItems.value.length) { ElMessage.error('无 SKU 可导出'); return }
  try {
    const res = await api.request('/api/pricing/export', {
      method: 'POST', raw: true,
      body: { profitRate: gen.profitRate, skus: currentItems.value.map((it) => ({ itemCode: it.itemCode || '', name: it.skuDisplayName || it.name || '', cost: it.cost || 0 })) },
    })
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a'); a.href = url; a.download = '定价表.xlsx'; a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('定价表已导出')
  } catch (e) { ElMessage.error('导出失败：' + e.message) }
}

// ── 上新(源 submitListing/pollTask)──
const targetStoreHint = computed(() => {
  const list = storesStore.list
  if (!list.length) return { text: '尚无店铺，请先在店铺管理添加并登录', err: true }
  const s = list.find((x) => x.profile === storesStore.targetProfile)
  if (!s) return { text: '未选目标店铺，请去店铺管理选要上新的店', err: true }
  if (!s.loggedIn) return { text: `⚠ 将上到「${s.name || s.profile}」，但该店未登录`, err: true }
  const stale = storesStore.storeLoginStale(s)
  return { text: `本次将上新到「${s.name || s.profile}」（${s.profile}）${stale ? '·登录可能过期' : ''}`, err: stale }
})

async function submitListing() {
  if (!pageCtx.value) { ElMessage.error('请先生成或加载商品'); return }
  if (settings.settings.reviewImages && !listing.value.dryRun && !(pageCtx.value.visual?.mainImages || []).length) {
    ElMessage.error('设置要求生图后人工过图：当前无主图，不允许上新'); return
  }
  if (!listing.value.dryRun && settings.settings.confirmBeforeListing && !confirm('将真实提交上新到拼多多，确认继续？')) return
  listing.value.running = true; listing.value.log = ''
  listing.value.msg = listing.value.dryRun ? '启动诊断…' : '启动上新…'; listing.value.msgType = ''
  try {
    const d = await api.post('/api/listing/from-context', {
      contextId: ctxStore.contextId, planIndex: selectedPlan.value,
      dryRun: listing.value.dryRun, brand: entry.brand || '', storeProfile: storesStore.targetProfile || '',
    })
    if (d.error) throw new Error(d.error)
    if (!d.taskId) throw new Error('未返回 taskId')
    pollListing(d.taskId)
  } catch (e) { listing.value.msg = '启动失败：' + e.message; listing.value.msgType = 'err'; listing.value.running = false }
}

async function pollListing(taskId, tries = 0) {
  if (tries > 1200) { listing.value.msg = '轮询超时（超过30分钟）'; listing.value.msgType = 'err'; listing.value.running = false; return }
  try {
    const t = await api.get('/api/task/' + taskId)
    listing.value.log = (t.results || []).map((x) => x.message || JSON.stringify(x)).join('\n')
    listing.value.msg = `${t.status} ${t.progress}/${t.total}`
    const res = t.results || []
    const last = [...res].reverse().find((x) => ['captcha', 'progress', 'done', 'error'].includes(x.type))
    if (t.status === 'running' && last?.type === 'captcha') {
      listing.value.msg = '🛑 需人工：请到浏览器窗口手动完成滑块验证，脚本会自动继续 — ' + last.message
      listing.value.msgType = 'err'
    }
    if (t.status === 'running') { setTimeout(() => pollListing(taskId, tries + 1), 1500); return }
    listing.value.running = false
    const ok = t.status === 'done'
    listing.value.msgType = ok ? 'ok' : 'err'
    listing.value.msg = ok
      ? (listing.value.dryRun ? '诊断完成（未真实提交，见日志/截图）' : '✓ 上新成功')
      : '✗ 上新未成功：' + t.status + '，见下方日志'
  } catch (e) { listing.value.msg = '轮询失败：' + e.message; listing.value.msgType = 'err'; listing.value.running = false }
}

const fmt = (n) => (Number(n) || 0).toFixed(2)

onMounted(() => {
  settings.init()
  storesStore.loadStores()
  // 若从导入页/切换器带入了 context,同步 profitRate 无需处理;方案已在 contextStore
})
</script>
<template>
  <div class="single">
    <h2>单品上新</h2>
    <div class="cols">
      <!-- 左栏:录入 → 生成 → 上新 -->
      <div class="left">
        <!-- 1 品类 -->
        <el-card class="step">
          <template #header>① 品类</template>
          <CascadeCategory v-model="entry.catPath" />
        </el-card>

        <!-- 2 选品 -->
        <el-card class="step">
          <template #header>
            ② 选品（主件/配件）
            <el-button size="small" style="float:right" @click="pickerOpen = true">+ 选品</el-button>
          </template>
          <el-empty v-if="!entry.skus.length" description="点右上「选品」从 ERP 选主件/配件" :image-size="50" />
          <div v-else class="chips">
            <el-tag
              v-for="(s, i) in entry.skus" :key="s.itemCode"
              :type="s.role === 'main' ? 'success' : 'info'" closable @close="entry.removeSku(i)" class="chip"
            >{{ entry.roleLabel(s.role) }} · {{ s.name }}</el-tag>
          </div>
        </el-card>

        <!-- 白底图操作/预览已整合到右侧预览区(不再占左栏) -->

        <!-- 3 生图选项 + 生成 -->
        <el-card class="step">
          <template #header>③ 生成{{ settings.settings.oneClickGen ? '（一键：布局+主图+SKU图+详情图）' : '布局 + 主图' }}</template>
          <div class="opts">
            <label>主图张数<el-select v-model.number="entry.genOpts.mainCount" size="small">
              <el-option v-for="n in [3,4,5,6,8,10]" :key="n" :label="n" :value="n" /></el-select></label>
            <label>方案数<el-select v-model.number="entry.genOpts.planCount" size="small">
              <el-option v-for="n in [1,2,3,4,5]" :key="n" :label="n+'套'" :value="n" /></el-select></label>
            <label>主图比例<el-select v-model="entry.genOpts.mainAspect" size="small">
              <el-option v-for="a in ['1:1','3:4','4:3','9:16','16:9']" :key="a" :label="a" :value="a" /></el-select></label>
            <label>改图风格<el-select v-model="entry.genOpts.styleId" size="small">
              <el-option v-for="s in styleOptions" :key="s.id" :label="s.name" :value="s.id" /></el-select></label>
          </div>
          <el-input v-model="entry.genOpts.customRequest" placeholder="生图要求(选填)：如 浴室场景、突出增压水流" style="margin:10px 0" />
          <!-- 一键模式(默认):布局+主图 出完自动接生 SKU图+详情图(方案1);关则只生布局+主图,SKU在下方⑤手动 -->
          <el-button
            type="primary" :disabled="!entry.canGenerate || gen.running" :loading="gen.running"
            @click="settings.settings.oneClickGen ? runOneClick(settings.antipriceTemplates) : runLayout()"
          >
            {{ settings.settings.oneClickGen ? '一键生成全部' : '生成布局 + 主图' }}
          </el-button>
          <el-button v-if="gen.running && gen.flowTaskId" @click="stopGen">停止</el-button>
          <span v-if="!entry.canGenerate" class="block-reason">还需：{{ entry.genBlockReason }}</span>
          <el-progress v-if="gen.running" :percentage="gen.progress" style="margin-top:10px" />
          <p v-if="gen.msg" class="gen-msg">{{ gen.msg }}</p>
        </el-card>

        <!-- 5 SKU图 + 定价 -->
        <el-card v-if="plans.length" class="step">
          <template #header>④ SKU 方案 / 定价</template>
          <el-tabs v-model="selectedPlan">
            <el-tab-pane v-for="(p, i) in plans" :key="i" :label="`方案${i + 1}`" :name="i" />
          </el-tabs>
          <div class="price-bar">
            <span>利润率 {{ Math.round(gen.profitRate * 100) }}%</span>
            <el-slider v-model="gen.profitRate" :min="0.2" :max="0.8" :step="0.01" style="width:200px" />
            <el-button size="small" @click="recalcPrice">按此重算</el-button>
            <el-button size="small" @click="exportPricing">导出定价表</el-button>
          </div>
          <el-table :data="currentItems" size="small" max-height="320">
            <el-table-column label="SKU" prop="skuDisplayName" show-overflow-tooltip />
            <el-table-column label="成本" width="90"><template #default="{ row }">{{ fmt(row.cost) }}</template></el-table-column>
            <el-table-column label="拼单价" width="120">
              <template #default="{ row, $index }">
                <el-input v-model.number="row.groupPrice" size="small" @change="onPriceEdit(row, $index)" />
              </template>
            </el-table-column>
          </el-table>
          <el-button style="margin-top:10px" :disabled="gen.running" :loading="gen.running"
            @click="runSkuImages(settings.antipriceTemplates)">生成 SKU 图 + 详情图</el-button>
        </el-card>

        <!-- 6 上新 -->
        <el-card v-if="pageCtx" class="step">
          <template #header>⑤ 上新</template>
          <p class="hint" :class="{ err: targetStoreHint.err }">{{ targetStoreHint.text }}</p>
          <el-checkbox v-model="listing.dryRun">诊断模式（不真实提交，留截图）</el-checkbox>
          <div style="margin-top:10px">
            <el-button type="primary" :disabled="listing.running || !currentItems.length" :loading="listing.running" @click="submitListing">
              {{ listing.dryRun ? '诊断上新' : '正式上新' }}
            </el-button>
          </div>
          <el-alert v-if="listing.msg" :title="listing.msg"
            :type="listing.msgType === 'ok' ? 'success' : listing.msgType === 'err' ? 'error' : 'info'"
            :closable="false" style="margin-top:10px" />
          <pre v-if="listing.log" class="log">{{ listing.log }}</pre>
        </el-card>
      </div>

      <!-- 右栏:预览 -->
      <div class="right">
        <el-card class="preview">
          <template #header>预览{{ pageCtx ? '：' + ctxStore.title : '' }}</template>

          <!-- 白底图:操作(补充/缺图导入/回传快麦)+ 预览,全在右侧 -->
          <div class="psec">
            <div class="psec-t">
              白底图（{{ entry.whites.length }}）· 选主件后自动拉快麦
              <el-button size="small" text @click="pickWhiteFiles">+ 补充白底图</el-button>
            </div>
            <div class="wdrop" :class="{ over: dropOver }"
              @dragover.prevent="dropOver = true" @dragleave.prevent="dropOver = false" @drop.prevent="onDrop">
              <div v-if="entry.whites.length" class="pgrid pgrid-sm">
                <img v-for="(w, i) in entry.whites" :key="'w' + i" :src="whiteThumb(w)" />
              </div>
              <span v-else class="drop-hint">拖白底图到此，或点上方「补充白底图」</span>
            </div>
            <!-- 缺图硬拦提示 + 导入/回传 -->
            <div v-if="entry.whiteCheck.missing.length" class="missing">
              <div class="miss-warn">⚠ 以下编码快麦无白底图，补齐或移除后才能生成（缺件会生出废图）：</div>
              <div v-for="c in entry.whiteCheck.missing" :key="c" class="miss-row">
                <span class="miss-code">缺 {{ c }}</span>
                <el-button size="small" type="warning" plain @click="importForCode(c)">导入白底图</el-button>
                <el-button v-if="entry.importedFor[c]" size="small" :loading="pushing === c" @click="pushWhite(c)">
                  {{ entry.pushedCodes.includes(c) ? '已回传✓' : '回传快麦' }}
                </el-button>
              </div>
            </div>
          </div>

          <el-empty v-if="!pageCtx && !entry.whites.length && !entry.skus.length"
            description="选品、生成后在此预览" :image-size="60" />

          <template v-if="pageCtx">
            <div v-if="pageCtx.visual?.title" class="ptitle">{{ pageCtx.visual.title }}</div>
            <div v-if="(pageCtx.visual?.mainImages || []).length" class="psec">
              <div class="psec-t">主图（{{ pageCtx.visual.mainImages.length }}）</div>
              <div class="pgrid">
                <img v-for="(m, i) in pageCtx.visual.mainImages" :key="'m' + i"
                  :src="'/api/gen/img?ref=' + encodeURIComponent(m)" />
              </div>
            </div>
          </template>
        </el-card>
      </div>
    </div>

    <SkuPicker v-model="pickerOpen" @picked="afterPick" />
  </div>
</template>

<style scoped>
.single { max-width: 1400px; }
.cols { display: flex; gap: 16px; align-items: flex-start; }
.left { flex: 0 0 620px; display: flex; flex-direction: column; gap: 12px; }
.right { flex: 1; position: sticky; top: 16px; }
.step :deep(.el-card__header) { font-weight: 600; }
.chips, .missing { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.chip { margin: 0; }
.missing { margin-top: 10px; }
.wdrop { border: 1px dashed #dcdfe6; border-radius: 6px; padding: 10px; min-height: 40px; }
.wdrop.over { border-color: #409eff; background: #ecf5ff; }
.drop-hint { color: #c0c4cc; font-size: 13px; }
.missing { margin-top: 10px; }
.miss-warn { font-size: 12px; color: #e6a23c; margin-bottom: 6px; }
.miss-row { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.miss-code { font-size: 13px; color: #e6a23c; min-width: 90px; }
.opts { display: flex; flex-wrap: wrap; gap: 12px; }
.opts label { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.opts :deep(.el-select) { width: 100px; }
.block-reason { color: #e6a23c; font-size: 12px; margin-left: 10px; }
.gen-msg { font-size: 13px; color: #606266; margin: 8px 0 0; }
.price-bar { display: flex; align-items: center; gap: 12px; margin: 8px 0; font-size: 13px; }
.hint { font-size: 13px; color: #606266; margin: 0 0 8px; }
.hint.err { color: #e6a23c; }
.log { background: #111827; color: #d1d5db; padding: 10px; border-radius: 4px; font-size: 12px; max-height: 200px; overflow: auto; white-space: pre-wrap; }
.ptitle { font-size: 14px; font-weight: 600; margin-bottom: 10px; }
.psec { margin-bottom: 16px; }
.psec-t { font-size: 13px; color: #909399; margin-bottom: 8px; }
.pgrid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.pgrid img { width: 100%; border: 1px solid #ebeef5; border-radius: 4px; }
.pgrid-sm { grid-template-columns: repeat(4, 1fr); }
.pgrid-sm img { aspect-ratio: 1; object-fit: contain; background: #fff; }
</style>

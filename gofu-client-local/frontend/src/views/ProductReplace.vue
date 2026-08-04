<script setup>
// 【权宜模块·产品替换】独立页面,不复用 ImportProduct.vue,便于后续生图质量起来后整体删除。
// 流程A(单商品):选文件夹(白底+N张参考图) → 抽卡 → 人工筛6 → step2(详情图+SKU图) → 上新。
// 流程B(批量):选「店铺/商品」两级根目录 → 解析分组+店铺精确匹配 → 逐商品顺序走流程A(筛图停等人工)。
import { reactive, computed, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api.js'
import { useContextStore } from '@/stores/context.js'
import { useSettingsStore } from '@/stores/settings.js'
import { useStoresStore } from '@/stores/stores-mgmt.js'
import { useProductReplaceStore } from '@/stores/product-replace.js'
import { useImageDownload } from '@/composables/useImageDownload.js'

const ctxStore = useContextStore()
const settings = useSettingsStore()
const storesStore = useStoresStore()
const prStore = useProductReplaceStore()
const { downloadMany, downloading } = useImageDownload()

const imgUrl = (r) => '/api/gen/img?ref=' + encodeURIComponent(r)

// -------------------- 模式: 单商品 vs 批量 --------------------
const mode = ref('single')  // 'single' | 'batch'

// -------------------- 单商品状态（切页不丢的字段挂 prStore，其余局部）--------------------
const st = reactive({
  white: [], refs: [], running: false, chaining: false,
})
// 本页 context(经 ctxStore 归属登记,别页 load 顶不掉本页预览)
const pageCtx = computed(() => ctxStore.currentFor('product-replace'))
// 筛选区读抽卡全集快照:/pick 覆写 mainImages 后仍完整,链路失败还能重选
const gachaImages = computed(() => prStore.gachaImages)
// 成品预览读 context 实况(选定主图 → 详情图 → SKU 图,随 step2 流式刷入)
const pickedMains = computed(() => (pageCtx.value?.visual?.mainImages || []).filter(Boolean))
const detailImages = computed(() => (pageCtx.value?.visual?.detailImages || []).filter(Boolean))
const skuItems = computed(() => {
  const stc = pageCtx.value?.structure
  return (stc?.plans?.[stc.selectedPlanIndex || 0]?.items || []).filter((it) => it.imgDir)
})
const busy = computed(() => st.running || st.chaining)

// -------------------- 批量状态 --------------------
const batch = reactive({ items: [], curIdx: -1, running: false })
const batchCurItem = computed(() => batch.curIdx >= 0 ? batch.items[batch.curIdx] : null)
const batchGachaImages = computed(() => batchCurItem.value?.gachaImages || [])

// 大图预览列表提到 computed 各算一次。原来写在 v-for 的 :preview-src-list 里，
// 每个格子每次渲染都重算一遍全量数组——抽卡 100 张就是每次 tick 一万次 encodeURIComponent，
// 而抽卡期间轮询每 1.5~2.5s 一跳，页面会明显卡。
const gachaPreview = computed(() => gachaImages.value.map(imgUrl))
const batchGachaPreview = computed(() => batchGachaImages.value.map(imgUrl))
const mainsPreview = computed(() => pickedMains.value.map(imgUrl))
const detailsPreview = computed(() => detailImages.value.map(imgUrl))
const skuPreview = computed(() => skuItems.value.map((x) => imgUrl(x.imgDir)))

// 抽卡全集快照:直接拉 context 取已出的图写进 target。
// 不经 ctxStore —— 批量并行时多个商品各自轮询,谁都不该顶掉用户正在看的那行预览。
// 传 ctx 可复用已拉到的快照,省一次请求。
async function snapGacha(target, contextId, ctx = null) {
  if (!contextId) return
  try {
    const c = ctx || await api.get('/api/context/' + contextId)
    const imgs = (c?.visual?.mainImages || []).filter(Boolean)
    if (imgs.length) target.gachaImages = imgs
  } catch (_) {}
}

// -------------------- 切页恢复 --------------------
onMounted(async () => {
  // 单商品：切页回来把本页 context 重新调回 current(否则被别页顶掉,成品预览空)
  const ownId = ctxStore.ownedId('product-replace') || prStore.contextId
  if (ownId) {
    try { await ctxStore.adopt('product-replace', ownId) } catch (_) {}
  }
  // 批量：从 store 恢复元数据（base64 已丢，pending 行白底/参考需重新解析）
  if (prStore.batchItems.length) {
    batch.items = prStore.batchItems.map((item) => ({
      ...item, white: [], refs: [],
      // 抽卡还没起跑的行,图片 base64 随刷新丢了 → 必须重新解析才能跑
      needReparse: item.status === 'pending',
    }))
    batch.curIdx = prStore.batchCurIdx
    if (batch.curIdx >= 0 && batch.items[batch.curIdx]?.contextId) {
      try { await ctxStore.adopt('product-replace', batch.items[batch.curIdx].contextId) } catch (_) {}
    }
  }
})

function fileToB64(f) {
  return new Promise((resolve) => {
    const rd = new FileReader()
    rd.onload = () => resolve(String(rd.result).split(',')[1] || '')
    rd.readAsDataURL(f)
  })
}

// -------------------- 单商品: 选文件夹 --------------------
function pickFolder() {
  const inp = document.createElement('input')
  inp.type = 'file'; inp.multiple = true; inp.webkitdirectory = true
  inp.onchange = () => processSingleFiles([...inp.files])
  inp.click()
}

// 单商品选层校验+白底/参考归类(复用白底/参考关键字判定逻辑)
async function processSingleFiles(files) {
  const imgs = files.filter((f) => f.type && f.type.startsWith('image/'))
  if (!imgs.length) { prStore.msg = '该文件夹没有图片'; prStore.msgType = 'err'; return }
  const white = [], refs = []
  const productPaths = new Set()
  for (const f of imgs) {
    const parts = (f.webkitRelativePath || f.name).split('/')
    const dir = (parts.length >= 2 ? parts[parts.length - 2] : '').toLowerCase()
    const isWhite = dir.includes('白底') || dir.includes('white')
    const isRef = dir.includes('参考') || dir.includes('ref')
    if (!isWhite && !isRef) continue
    const b64 = await fileToB64(f)
    const ext = f.name.toLowerCase().endsWith('.png') ? 'png' : 'jpg'
    const rec = { name: parts[parts.length - 1], b64, ext }
    if (isWhite) white.push(rec); else refs.push(rec)
    productPaths.add(parts.slice(0, parts.length - 2).join('/'))
  }
  if (productPaths.size > 1) {
    st.white = []; st.refs = []
    prStore.msg = `❌ 选到的文件夹里有 ${productPaths.size} 个不同商品(检测到多组「白底/参考」子目录),会把不同商品的图混在一起。请选到单个商品的文件夹（如「花洒喷头-GF-112-银色花洒」），不要选店铺层或更外层。`
    prStore.msgType = 'err'; return
  }
  const productPath = [...productPaths][0] || (imgs[0].webkitRelativePath || '').split('/')[0] || ''
  prStore.folderName = productPath.split('/').pop() || ''
  st.white = white; st.refs = refs
  if (!white.length || !refs.length) {
    prStore.msg = `❌ 结构不完整（白底${white.length}·参考${refs.length}）。商品文件夹内需含名字带「白底」的子目录(放1张或多张产品白底图) + 名字带「参考」的子目录(放N张构图参考图)。`
    prStore.msgType = 'err'; return
  }
  const warns = []
  if (!prStore.folderName.includes('-')) warns.push(`文件夹名「${prStore.folderName}」没按「品类-主件名」命名（缺"-"），品类/SKU反推会不准。`)
  if (!prStore.count) prStore.count = 100
  const perWhiteApprox = Math.floor(prStore.count / white.length)
  prStore.msg = `✓ 已读「${prStore.folderName}」（白底${white.length}张 × 参考${refs.length}张）。${warns.join(' ')}`
    + (white.length > 1 ? `${white.length} 张白底各自出图，约每张白底分到 ${perWhiteApprox} 张。` : '')
    + `点「开始抽卡」凑${prStore.count}张。`
  prStore.msgType = warns.length ? 'err' : 'ok'
}

// -------------------- 批量: 选根目录 --------------------
function pickBatchFolder() {
  const inp = document.createElement('input')
  inp.type = 'file'; inp.multiple = true; inp.webkitdirectory = true
  inp.onchange = () => parseBatchFiles([...inp.files])
  inp.click()
}

// 解析两级结构: 根/店铺名/商品名/白底图|参考图/文件
async function parseBatchFiles(files) {
  const imgs = files.filter((f) => f.type && f.type.startsWith('image/'))
  if (!imgs.length) { ElMessage.error('选中的文件夹里没有图片'); return }
  // 按 (店铺名, 商品名) 分组
  const map = new Map()  // key='shopName///productName'
  for (const f of imgs) {
    const parts = (f.webkitRelativePath || f.name).split('/')
    if (parts.length < 5) continue   // 根/店铺/商品/子目录/文件 至少5层
    const shopName = parts[1]
    const productName = parts[2]
    const subDir = parts[3].toLowerCase()
    const isWhite = subDir.includes('白底') || subDir.includes('white')
    const isRef = subDir.includes('参考') || subDir.includes('ref')
    if (!isWhite && !isRef) continue
    const key = shopName + '///' + productName
    if (!map.has(key)) map.set(key, { shopName, productName, white: [], refs: [] })
    const g = map.get(key)
    const b64 = await fileToB64(f)
    const ext = f.name.toLowerCase().endsWith('.png') ? 'png' : 'jpg'
    const rec = { name: parts[parts.length - 1], b64, ext }
    if (isWhite) g.white.push(rec); else g.refs.push(rec)
  }
  if (!map.size) {
    ElMessage.error('未检测到「店铺/商品/白底图|参考图」两级结构。请确认文件夹层级：根目录下第1层=店铺名，第2层=商品名，第3层=白底图/参考图子目录。')
    return
  }
  await storesStore.loadStores()
  const items = []
  for (const [, g] of map) {
    let status = 'pending', profile = '', warnReason = ''
    const matched = storesStore.list.filter((s) => s.name === g.shopName)
    if (matched.length === 0) { status = 'warn'; warnReason = `未找到同名店铺「${g.shopName}」——请核对店铺管理页登记名是否完全一致，改好后重新解析` }
    else if (matched.length > 1) { status = 'warn'; warnReason = `匹配到 ${matched.length} 个同名店铺「${g.shopName}」——请在店铺管理页确保名称唯一后重新解析` }
    else if (!matched[0].loggedIn) { status = 'warn'; warnReason = `店铺「${g.shopName}」未登录——请先在店铺管理页登录后重新解析` }
    else { profile = matched[0].profile }
    if (!g.white.length || !g.refs.length) { status = 'warn'; warnReason = `结构不完整（白底${g.white.length}·参考${g.refs.length}）——请检查文件夹内是否有「白底图」「参考图」子目录` }
    items.push({ shopName: g.shopName, productName: g.productName, white: g.white, refs: g.refs,
                 status, profile, warnReason, contextId: '', cloudTaskId: '',
                 pct: 0, phase: '', gachaDone: false, picked: [], gachaImages: [], chainLog: '',
                 needReparse: false })
  }
  batch.items = items
  batch.curIdx = -1
  batch.running = false
  // 持久化元数据到 store（base64 不存）
  prStore.batchItems = items.map(({ white, refs, ...rest }) => ({ ...rest, refsCount: refs.length, whiteCount: white.length }))
  prStore.batchCurIdx = -1
  const pending = items.filter((x) => x.status === 'pending').length
  const warned = items.filter((x) => x.status === 'warn').length
  if (warned > 0) ElMessage.warning(`解析完成：${items.length} 个商品，其中 ${warned} 个有警告（橙色项请按提示修正后重新解析），${pending} 个待处理`)
  else ElMessage.success(`解析完成：${items.length} 个商品，全部待处理，可点「开始批量」`)
}

// 批量启动：并行对所有 pending 商品同时抽卡，抽完各自进 waiting-pick 等用户筛图
async function startBatch() {
  if (batch.running) return
  // needReparse 行的图片 base64 已随刷新丢失,跑必失败,排除在外
  const pendingIdxs = batch.items.map((x, i) => (x.status === 'pending' && !x.needReparse) ? i : -1).filter((i) => i >= 0)
  if (!pendingIdxs.length) {
    const warned = batch.items.filter((x) => x.status === 'warn').length
    const stale = batch.items.filter((x) => x.needReparse).length
    if (stale > 0) ElMessage.warning(`有 ${stale} 个商品的图片数据已随页面刷新丢失，请重新选根目录解析后再跑`)
    else ElMessage.warning(warned > 0 ? `没有待处理商品，有 ${warned} 个警告项需先按提示修正后重新解析` : '没有待处理的商品了')
    return
  }
  batch.running = true
  // 并行启动所有 pending 商品抽卡，不等待完成
  Promise.all(pendingIdxs.map((idx) => runBatchItem(idx))).then(() => {
    batch.running = false
    const done = batch.items.filter((x) => x.status === 'done').length
    const failed = batch.items.filter((x) => x.status === 'failed').length
    const waiting = batch.items.filter((x) => x.status === 'waiting-pick').length
    if (waiting > 0) ElMessage.success(`抽卡全部完成，${waiting} 个商品等待筛图`)
    else ElMessage.success(`批量全部跑完：成功 ${done}，失败 ${failed}`)
  })
}

// 对单个批量商品跑抽卡(自动)，抽完进 waiting-pick
async function runBatchItem(idx) {
  const item = batch.items[idx]
  if (!item) return
  item.status = 'running-gacha'; item.pct = 0; item.phase = '启动中…'; item.chainLog = ''
  try {
    const started = await api.post('/api/product-replace/start', {
      folderName: item.productName, white: item.white, refs: item.refs, count: prStore.count || 100,
    })
    if (started.error) throw new Error(started.error)
    const info = await pollBatchStart(idx, started.replaceId)
    if (!info.contextId || !info.cloudTaskId) throw new Error('未返回 contextId/cloudTaskId')
    item.contextId = info.contextId
    item.cloudTaskId = info.cloudTaskId
    item.recognized = info.recognized || null
    prStore.syncBatchItem(idx, item)
    await pollBatchGacha(idx, info.cloudTaskId)
    item.status = 'waiting-pick'
    item.phase = `抽卡完成，请勾选最多 ${item.refs.length || item.refsCount} 张后点「确认并上新」。`
    prStore.syncBatchItem(idx, item)
    // 自动切换筛图面板到第一个完成的商品
    if (batch.curIdx < 0 || !['waiting-pick', 'running-list'].includes(batch.items[batch.curIdx]?.status)) {
      await selectBatchItem(idx)
    }
  } catch (e) {
    item.status = 'failed'; item.phase = '抽卡失败：' + e.message
  }
}

// 切换当前筛图商品：加载该商品的 context 到 ctxStore
async function selectBatchItem(idx) {
  const item = batch.items[idx]
  if (!item) return
  batch.curIdx = idx
  prStore.batchCurIdx = idx
  if (item.contextId) {
    try { await ctxStore.adopt('product-replace', item.contextId) } catch (_) {}
  }
}

async function pollBatchStart(idx, replaceId) {
  const item = batch.items[idx]
  while (true) {
    await new Promise((r) => setTimeout(r, 1200))
    let t
    try { t = await api.get('/api/product-replace/progress/' + replaceId) } catch (_) { continue }
    item.pct = Math.min(t.pct || 0, 60); item.phase = t.phase || '处理中…'
    if (t.done) { if (t.error) throw new Error(t.error); return t }
  }
}

async function pollBatchGacha(idx, taskId) {
  const item = batch.items[idx]
  while (true) {
    // 08.03 #1/#3:同单商品,2500→1200ms,让首张落地后尽快显示
    await new Promise((r) => setTimeout(r, 1200))
    let t
    try { t = await api.get('/api/flow/task/' + taskId) } catch (_) { continue }
    const done = t.progress || 0, total = t.total || prStore.count
    item.pct = 60 + Math.round(38 * done / Math.max(1, total))
    // 07.31: progress只是已尝试数,跟成功数脱钩(欠费/超时时全部失败也照样跑到接近total)。
    const succ = t.successCount ?? 0
    item.phase = `抽卡替换中 已尝试${done}/${total}(成功${succ})…` + (done > 0 && succ === 0 ? ' ⚠ 全部失败,请检查生图服务/账户余额' : '')
    // 每行独立快照(不经 ctxStore,并行的多个商品互不干扰),筛选区边生边出
    await snapGacha(item, item.contextId)
    if (t.status === 'done' || t.status === 'error') {
      if (t.status === 'error') throw new Error(t.error || `云端生图失败（已尝试${done}/${total}张，成功${succ}张）——若账户欠费请充值 api.linapi.net`)
      await snapGacha(item, item.contextId)   // 收尾补齐最后几张
      prStore.syncBatchItem(idx, item)
      item.pct = 100; item.gachaDone = true; return
    }
  }
}

function toggleBatch(key) {
  const item = batchCurItem.value; if (!item) return
  const maxPick = item.refs.length || item.refsCount || 6
  const i = item.picked.indexOf(key)
  if (i >= 0) item.picked.splice(i, 1)
  else {
    if (item.picked.length >= maxPick) { ElMessage.warning(`最多选 ${maxPick} 张（与参考图张数一致）`); return }
    item.picked.push(key)
  }
  prStore.syncBatchItem(batch.curIdx, item)   // 勾选落盘,刷新/切页不丢
}
const isBatchPicked = (key) => batchCurItem.value?.picked.includes(key) ?? false

async function confirmBatchAndList() {
  const item = batchCurItem.value; if (!item) return
  if (!item.picked.length) { ElMessage.warning('请先勾选主图'); return }
  item.status = 'running-list'; item.chainLog = ''
  const idx = batch.curIdx
  const log = (msg) => { item.chainLog = msg }
  try {
    await doChain({ contextId: item.contextId, pickedKeys: item.picked, storeProfile: item.profile, folderName: item.productName, recognized: item.recognized, log })
    item.status = 'done'; item.chainLog = (item.chainLog || '') + '\n✓ 全自动完成。'
  } catch (e) {
    item.status = 'failed'; item.chainLog = (item.chainLog || '') + '\n✗ 失败：' + e.message
  }
  prStore.syncBatchItem(idx, item)
  // 自动切到下一个 waiting-pick 商品（如果有）
  const nextIdx = batch.items.findIndex((x, i) => i !== batch.curIdx && x.status === 'waiting-pick')
  if (nextIdx >= 0) await selectBatchItem(nextIdx)
  else {
    const done = batch.items.filter((x) => x.status === 'done').length
    const failed = batch.items.filter((x) => x.status === 'failed').length
    if (batch.items.every((x) => ['done', 'failed', 'warn', 'skipped'].includes(x.status)))
      ElMessage.success(`批量全部跑完：成功 ${done}，失败 ${failed}`)
  }
}

// -------------------- 共用抽卡链(单商品版) --------------------
function pickFolder2() { pickFolder() }   // alias，template 里用

async function startGacha() {
  if (!st.white.length || !st.refs.length) return
  st.running = true; prStore.gachaDone = false; prStore.picked = []; prStore.gachaImages = []; prStore.cloudTaskId = ''
  prStore.refsCount = st.refs.length   // 快照:抽卡完成后 refs 可能被清,用快照保住上限数字
  prStore.pct = 0; prStore.phase = '启动中…'; prStore.msg = ''; prStore.msgType = ''
  try {
    const started = await api.post('/api/product-replace/start', {
      folderName: prStore.folderName, white: st.white, refs: st.refs, count: prStore.count,
    })
    if (started.error) throw new Error(started.error)
    if (!started.replaceId) throw new Error('未返回 replaceId')
    const info = await pollStart(started.replaceId)
    if (!info.contextId || !info.cloudTaskId) throw new Error('抽卡启动未返回 contextId/cloudTaskId')
    prStore.recognizedCategory = info.recognized?.category || ''
    prStore.recognizedProductName = info.recognized?.productName || ''
    prStore.recognizedSkus = info.recognized?.skus || []
    await ctxStore.adopt('product-replace', info.contextId)
    prStore.cloudTaskId = info.cloudTaskId
    prStore.contextId = info.contextId
    await pollCloudGacha(info.cloudTaskId)
  } catch (e) {
    prStore.msg = '抽卡失败：' + e.message; prStore.msgType = 'err'
  } finally {
    st.running = false
  }
}

async function pollStart(replaceId) {
  while (true) {
    await new Promise((r) => setTimeout(r, 1200))
    let t
    try { t = await api.get('/api/product-replace/progress/' + replaceId) } catch (_) { continue }
    prStore.pct = Math.min(t.pct || 0, 60); prStore.phase = t.phase || '处理中…'
    if (t.done) { if (t.error) throw new Error(t.error); return t }
  }
}

async function pollCloudGacha(taskId) {
  while (true) {
    // 08.03 #1/#3:2500ms→1200ms。首张落地后要让用户尽快看到,轮询太慢会显得"图出了但界面没动"。
    await new Promise((r) => setTimeout(r, 1200))
    let t
    try { t = await api.get('/api/flow/task/' + taskId) } catch (_) { continue }
    const done = t.progress || 0, total = t.total || prStore.count
    prStore.pct = 60 + Math.round(38 * done / Math.max(1, total))
    const succ = t.successCount ?? 0
    prStore.phase = `抽卡替换中 已尝试${done}/${total}(成功${succ})…` + (done > 0 && succ === 0 ? ' ⚠ 全部失败,请检查生图服务/账户余额' : '')
    // 本页还持有 current 就顺带刷 ctxStore(复用同一份快照,不多打一次请求);
    // 切走了只更新全集快照,不去顶别页的 current。
    if (prStore.contextId) {
      if (ctxStore.origin === 'product-replace') {
        try { await ctxStore.load(prStore.contextId) } catch (_) {}
        await snapGacha(prStore, prStore.contextId, ctxStore.current)
      } else {
        await snapGacha(prStore, prStore.contextId)
      }
    }
    if (t.status === 'done' || t.status === 'error') {
      if (t.status === 'error') throw new Error(t.error || `云端生图失败（已尝试${done}/${total}张，成功${succ}张）——若账户欠费请充值 api.linapi.net`)
      await snapGacha(prStore, prStore.contextId)   // 收尾补齐最后几张
      prStore.pct = 100; prStore.gachaDone = true
      prStore.msg = `✓ 抽卡完成，共 ${prStore.gachaImages.length} 张。请勾选后点「确认并全自动上新」。`
      prStore.msgType = 'ok'; return
    }
  }
}

async function cancelGacha() {
  if (!prStore.cloudTaskId) return
  try { await api.post('/api/flow/cancel/' + prStore.cloudTaskId, {}) } catch (_) {}
  prStore.msg = '已请求停止抽卡。'; prStore.msgType = ''
}

function toggle(key) {
  const maxPick = prStore.refsCount || st.refs.length
  const i = prStore.picked.indexOf(key)
  if (i >= 0) prStore.picked.splice(i, 1)
  else {
    if (prStore.picked.length >= maxPick) { ElMessage.warning(`最多选 ${maxPick} 张（与参考图张数一致）`); return }
    prStore.picked.push(key)
  }
}
const isPicked = (key) => prStore.picked.includes(key)

// -------------------- 确认筛选 → step2(详情+SKU图) → 上新 --------------------
async function confirmAndList() {
  if (prStore.picked.length === 0) { ElMessage.warning('请先勾选主图'); return }
  // 用本页自己的 contextId(不用 ctxStore.contextId —— 它可能被别页顶掉了)
  if (!prStore.contextId) { ElMessage.error('缺 contextId'); return }
  st.chaining = true; prStore.chainLog = ''
  const log = (msg) => { prStore.chainLog = msg }
  try {
    await doChain({ contextId: prStore.contextId, pickedKeys: prStore.picked, storeProfile: storesStore.targetProfile || '', folderName: prStore.folderName, recognized: { category: prStore.recognizedCategory, productName: prStore.recognizedProductName, skus: prStore.recognizedSkus }, log })
    prStore.msg = '✓ 全自动完成：抽卡→筛图→详情/SKU图→方案→定价→上新成功。'; prStore.msgType = 'ok'
  } catch (e) {
    prStore.chainLog = '✗ 失败：' + e.message; prStore.msg = '全自动链失败：' + e.message; prStore.msgType = 'err'
  } finally {
    st.chaining = false
  }
}

// doChain: pick → generate-layout → step2(详情图+SKU图) → from-context
// log 回调写各自的 chainLog，避免批量模式下日志错写到单商品 prStore.chainLog
async function doChain({ contextId, pickedKeys, storeProfile, folderName, recognized, log }) {
  // 1) 筛选覆写
  log('① 覆写选定主图…')
  const p = await api.post('/api/product-replace/pick', { contextId, keys: pickedKeys })
  if (p.error) throw new Error(p.error)

  // 2) 生成布局(SKU方案+AI标题+算价)
  log('② 生成方案/标题/定价…')
  const rec = recognized || {}
  const started = await api.post('/api/semi-auto/generate-layout', {
    contextId,
    category: rec.category || ctxStore.category || '',
    productName: rec.productName || ctxStore.mainItem || folderName || '',
    skus: rec.skus || [], sku: [],
    planCount: settings.settings.defaultPlanCount || 1,
    profitRate: 0,
  })
  if (started.error) throw new Error(started.error)
  if (started.importId) {
    const genResult = await pollGen(started.importId, log)
    const skuCount = genResult?.skuPlanCount ?? 0
    if (skuCount === 0) throw new Error('快麦未找到对应商品编码，无法生成SKU方案。请在快麦ERP补充该商品白底图编码后重试，或到单品页手动选品上新。')
  }
  await ctxStore.adopt('product-replace', contextId)

  // 3) step2: 生成详情图 + 逐SKU专属图
  log('③ 生成详情图 + SKU图…')
  const planIdx = ctxStore.current?.structure?.selectedPlanIndex || 0
  const d2 = await api.post('/api/flow/step2', {
    contextId, planIndex: planIdx,
    accWhiteImages: [], templateId: '',
    genDetail: true, genSku: true, skuOnlyMissing: true,
  })
  if (d2.error) throw new Error(d2.error)
  if (!d2.taskId) throw new Error('step2 未返回 taskId')
  await pollStep2(d2.taskId, d2.total || 0, log, contextId)
  await ctxStore.adopt('product-replace', contextId)

  // 4) from-context 上新
  log('④ 上新中…')
  if (settings.settings.confirmBeforeListing && !confirm('将真实提交上新到拼多多，确认继续?')) {
    log('已取消上新（方案/定价已生成，可到单品页手动上新）。'); return
  }
  const d = await api.post('/api/listing/from-context', {
    contextId, planIndex: planIdx, dryRun: false, brand: '', storeProfile: storeProfile || '',
  })
  if (d.error) throw new Error(d.error)
  if (!d.taskId) throw new Error('上新未返回 taskId')
  await pollListing(d.taskId, log, 0)
}

async function pollGen(importId, log) {
  while (true) {
    await new Promise((r) => setTimeout(r, 1500))
    let t
    try { t = await api.get('/api/semi-auto/import-progress/' + importId) } catch (_) { continue }
    log(`② 生成中 ${t.pct || 0}% · ${t.phase || ''}`)
    if (t.done) { if (t.error) throw new Error(t.error); return t.result }
  }
}

async function pollStep2(taskId, total, log, contextId) {
  for (let tries = 0; tries < 1200; tries++) {
    await new Promise((r) => setTimeout(r, 2000))
    let t
    try { t = await api.get('/api/flow/task/' + taskId) } catch (_) { continue }
    log(`③ 详情/SKU图 ${t.progress || 0}/${total || '?'}…`)
    // 成品预览区流式出图:详情/SKU 每出一张就刷进来(本页还持有 current 才刷)
    if (contextId && ctxStore.origin === 'product-replace') {
      try { await ctxStore.load(contextId) } catch (_) {}
    }
    if (t.status === 'done') return
    if (t.status === 'error') throw new Error(t.error || 'step2 失败')
  }
  throw new Error('step2 轮询超时')
}

async function pollListing(taskId, log, tries) {
  if (tries > 1200) { log('✗ 上新轮询超时'); return }
  const t = await api.get('/api/task/' + taskId)
  log('④ 上新日志:\n' + (t.results || []).map((x) => x.message || '').join('\n'))
  const last = [...(t.results || [])].reverse().find((x) => ['captcha', 'done', 'error'].includes(x.type))
  if (t.status === 'running' && last?.type === 'captcha') log('④ 上新日志:\n' + (t.results || []).map((x) => x.message || '').join('\n') + '\n🛑 需人工完成滑块验证，脚本会自动继续')
  if (t.status === 'running') { await new Promise((r) => setTimeout(r, 1500)); return pollListing(taskId, log, tries + 1) }
  if (t.status !== 'done') throw new Error('上新未成功：' + t.status)
}

const statusLabel = (s) => ({ pending: '待处理', warn: '⚠ 警告', 'running-gacha': '抽卡中', 'waiting-pick': '待筛图', 'running-list': '上新中', done: '✓ 成功', failed: '✗ 失败' })[s] || s
const statusType = (s) => ({ pending: '', warn: 'warning', 'running-gacha': 'warning', 'waiting-pick': 'primary', 'running-list': 'warning', done: 'success', failed: 'danger' })[s] || ''
</script>

<template>
  <div class="preplace">
    <h2>产品替换 <span class="tag">权宜·抽卡换品</span></h2>
    <el-alert type="info" :closable="false" show-icon class="intro">
      生图质量的临时手段：传入 <b>1 张或多张产品白底图</b>(多张=多SKU，各自出各自的图) + <b>N 张构图参考图</b>，
      程序对每张参考图只替换其中的产品主体、尽量保留构图，靠抽卡随机性凑满 {{ prStore.count }} 张，
      人工筛 6 张后全自动出方案/定价/详情图/SKU图/上新。<br>
      单商品模式：<b>选到商品层文件夹</b>（内含「白底图」「参考图」两个子目录）。
      批量模式：选含「<b>店铺名/商品名</b>」两层结构的根目录，程序自动分组按顺序跑完。
    </el-alert>

    <div class="mode-bar">
      <el-radio-group v-model="mode" :disabled="busy || batch.running" size="small">
        <el-radio-button value="single">单商品</el-radio-button>
        <el-radio-button value="batch">批量</el-radio-button>
      </el-radio-group>
    </div>

    <!-- ===== 单商品模式 ===== -->
    <template v-if="mode === 'single'">
      <el-card class="step">
        <template #header>① 导入 + 抽卡</template>
        <div class="actions">
          <el-button :disabled="busy" @click="pickFolder">📁 选择文件夹</el-button>
          <span class="cnt">
            抽卡张数
            <el-input-number v-model="prStore.count" :min="1" :max="100" :step="1" :value-on-clear="100" :disabled="busy" size="small" controls-position="right" style="width: 110px" />
            <span class="cnt-hint">（自测先用小数如 15，正式凑量用 100）</span>
          </span>
          <el-button type="primary" :disabled="!st.white.length || !st.refs.length || busy" :loading="st.running" @click="startGacha">
            {{ st.running ? '抽卡中…' : `开始抽卡（共 ${prStore.count} 张）` }}
          </el-button>
          <el-button v-if="st.running && prStore.cloudTaskId" @click="cancelGacha">停止</el-button>
        </div>
        <el-progress v-if="st.running || prStore.pct > 0" :percentage="prStore.pct" :status="prStore.gachaDone ? 'success' : ''" />
        <p v-if="prStore.phase" class="phase">{{ prStore.phase }}</p>
        <p v-if="prStore.msg" :class="['msg', prStore.msgType]">{{ prStore.msg }}</p>
      </el-card>

      <!-- ② 筛选主图:抽卡出的全集,边生边筛。点图=开大图,左上复选框=勾选 -->
      <el-card v-if="gachaImages.length || st.running" class="step">
        <template #header>
          ② 筛选主图（已选 {{ prStore.picked.length }} / {{ prStore.refsCount || st.refs.length }}，共 {{ gachaImages.length }} 张）
          <span v-if="st.running" class="gen-hint">· 生成中…</span>
        </template>
        <!-- 08.03 #1/#3:实测首张落地要 40~60 秒(N 张全并发跑,谁都没先完成),这段时间前端只能空着。
             不写清楚就像"卡住/没流式"。写明预期 + 已尝试数,让用户知道在跑。 -->
        <div v-if="!gachaImages.length" class="gen-empty">
          生成中，首张约需 40~60 秒（{{ prStore.count }} 张并行跑，出图后会逐张出现在这里）…
          <span v-if="prStore.phase" style="display:block;margin-top:6px">{{ prStore.phase }}</span>
        </div>
        <div v-else class="grid">
          <div v-for="(m, i) in gachaImages" :key="i" :class="['cell', { on: isPicked(m) }]">
            <el-image :src="imgUrl(m)" fit="cover" loading="lazy" :preview-src-list="gachaPreview"
              :initial-index="i" preview-teleported hide-on-click-modal />
            <el-checkbox class="pickbox" :model-value="isPicked(m)" @click.stop @change="toggle(m)" />
            <span v-if="isPicked(m)" class="badge">{{ prStore.picked.indexOf(m) + 1 }}</span>
          </div>
        </div>
        <div class="actions foot">
          <el-button type="success" :disabled="prStore.picked.length === 0 || busy" :loading="st.chaining" @click="confirmAndList">
            {{ st.chaining ? '处理中…' : `确认并全自动上新（${prStore.picked.length} 张）` }}
          </el-button>
          <el-button :disabled="!prStore.picked.length" :loading="downloading"
            @click="downloadMany(prStore.picked, '产品替换-抽卡')">
            下载选中（{{ prStore.picked.length }} 张）
          </el-button>
          <el-button :disabled="!gachaImages.length" :loading="downloading"
            @click="downloadMany(gachaImages, '产品替换-抽卡')">
            下载全部（{{ gachaImages.length }} 张）
          </el-button>
        </div>
        <pre v-if="prStore.chainLog" class="chainlog">{{ prStore.chainLog }}</pre>
      </el-card>

      <!-- ③ 成品预览:确认后才出现。选定主图 → 详情图 → SKU 图,随生成流式刷入 -->
      <el-card v-if="st.chaining || pickedMains.length || detailImages.length" class="step">
        <template #header>③ 成品预览<span v-if="st.chaining" class="gen-hint">· 生成中…</span></template>
        <!-- 上新标题:加标签明示这是要提交到平台的标题 -->
        <div class="ptitle-row">
          <span class="ptitle-l">上新标题</span>
          <span v-if="pageCtx?.visual?.title" class="ptitle">{{ pageCtx.visual.title }}</span>
          <span v-else class="ptitle-empty">未生成（②生成方案/标题时出）</span>
        </div>
        <div v-if="pickedMains.length" class="psec">
          <div class="psec-t">选定主图（{{ pickedMains.length }}）· 点击看大图
            <el-button link type="primary" size="small" :loading="downloading"
              @click="downloadMany(pickedMains, '主图')">下载全部</el-button>
          </div>
          <div class="pgrid">
            <el-image v-for="(m, i) in pickedMains" :key="'m' + i" :src="imgUrl(m)"
              :preview-src-list="mainsPreview" :initial-index="i" fit="contain" preview-teleported hide-on-click-modal />
          </div>
        </div>
        <div v-if="detailImages.length" class="psec">
          <div class="psec-t">详情图（{{ detailImages.length }}）· 点击看大图
            <el-button link type="primary" size="small" :loading="downloading"
              @click="downloadMany(detailImages, '详情图')">下载全部</el-button>
          </div>
          <div class="pgrid">
            <el-image v-for="(d, i) in detailImages" :key="'d' + i" :src="imgUrl(d)"
              :preview-src-list="detailsPreview" :initial-index="i" fit="contain" preview-teleported hide-on-click-modal />
          </div>
        </div>
        <div v-if="skuItems.length" class="psec">
          <div class="psec-t">SKU 图（{{ skuItems.length }}）· 点击看大图
            <el-button link type="primary" size="small" :loading="downloading"
              @click="downloadMany(skuItems.map((x) => x.imgDir), 'SKU图')">下载全部</el-button>
          </div>
          <div class="pgrid">
            <el-image v-for="(it, i) in skuItems" :key="'s' + i" :src="imgUrl(it.imgDir)"
              :preview-src-list="skuPreview" :initial-index="i"
              fit="contain" preview-teleported hide-on-click-modal :title="it.skuDisplayName || it.name" />
          </div>
        </div>
        <div v-if="st.chaining && !pickedMains.length && !detailImages.length" class="gen-empty">等待成品图生成中…</div>
      </el-card>
    </template>

    <!-- ===== 批量模式 ===== -->
    <template v-else>
      <el-card class="step">
        <template #header>① 选根目录 + 解析</template>
        <div class="actions">
          <el-button :disabled="batch.running" @click="pickBatchFolder">📁 选择根目录（店铺/商品两级）</el-button>
          <span class="cnt">
            抽卡张数
            <el-input-number v-model="prStore.count" :min="1" :max="100" :step="1" :value-on-clear="100" :disabled="batch.running" size="small" controls-position="right" style="width: 110px" />
          </span>
          <el-button type="primary" :disabled="!batch.items.some((x) => x.status === 'pending') || batch.running" @click="startBatch">
            {{ batch.running ? '批量运行中…' : '开始批量' }}
          </el-button>
        </div>
        <p class="phase cnt-hint" v-if="batch.items.length">
          共 {{ batch.items.length }} 个商品 · 待处理 {{ batch.items.filter((x) => x.status === 'pending').length }}
          · 警告 {{ batch.items.filter((x) => x.status === 'warn').length }}
          · 完成 {{ batch.items.filter((x) => x.status === 'done').length }}
          · 失败 {{ batch.items.filter((x) => x.status === 'failed').length }}
        </p>
        <div v-if="batch.items.length" class="batch-list">
          <div v-for="(item, i) in batch.items" :key="i"
               :class="['batch-row', item.status, { active: batch.curIdx === i }]"
               :style="item.contextId ? 'cursor:pointer' : ''"
               @click="item.contextId ? selectBatchItem(i) : null">
            <el-tag :type="statusType(item.status)" size="small">{{ statusLabel(item.status) }}</el-tag>
            <span class="bname">{{ item.shopName }} / {{ item.productName }}</span>
            <span class="binfo">白底{{ item.white.length || item.whiteCount || 0 }}·参考{{ item.refs.length || item.refsCount || 0 }}</span>
            <span v-if="item.status === 'waiting-pick' && batch.curIdx !== i" class="bwarn" style="color:#409eff">← 点击筛图</span>
            <span v-if="item.needReparse" class="bwarn">图片数据已随页面刷新丢失，请重新选根目录解析</span>
            <span v-if="item.warnReason" class="bwarn">{{ item.warnReason }}</span>
            <el-progress v-if="['running-gacha','waiting-pick','running-list'].includes(item.status)"
              :percentage="item.pct" :status="item.gachaDone ? 'success' : ''" style="flex:1;max-width:200px" />
          </div>
        </div>
      </el-card>

      <!-- ② 当前商品筛图:抽卡中就能边生边筛。点图=开大图,左上复选框=勾选 -->
      <!-- 08.03 修 #2:done/failed 也要能点回来看(原来只有 running-gacha/waiting-pick/running-list
           三态显示,上新成功后点回该行整个面板消失,回不去) -->
      <el-card v-if="batchCurItem && batchGachaImages.length" class="step">
        <template #header>
          ② 筛选主图 — {{ batchCurItem.shopName }} / {{ batchCurItem.productName }}
          （已选 {{ batchCurItem.picked.length }} / {{ batchCurItem.refs.length || batchCurItem.refsCount || 6 }}，共 {{ batchGachaImages.length }} 张）
          <span v-if="batchCurItem.status === 'running-gacha'" class="gen-hint">· 生成中…</span>
        </template>
        <p class="phase">{{ batchCurItem.phase }}</p>
        <div v-if="!batchGachaImages.length" class="gen-empty">
          生成中，首张约需 40~60 秒（并行跑，出图后会逐张出现在这里）…
        </div>
        <div v-else class="grid">
          <div v-for="(m, i) in batchGachaImages" :key="i" :class="['cell', { on: isBatchPicked(m) }]">
            <el-image :src="imgUrl(m)" fit="cover" loading="lazy" :preview-src-list="batchGachaPreview"
              :initial-index="i" preview-teleported hide-on-click-modal />
            <el-checkbox class="pickbox" :model-value="isBatchPicked(m)" @click.stop @change="toggleBatch(m)" />
            <span v-if="isBatchPicked(m)" class="badge">{{ batchCurItem.picked.indexOf(m) + 1 }}</span>
          </div>
        </div>
        <div class="actions foot">
          <el-button v-if="batchCurItem.status !== 'done'" type="success"
            :disabled="batchCurItem.picked.length === 0 || batchCurItem.status !== 'waiting-pick'"
            :loading="batchCurItem.status === 'running-list'" @click="confirmBatchAndList">
            {{ batchCurItem.status === 'running-list' ? '处理中…' : `确认并上新（${batchCurItem.picked.length} 张）` }}
          </el-button>
          <el-button :disabled="!batchGachaImages.length" :loading="downloading"
            @click="downloadMany(batchCurItem.picked.length ? batchCurItem.picked : batchGachaImages, '批量抽卡')">
            下载{{ batchCurItem.picked.length ? `选中（${batchCurItem.picked.length} 张）` : `全部（${batchGachaImages.length} 张）` }}
          </el-button>
          <span v-if="batchCurItem.status === 'running-gacha'" class="cnt-hint">抽卡还在跑，可先勾选，跑完再点确认</span>
          <span v-if="batchCurItem.status === 'done'" class="cnt-hint" style="color:#67c23a">✓ 该商品已上新完成（下方为成品预览，可回看）</span>
        </div>
        <pre v-if="batchCurItem.chainLog" class="chainlog">{{ batchCurItem.chainLog }}</pre>
      </el-card>

      <!-- ③ 成品预览:确认上新后出现,与单商品同构 -->
      <el-card v-if="batchCurItem && ['running-list','done'].includes(batchCurItem.status)" class="step">
        <template #header>③ 成品预览 — {{ batchCurItem.productName }}<span v-if="batchCurItem.status === 'running-list'" class="gen-hint">· 生成中…</span></template>
        <!-- 08.03 修 #4:批量模式原来没有上新标题预览(只有单商品模式有) -->
        <div class="ptitle-row">
          <span class="ptitle-l">上新标题</span>
          <span v-if="pageCtx?.visual?.title" class="ptitle">{{ pageCtx.visual.title }}</span>
          <span v-else class="ptitle-empty">未生成（②生成方案/标题时出）</span>
        </div>
        <div v-if="pickedMains.length" class="psec">
          <div class="psec-t">选定主图（{{ pickedMains.length }}）· 点击看大图</div>
          <div class="pgrid">
            <el-image v-for="(m, i) in pickedMains" :key="'bm' + i" :src="imgUrl(m)"
              :preview-src-list="mainsPreview" :initial-index="i" fit="contain" preview-teleported hide-on-click-modal />
          </div>
        </div>
        <div v-if="detailImages.length" class="psec">
          <div class="psec-t">详情图（{{ detailImages.length }}）· 点击看大图</div>
          <div class="pgrid">
            <el-image v-for="(d, i) in detailImages" :key="'bd' + i" :src="imgUrl(d)"
              :preview-src-list="detailsPreview" :initial-index="i" fit="contain" preview-teleported hide-on-click-modal />
          </div>
        </div>
        <div v-if="skuItems.length" class="psec">
          <div class="psec-t">SKU 图（{{ skuItems.length }}）· 点击看大图</div>
          <div class="pgrid">
            <el-image v-for="(it, i) in skuItems" :key="'bs' + i" :src="imgUrl(it.imgDir)"
              :preview-src-list="skuPreview" :initial-index="i"
              fit="contain" preview-teleported hide-on-click-modal :title="it.skuDisplayName || it.name" />
          </div>
        </div>
        <div v-if="!pickedMains.length && !detailImages.length" class="gen-empty">等待成品图生成中…</div>
      </el-card>
    </template>
  </div>
</template>

<style scoped>
.preplace { padding: 16px; }
.tag { font-size: 12px; background: #fdf6ec; color: #e6a23c; padding: 2px 8px; border-radius: 10px; vertical-align: middle; }
.intro { margin: 10px 0 10px; }
.mode-bar { margin-bottom: 14px; }
.step { margin-bottom: 16px; }
.actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.actions.foot { margin-top: 12px; }
.cnt { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: #606266; }
.cnt-hint { color: #909399; font-size: 12px; }
.phase { color: #909399; font-size: 13px; margin: 6px 0 0; }
.msg { margin: 8px 0 0; font-size: 13px; }
.msg.ok { color: #67c23a; }
.msg.err { color: #f56c6c; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 8px; margin-top: 8px; }
/* 点图=开大图(zoom-in),勾选只认左上角复选框 —— 两者不再抢同一次点击 */
.cell { position: relative; cursor: zoom-in; border: 3px solid transparent; border-radius: 6px; overflow: hidden; aspect-ratio: 1; }
.cell.on { border-color: #67c23a; }
.cell .el-image { width: 100%; height: 100%; }
.pickbox { position: absolute; top: 2px; left: 6px; z-index: 2; background: rgba(255,255,255,.85); border-radius: 4px; padding: 0 4px; height: 22px; }
.badge { position: absolute; top: 4px; right: 4px; background: #67c23a; color: #fff; width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: bold; }
/* 成品预览区(与单品页 SingleProduct 同一套 class 语义) */
.ptitle-row { display: flex; gap: 8px; align-items: baseline; margin-bottom: 12px; padding-bottom: 10px; border-bottom: 1px solid #ebeef5; }
.ptitle-l { flex: 0 0 auto; font-size: 12px; color: #909399; }
.ptitle { font-size: 14px; font-weight: 600; line-height: 1.5; }
.ptitle-empty { font-size: 13px; color: #c0c4cc; }
.psec { margin-bottom: 16px; }
.psec-t { font-size: 13px; color: #909399; margin-bottom: 8px; }
.pgrid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 8px; }
.pgrid :deep(.el-image) { width: 100%; aspect-ratio: 1; border: 1px solid #ebeef5; border-radius: 4px; cursor: zoom-in; display: block; }
.chainlog { margin-top: 12px; background: #f5f7fa; padding: 10px; border-radius: 6px; font-size: 12px; white-space: pre-wrap; max-height: 300px; overflow: auto; }
.batch-list { margin-top: 10px; display: flex; flex-direction: column; gap: 6px; }
.batch-row { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-radius: 6px; background: #f9f9fb; flex-wrap: wrap; }
.batch-row.warn { background: #fff7e6; }
.batch-row.done { background: #f0fff4; }
.batch-row.failed { background: #fff1f0; }
.batch-row.waiting-pick { background: #e6f4ff; }
.batch-row.active { outline: 2px solid #409eff; }
.bname { font-size: 13px; font-weight: 500; }
.binfo { font-size: 12px; color: #909399; }
.bwarn { font-size: 12px; color: #e6a23c; flex: 1; }
.gen-empty { color: #909399; font-size: 13px; padding: 20px 0; text-align: center; }
.gen-hint { font-size: 12px; color: #909399; margin-left: 4px; }
.gen-hint.ok { color: #67c23a; }
</style>


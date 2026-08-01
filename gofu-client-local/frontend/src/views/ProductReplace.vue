<script setup>
// 【权宜模块·产品替换】独立页面,不复用 ImportProduct.vue,便于后续生图质量起来后整体删除。
// 流程A(单商品):选文件夹(白底+N张参考图) → 抽卡 → 人工筛6 → step2(详情图+SKU图) → 上新。
// 流程B(批量):选「店铺/商品」两级根目录 → 解析分组+店铺精确匹配 → 逐商品顺序走流程A(筛图停等人工)。
import { reactive, computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '@/api.js'
import { useContextStore } from '@/stores/context.js'
import { useSettingsStore } from '@/stores/settings.js'
import { useStoresStore } from '@/stores/stores-mgmt.js'
import { useProductReplaceStore } from '@/stores/product-replace.js'

const ctxStore = useContextStore()
const settings = useSettingsStore()
const storesStore = useStoresStore()
const prStore = useProductReplaceStore()

const imgUrl = (r) => '/api/gen/img?ref=' + encodeURIComponent(r)

// -------------------- 模式: 单商品 vs 批量 --------------------
const mode = ref('single')  // 'single' | 'batch'

// -------------------- 单商品状态（切页不丢的字段挂 prStore，其余局部）--------------------
// white/refs 含 base64 太大，不进 store，切页后需重选文件夹（提示用 prStore.folderName 显示上次名字）
const st = reactive({
  white: [],        // [{name,b64,ext}] 产品白底图（局部，切页丢失）
  refs: [],         // [{name,b64,ext}] N张构图参考图（局部，切页丢失）
  running: false,
  chaining: false,
})
// 切页保留字段直接用 prStore（folderName/refsCount/count/msg/msgType/phase/pct/gachaDone/picked/cloudTaskId/contextId/chainLog）
const mainImages = computed(() => {
  if (!prStore.contextId || ctxStore.contextId !== prStore.contextId) return []
  return (ctxStore.current?.visual?.mainImages || []).filter(Boolean)
})
const busy = computed(() => st.running || st.chaining)

// -------------------- 批量状态 --------------------
// item: { shopName, productName, white, refs, status, profile?, skipReason?,
//         contextId?, cloudTaskId?, pct, phase, gachaDone, picked, chainLog }
const batch = reactive({
  items: [],
  curIdx: -1,     // 当前正在处理的 item index (-1=idle/全部完成)
  running: false,
})
const batchCurItem = computed(() => batch.curIdx >= 0 ? batch.items[batch.curIdx] : null)
const batchMainImages = computed(() => {
  const item = batchCurItem.value
  if (!item?.contextId || ctxStore.contextId !== item.contextId) return []
  return (ctxStore.current?.visual?.mainImages || []).filter(Boolean)
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
                 pct: 0, phase: '', gachaDone: false, picked: [], chainLog: '' })
  }
  batch.items = items
  batch.curIdx = -1
  batch.running = false
  const pending = items.filter((x) => x.status === 'pending').length
  const warned = items.filter((x) => x.status === 'warn').length
  if (warned > 0) ElMessage.warning(`解析完成：${items.length} 个商品，其中 ${warned} 个有警告（橙色项请按提示修正后重新解析），${pending} 个待处理`)
  else ElMessage.success(`解析完成：${items.length} 个商品，全部待处理，可点「开始批量」`)
}

// 批量启动：并行对所有 pending 商品同时抽卡，抽完各自进 waiting-pick 等用户筛图
async function startBatch() {
  if (batch.running) return
  const pendingIdxs = batch.items.map((x, i) => x.status === 'pending' ? i : -1).filter((i) => i >= 0)
  if (!pendingIdxs.length) {
    const warned = batch.items.filter((x) => x.status === 'warn').length
    ElMessage.warning(warned > 0 ? `没有待处理商品，有 ${warned} 个警告项需先按提示修正后重新解析` : '没有待处理的商品了')
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
    await pollBatchGacha(idx, info.cloudTaskId)
    item.status = 'waiting-pick'
    item.phase = `抽卡完成，请勾选最多 ${item.refs.length} 张后点「确认并上新」。`
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
  if (!item?.contextId) return
  batch.curIdx = idx
  try { await ctxStore.load(item.contextId, 'product-replace') } catch (_) {}
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
    await new Promise((r) => setTimeout(r, 2500))
    let t
    try { t = await api.get('/api/flow/task/' + taskId) } catch (_) { continue }
    const done = t.progress || 0, total = t.total || prStore.count
    item.pct = 60 + Math.round(38 * done / Math.max(1, total))
    // 07.31: progress只是已尝试数,跟成功数脱钩(欠费/超时时全部失败也照样跑到接近total)。
    const succ = t.successCount ?? 0
    item.phase = `抽卡替换中 已尝试${done}/${total}(成功${succ})…` + (done > 0 && succ === 0 ? ' ⚠ 全部失败,请检查生图服务/账户余额' : '')
    if (item.contextId) { try { await ctxStore.load(item.contextId) } catch (_) {} }
    if (t.status === 'done' || t.status === 'error') {
      if (t.status === 'error') throw new Error(t.error || `云端生图失败（已尝试${done}/${total}张，成功${succ}张）——若账户欠费请充值 api.linapi.net`)
      item.pct = 100; item.gachaDone = true; return
    }
  }
}

function toggleBatch(key) {
  const item = batchCurItem.value; if (!item) return
  const maxPick = item.refs.length
  const i = item.picked.indexOf(key)
  if (i >= 0) item.picked.splice(i, 1)
  else {
    if (item.picked.length >= maxPick) { ElMessage.warning(`最多选 ${maxPick} 张（与参考图张数一致）`); return }
    item.picked.push(key)
  }
}
const isBatchPicked = (key) => batchCurItem.value?.picked.includes(key) ?? false

async function confirmBatchAndList() {
  const item = batchCurItem.value; if (!item) return
  if (!item.picked.length) { ElMessage.warning('请先勾选主图'); return }
  item.status = 'running-list'; item.chainLog = ''
  const log = (msg) => { item.chainLog = msg }
  try {
    await doChain({ contextId: item.contextId, pickedKeys: item.picked, storeProfile: item.profile, folderName: item.productName, recognized: item.recognized, log })
    item.status = 'done'; item.chainLog = (item.chainLog || '') + '\n✓ 全自动完成。'
  } catch (e) {
    item.status = 'failed'; item.chainLog = (item.chainLog || '') + '\n✗ 失败：' + e.message
  }
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
  st.running = true; prStore.gachaDone = false; prStore.picked = []; prStore.cloudTaskId = ''
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
    await ctxStore.load(info.contextId, 'product-replace')
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
    await new Promise((r) => setTimeout(r, 2500))
    let t
    try { t = await api.get('/api/flow/task/' + taskId) } catch (_) { continue }
    const done = t.progress || 0, total = t.total || prStore.count
    prStore.pct = 60 + Math.round(38 * done / Math.max(1, total))
    const succ = t.successCount ?? 0
    prStore.phase = `抽卡替换中 已尝试${done}/${total}(成功${succ})…` + (done > 0 && succ === 0 ? ' ⚠ 全部失败,请检查生图服务/账户余额' : '')
    if (prStore.contextId) { try { await ctxStore.load(prStore.contextId) } catch (_) {} }
    if (t.status === 'done' || t.status === 'error') {
      if (t.status === 'error') throw new Error(t.error || `云端生图失败（已尝试${done}/${total}张，成功${succ}张）——若账户欠费请充值 api.linapi.net`)
      prStore.pct = 100; prStore.gachaDone = true
      prStore.msg = `✓ 抽卡完成，共 ${mainImages.value.length} 张。请从下方勾选 6 张，点「确认并全自动上新」。`
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
  if (!ctxStore.contextId) { ElMessage.error('缺 contextId'); return }
  st.chaining = true; prStore.chainLog = ''
  const log = (msg) => { prStore.chainLog = msg }
  try {
    await doChain({ contextId: ctxStore.contextId, pickedKeys: prStore.picked, storeProfile: storesStore.targetProfile || '', folderName: prStore.folderName, recognized: { category: prStore.recognizedCategory, productName: prStore.recognizedProductName, skus: prStore.recognizedSkus }, log })
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
  await ctxStore.load(contextId)

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
  await pollStep2(d2.taskId, d2.total || 0, log)
  await ctxStore.load(contextId)

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

async function pollStep2(taskId, total, log) {
  for (let tries = 0; tries < 1200; tries++) {
    await new Promise((r) => setTimeout(r, 2000))
    let t
    try { t = await api.get('/api/flow/task/' + taskId) } catch (_) { continue }
    log(`③ 详情/SKU图 ${t.progress || 0}/${total || '?'}…`)
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

      <el-card v-if="mainImages.length" class="step">
        <template #header>② 筛选主图（已选 {{ prStore.picked.length }} / {{ prStore.refsCount || st.refs.length }}，共 {{ mainImages.length }} 张）</template>
        <div class="grid">
          <div v-for="(m, i) in mainImages" :key="i" :class="['cell', { on: isPicked(m) }]" @click="toggle(m)">
            <el-image :src="imgUrl(m)" fit="cover" loading="lazy" />
            <span v-if="isPicked(m)" class="badge">{{ prStore.picked.indexOf(m) + 1 }}</span>
          </div>
        </div>
        <div class="actions foot">
          <el-button type="success" :disabled="prStore.picked.length === 0 || busy" :loading="st.chaining" @click="confirmAndList">
            {{ st.chaining ? '处理中…' : `确认并全自动上新（${prStore.picked.length} 张）` }}
          </el-button>
        </div>
        <pre v-if="prStore.chainLog" class="chainlog">{{ prStore.chainLog }}</pre>
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
               :style="item.status === 'waiting-pick' ? 'cursor:pointer' : ''"
               @click="item.status === 'waiting-pick' ? selectBatchItem(i) : null">
            <el-tag :type="statusType(item.status)" size="small">{{ statusLabel(item.status) }}</el-tag>
            <span class="bname">{{ item.shopName }} / {{ item.productName }}</span>
            <span class="binfo">白底{{ item.white.length }}·参考{{ item.refs.length }}</span>
            <span v-if="item.status === 'waiting-pick' && batch.curIdx !== i" class="bwarn" style="color:#409eff">← 点击筛图</span>
            <span v-if="item.warnReason" class="bwarn">{{ item.warnReason }}</span>
            <el-progress v-if="['running-gacha','waiting-pick','running-list'].includes(item.status)"
              :percentage="item.pct" :status="item.gachaDone ? 'success' : ''" style="flex:1;max-width:200px" />
          </div>
        </div>
      </el-card>

      <!-- 当前处理商品的筛图面板 -->
      <el-card v-if="batchCurItem && ['waiting-pick','running-list'].includes(batchCurItem.status)" class="step">
        <template #header>
          ② 筛选主图 — {{ batchCurItem.shopName }} / {{ batchCurItem.productName }}
          （已选 {{ batchCurItem.picked.length }} / {{ batchCurItem.refs.length }}，共 {{ batchMainImages.length }} 张）
        </template>
        <p class="phase">{{ batchCurItem.phase }}</p>
        <div class="grid">
          <div v-for="(m, i) in batchMainImages" :key="i" :class="['cell', { on: isBatchPicked(m) }]" @click="toggleBatch(m)">
            <el-image :src="imgUrl(m)" fit="cover" loading="lazy" />
            <span v-if="isBatchPicked(m)" class="badge">{{ batchCurItem.picked.indexOf(m) + 1 }}</span>
          </div>
        </div>
        <div class="actions foot">
          <el-button type="success" :disabled="batchCurItem.picked.length === 0" :loading="batchCurItem.status === 'running-list'" @click="confirmBatchAndList">
            {{ batchCurItem.status === 'running-list' ? '处理中…' : `确认并上新（${batchCurItem.picked.length} 张）` }}
          </el-button>
        </div>
        <pre v-if="batchCurItem.chainLog" class="chainlog">{{ batchCurItem.chainLog }}</pre>
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
.cell { position: relative; cursor: pointer; border: 3px solid transparent; border-radius: 6px; overflow: hidden; aspect-ratio: 1; }
.cell.on { border-color: #67c23a; }
.cell .el-image { width: 100%; height: 100%; }
.badge { position: absolute; top: 4px; right: 4px; background: #67c23a; color: #fff; width: 22px; height: 22px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: bold; }
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
</style>


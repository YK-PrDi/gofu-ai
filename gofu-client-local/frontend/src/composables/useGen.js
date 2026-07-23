import { reactive } from 'vue'
import { api } from '@/api.js'
import { useEntryStore } from '@/stores/entry.js'
import { useContextStore } from '@/stores/context.js'

// 生成编排(单品页+生图页共享)。源:runLayout/runSkuImages/pollFlowTask/stopGen/
// autoResolveAccessories/fillCostAndPrice/fillOnePlan/genTitle/regenImage/exportPricing。
// 反风控无关;纯生图流程,与旧版逻辑对齐,不改行为。
export function useGen() {
  const entry = useEntryStore()
  const ctxStore = useContextStore()

  const gen = reactive({
    running: false, done: false, layoutDone: false, progress: 0, msg: '',
    flowTaskId: '', imgBusy: false, profitRate: 0.58,
  })

  const fmtSec = (s) => { s = Math.max(0, Math.round(s)); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0') }

  // 解析防比价 templateId(源 resolveTemplateId),模板库从 settingsStore 读
  function resolveTemplateId(antipriceTemplates) {
    if (entry.genOpts.templateId === '__random__') {
      const ids = (antipriceTemplates || []).map((t) => t.id)
      return ids.length ? ids[Math.floor(Math.random() * ids.length)] : ''
    }
    return entry.genOpts.templateId || ''
  }

  // 轮询异步生图任务,进度映射到[lo,hi],每拍刷新预览(源 pollFlowTask)
  async function pollFlowTask(taskId, total, lo = 5, hi = 98) {
    gen.flowTaskId = taskId
    const startAt = Date.now()
    for (let tries = 0; tries < 1200; tries++) {
      await new Promise((r) => setTimeout(r, 1500))
      let t
      try { t = await api.get('/api/flow/task/' + taskId) } catch (_) { continue }
      const pct = total > 0 ? Math.round((t.progress / total) * (hi - lo)) : 0
      gen.progress = Math.min(hi, lo + pct)
      const elapsed = (Date.now() - startAt) / 1000
      let eta = '估算中…'
      if (t.progress > 0 && t.progress < t.total) eta = fmtSec((elapsed / t.progress) * (t.total - t.progress))
      else if (t.total > 0 && t.progress >= t.total) eta = '收尾中…'
      const cur = t.currentProduct || (t.progress < t.total ? `第 ${t.progress + 1} 张` : '')
      gen.msg = `生图中… ${t.progress}/${t.total} · 已用时 ${fmtSec(elapsed)} · 预计剩余 ${eta}` + (cur ? ` · 正在生成 ${cur}` : '')
      if (t.progress > 0) { try { await ctxStore.load(ctxStore.contextId) } catch (_) {} }
      if (t.status === 'done') { gen.flowTaskId = ''; return }
      if (t.status === 'stopped') { gen.msg = '已停止生成（已生成的图保留）'; gen.flowTaskId = ''; return }
      if (t.status === 'error') throw new Error((t.results || []).map((x) => x.message).join('；') || '生图失败')
    }
    throw new Error('生图轮询超时（超过30分钟）')
  }

  async function stopGen() {
    if (!gen.flowTaskId) return
    if (!confirm('停止本次生成？已生成的图会保留，未生成的不再继续。')) return
    try {
      await api.post('/api/flow/cancel/' + gen.flowTaskId)
      gen.msg = '停止中…（等在途的最后一张收尾）'
    } catch (e) { gen.msg = '停止请求失败：' + e.message }
  }

  // 配件自动搭配(源 autoResolveAccessories)
  async function autoResolveAccessories() {
    try {
      const pool = (await api.get('/api/erp/sku-items?keyword=')).items || []
      const erpSkus = pool.map((r) => ({ itemCode: r.outerId, name: r.title || r.outerId }))
      const d = await api.post('/api/listing/auto-resolve', { category: entry.categoryStr, mainItemCode: entry.mainCodes[0], erpSkus })
      if (d.error) throw new Error(d.error)
      return (d.accSkus || []).map((a) => ({ itemCode: a.itemCode, name: a.name, cost: 0, role: a.role || 'accessory' }))
    } catch (_) { return [] }
  }

  // 第一步:布局+主图(源 runLayout)
  async function runLayout() {
    if (!entry.whites.length) { gen.msg = '请先加白底图'; return }
    if ((ctxStore.current?.visual?.mainImages || []).length && !confirm('已有主图，重新生成将【覆盖】现有所有主图与详情图，是否继续？')) return
    gen.running = true; gen.done = false; gen.layoutDone = false; gen.progress = 5
    try {
      let skus = entry.skus.slice()
      const isShower = entry.categoryStr.includes('花洒') || entry.categoryStr.includes('淋浴')
      const hasAcc = skus.some((s) => s.role === 'accessory' || s.role === 'batch')
      if (isShower && entry.mainCodes.length && !hasAcc) {
        gen.msg = '按规则库自动搭配配件…'
        const acc = await autoResolveAccessories()
        if (acc.length) { skus = skus.concat(acc); entry.skus = skus }
      }
      gen.msg = '拉取配件白底图…'
      await entry.fetchAccWhites(skus)
      gen.progress = 12; gen.msg = '生成布局+主图（并行，异步）…'
      const d1 = await api.post('/api/flow/step1', {
        contextId: ctxStore.contextId || undefined,
        category: entry.categoryStr, mainItem: entry.mainItemName, productName: entry.mainItemName,
        whiteImages: entry.whites, skus, agentId: entry.agentId,
        mainCount: entry.genOpts.mainCount, planCount: entry.genOpts.planCount,
        mainAspect: entry.genOpts.mainAspect, customRequest: entry.genOpts.customRequest, styleId: entry.genOpts.styleId,
      })
      if (d1.error) throw new Error(d1.error)
      if (!d1.taskId) throw new Error('step1 未返回 taskId')
      ctxStore.contextId = d1.contextId
      ctxStore.origin = 'single' // 生成一开始就标single,否则轮询期pageCtx因旧origin=import返null→预览空白,图全等结束才一次性出现(流式渲染失效)
      await pollFlowTask(d1.taskId, d1.total || 0, 12, 88)
      await ctxStore.load(d1.contextId, 'single')
      await fillCostAndPrice()
      gen.msg = '生成标题…'; await genTitle()
      await ctxStore.save?.(); await ctxStore.load(d1.contextId)
      gen.progress = 100; gen.done = true; gen.layoutDone = true
      gen.msg = `布局完成：${(ctxStore.current?.structure?.plans || []).length} 套方案，主图 ${(ctxStore.current?.visual?.mainImages || []).length} 张。请在下方选定方案，再生成 SKU 图和详情图。`
    } catch (e) { gen.msg = '布局生成失败：' + e.message }
    finally { gen.running = false }
  }

  // 第二步:选定方案生 SKU 图+详情图(源 runSkuImages)
  async function runSkuImages(antipriceTemplates) {
    if (!ctxStore.contextId) { gen.msg = '请先生成布局'; return }
    gen.running = true; gen.done = false; gen.progress = 5
    try {
      gen.msg = '生成选定方案的 SKU 图 + 详情图（异步）…'
      const st = ctxStore.current?.structure
      const d2 = await api.post('/api/flow/step2', {
        contextId: ctxStore.contextId, planIndex: st?.selectedPlanIndex || 0,
        accWhiteImages: entry.accWhites, templateId: resolveTemplateId(antipriceTemplates),
      })
      if (d2.error) throw new Error(d2.error)
      if (!d2.taskId) throw new Error('step2 未返回 taskId')
      await pollFlowTask(d2.taskId, d2.total || 0, 5, 98)
      await ctxStore.load(ctxStore.contextId)
      await fillCostAndPrice()
      gen.progress = 100; gen.done = true; gen.msg = '完成：SKU 图 + 详情图已生成'
    } catch (e) { gen.msg = 'SKU/详情图生成失败：' + e.message }
    finally { gen.running = false }
  }

  // 成本+定价回填(源 fillCostAndPrice/fillOnePlan)
  async function fillCostAndPrice() {
    const plans = ctxStore.current?.structure?.plans || []
    if (!plans.length) return
    const cp = entry.catPath.length ? entry.categoryStr : (ctxStore.current?.category || '')
    const productType = cp.includes('花洒') ? '花洒' : cp.includes('代发') ? '代发' : '架类'
    try {
      for (const plan of plans) await fillOnePlan(plan.items || [], productType)
      await ctxStore.save?.()
    } catch (e) {
      // 成本/定价接口失败(如后端不可达/快麦缓存空)要显式暴露,否则表里成本静默为0,用户以为算过了
      gen.msg = '⚠ 成本/定价回填失败（成本可能显示为0）：' + (e.message || e) + '。请确认后端在跑、快麦缓存已建，再点「按此重算」。'
      throw e
    }
  }

  async function fillOnePlan(items, productType) {
    if (!items.length) return
    const mainCodeOf = (it) => (it.itemCode || '').split('+')[0]
    const codes = new Set()
    items.forEach((it) => {
      const mc = mainCodeOf(it); if (mc) codes.add(mc)
      ;(it.accParts || []).forEach((a) => { if (a.code) codes.add(a.code) })
    })
    const costMap = {}
    if (codes.size) {
      const cd = await api.post('/api/erp/calc-cost', { skuOuterIds: [...codes], productType })
      ;(cd.items || []).forEach((x) => { costMap[x.skuOuterId] = { cost: Number(x.cost) || 0, weight: Number(x.weight) || 0 } })
    }
    const comboSkus = items.map((it) => {
      const comps = []
      const mc = mainCodeOf(it)
      if (mc) comps.push({ itemCode: mc, qty: 1, name: it.spec1, cost: costMap[mc]?.cost || 0, weight: costMap[mc]?.weight || 0 })
      ;(it.accParts || []).forEach((a) => comps.push({ itemCode: a.code, qty: a.qty || 1, cost: costMap[a.code]?.cost || 0, weight: costMap[a.code]?.weight || 0 }))
      return { name: it.skuDisplayName || it.name || '', components: comps }
    })
    const combo = await api.post('/api/erp/calc-combo-cost', { productType, fixedAccessories: [], skus: comboSkus })
    ;(combo.skus || []).forEach((s, i) => { if (items[i]) items[i].cost = Number(s.cost) || 0 })
    const pd = await api.post('/api/pricing/calculate', {
      profitRate: gen.profitRate,
      skus: items.map((it) => ({ itemCode: it.itemCode || '', name: it.skuDisplayName || it.name || '', cost: it.cost || 0 })),
    })
    if (!pd.error && pd.skus) {
      items.forEach((it, i) => { if (pd.skus[i] && !it.__edited) { it.groupPrice = pd.skus[i].pinPrice; it.singlePrice = pd.singlePrice } })
    }
  }

  // 生成标题(源 genTitle)
  async function genTitle() {
    if (!ctxStore.contextId) return
    const st = ctxStore.current?.structure
    const items = st?.plans?.[st.selectedPlanIndex || 0]?.items || []
    const skuNames = items.map((it) => it.skuDisplayName || it.name).filter(Boolean)
    const mainImgPaths = ctxStore.current?.visual?.mainImages || []
    try {
      const d = await api.post('/api/gen/title', {
        contextId: ctxStore.contextId, mode: mainImgPaths.length ? 'vision' : 'titlelib',
        category: entry.categoryStr, productName: entry.mainItemName, brand: entry.brand || '', skuNames, mainImgPaths,
      })
      if (d.title && ctxStore.current?.visual) ctxStore.current.visual.title = d.title
    } catch (_) {}
    if (ctxStore.current?.visual && !ctxStore.current.visual.title) {
      const leaf = entry.catPath.at(-1) || ''
      const pts = (ctxStore.current.visual.sellingPoints || []).slice(0, 3).join(' ')
      ctxStore.current.visual.title = [entry.brand, leaf, pts, entry.mainItemName].filter(Boolean).join(' ').trim() || leaf || '新品'
    }
  }

  async function recalcPrice() { await fillCostAndPrice() }

  // ── 风格迁移(源 runStyleTransfer):对已有成品图整套换基调,覆盖回写 ──
  const style = reactive({ styleId: '', running: false, msg: '', msgType: '' })
  // 风格迁移(源 runStyleTransfer):对已有成品图(主图/详情/SKU图)整套换基调,覆盖回写。
  // 后端 style-transfer 已覆盖主图/详情/SKU 三者(见 FlowController #2c),不需前端再串生SKU。
  // auto=true:导入自动链调用,跳过 confirm 静默跑。
  async function runStyleTransfer(styleId, { auto = false } = {}) {
    const ctx = ctxStore.current
    const sid = styleId || style.styleId
    if (!ctx || !sid) return
    const hasFinished = (ctx.visual?.mainImages || []).length > 0 || (ctx.visual?.detailImages || []).length > 0
    if (!hasFinished) { style.msg = '当前商品没有成品图(主图/详情),无法换风格'; style.msgType = 'err'; return }
    if (!auto) {
      const skuN = (ctx.structure?.plans || []).reduce((s, p) => s + (p.items || []).filter((it) => it.imgDir).length, 0)
      const n = (ctx.visual.mainImages || []).length + (ctx.visual.detailImages || []).length + skuN
      if (!confirm(`将对当前商品 ${n} 张成品图(含主图/详情/SKU)整套换风格,换完覆盖原图(可接着上新)。继续?`)) return
    }
    style.running = true; style.msg = '风格迁移中…'; style.msgType = ''
    try {
      const d = await api.post('/api/flow/style-transfer', { contextId: ctxStore.contextId, styleId: sid })
      if (d.error) throw new Error(d.error)
      if (!d.taskId) throw new Error('未返回 taskId')
      await pollFlowTask(d.taskId, d.total || 0, 5, 98)
      await ctxStore.load(ctxStore.contextId)
      style.msg = '✓ 风格迁移完成,预览已更新'; style.msgType = 'ok'
    } catch (e) { style.msg = '风格迁移失败：' + e.message; style.msgType = 'err' }
    finally { style.running = false }
  }

  // ── 局部重绘(inpaint):框选/涂抹选区 + 指令 → /api/gen/inpaint(带mask),原地替换第 i 张 ──
  // maskBlob/imageBlob 由 InpaintDialog 从 canvas 导出(mask透明区=重绘区,OpenAI edits语义)。
  async function inpaintImage(kind, i, imageBlob, maskBlob, prompt, aspect = 'auto') {
    const ctx = ctxStore.current
    if (!ctx) return
    gen.imgBusy = true
    try {
      const fd = new FormData()
      fd.append('image', imageBlob, 'image.png')
      fd.append('mask', maskBlob, 'mask.png')
      fd.append('prompt', prompt || '')
      fd.append('aspect', aspect || 'auto')
      const d = await api.post('/api/gen/inpaint', fd)   // api.js 对 FormData 自动 multipart
      if (d.error) throw new Error(d.error)
      if (!d.imageRef) throw new Error('未返回 imageRef')
      // 原地替换第 i 张(同 regenImage 模式)
      const arr = kind === 'detail' ? ctx.visual?.detailImages : ctx.visual?.mainImages
      if (arr && i < arr.length) arr[i] = d.imageRef
      await ctxStore.save?.()
      await ctxStore.load(ctxStore.contextId)
      gen.msg = `第 ${i + 1} 张已局部重绘`
    } catch (e) { gen.msg = '局部重绘失败：' + e.message; throw e }
    finally { gen.imgBusy = false }
  }

  // ── 单张重生(源 regenImage):走 regen-main,原地替换第 i 张 ──
  async function regenImage(kind, i) {
    const ctx = ctxStore.current
    if (!ctx) return
    gen.imgBusy = true
    try {
      const d = await api.post('/api/flow/regen-main', {
        contextId: ctx.id, kind, index: i,
        mainAspect: entry.genOpts.mainAspect, customRequest: entry.genOpts.customRequest, styleId: entry.genOpts.styleId,
      })
      if (d.error) throw new Error(d.error)
      await ctxStore.load(ctxStore.contextId)
      gen.msg = `第 ${i + 1} 张已重生`
    } catch (e) { gen.msg = '重生失败：' + e.message }
    finally { gen.imgBusy = false }
  }

  // 8c 交叉并行:一次调 /api/flow/step-all,后端把主图/SKU/详情融成一条交叉管线
  // (首图串行定基调→主图2~N并发,每张链对应详情;SKU等首图refMain+布局就绪即开生),固定方案0。
  // 单次轮询到底,前端不再串接两步。step1/step2(runLayout/runSkuImages)保留给手选方案页。
  async function runStepAll(antipriceTemplates) {
    if (!entry.whites.length) { gen.msg = '请先加白底图'; return }
    if ((ctxStore.current?.visual?.mainImages || []).length && !confirm('已有主图，重新生成将【覆盖】现有所有主图/详情/SKU图，是否继续？')) return
    gen.running = true; gen.done = false; gen.layoutDone = false; gen.progress = 5
    try {
      let skus = entry.skus.slice()
      const isShower = entry.categoryStr.includes('花洒') || entry.categoryStr.includes('淋浴')
      const hasAcc = skus.some((s) => s.role === 'accessory' || s.role === 'batch')
      if (isShower && entry.mainCodes.length && !hasAcc) {
        gen.msg = '按规则库自动搭配配件…'
        const acc = await autoResolveAccessories()
        if (acc.length) { skus = skus.concat(acc); entry.skus = skus }
      }
      gen.msg = '拉取配件白底图…'
      await entry.fetchAccWhites(skus)
      gen.progress = 12; gen.msg = '交叉并行生成（主图/SKU/详情，异步）…'
      const d = await api.post('/api/flow/step-all', {
        contextId: ctxStore.contextId || undefined,
        category: entry.categoryStr, mainItem: entry.mainItemName, productName: entry.mainItemName,
        whiteImages: entry.whites, skus, agentId: entry.agentId,
        mainCount: entry.genOpts.mainCount, planCount: entry.genOpts.planCount,
        mainAspect: entry.genOpts.mainAspect, customRequest: entry.genOpts.customRequest, styleId: entry.genOpts.styleId,
        accWhiteImages: entry.accWhites, templateId: resolveTemplateId(antipriceTemplates),
      })
      if (d.error) throw new Error(d.error)
      if (!d.taskId) throw new Error('step-all 未返回 taskId')
      ctxStore.contextId = d.contextId
      ctxStore.origin = 'single' // 生成一开始就标single,否则轮询期预览空白(流式渲染失效),同 runLayout
      await pollFlowTask(d.taskId, d.total || 0, 12, 98)
      await ctxStore.load(d.contextId, 'single')
      await fillCostAndPrice()
      gen.msg = '生成标题…'; await genTitle()
      await ctxStore.save?.(); await ctxStore.load(d.contextId)
      gen.progress = 100; gen.done = true; gen.layoutDone = true
      gen.msg = `完成：${(ctxStore.current?.structure?.plans || []).length} 套方案，主图 ${(ctxStore.current?.visual?.mainImages || []).length} 张，详情 ${(ctxStore.current?.visual?.detailImages || []).length} 张。`
    } catch (e) { gen.msg = '一键生成失败：' + e.message }
    finally { gen.running = false }
  }

  // 一键生成:走 8c 交叉并行融合端点(固定方案0)。用户想换方案再手动走 runSkuImages(step2)重生。
  async function runOneClick(antipriceTemplates) {
    await runStepAll(antipriceTemplates)
  }

  return { gen, style, pollFlowTask, stopGen, runLayout, runSkuImages, runStepAll, runOneClick, runStyleTransfer, regenImage, inpaintImage, fillCostAndPrice, genTitle, recalcPrice, resolveTemplateId }
}

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
      await pollFlowTask(d1.taskId, d1.total || 0, 12, 88)
      await ctxStore.load(d1.contextId)
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

  return { gen, pollFlowTask, stopGen, runLayout, runSkuImages, fillCostAndPrice, genTitle, recalcPrice, resolveTemplateId }
}

import { defineStore } from 'pinia'
import { api } from '@/api.js'

// 录入草稿:正在录入/编辑的"在建商品"。跨页共享:选品/单品/生图页读写。
// P2-e 扩全:skus + 品类 + 白底图(主件/配件) + 缺图导入 + 生图选项。
const ROLE_LABELS = { main: '主件', accessory: '配件', batch: '批量件' }

export const useEntryStore = defineStore('entry', {
  state: () => ({
    catPath: [], // 品类路径数组,如 ['家装主材','卫浴配件','花洒配件','花洒喷头']
    brand: 'GOFU',
    agentId: 'gpt-image',
    skus: [], // [{ itemCode, name, cost, role }]
    whites: [], // 主件白底图(dataURL / 本地路径 / http)
    accWhites: [], // 配件白底图
    importedFor: {}, // 缺图编码 → dataURL
    whiteCodeMap: {}, // 编码 → [白底图,...] 来源映射(删单品时反查移除)
    pushedCodes: [], // 已回传快麦的编码
    whiteCheck: { done: false, has: [], missing: [] },
    genOpts: { mainCount: 6, planCount: 3, templateId: '__random__', mainAspect: '1:1', customRequest: '', styleId: 'random' },
  }),
  getters: {
    mainCodes: (s) => s.skus.filter((x) => x.role === 'main').map((x) => x.itemCode),
    hasMain: (s) => s.skus.some((x) => x.role === 'main'),
    mainItemName: (s) => s.skus.find((x) => x.role === 'main')?.name || '',
    categoryStr: (s) => s.catPath.join('>'),
    isShowerCategory: (s) => {
      const p = s.catPath.join('>')
      return p.includes('花洒') || p.includes('淋浴') || p.includes('喷头')
    },
    // 生成布局按钮不可点时,精确说明缺哪项(源 genBlockReason)
    genBlockReason: (s) => {
      const miss = []
      if (!s.catPath.length) miss.push('选品类')
      if (!s.skus.length) miss.push('选主件/配件')
      else if (!s.skus.some((x) => x.role === 'main')) miss.push('把至少一个单品标为「主件」')
      if (!s.whites.length) miss.push('备齐主件白底图')
      return miss.join('、')
    },
    canGenerate() {
      return this.catPath.length && this.skus.length && this.whites.length
    },
  },
  actions: {
    roleLabel: (r) => ROLE_LABELS[String(r || '').toLowerCase()] || r,
    addPicks(picks) {
      let n = 0
      picks.forEach((r) => {
        if (!r.role) return
        if (this.skus.some((s) => s.itemCode === r.outerId)) return
        this.skus.push({ itemCode: r.outerId, name: r.title || r.outerId, cost: r.purchasePrice || 0, role: r.role })
        n++
      })
      return n
    },
    // 删单品:同步移除该编码的白底图(源 removeSku)
    removeSku(i) {
      const sku = this.skus[i]
      const code = sku?.itemCode
      if (code) {
        ;(this.whiteCodeMap[code] || []).forEach((f) => {
          let k = this.whites.indexOf(f); if (k >= 0) this.whites.splice(k, 1)
          k = this.accWhites.indexOf(f); if (k >= 0) this.accWhites.splice(k, 1)
        })
        delete this.whiteCodeMap[code]
        delete this.importedFor[code]
        // 修(旧版遗留):删单品时同步从缺图列表移除该编码,否则删掉选错的品后仍提示导入它的白底图
        this.whiteCheck.missing = this.whiteCheck.missing.filter((c) => c !== code)
        this.whiteCheck.has = this.whiteCheck.has.filter((c) => c !== code)
      }
      this.skus.splice(i, 1)
      if (!this.mainCodes.length) this.whiteCheck = { done: false, has: [], missing: [] }
    },
    recordWhiteCode(code, file) {
      if (!code || !file) return
      const arr = this.whiteCodeMap[code] || (this.whiteCodeMap[code] = [])
      if (!arr.includes(file)) arr.push(file)
    },
    // 回传快麦:把导入的白底图写回该编码的快麦单品档案(源 pushWhiteToKuaimai,P2-e漏迁,补回)
    async pushWhiteToKuaimai(code) {
      const dataUrl = this.importedFor[code]
      if (!dataUrl) throw new Error('请先为 ' + code + ' 导入白底图')
      const d = await api.post('/api/erp/upload-white-image', { outerId: code, dataUrl })
      if (d.error) throw new Error(d.error)
      if (!this.pushedCodes.includes(code)) this.pushedCodes.push(code)
      return d
    },
    addWhites(dataUrls) {
      dataUrls.forEach((u) => { if (!this.whites.includes(u)) this.whites.push(u) })
    },
    // 自动拉主件白底图(源 autoFetchWhite)
    async autoFetchWhite() {
      const codes = this.mainCodes
      if (!codes.length) { this.whiteCheck = { done: false, has: [], missing: [] }; return { matched: 0, missing: 0 } }
      const d = await api.post('/api/erp/fetch-white-images', { codes })
      if (d.error) throw new Error(d.error)
      ;(d.matched || []).forEach((m) => {
        if (m.file && !this.whites.includes(m.file)) this.whites.push(m.file)
        if (m.code && m.file) this.recordWhiteCode(m.code, m.file)
      })
      this.whiteCheck = { done: true, has: (d.matched || []).map((m) => m.code), missing: d.missing || [] }
      return { matched: (d.matched || []).length, missing: (d.missing || []).length }
    },
    // 拉配件白底图(源 fetchAccWhites)
    async fetchAccWhites(skus) {
      const accCodes = [...new Set((skus || this.skus).filter((s) => s.role === 'accessory' || s.role === 'batch').map((s) => s.itemCode).filter(Boolean))]
      if (!accCodes.length) return
      const d = await api.post('/api/erp/fetch-white-images', { codes: accCodes })
      if (d.error) throw new Error(d.error)
      this.accWhites = (d.matched || []).map((m) => m.file).filter(Boolean)
      ;(d.matched || []).forEach((m) => { if (m.code && m.file) this.recordWhiteCode(m.code, m.file) })
      const accMissing = (d.missing || []).filter((c) => !this.whiteCheck.missing.includes(c))
      if (accMissing.length) this.whiteCheck.missing = [...this.whiteCheck.missing, ...accMissing]
    },
    clear() {
      this.skus = []; this.whites = []; this.accWhites = []
      this.importedFor = {}; this.whiteCodeMap = {}; this.pushedCodes = []
      this.whiteCheck = { done: false, has: [], missing: [] }
    },
  },
})

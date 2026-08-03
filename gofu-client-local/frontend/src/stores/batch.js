import { defineStore } from 'pinia'
import { api } from '@/api.js'

// 文件夹批量上新(平移 batch.js/BatchMixin):多套图→多店,按文件夹名识别,串行连续上新。
// 反风控:服务端 browserBusy 闸已串行化上新进程,前端不用错开。
const localUrl = (p) => '/api/erp/local-image?path=' + encodeURIComponent(p)

export const useBatchStore = defineStore('batch', {
  state: () => ({
    folderName: '',
    rootPath: '',
    fileCount: 0,
    preview: null, // {name,shop,category,title,main[],detail[],white[],sku[],plans,planIdx,contextId}
    outcomes: [],
    busy: false,
    msg: '',
    msgType: '',
    profitRate: 0.45, // 批量流默认在33/45/53随机
    // 上传后后端重建的临时目录仍在,刷新后可直接接着跑,不必重新上传整个文件夹。
  }),
  getters: {
    readyCount: (s) => s.outcomes.filter((o) => o.status === 'ready' || o.status === 'listing_started').length,
    // canRun 用 getter 实时算(不用state):补生完把商品置ready后,按钮立即可点(修:原state只在preflight算一次→补生完按钮一直灰)
    canRun() { return this.outcomes.some((o) => o.status === 'ready') },
    // 按店铺分组(保留原索引 _i 供定位)
    byShop: (s) => {
      const groups = []; const idx = {}
      s.outcomes.forEach((o, i) => {
        const shop = o.shopName || '(未知店铺)'
        if (!(shop in idx)) { idx[shop] = groups.length; groups.push({ shop, rows: [] }) }
        groups[idx[shop]].rows.push({ ...o, _i: i })
      })
      return groups
    },
  },
  actions: {
    setMsg(m, t) { this.msg = m; this.msgType = t || '' },
    randomProfit() { this.profitRate = [0.33, 0.45, 0.53][Math.floor(Math.random() * 3)] },
    fileToB64(f) {
      return new Promise((resolve) => {
        const rd = new FileReader()
        rd.onload = () => resolve(String(rd.result).split(',')[1] || '')
        rd.readAsDataURL(f)
      })
    },
    // 上传大文件夹(webkitdirectory)→后端重建临时目录→拿回 rootPath→自动预检
    async uploadTree(files) {
      const imgs = [...files].filter((f) => f.type && f.type.startsWith('image/'))
      if (!imgs.length) { this.setMsg('该文件夹没有图片', 'err'); return }
      this.folderName = (imgs[0].webkitRelativePath || '').split('/')[0] || ''
      this.busy = true; this.outcomes = []
      this.randomProfit()
      this.setMsg(`上传大文件夹（${imgs.length} 张图，本批利润率随机=${(this.profitRate * 100).toFixed(0)}%）到后端…`, '')
      try {
        const payload = []
        for (const f of imgs) {
          const b64 = await this.fileToB64(f)
          const ext = f.name.toLowerCase().endsWith('.png') ? 'png' : 'jpg'
          payload.push({ path: f.webkitRelativePath || f.name, b64, ext })
        }
        const d = await api.post('/api/semi-auto/upload-tree', { files: payload })
        if (d.error) throw new Error(d.error)
        this.rootPath = d.rootPath || ''
        this.fileCount = d.fileCount || 0
        this.setMsg(`✓ 已上传「${this.folderName}」（${this.fileCount} 张图），预检中…`, '')
        await this.preflight()
      } catch (e) { this.setMsg('上传失败：' + e.message, 'err') }
      finally { this.busy = false }
    },
    async preflight() {
      const d = await api.post('/api/semi-auto/preflight', { rootPath: this.rootPath.trim(), profitRate: this.profitRate })
      if (d.error) throw new Error(d.error)
      this.outcomes = d.outcomes || []
      this.setMsg(`预检完成：${this.readyCount} 个商品齐全可上新，其余见下方说明`, this.readyCount > 0 ? 'ok' : 'err')
    },
    // 轻量预览(只读本地图,零云端)
    async previewOne(idx) {
      const o = this.outcomes[idx]
      if (!o || !o.folderPath) { this.setMsg('该商品无文件夹路径，无法预览', 'err'); return }
      const d = await api.post('/api/semi-auto/product-images', { folderPath: o.folderPath })
      if (d.error) throw new Error(d.error)
      const white = (d.white || []).map(localUrl).concat(d.whiteErp || [])
      const folderSku = (d.sku || []).map(localUrl)
      const planSku = ((o._plans && o._plans[0] && o._plans[0].items) || []).map((it) => it._img).filter(Boolean)
      const sku = folderSku.length ? folderSku : planSku
      this.preview = {
        name: o.mainItem || o.productName, shop: o.shopName, category: o.category, title: o.title || '',
        main: (d.main || []).map(localUrl), detail: (d.detail || []).map(localUrl),
        white, sku, plans: o._plans || null, planIdx: 0, contextId: o.contextId || '',
      }
      this.setMsg(`预览「${this.preview.name}」：主图${(d.main || []).length}·详情${(d.detail || []).length}·白底${white.length}·sku${sku.length}`, 'ok')
    },
    async saveTitle() {
      const p = this.preview
      if (!p || !p.contextId) return
      const ctx = await api.get('/api/context/' + encodeURIComponent(p.contextId))
      if ((ctx.visual?.title || '') === p.title) return
      ctx.visual.title = p.title
      await api.post('/api/context', ctx)
      const o = this.outcomes.find((x) => (x.mainItem || x.productName) === p.name)
      if (o) o.title = p.title
      this.setMsg('标题已保存', 'ok')
    },
    // 轮询单个商品上新任务(复用 /api/task)
    async pollTask(idx, tries = 0) {
      const o = this.outcomes[idx]
      if (!o || !o.taskId) return
      if (tries > 800) { o.taskMsg = '轮询超时'; return }
      try {
        const t = await api.get('/api/task/' + o.taskId)
        o.progress = t.total > 0 ? t.progress + '/' + t.total : '' + t.progress
        if (t.status === 'done') { o.taskStatus = 'done'; o.status = 'listing_started'; o.taskMsg = '✓ 上新成功'; return }
        if (t.status === 'error') {
          o.taskStatus = 'error'; o.status = 'ready'
          o.taskMsg = '✗ ' + ((t.results || []).filter((x) => x.type === 'error').map((x) => x.message).join('；') || '上新失败') + '（可点「开始批量上新」重试）'
          return
        }
        o.taskMsg = '上新中… ' + o.progress
      } catch (_) {}
      setTimeout(() => this.pollTask(idx, tries + 1), 1500)
    },
    // 智能分流上新:有context走from-context,无context走/run
    async run() {
      const ready = this.outcomes.map((o, i) => ({ o, i })).filter((x) => x.o.status === 'ready' && x.o.taskStatus !== 'listing_started')
      const ctxItems = ready.filter((x) => x.o.contextId)
      const folderItems = ready.filter((x) => !x.o.contextId)
      if (!ctxItems.length && !folderItems.length) { this.setMsg('没有齐全待上的商品', 'err'); return }
      if (!confirm(`确认对 ${ready.length} 个齐全商品批量上新？将逐店逐商品串行操作真实商家后台。`)) return
      this.busy = true
      try {
        let started = 0
        for (const { o, i } of ctxItems) {
          try {
            const ld = await api.post('/api/listing/from-context', { contextId: o.contextId, planIndex: 0, dryRun: false, storeProfile: o.shopProfile || '' })
            if (ld.error || !ld.taskId) throw new Error(ld.error || '未返回 taskId')
            o.taskId = ld.taskId; o.status = 'listing_started'; o.taskStatus = ''; o.taskMsg = '✓ 已启动上新…'
            this.pollTask(i); started++
          } catch (e) { o.taskStatus = 'error'; o.taskMsg = '✗ 上新启动失败：' + e.message }
        }
        if (folderItems.length) {
          const d = await api.post('/api/semi-auto/run', { rootPath: this.rootPath.trim(), profitRate: this.profitRate })
          const runStarted = (d.outcomes || []).filter((x) => x.status === 'listing_started')
          for (const { o, i } of folderItems) {
            const hit = runStarted.find((x) => x.shopName === o.shopName && (x.productName === o.productName || x.mainItem === o.mainItem))
            if (hit && hit.taskId) { o.taskId = hit.taskId; o.status = 'listing_started'; o.taskStatus = ''; o.taskMsg = '✓ 已启动上新…'; this.pollTask(i); started++ }
          }
        }
        this.setMsg(`已启动 ${started} 个商品的上新，实时进度见下方`, started ? 'ok' : 'err')
      } catch (e) { this.setMsg('批量上新失败：' + e.message, 'err') }
      finally { this.busy = false }
    },
    statusText(s) {
      return { ready: '齐全待上', listing_started: '已启动上新', blocked: '缺素材·已拦', shop_unmatched: '店铺未匹配', not_logged_in: '店铺未登录', sku_gen_available: '缺图·可AI生成' }[s] || s
    },
    statusClass(s) {
      if (s === 'ready' || s === 'listing_started') return 'ok'
      if (s === 'blocked') return 'err'
      return 'warn'
    },
  },
  // 不持久化(08.03 二次修正,同 context.js):预检结果是干活中间态,切页靠内存态就够(hash 路由切页
  // 不重载页面),F5 该是干净重来。另 rootPath 指向后端临时目录,重启后端即失效,留着也只是失效路径。
})

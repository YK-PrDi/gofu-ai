import { defineStore } from 'pinia'
import { api } from '@/api.js'

// ProductContext 脊柱镜像(架构铁律3):当前活动商品上下文。
// 各页共享同一份 —— 选品页选中 / 生图页重生 / 单品页编辑,都读写这里,实时互通。
export const useContextStore = defineStore('context', {
  state: () => ({
    contextId: '',
    current: null, // 完整 ProductContext 快照
    loading: false,
    origin: '', // 当前 current 属于哪个页面(owner):'single'/'import'/'product-replace'/'kaipin'/'batch'。
                // 只记"最后一个写入者",页面切走被别页顶掉后无从回退 —— 故配 ownedIds 记账。
    ownedIds: {}, // owner → 该页最后持有的 contextId。切页回来据此把自己的 context 重新调回 current。
  }),
  getters: {
    hasContext: (s) => !!s.contextId,
    title: (s) => s.current?.basic?.title || s.current?.mainItem || s.contextId || '未选择商品',
    // 品类/主件:后端反推,识别不出为空(ctx.category / ctx.mainItem)
    category: (s) => s.current?.category || '',
    mainItem: (s) => s.current?.mainItem || '',
    // 页面预览统一读这个:current 不属于本页就返 null,杜绝渲染别页商品(串页)。
    currentFor: (s) => (owner) => (s.origin === owner ? s.current : null),
    ownedId: (s) => (owner) => s.ownedIds[owner] || '',
    // 当前选中方案的 SKU 单品列表(展示用)
    skuItems: (s) => {
      const st = s.current?.structure
      if (!st?.plans?.length) return []
      const p = st.plans[st.selectedPlanIndex || 0]
      return p?.items || []
    },
  },
  actions: {
    async load(id, origin = '') {
      if (!id) return
      this.loading = true
      try {
        this.current = await api.get(`/api/context/${id}`)
        this.contextId = id
        if (origin) this.origin = origin
      } finally {
        this.loading = false
      }
    },
    // 页面接管某 context:登记归属 + 载入。各页新建/切换 context 都走它(替代裸 load(id,'xxx'))。
    async adopt(owner, id) {
      if (!owner || !id) return
      this.ownedIds[owner] = id
      await this.load(id, owner)
    },
    releaseOwner(owner) {
      if (owner) delete this.ownedIds[owner]
    },
    async listAll() {
      return api.get('/api/context')
    },
    setCurrent(ctx) {
      this.current = ctx
      this.contextId = ctx?.contextId || ctx?.id || this.contextId
    },
    // 保存当前 context 回云端(源 saveContext):清 __edited 临时标记后 POST
    async save() {
      if (!this.current) return
      const payload = JSON.parse(JSON.stringify(this.current))
      ;(payload.structure?.plans || []).forEach((p) => (p.items || []).forEach((it) => delete it.__edited))
      await api.post('/api/context', payload)
    },
    clear() {
      this.contextId = ''
      this.current = null
      this.origin = ''
      this.ownedIds = {}
    },
  },
  // 不持久化(08.03 二次修正)。
  // 「切页回来不丢」靠的是 Pinia 内存态 —— hash 路由切页是 router 导航、页面不重载,内存本来就在,
  // 压根不需要持久化。持久化只决定「页面重载后要不要恢复」,而那正是不想要的:
  //   · 一开始放 localStorage → 新开应用自动铺出上次商品(用户反馈#1)
  //   · 改成 sessionStorage 仍然错 → sessionStorage 在同一标签页内**跨 F5 存活**,
  //     只有关标签页才清,所以 F5 后残留照旧(用户反馈#2)。
  // 结论:干活中间态一律不持久化,F5 = 干净重来。
})

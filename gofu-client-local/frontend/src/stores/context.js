import { defineStore } from 'pinia'
import { api } from '@/api.js'

// ProductContext 脊柱镜像(架构铁律3):当前活动商品上下文。
// 各页共享同一份 —— 选品页选中 / 生图页重生 / 单品页编辑,都读写这里,实时互通。
export const useContextStore = defineStore('context', {
  state: () => ({
    contextId: '',
    current: null, // 完整 ProductContext 快照
    loading: false,
  }),
  getters: {
    hasContext: (s) => !!s.contextId,
    title: (s) => s.current?.basic?.title || s.current?.mainItem || s.contextId || '未选择商品',
    // 品类/主件:后端反推,识别不出为空(ctx.category / ctx.mainItem)
    category: (s) => s.current?.category || '',
    mainItem: (s) => s.current?.mainItem || '',
    // 当前选中方案的 SKU 单品列表(展示用)
    skuItems: (s) => {
      const st = s.current?.structure
      if (!st?.plans?.length) return []
      const p = st.plans[st.selectedPlanIndex || 0]
      return p?.items || []
    },
  },
  actions: {
    async load(id) {
      if (!id) return
      this.loading = true
      try {
        this.current = await api.get(`/api/context/${id}`)
        this.contextId = id
      } finally {
        this.loading = false
      }
    },
    async listAll() {
      return api.get('/api/context')
    },
    setCurrent(ctx) {
      this.current = ctx
      this.contextId = ctx?.contextId || ctx?.id || this.contextId
    },
    clear() {
      this.contextId = ''
      this.current = null
    },
  },
  persist: { pick: ['contextId'] }, // 只持久化 id,内容刷新时重拉,避免存陈旧快照
})

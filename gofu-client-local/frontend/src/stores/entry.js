import { defineStore } from 'pinia'

// 录入草稿:正在录入/编辑的"在建商品"(选品页选的单品、白底图、品类等)。
// 区别于 contextStore(已存服务端的 ProductContext)。跨页共享:选品页写 skus,
// 单品页/生图页读用。P2-c 先建 skus 部分,P2-e 单品页迁移时扩 whites/cat/brand。
const ROLE_LABELS = { main: '主件', accessory: '配件', batch: '批量件' }

export const useEntryStore = defineStore('entry', {
  state: () => ({
    skus: [], // [{ itemCode, name, cost, role }]
  }),
  getters: {
    mainCodes: (s) => s.skus.filter((x) => x.role === 'main').map((x) => x.itemCode),
    hasMain: (s) => s.skus.some((x) => x.role === 'main'),
  },
  actions: {
    roleLabel: (r) => ROLE_LABELS[r] || r,
    // 批量加入选中单品(去重),返回新增数。源:confirmPick 的写入部分。
    addPicks(picks) {
      let n = 0
      picks.forEach((r) => {
        const role = r.role
        if (!role) return
        if (this.skus.some((s) => s.itemCode === r.outerId)) return // 去重
        this.skus.push({ itemCode: r.outerId, name: r.title || r.outerId, cost: r.purchasePrice || 0, role })
        n++
      })
      return n
    },
    removeSku(i) {
      this.skus.splice(i, 1)
    },
    clear() {
      this.skus = []
    },
  },
})

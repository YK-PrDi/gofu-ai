import { defineStore } from 'pinia'
import { useContextStore } from '@/stores/context.js'

// 产品替换的状态。放 store 切页不丢;白名单持久化后刷新/重启也不丢(不含 base64)。
export const useProductReplaceStore = defineStore('productReplace', {
  state: () => ({
    folderName: '',
    refsCount: 0,       // 抽卡启动时快照的参考图张数(重启后也能显示正确上限)
    count: 100,
    msg: '', msgType: '',
    phase: '', pct: 0,
    gachaDone: false,
    cloudTaskId: '',
    contextId: '',      // 抽卡完成后的 context(切页回来仍可筛图)
    picked: [],
    // 抽卡出的全集快照。/pick 会把 context.visual.mainImages 覆写成筛中的那几张,
    // 筛选区若直接读 mainImages 就会塌成几张、失败了也没法重选 —— 故单独留全集。
    gachaImages: [],
    chaining: false,
    chainLog: '',
    recognizedCategory: '',
    recognizedProductName: '',
    recognizedSkus: [],
    // 批量状态(白底/参考图 base64 太大不存,切页回来 pending 行需重选文件夹)
    batchItems: [],   // [{shopName,productName,status,profile,warnReason,contextId,cloudTaskId,pct,phase,gachaDone,picked,gachaImages,chainLog,refsCount,whiteCount}]
    batchCurIdx: -1,
  }),
  actions: {
    resetGacha() {
      this.gachaDone = false
      this.cloudTaskId = ''
      this.contextId = ''
      this.picked = []
      this.gachaImages = []
      this.chainLog = ''
      this.phase = ''
      this.pct = 0
      this.msg = ''
      this.msgType = ''
      useContextStore().releaseOwner('product-replace')
    },
    syncBatchItem(idx, item) {
      // 只持久化不含 base64 的字段
      const { white, refs, ...rest } = item
      this.batchItems[idx] = { ...rest, refsCount: refs?.length ?? item.refsCount ?? 0, whiteCount: white?.length ?? item.whiteCount ?? 0 }
    },
  },
  // 不持久化(08.03 二次修正,同 context.js):抽卡进度/已勾选是干活中间态,切页靠内存态就够了
  // (hash 路由切页不重载页面),F5 该是干净重来。sessionStorage 也不行——它跨 F5 存活。
})

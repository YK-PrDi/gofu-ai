import { defineStore } from 'pinia'

// 产品替换的状态。放 store 切页不丢。
// white/refs 含 base64 太大不存，切页后若 folderName 存在但 white 空，提示重新选文件夹即可。
export const useProductReplaceStore = defineStore('productReplace', {
  state: () => ({
    folderName: '',
    refsCount: 0,       // 抽卡启动时快照的参考图张数（重启后也能显示正确上限）
    count: 100,
    msg: '', msgType: '',
    phase: '', pct: 0,
    gachaDone: false,
    cloudTaskId: '',
    contextId: '',      // 抽卡完成后的 context（切页回来仍可筛图）
    picked: [],
    chaining: false,
    chainLog: '',
    // 识别结果（切页后仍能用于下游 generate-layout）
    recognizedCategory: '',
    recognizedProductName: '',
    recognizedSkus: [],
  }),
  actions: {
    resetGacha() {
      this.gachaDone = false
      this.cloudTaskId = ''
      this.contextId = ''
      this.picked = []
      this.chainLog = ''
      this.phase = ''
      this.pct = 0
      this.msg = ''
      this.msgType = ''
    },
  },
})

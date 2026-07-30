import { defineStore } from 'pinia'

// 开品模式的状态。放 store 切页不丢。
// imageA/imageB 的 File 对象和 b64 不存（太大），切页后 preview 消失是可接受的。
export const useKaipinStore = defineStore('kaipin', {
  state: () => ({
    // 分析卡片（最重要，用户编辑了不能丢）
    analyzed: false,
    fields: [],          // [{key, value}]
    analyzeMsg: '', analyzeMsgType: '',
    // 生图配置
    selectedTag: '',
    n: 3,
    genCount: 6,
    genPrompt: '',
    // 生图结果
    contextId: '',
    cloudTaskId: '',
    genDone: false,
    genPct: 0,
    genPhase: '',
    genMsg: '', genMsgType: '',
    picked: [],
    // 上新链日志
    chainLog: '',
    // 识别结果（供下游用）
    recognizedCategory: '',
    recognizedProductName: '',
    recognizedSkus: [],
  }),
  actions: {
    resetGen() {
      this.contextId = ''
      this.cloudTaskId = ''
      this.genDone = false
      this.genPct = 0
      this.genPhase = ''
      this.picked = []
      this.chainLog = ''
    },
  },
})

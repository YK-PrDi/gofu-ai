import { defineStore } from 'pinia'
import { useContextStore } from '@/stores/context.js'

// 开品模式的状态。放 store 切页不丢。
// imageA/imageB 的 File 对象和 b64 不存（太大），切页后 preview 消失是可接受的。
export const useKaipinStore = defineStore('kaipin', {
  state: () => ({
    // 分析卡片（最重要，用户编辑了不能丢）
    analyzed: false,
    fields: [],          // [{key, value}]
    analyzeMsg: '', analyzeMsgType: '',
    // 上传的产品图（08.05 修：原来存在组件的 reactive 里，切页组件卸载就没了——
    // 用户反馈"上传白底图点分析后，切到别的模式再回来白底图不见了"。
    // 存 b64 而不是 File：切页回来要能重新上传/重新分析，而 File 对象没法从 store 恢复；
    // 有了 b64 + ext 就够了（uploadImage 只用这两样，analyze 的 FormData 可由 b64 重建 File）。
    // ⚠ 只进内存、**不持久化**（本 store 整体不持久化，见文件末注释）：b64 可能几 MB，
    // localStorage 装不下；且 F5 该是干净重来（L6 决定）。切页不丢靠的是内存态，够用。
    imageAPreview: '', imageAExt: 'jpg', imageAName: '',
    imageBPreview: '', imageBExt: 'jpg', imageBName: '',
    // 分析输入的文字字段（同上，切页不丢）
    productA: '', productB: '', selling: '',
    focus: 'cost', focusText: '', style: '', styleText: '',
    // 生图配置（08.03 删掉 n：抽样数改由后端按 genCount 自动推导，不再让用户设）
    selectedTag: '',
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
      useContextStore().releaseOwner('kaipin')
    },
  },
  // 不持久化(08.03 二次修正,同 context.js):分析卡/生图结果是干活中间态,切页靠内存态就够
  // (hash 路由切页不重载页面),F5 该是干净重来。sessionStorage 也不行——它跨 F5 存活。
})

import { defineStore } from 'pinia'

// 导入建品的态。放 store 而非组件本地,切页再切回不丢(选的文件夹/识别结果/进度保持)。
// groups(含图片base64)不持久化到 localStorage(太大),只在会话内存里存活。
export const useImportStore = defineStore('importFlow', {
  state: () => ({
    folderName: '',
    groups: null, // { main:[], detail:[], white:[], sku:[] } 含 base64,仅内存
    counts: { main: 0, detail: 0, white: 0, sku: 0 },
    running: false,
    progress: 0,
    msg: '',
    msgType: '',
    done: false,
    lastImportedFolder: '',
    // 识别/生成分离：识别段完成后停在确认页,存识别结果供确认+回传生成段。
    recognized: false, // 识别完成、等用户确认生成
    recCategory: '', // 识别出的品类
    recProductName: '', // 识别出的主件名
    recSkus: [], // 识别出的主件+配件清单 [{itemCode,name,role}],确认后回传 /generate-layout
    recWarnings: [], // 识别段告警
    styled: false, // 是否已风格迁移:迁移前导入的原图不给重生/重绘,迁移后才给
  }),
  actions: {
    reset() {
      this.folderName = ''
      this.groups = null
      this.counts = { main: 0, detail: 0, white: 0, sku: 0 }
      this.progress = 0
      this.msg = ''
      this.msgType = ''
      this.done = false
      this.recognized = false
      this.recCategory = ''
      this.recProductName = ''
      this.recSkus = []
      this.recWarnings = []
      this.styled = false
    },
  },
})

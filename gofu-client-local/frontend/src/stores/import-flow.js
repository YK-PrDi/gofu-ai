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
    },
  },
})

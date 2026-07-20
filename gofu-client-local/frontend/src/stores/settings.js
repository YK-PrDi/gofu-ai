import { defineStore } from 'pinia'
import { api } from '@/api.js'

// 全局设置 + 参考数据:行为开关(跨页读)、生图Agent、防比价模板库、快麦ERP配置。
// 源:index.html 的 settings/agents/antipriceTemplates/erpCfg + loadAgents/loadAntipriceTemplates/openErpConfig/saveErpConfig/refreshErpToken。
export const useSettingsStore = defineStore('settings', {
  state: () => ({
    // 行为开关(默认值与旧版一致)
    settings: {
      reviewImages: true,
      confirmBeforeListing: true,
      genAllPlansSku: false,
      batchAutoList: false,
      batchAutoPreview: true,
      batchAutoGen: true,
    },
    agents: [{ id: 'gpt-image', name: 'GPT-Image 2' }], // 兜底,init 拉全量
    antipriceTemplates: [], // 防比价模板库(云端)
    loaded: false,
  }),
  actions: {
    // 拉可用生图 Agent(源 loadAgents)
    async loadAgents() {
      try {
        const a = await api.get('/api/gen/agents')
        if (Array.isArray(a) && a.length) this.agents = a
      } catch (_) {}
    },
    // 拉防比价模板库(源 loadAntipriceTemplates)
    async loadAntipriceTemplates() {
      try {
        const d = await api.get('/api/ly-gen/antiprice-templates')
        this.antipriceTemplates = (d.templates || []).map((t) => ({ id: t.id, name: t.name }))
      } catch (_) {}
    },
    // 拉快麦 ERP 配置(源 openErpConfig)
    async loadErpConfig() {
      return api.get('/api/erp/config')
    },
    // 保存快麦 ERP 配置(源 saveErpConfig)
    async saveErpConfig(form) {
      const d = await api.post('/api/erp/config', form)
      if (d.error) throw new Error(d.error)
      return d
    },
    // 刷新 Token 并回读最新配置(源 refreshErpToken)
    async refreshErpToken() {
      const d = await api.post('/api/erp/refresh-token')
      if (d.error) throw new Error(d.error)
      return api.get('/api/erp/config')
    },
    async init() {
      if (this.loaded) return
      await Promise.all([this.loadAgents(), this.loadAntipriceTemplates()])
      this.loaded = true
    },
  },
  persist: { pick: ['settings'] }, // 行为开关持久化,agents/模板每次重拉
})

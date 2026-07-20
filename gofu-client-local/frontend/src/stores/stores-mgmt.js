import { defineStore } from 'pinia'
import { api } from '@/api.js'

// 多店铺管理 —— 全局能力(旧 stores.js/StoreMixin 平移)。
// 反风控铁律:每店独立 profile/cookie 隔离,脚本一行不动,仅按 profile 传路径。
// 逻辑原样保留:顺位继承默认选店 / 3s轮询登录态 / 24h过期判断 / 加店即弹扫码。
export const useStoresStore = defineStore('storesMgmt', {
  state: () => ({
    targetProfile: '', // 第4步「目标店铺」选中的 profile;'' = 默认单店旧行为
    list: [],
    adding: false,
    msg: '',
    msgType: '',
    _timer: null,
  }),
  actions: {
    async loadStores() {
      try {
        const d = await api.get('/api/semi-auto/stores')
        this.list = d.stores || []
        // 目标店铺默认【顺位继承】:targetProfile 空时按 store_1→2… 取第一个已登录店。
        // 避免登录了某店、下拉却停在别处→上新跑错店要重登。不覆盖用户手动选择。
        if (!this.targetProfile) {
          const logged = this.list.find((s) => s.loggedIn)
          if (logged) this.targetProfile = logged.profile
        }
      } catch (e) {
        this.msg = '加载店铺失败：' + e.message
        this.msgType = 'err'
      }
    },
    // 轮询自动刷新:登录/扫码耗时不定,每3s拉一次;检测到"登录态变已登录"或
    // "店铺名回填"即停;最多2分钟(40×3s)兜底。免去退出重进。
    pollStoreUntilReady(profile, prevName, prevLoggedIn) {
      let n = 0
      if (this._timer) clearInterval(this._timer)
      this._timer = setInterval(async () => {
        n++
        await this.loadStores()
        const s = this.list.find((x) => x.profile === profile)
        const nameChanged = s && s.name && s.name !== prevName && !/^未命名店铺/.test(s.name)
        const loginChanged = s && s.loggedIn && !prevLoggedIn
        if (nameChanged || loginChanged || n >= 40) {
          clearInterval(this._timer)
          this._timer = null
          if (s && s.loggedIn) {
            this.msg = '「' + (s.name || s.profile) + '」已登录'
            this.msgType = 'ok'
          }
        }
      }, 3000)
    },
    // 加店铺:不打名字→后端建占位店铺(自动分配profile+临时名)→立刻弹浏览器扫码。
    async addAndLogin() {
      this.adding = true
      try {
        const seq = this.list.length + 1
        const d = await api.post('/api/semi-auto/stores', { name: '未命名店铺' + seq })
        if (d.error) throw new Error(d.error)
        await this.loadStores()
        const lg = await api.post('/api/semi-auto/stores/login', { profile: d.profile })
        if (lg.error) throw new Error(lg.error)
        this.msg = '已弹出浏览器，请扫码登录；登录后自动识别店铺名并刷新'
        this.msgType = 'ok'
        this.pollStoreUntilReady(d.profile, '未命名店铺' + seq, false)
      } catch (e) {
        this.msg = '加店铺失败：' + e.message
        this.msgType = 'err'
      } finally {
        this.adding = false
      }
    },
    // 重新登录已有店铺(登录态过期时用)
    async loginStore(s) {
      try {
        const d = await api.post('/api/semi-auto/stores/login', { profile: s.profile })
        if (d.error) throw new Error(d.error)
        this.msg = '已为「' + (s.name || s.profile) + '」弹出浏览器，请扫码登录；完成后自动刷新'
        this.msgType = 'ok'
        this.pollStoreUntilReady(s.profile, s.name, s.loggedIn)
      } catch (e) {
        this.msg = '登录启动失败：' + e.message
        this.msgType = 'err'
      }
    },
    // 登录态文案:cookie存在≠当前有效(拼多多token会过期)。保活每8h回写,
    // lastActiveMs超24h未刷新=保活多次失败/很可能失效→"可能过期"提示重登。
    storeLoginText(s) {
      if (!s || !s.loggedIn) return '未登录'
      const last = s.lastActiveMs || 0
      if (last && Date.now() - last > 24 * 3600 * 1000) return '可能过期'
      return '已登录'
    },
    storeLoginStale(s) {
      return this.storeLoginText(s) === '可能过期'
    },
    // 手动改店铺名
    async renameStore(s, name) {
      const nn = (name || '').trim()
      if (!nn || nn === s.name) return
      try {
        const d = await api.post('/api/semi-auto/stores', { name: nn, profile: s.profile })
        if (d.error) throw new Error(d.error)
        await this.loadStores()
      } catch (e) {
        this.msg = '改名失败：' + e.message
        this.msgType = 'err'
      }
    },
  },
  persist: { pick: ['targetProfile'] }, // 记住选中的目标店铺,跨页/刷新保持
})

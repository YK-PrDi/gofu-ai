import { defineStore } from 'pinia'

// 会话态:登录 token + 当前用户。B1 模型下 token 由云端签发,本地只存与透传。
// DEV_BYPASS: 云端鉴权端点尚未就绪前,先放行(接缝为真,翻此开关即接入)。
export const DEV_BYPASS_AUTH = true

export const useSessionStore = defineStore('session', {
  state: () => ({
    token: '',
    user: null, // { name, role: 'admin' | 'operator' }
  }),
  getters: {
    isLoggedIn: (s) => DEV_BYPASS_AUTH || !!s.token,
    isAdmin: (s) => s.user?.role === 'admin',
  },
  actions: {
    setAuth(token, user) {
      this.token = token
      this.user = user
    },
    logout() {
      this.token = ''
      this.user = null
    },
  },
  persist: true, // token/user 存 localStorage,刷新不掉登录
})

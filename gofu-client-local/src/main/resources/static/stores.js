/**
 * 多店铺管理 —— 作为 workbench 工作台的全局能力（Vue mixin 注入 createApp）。
 * 每店独立 profile/cookie 隔离（方案A：反风控脚本一行不动，仅 Java 侧按 profile 传路径）。
 * 上新时在第 4 步「目标店铺」选 profile，from-context 透传该店 cookie/profile 路径。
 * 后端沿用已就绪的 /api/semi-auto/stores（stores.json + 每店独立 profile 目录）。
 */
window.StoreMixin = {
  data() {
    return {
      targetProfile: '',   // 第4步「目标店铺」选中的 profile；'' = 默认单店旧行为
      stores: {
        open: false,
        list: [],
        adding: false,
        msg: '',
        msgType: '',
      },
    };
  },
  methods: {
    async storeApi(url, body) {
      const r = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body || {}),
      });
      const d = await r.json();
      if (!r.ok || d.error) throw new Error(d.error || ('HTTP ' + r.status));
      return d;
    },
    openStores() {
      this.stores.open = true;
      this.stores.msg = '';
      this.loadStores();
    },
    async loadStores() {
      try {
        const d = await (await fetch('/api/semi-auto/stores')).json();
        this.stores.list = d.stores || [];
      } catch (e) {
        this.stores.msg = '加载店铺失败：' + e.message;
        this.stores.msgType = 'err';
      }
    },
    // 加店铺：不用打名字 → 后端建占位店铺(自动分配 profile+临时名) → 立刻弹浏览器扫码；
    // 登录成功后脚本尽力抓真实店铺名回填(抓不到保留"未命名店铺N"，可点名字手改)。
    async addAndLogin() {
      this.stores.adding = true;
      try {
        const seq = this.stores.list.length + 1;
        const d = await this.storeApi('/api/semi-auto/stores', { name: '未命名店铺' + seq });   // profile 后端自动分配
        await this.loadStores();
        await this.storeApi('/api/semi-auto/stores/login', { profile: d.profile });
        this.stores.msg = '已弹出浏览器，请扫码登录；登录后自动识别店铺名并刷新';
        this.stores.msgType = 'ok';
        setTimeout(() => this.loadStores(), 15000);
      } catch (e) {
        this.stores.msg = '加店铺失败：' + e.message;
        this.stores.msgType = 'err';
      } finally {
        this.stores.adding = false;
      }
    },
    // 重新登录已有店铺（登录态过期时用）
    async loginStore(s) {
      try {
        await this.storeApi('/api/semi-auto/stores/login', { profile: s.profile });
        this.stores.msg = '已为「' + (s.name || s.profile) + '」弹出浏览器，请扫码登录；完成后自动刷新';
        this.stores.msgType = 'ok';
        setTimeout(() => this.loadStores(), 15000);
      } catch (e) {
        this.stores.msg = '登录启动失败：' + e.message;
        this.stores.msgType = 'err';
      }
    },
    // 手动改店铺名（自动识别不到，或想改成自定义名时）
    async renameStorePrompt(s) {
      const nn = window.prompt('修改店铺名', s.name || '');
      if (nn === null) return;
      const name = nn.trim();
      if (!name || name === s.name) return;
      try {
        await this.storeApi('/api/semi-auto/stores', { name, profile: s.profile });
        await this.loadStores();
      } catch (e) {
        this.stores.msg = '改名失败：' + e.message;
        this.stores.msgType = 'err';
      }
    },
  },
};

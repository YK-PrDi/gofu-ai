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
        // 目标店铺默认自动选中【第一个已登录】的店:避免运营在店铺管理登录了某店、
        // 但第4步下拉停在"默认店铺"→上新跑单店默认profile(无该店登录态)→要求重登。
        // 仅当当前 targetProfile 为空(没手动选过)时才自动选,不覆盖用户的手动选择。
        if (!this.targetProfile) {
          const logged = this.stores.list.find(s => s.loggedIn);
          if (logged) this.targetProfile = logged.profile;
        }
      } catch (e) {
        this.stores.msg = '加载店铺失败：' + e.message;
        this.stores.msgType = 'err';
      }
    },
    // 轮询自动刷新：登录/扫码耗时不定，每 3s 拉一次 stores，检测到该店“登录态变已登录”或
    // “店铺名回填(不再是未命名)”即刷新到位并停；最多轮询 2 分钟。免去退出重进。
    pollStoreUntilReady(profile, prevName, prevLoggedIn) {
      let n = 0;
      const timer = setInterval(async () => {
        n++;
        await this.loadStores();
        const s = this.stores.list.find(x => x.profile === profile);
        const nameChanged = s && s.name && s.name !== prevName && !/^未命名店铺/.test(s.name);
        const loginChanged = s && s.loggedIn && !prevLoggedIn;
        if (nameChanged || loginChanged || n >= 40) {   // 40×3s = 2分钟兜底
          clearInterval(timer);
          if (s && s.loggedIn) this.stores.msg = '「' + (s.name || s.profile) + '」已登录', this.stores.msgType = 'ok';
        }
      }, 3000);
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
        this.pollStoreUntilReady(d.profile, '未命名店铺' + seq, false);
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
        this.pollStoreUntilReady(s.profile, s.name, s.loggedIn);
      } catch (e) {
        this.stores.msg = '登录启动失败：' + e.message;
        this.stores.msgType = 'err';
      }
    },
    // 登录态显示文案：cookie 存在≠当前有效（拼多多 token 会过期）。保活每 8h 回写一次 cookie，
    // 故 lastActiveMs 超过 24h 未刷新 = 保活已多次失败/登录很可能失效 → 显示"可能过期"，提示重登。
    storeLoginText(s) {
      if (!s || !s.loggedIn) return '未登录';
      const last = s.lastActiveMs || 0;
      if (last && (Date.now() - last) > 24 * 3600 * 1000) return '可能过期';
      return '已登录';
    },
    storeLoginStale(s) {
      return this.storeLoginText(s) === '可能过期';
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

/**
 * 文件夹批量上新 —— workbench 工作台内的独立区块（多套图→多店，按文件夹名识别，串行连续上新）。
 * 以 Vue mixin 注入 createApp，状态收在 batch:{}、方法加 batch 前缀，不碰全自动流与 stores。
 * 后端全就绪：/api/semi-auto/preflight（预检）、/run（批量上新），编排器 SemiAutoOrchestrator。
 * 简化：选目录用"粘贴绝对路径"（Electron 选择器未迁）。
 */
window.BatchMixin = {
  data() {
    return {
      batch: {
        open: false,        // 区块折叠/展开
        rootPath: '',       // 上传重建后的临时目录根路径(不再让用户粘贴)
        folderName: '',     // 用户选的大文件夹名(显示用)
        fileCount: 0,
        outcomes: [],
        busy: false,
        msg: '',
        msgType: '',
        canRun: false,
      },
    };
  },
  computed: {
    batchReadyCount() {
      return this.batch.outcomes.filter(
        o => o.status === 'ready' || o.status === 'listing_started'
      ).length;
    },
  },
  methods: {
    async batchApi(url, body) {
      const r = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body || {}),
      });
      const d = await r.json();
      if (!r.ok || d.error) throw new Error(d.error || ('HTTP ' + r.status));
      return d;
    },
    // 图形选大文件夹(webkitdirectory)：整棵树 base64 上传后端重建临时目录，拿回可扫描根路径塞 batch.rootPath。
    // 应用是浏览器打开、非Electron，选择器拿不到绝对路径，故走上传重建(与风格迁移/导入同思路)。
    batchPickFolder() {
      const inp = document.createElement('input');
      inp.type = 'file'; inp.multiple = true; inp.webkitdirectory = true;
      inp.onchange = () => this._batchUploadTree([...inp.files]);
      inp.click();
    },
    async _batchUploadTree(files) {
      const imgs = files.filter(f => f.type && f.type.startsWith('image/'));
      if (!imgs.length) { this.batchMsg('该文件夹没有图片', 'err'); return; }
      this.batch.folderName = (imgs[0].webkitRelativePath || '').split('/')[0] || '';
      this.batch.busy = true; this.batch.canRun = false; this.batch.outcomes = [];
      this.batchMsg('上传大文件夹（' + imgs.length + ' 张图）到后端…', '');
      try {
        const payload = [];
        for (const f of imgs) {
          const b64 = await this._fileToB64(f);   // 复用导入流的 base64 读取
          const ext = f.name.toLowerCase().endsWith('.png') ? 'png' : 'jpg';
          payload.push({ path: f.webkitRelativePath || f.name, b64, ext });
        }
        const d = await this.batchApi('/api/semi-auto/upload-tree', { files: payload });
        this.batch.rootPath = d.rootPath || '';
        this.batch.fileCount = d.fileCount || 0;
        this.batchMsg('✓ 已上传「' + this.batch.folderName + '」（' + this.batch.fileCount + ' 张图），点「预检」', 'ok');
      } catch (e) {
        this.batchMsg('上传失败：' + e.message, 'err');
      } finally {
        this.batch.busy = false;
      }
    },
    async batchPreflight() {
      this.batch.busy = true;
      this.batch.canRun = false;
      try {
        const d = await this.batchApi('/api/semi-auto/preflight', { rootPath: this.batch.rootPath.trim() });
        this.batch.outcomes = d.outcomes || [];
        this.batch.canRun = this.batchReadyCount > 0;
        this.batchMsg('预检完成：' + this.batchReadyCount + ' 个商品齐全可上新，其余见下方说明', this.batch.canRun ? 'ok' : 'err');
      } catch (e) {
        this.batchMsg('预检失败：' + e.message, 'err');
      } finally {
        this.batch.busy = false;
      }
    },
    async batchRun() {
      if (!confirm('确认对预检齐全的商品批量上新？将逐店逐商品串行操作真实商家后台。')) return;
      this.batch.busy = true;
      try {
        const d = await this.batchApi('/api/semi-auto/run', { rootPath: this.batch.rootPath.trim() });
        this.batch.outcomes = d.outcomes || [];
        const started = this.batch.outcomes.filter(o => o.status === 'listing_started').length;
        this.batchMsg('已启动 ' + started + ' 个商品的上新，实时进度见下方（每个任务独立轮询）', 'ok');
        // A：对每个已启动上新的商品轮询其 taskId，把进度/成败回填到结果表(progress/taskStatus/taskMsg)。
        this.batch.outcomes.forEach((o, i) => { if (o.taskId && o.status === 'listing_started') this.batchPollTask(i); });
      } catch (e) {
        this.batchMsg('批量上新失败：' + e.message, 'err');
      } finally {
        this.batch.busy = false;
      }
    },
    // 轮询单个商品的上新任务(复用 /api/task/{id})，进度/成败写回该 outcome。最多 20 分钟。
    async batchPollTask(idx, tries) {
      tries = tries || 0;
      const o = this.batch.outcomes[idx];
      if (!o || !o.taskId) return;
      if (tries > 800) { o.taskMsg = '轮询超时'; return; }   // 800×1.5s=20分钟
      try {
        const r = await fetch('/api/task/' + o.taskId);
        if (r.ok) {
          const t = await r.json();
          o.progress = t.total > 0 ? t.progress + '/' + t.total : '' + t.progress;
          if (t.status === 'done') { o.taskStatus = 'done'; o.taskMsg = '✓ 上新成功'; return; }
          if (t.status === 'error') {
            o.taskStatus = 'error';
            o.taskMsg = '✗ ' + ((t.results || []).filter(x => x.type === 'error').map(x => x.message).join('；') || '上新失败');
            return;
          }
          o.taskMsg = '上新中… ' + o.progress;
        }
      } catch (e) { /* 网络抖动忽略，继续轮询 */ }
      setTimeout(() => this.batchPollTask(idx, tries + 1), 1500);
    },
    batchMsg(m, t) {
      this.batch.msg = m;
      this.batch.msgType = t || '';
    },
    batchStatusText(s) {
      return {
        ready: '齐全待上', listing_started: '已启动上新', blocked: '缺素材·已拦',
        shop_unmatched: '店铺未匹配', not_logged_in: '店铺未登录',
        sku_gen_available: '缺图·可AI生成',
      }[s] || s;
    },
    batchStatusClass(s) {
      if (s === 'ready' || s === 'listing_started') return 'tag-ok';
      if (s === 'blocked') return 'tag-err';
      return 'tag-warn';
    },
    // #3 缺SKU图→AI生成:复用导入链(建context→快麦拉白底→云端出方案+补SKU图)。非阻塞,轮询 /import-progress。
    async batchGenSku(idx) {
      const o = this.batch.outcomes[idx];
      if (!o || !o.folderPath) { this.batchMsg('该商品无文件夹路径，无法生成', 'err'); return; }
      o.taskStatus = 'gen'; o.taskMsg = 'AI生成SKU图中…（建档→拉白底→出方案→补图）';
      try {
        const d = await this.batchApi('/api/semi-auto/gen-sku', { folderPath: o.folderPath });
        if (!d.importId) throw new Error('未返回 importId');
        // 轮询导入进度(复用 /import-progress)
        while (true) {
          await new Promise(res => setTimeout(res, 1500));
          let t;
          try { const r = await fetch('/api/semi-auto/import-progress/' + d.importId); t = await r.json(); }
          catch (e) { continue; }
          o.taskMsg = 'AI生成 ' + (t.pct || 0) + '% · ' + (t.phase || '处理中…');
          if (t.done) {
            if (t.error) { o.taskStatus = 'error'; o.taskMsg = '✗ 生成失败：' + t.error; return; }
            o.taskStatus = 'done';
            o.taskMsg = '✓ 已生成SKU图并建商品，可在「云端商品」加载后上新（SKU方案' + ((t.result && t.result.skuPlanCount) || 0) + '个）';
            return;
          }
        }
      } catch (e) { o.taskStatus = 'error'; o.taskMsg = '✗ 生成失败：' + e.message; }
    },
  },
};

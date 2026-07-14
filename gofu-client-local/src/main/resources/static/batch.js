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
        rootPath: '',
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
      }[s] || s;
    },
    batchStatusClass(s) {
      if (s === 'ready' || s === 'listing_started') return 'tag-ok';
      if (s === 'blocked') return 'tag-err';
      return 'tag-warn';
    },
  },
};

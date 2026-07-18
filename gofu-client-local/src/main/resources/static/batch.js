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
        preview: null,      // 轻量预览:{name,shop,category,main[],detail[],white[],sku[]} (本地图URL)
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
    // 按店铺分组呈现(保留原索引 _i 供预览/生成按钮定位到 batch.outcomes)
    batchByShop() {
      const groups = [];
      const idx = {};
      this.batch.outcomes.forEach((o, i) => {
        const shop = o.shopName || '(未知店铺)';
        if (!(shop in idx)) { idx[shop] = groups.length; groups.push({ shop, rows: [] }); }
        groups[idx[shop]].rows.push({ ...o, _i: i });
      });
      return groups;
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
        this.batchMsg('✓ 已上传「' + this.batch.folderName + '」（' + this.batch.fileCount + ' 张图），预检中…', '');
        await this.batchPreflight();   // 上传后自动预检，不用人工再点
      } catch (e) {
        this.batchMsg('上传失败：' + e.message, 'err');
      } finally {
        this.batch.busy = false;
      }
    },
    // 为某商品确保有 context：已建过直接返回;否则走 gen-sku(读本地图→建context+方案+快麦白底)。返回 contextId。
    // 复用导入链,批量流每个商品(含齐全待上的本地直传品)都能进右侧 ctx 预览面板。
    async _batchEnsureContext(o) {
      if (o.contextId) return o.contextId;
      const d = await this.batchApi('/api/semi-auto/gen-sku', { folderPath: o.folderPath });
      if (!d.importId) throw new Error('未返回 importId');
      while (true) {
        await new Promise(res => setTimeout(res, 1500));
        let t;
        try { const r = await fetch('/api/semi-auto/import-progress/' + d.importId); t = await r.json(); }
        catch (e) { continue; }
        o.taskMsg = '建档 ' + (t.pct || 0) + '% · ' + (t.phase || '处理中…');
        if (t.done) {
          if (t.error) throw new Error(t.error);
          o.contextId = (t.result && t.result.contextId) || '';
          o.warnings = (t.result && t.result.warnings) || [];   // 缺白底图等提示,供前端显示
          if (!o.contextId) throw new Error('未建出 contextId');
          return o.contextId;
        }
      }
    },
    // 👁 轻量预览：只读该商品文件夹已有的主图/详情/白底/sku图直接显示，不建context、不碰云端(零额度)。
    //    (建context走云端出方案是给"AI生成缺图"的重路径,只看已有图不该用它。)
    async batchPreview(idx) {
      const o = this.batch.outcomes[idx];
      if (!o || !o.folderPath) { this.batchMsg('该商品无文件夹路径，无法预览', 'err'); return; }
      try {
        const d = await this.batchApi('/api/semi-auto/product-images', { folderPath: o.folderPath });
        const toUrl = p => '/api/erp/local-image?path=' + encodeURIComponent(p);
        // 白底图=本地(local-image代理) + 快麦兜底(whiteErp,http原样,浏览器直连pdd图床)
        const white = (d.white || []).map(toUrl).concat(d.whiteErp || []);
        this.batch.preview = {
          name: (o.mainItem || o.productName), shop: o.shopName, category: o.category, title: o.title || '',
          main: (d.main || []).map(toUrl), detail: (d.detail || []).map(toUrl),
          white, sku: (d.sku || []).map(toUrl),
        };
        this.batchMsg('预览「' + this.batch.preview.name + '」：主图' + (d.main||[]).length + '·详情' + (d.detail||[]).length + '·白底' + white.length + '·sku' + (d.sku||[]).length, 'ok');
      } catch (e) {
        this.batchMsg('预览失败：' + e.message, 'err');
      }
    },
    // 全自动补生:对所有"缺图·可AI生成"的商品串行跑 batchGenSku(建context→补生SKU图→定价→按设置上新)。
    // 不用人工逐个点。串行(共用生图队列);单个失败不影响其余。
    async _batchAutoGenAll() {
      const targets = this.batch.outcomes
        .map((o, i) => ({ o, i }))
        .filter(x => x.o.status === 'sku_gen_available' && !x.o.taskStatus);
      if (!targets.length) return;
      this.batchMsg('自动为 ' + targets.length + ' 个缺图商品生成SKU图…（串行，见各行进度）', '');
      for (const { i } of targets) {
        try { await this.batchGenSku(i); } catch (e) { /* 单个失败已写回该行 */ }
      }
      this.batchMsg('批量自动流程完成（预览+缺图补生）。' + (this.settings.batchAutoList ? '已按设置自动上新。' : '未开自动上新，请核对后上。'), 'ok');
    },
    async batchPreflight() {
      this.batch.canRun = false;
      try {
        const d = await this.batchApi('/api/semi-auto/preflight', { rootPath: this.batch.rootPath.trim() });
        this.batch.outcomes = d.outcomes || [];
        this.batch.canRun = this.batchReadyCount > 0;
        this.batchMsg('预检完成：' + this.batchReadyCount + ' 个商品齐全可上新，其余见下方说明', this.batch.canRun ? 'ok' : 'err');
        // 全自动链(默认开)：轻量预览首品(零云端) → 缺图商品自动AI补生(不用手点)。设置可关。
        if (this.batch.outcomes.length) {
          if (this.settings.batchAutoPreview !== false) await this.batchPreview(0);
          if (this.settings.batchAutoGen !== false) this._batchAutoGenAll();
        }
      } catch (e) {
        this.batchMsg('预检失败：' + e.message, 'err');
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
    // #3 缺SKU图→AI生成:复用导入链。完整链=建context→快麦拉白底→出方案→【补生SKU图(step2)】→【定价】，
    //    设置「batchAutoList」开=继续【从context上新到该店铺】;默认关=停在"已生图+定价"等人工。非阻塞,进度写回该行。
    async batchGenSku(idx) {
      const o = this.batch.outcomes[idx];
      if (!o || !o.folderPath) { this.batchMsg('该商品无文件夹路径，无法生成', 'err'); return; }
      if (this._batchGenBusy) { this.batchMsg('已有一个AI生成/预览在跑，请等它完成(共用生图队列)', 'err'); return; }
      this._batchGenBusy = true;
      o.taskStatus = 'gen'; o.taskMsg = 'AI生成中…（建档→拉白底→出方案）';
      try {
        // 1) 确保有 context(复用 _batchEnsureContext:建context+方案+标题+快麦白底)
        const contextId = await this._batchEnsureContext(o);
        // 2) 把该 context 载入工作台(复用 fillCostAndPrice/plans 需要工作台状态)，补生SKU图+定价
        this.contextId = contextId;
        await this.loadContext();
        // 2a) 补生当前方案缺失的SKU图(step2 skuOnlyMissing)——白底图已由 gen-sku 从快麦拉好
        const plan0 = (this.plans && this.plans[0]) || null;
        const missImg = plan0 ? (plan0.items || []).filter(it => !it.imgDir).length : 0;
        const hasWhite = (this.ctx?.visual?.whiteImages || []).length > 0;
        if (missImg > 0 && hasWhite) {
          o.taskMsg = '补生 ' + missImg + ' 张SKU图中…（进度见右侧）';
          const r = await fetch('/api/flow/step2', { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ contextId, planIndex: 0, genDetail: false, genSku: true, skuOnlyMissing: true }) });
          const sd = await r.json();
          if (!sd.error && sd.taskId) {
            // 借右侧生图进度条(genRunning+genProgress,pollFlowTask 会驱动)显示SKU补生进度
            this.genRunning = true; this.genDone = false; this.genProgress = 2; this.genMsg = 'SKU补生中…';
            try { await this.pollFlowTask(sd.taskId, sd.total || 0, 2, 98); this.genProgress = 100; this.genDone = true; }
            finally { this.genRunning = false; }
            await this.loadContext();
          }
        } else if (missImg > 0 && !hasWhite) {
          throw new Error('缺SKU图但无白底图可参考(快麦也没拉到)，请在快麦补白底图后重试，或手动导入SKU图');
        }
        // 2b) 定价(复用导入流 fillCostAndPrice)
        o.taskMsg = '自动定价中…';
        await this.fillCostAndPrice();
        const zero = (this.plans[0]?.items || []).filter(it => !(it.groupPrice > 0)).length;
        if (zero > 0) { o.taskStatus = 'error'; o.taskMsg = '✗ 有 ' + zero + ' 个SKU定不出价(快麦缺进价)，请补进价后重试'; return; }
        // 补生+定价成功→清掉预检旧的"缺图"文案(#4:那是补生前的,已过时),并把新SKU图+AI标题刷进预览
        o.missing = (o.missing || []).filter(m => !m.includes('缺图'));
        o.title = this.ctx?.visual?.title || '';   // 存该商品AI标题,预览strip显示
        if (this.batch.preview && this.batch.preview.name === (o.mainItem || o.productName)) {
          this.batch.preview.sku = (this.skuImages || []).map(k => this.signed[k]).filter(Boolean);
          this.batch.preview.title = o.title;
        }
        // 3) 按设置决定是否自动上新
        if (!this.settings.batchAutoList) {
          o.taskStatus = 'done';
          o.taskMsg = '✓ 已生图+定价(contextId=' + contextId.slice(0, 8) + ')。设置未开自动上新，请到「云端商品」核对后上新。';
          return;
        }
        o.taskMsg = '从context上新到「' + o.shopName + '」…';
        const lr = await fetch('/api/listing/from-context', { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ contextId, planIndex: 0, dryRun: false, storeProfile: o.shopProfile || '' }) });
        const ld = await lr.json();
        if (ld.error || !ld.taskId) throw new Error(ld.error || '上新未返回taskId');
        o.taskId = ld.taskId;
        this.batchPollTask(idx);   // 复用批量上新任务轮询，成败写回该行
        o.taskMsg = '✓ 已启动上新，进度见下…';
      } catch (e) {
        o.taskStatus = 'error'; o.taskMsg = '✗ 生成失败：' + e.message;
      } finally {
        this._batchGenBusy = false;
      }
    },
  },
};

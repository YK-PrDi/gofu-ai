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
    // 利润率默认在 33/45/53 三档里随机选一个(批量流默认,与手动流的58%分开)。
    batchRandomProfit() { this.profitRate = [0.33, 0.45, 0.53][Math.floor(Math.random() * 3)]; },
    // 展开/收起批量区块。展开且不在跑批时把利润率随机到 33/45/53,否则滑块一直显示手动流的58%默认。
    batchToggle() {
      this.batch.open = !this.batch.open;
      if (this.batch.open && !this.batch.busy) this.batchRandomProfit();
    },
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
      // 利润率每次选新大文件夹时重新随机(33/45/53)。用户可拖滑块微调后「按此重算」。
      this.batchRandomProfit();
      this.batchMsg('上传大文件夹（' + imgs.length + ' 张图，本批利润率随机=' + (this.profitRate * 100).toFixed(0) + '%）到后端…', '');
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
        // SKU图:本地文件夹的sku图(d.sku) ∪ 已补生方案里的AI生成图(o._plans首套的_img)。
        // 修:补生商品的SKU图是AI生成、存云端context(o._plans[].items[]._img)、不在本地文件夹→
        //    原来只取 d.sku 导致点预览显示"SKU图(0)/无SKU图"。本地无sku图时回退用方案首套的生成图。
        const folderSku = (d.sku || []).map(toUrl);
        const planSku = ((o._plans && o._plans[0] && o._plans[0].items) || []).map(it => it._img).filter(Boolean);
        const sku = folderSku.length ? folderSku : planSku;
        this.batch.preview = {
          name: (o.mainItem || o.productName), shop: o.shopName, category: o.category, title: o.title || '',
          main: (d.main || []).map(toUrl), detail: (d.detail || []).map(toUrl),
          white, sku,
          plans: o._plans || null, planIdx: 0,   // 已补生过的商品带出其方案(布局);未补生则空
          contextId: o.contextId || '',           // 已建context的带出,标题可编辑存回
        };
        this.batchMsg('预览「' + this.batch.preview.name + '」：主图' + (d.main||[]).length + '·详情' + (d.detail||[]).length + '·白底' + white.length + '·sku' + sku.length, 'ok');
      } catch (e) {
        this.batchMsg('预览失败：' + e.message, 'err');
      }
    },
    // 标题编辑后存回 context(上新用 context.visual.title)。拉最新context→改title→保存,不覆盖其它字段。
    async batchSaveTitle() {
      const p = this.batch.preview;
      if (!p || !p.contextId) return;
      try {
        const r = await fetch('/api/context/' + encodeURIComponent(p.contextId));
        if (!r.ok) throw new Error('HTTP ' + r.status);
        const ctx = await r.json();
        if ((ctx.visual?.title || '') === p.title) return;   // 没改就不存
        ctx.visual.title = p.title;
        const sr = await fetch('/api/context', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ctx) });
        if (!sr.ok) throw new Error('HTTP ' + sr.status);
        // 同步到该行 o.title
        const o = this.batch.outcomes.find(x => (x.mainItem || x.productName) === p.name);
        if (o) o.title = p.title;
        this.batchMsg('标题已保存', 'ok');
      } catch (e) { this.batchMsg('标题保存失败：' + e.message, 'err'); }
    },
    // 全自动补生:对所有"缺图·可AI生成"的商品串行跑 batchGenSku(建context→补生SKU图→定价→按设置上新)。
    // 不用人工逐个点。串行(共用生图队列);单个失败不影响其余。
    async _batchAutoGenAll() {
      const targets = this.batch.outcomes
        .map((o, i) => ({ o, i }))
        .filter(x => x.o.status === 'sku_gen_available' && !x.o.taskStatus);
      if (!targets.length) return;
      this.batchMsg('自动为 ' + targets.length + ' 个缺图商品生成SKU图…（串行，见各行进度）', '');
      // 先把待补生的都标"排队中"(去掉手动按钮的空闲态,让用户知道会自动跑、只是在等前一个)
      targets.forEach(({ o }, k) => { if (k > 0) { o.taskStatus = 'queued'; o.taskMsg = '排队等待自动补生…'; } });
      for (const { i, o } of targets) {
        o.taskStatus = '';   // 轮到自己:清排队态,batchGenSku 内会置 'gen'
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
    // 智能分流上新：ready 商品分两类走不同路径——
    //  · 有 contextId(AI补生过)：走 from-context 上新。成果(SKU图/方案)在云端 context,若走重扫文件夹的
    //    /run 会因本地无SKU图判"缺图"丢成果,故必须 from-context。
    //  · 无 contextId(文件夹本就齐全):走 /run(编排器从本地文件夹组config上新)。
    //  服务端 browserBusy 闸(ListingService)已串行化所有上新进程,并发调用会自动排队,无需前端错开。
    async batchRun() {
      const ready = this.batch.outcomes
        .map((o, i) => ({ o, i }))
        .filter(x => x.o.status === 'ready' && x.o.taskStatus !== 'listing_started');
      const ctxItems = ready.filter(x => x.o.contextId);
      const folderItems = ready.filter(x => !x.o.contextId);
      if (!ctxItems.length && !folderItems.length) { this.batchMsg('没有齐全待上的商品', 'err'); return; }
      if (!confirm('确认对 ' + ready.length + ' 个齐全商品批量上新？将逐店逐商品串行操作真实商家后台。')) return;
      this.batch.busy = true;
      try {
        let started = 0;
        // 1) 有 context 的逐个走 from-context(带该店 profile,不重扫文件夹)
        for (const { o, i } of ctxItems) {
          try {
            const lr = await fetch('/api/listing/from-context', { method: 'POST', headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ contextId: o.contextId, planIndex: 0, dryRun: false, storeProfile: o.shopProfile || '' }) });
            const ld = await lr.json();
            if (ld.error || !ld.taskId) throw new Error(ld.error || '未返回 taskId');
            o.taskId = ld.taskId; o.status = 'listing_started'; o.taskStatus = ''; o.taskMsg = '✓ 已启动上新…';
            this.batchPollTask(i); started++;
          } catch (e) { o.taskStatus = 'error'; o.taskMsg = '✗ 上新启动失败：' + e.message; }
        }
        // 2) 文件夹本就齐全(无context)的走 /run。/run 重扫全根,context 商品会因本地缺SKU图被判非ready而跳过,
        //    故只取其返回里 listing_started 的行,按(店铺,商品名)回填到对应 folderItems,不覆盖 context 行。
        if (folderItems.length) {
          const d = await this.batchApi('/api/semi-auto/run', { rootPath: this.batch.rootPath.trim() });
          const runStarted = (d.outcomes || []).filter(x => x.status === 'listing_started');
          for (const { o, i } of folderItems) {
            const hit = runStarted.find(x => x.shopName === o.shopName && (x.productName === o.productName || x.mainItem === o.mainItem));
            if (hit && hit.taskId) {
              o.taskId = hit.taskId; o.status = 'listing_started'; o.taskStatus = ''; o.taskMsg = '✓ 已启动上新…';
              this.batchPollTask(i); started++;
            }
          }
        }
        this.batchMsg('已启动 ' + started + ' 个商品的上新，实时进度见下方（每个任务独立轮询）', started ? 'ok' : 'err');
      } catch (e) {
        this.batchMsg('批量上新失败：' + e.message, 'err');
      } finally {
        this.batch.busy = false;
      }
    },
    // 拖动利润率滑块后重算已生成商品的定价：逐个载入其 context→按当前 profitRate 重算落库。
    // 只对已建 context(补生/定价过)的商品有效；未生成的等生成时自然用新利润率。
    async batchRecalcPrice() {
      const targets = this.batch.outcomes.map((o, i) => ({ o, i })).filter(x => x.o.contextId);
      if (!targets.length) { this.batchMsg('还没有已生成定价的商品，改利润率会在下次生成时生效', 'err'); return; }
      this.batch.busy = true;
      try {
        let n = 0;
        for (const { o } of targets) {
          this.contextId = o.contextId;
          await this.loadContext();
          await this.fillCostAndPrice();   // 内部用 this.profitRate,算完落库
          // 刷新该行方案(布局)里的价，正在预览的同步刷
          o._plans = (this.ctx?.structure?.plans || []).map(p => ({
            planName: p.planName, description: p.description,
            items: (p.items || []).map(it => ({ ...it, _img: it.imgDir ? (this.signed[it.imgDir] || '') : '' })),
          }));
          if (this.batch.preview && this.batch.preview.name === (o.mainItem || o.productName)) {
            this.batch.preview.plans = o._plans;
          }
          n++;
        }
        this.batchMsg('已按利润率 ' + (this.profitRate * 100).toFixed(0) + '% 重算 ' + n + ' 个商品的定价', 'ok');
      } catch (e) {
        this.batchMsg('重算定价失败：' + e.message, 'err');
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
          if (t.status === 'done') { o.taskStatus = 'done'; o.status = 'listing_started'; o.taskMsg = '✓ 上新成功'; return; }
          if (t.status === 'error') {
            o.taskStatus = 'error';
            // 修:上新失败要把 status 退回 ready(补生完的成果/context还在),否则它卡在 listing_started →
            //    batchRun 的 ready 过滤把它排除 → 前端报"没有齐全待上的商品"、无法重试上新。
            o.status = 'ready';
            o.taskMsg = '✗ ' + ((t.results || []).filter(x => x.type === 'error').map(x => x.message).join('；') || '上新失败') + '（可点「开始批量上新」重试）';
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
          // 修:批量补生原来 step2 只传 genSku,不传 accWhiteImages/templateId → 云端取默认(空配件图+固定
          //    sticker-leftcard 模板)。后果:SKU图无配件、样式千篇一律、防比价没接。与手动流(index.html:1216)对齐:
          //    ① 从方案里收配件编码去快麦拉配件白底图 ② step2 带 accWhiteImages + 随机防比价 templateId。
          const accCodes = [...new Set((plan0.items || [])
            .flatMap(it => (it.accParts || []).map(a => a.code)).filter(Boolean))];
          let accWhiteImages = [];
          if (accCodes.length) {
            try {
              const ar = await fetch('/api/erp/fetch-white-images', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ codes: accCodes }) });
              const ad = await ar.json();
              accWhiteImages = (ad.matched || []).map(m => m.file).filter(Boolean);
            } catch (e) { console.warn('[批量补生] 取配件白底图失败(SKU图可能缺配件):', e); }
          }
          o.taskMsg = '补生 ' + missImg + ' 张SKU图中…（进度见右侧）';
          const r = await fetch('/api/flow/step2', { method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ contextId, planIndex: 0, genDetail: false, genSku: true, skuOnlyMissing: true,
              accWhiteImages, templateId: this.resolveTemplateId() }) });
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
        // 补生+定价成功→清掉预检旧的"缺图"文案(#4),并把 context 的 SKU图+方案(布局)+标题刷进预览strip
        o.missing = (o.missing || []).filter(m => !m.includes('缺图'));
        o.title = this.ctx?.visual?.title || '';
        // 方案(布局):每套 items 附上已签名的SKU图URL(_img),供预览表格显示。
        // 修:原来只在"当前预览的就是本商品"时才算并存 o._plans → 批量自动流预览只锁首品(batchPreview(0))，
        //    第二个店铺的商品补生完 name 对不上、o._plans 从没被设 → 点它👁预览时 plans=null、SKU布局不显示。
        //    改为无条件把本商品自己的方案存到 o._plans(每行独立)，只有"正在预览的就是本商品"才顺带刷进 preview strip。
        const plans = (this.ctx?.structure?.plans || []).map(p => ({
          planName: p.planName, description: p.description,
          items: (p.items || []).map(it => ({ ...it, _img: it.imgDir ? (this.signed[it.imgDir] || '') : '' })),
        }));
        o._plans = plans;   // 存到该行,重新预览时能带出方案(布局),不用再建context
        if (this.batch.preview && this.batch.preview.name === (o.mainItem || o.productName)) {
          this.batch.preview.title = o.title;
          this.batch.preview.contextId = contextId;   // 供标题编辑后存回 context
          this.batch.preview.plans = plans;
          this.batch.preview.planIdx = 0;
          // SKU图缩略:取首套方案各item的图
          this.batch.preview.sku = (plans[0]?.items || []).map(it => it._img).filter(Boolean);
        }
        // 3) 按设置决定是否自动上新
        if (!this.settings.batchAutoList) {
          o.taskStatus = 'done';
          // 修:补生+定价完=齐全待上(成果在云端context)。原来 status 卡在 sku_gen_available →
          //    标签绿底却写"缺图"自相矛盾、且 batchReadyCount=0 让「开始批量上新」按钮一直灰。
          //    置 ready 并保留 o.contextId,batchRun 据 contextId 走 from-context 上新(不重扫文件夹丢云端图)。
          o.status = 'ready';
          o.taskMsg = '✓ 已生图+定价(contextId=' + contextId.slice(0, 8) + ')，可点「开始批量上新」上到「' + o.shopName + '」，或到「云端商品」核对。';
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

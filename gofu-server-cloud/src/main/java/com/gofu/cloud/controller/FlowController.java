package com.gofu.cloud.controller;

import com.gofu.cloud.config.AppProperties;
import com.gofu.cloud.model.GenerationTask;
import com.gofu.cloud.service.CosService;
import com.gofu.cloud.service.ImageGenerationService;
import com.gofu.cloud.service.context.ContextService;
import com.gofu.cloud.service.lytext.LyTextService;
import com.gofu.shared.context.ProductContext;
import com.gofu.shared.context.SkuPlan;
import com.gofu.shared.enums.FlowStage;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 双轨交错编排（M8）。把"羽刃出图 ↔ 乐羽出结构"的交错流程串成两步，ProductContext 当中枢。
 *
 * <p>step1：记录主件白底图 → 生成首张主图(羽刃线) + SKU 布局(乐羽线) → stage=LAYOUT_DONE
 * <p>step2：生成剩余主图/详情 + SKU 图 → stage=SKU_DONE
 *
 * <p>B3：主图用 generateImageMulti（不依赖大参考目录，prompt+白底图即可）真实出图，产物走 COS。
 * 白底图入参可为 COS key/http URL/本地路径，生图前统一 localizeWhite 下载成本地文件
 * （GptImageAgent 只读本地文件，不下 URL）。无白底图则跳过并如实标注。
 */
@RestController
@RequestMapping("/api/flow")
public class FlowController {

    private static final Logger log = LoggerFactory.getLogger(FlowController.class);

    private final ImageGenerationService imageGen;
    private final LyTextService lyTextService;
    private final ContextService contextService;
    private final CosService cosService;
    private final AppProperties appProperties;
    private final com.gofu.cloud.service.lyimage.ImageGenService lyImageGen;   // 乐羽成熟 SKU 生图(sticker贴图/基准图)
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();

    public FlowController(ImageGenerationService imageGen, LyTextService lyTextService,
                          ContextService contextService, CosService cosService,
                          AppProperties appProperties,
                          com.gofu.cloud.service.lyimage.ImageGenService lyImageGen) {
        this.imageGen = imageGen;
        this.lyTextService = lyTextService;
        this.contextService = contextService;
        this.cosService = cosService;
        this.appProperties = appProperties;
        this.lyImageGen = lyImageGen;
    }

    /**
     * 交错第一步。入参 {@code { contextId?, category, mainItem?, whiteImages:[url], skus:[...], sellingPoints?:[] }}。
     * 记录白底图 → 生成首张主图(best-effort) → 生成 SKU 布局 → 写 context, stage=LAYOUT_DONE。
     */
    @PostMapping("/step1")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> step1(@RequestBody Map<String, Object> body) {
        try {
            ProductContext ctx = resolveContext(body);
            List<String> rawWhites = (List<String>) body.getOrDefault("whiteImages", List.of());
            // 白底图先本地化：data URL(前端拖拽的base64) → 落地成本地文件路径，避免原始 base64 存进
            // context_json 导致 SQLite blob 膨胀+加载慢；http/COS key/本地路径原样保留。
            // data URL 本地化失败则丢弃(不回退存原始 base64，否则违背避免膨胀的初衷)。
            List<String> localWhites = new ArrayList<>();
            for (String w : rawWhites) {
                if (w == null || w.isBlank()) continue;
                if (w.startsWith("data:")) {
                    String local = localizeWhite(w);
                    if (local != null) localWhites.add(local);
                    else log.warn("白底图 data URL 本地化失败，已丢弃");
                } else {
                    localWhites.add(w);
                }
            }
            // 重跑同一 context 时先清空旧白底图，避免重复累加
            ctx.getVisual().getWhiteImages().clear();
            ctx.getVisual().getWhiteImages().addAll(localWhites);
            // 先存一次分配 id（异步生图在后台改 ctx，前端要立刻拿到 contextId 供 step2 用）
            contextService.save(ctx);

            int mainTotal = body.get("mainCount") instanceof Number n ? Math.max(1, n.intValue()) : 6;
            String mainAspect = normalizeAspect(body.get("mainAspect"));
            String customRequest = body.get("customRequest") instanceof String s ? s.trim() : "";   // M11:可选手输生图要求
            // 07.09#2 改图风格：前端传 styleId(默认 random)，random 后端随机抽一种，整组主图统一风格。
            String styleReq = resolveStylePrompt(body.get("styleId"));
            if (!styleReq.isBlank()) customRequest = (customRequest + "\n" + styleReq).trim();
            Map<String, Object> planReq = new LinkedHashMap<>(body);

            // 07.08重构：布局(乐羽线/千问)与全部主图(羽刃线/白底图)互不依赖→并行跑，缩短总时长。
            // 主图 step1 就出全部 mainTotal 张(原来只出首图);详情图/SKU图 留到 step2(选定方案后)。
            // 异步:立即返回 taskId,前端轮询;进度总数 = 主图张数(布局不计张)。
            String taskId = "flow1-" + System.nanoTime();
            GenerationTask task = new GenerationTask(taskId, mainTotal);
            task.setStatus("running");
            flowTasks.put(taskId, task);
            final int fMainTotal = mainTotal;
            final String fMainAspect = mainAspect;
            final String fCustomReq = customRequest;
            imageGen.getExecutor().submit(() -> {
                try {
                    // 布局与主图并行
                    var layoutF = java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            var planResult = lyTextService.generateSkuPlans(planReq);
                            var plans = lyTextService.flattenPlans(planResult);
                            synchronized (ctx) {
                                ctx.getStructure().setPlans(plans);
                                ctx.getStructure().setSelectedPlanIndex(0);
                            }
                        } catch (Exception e) {
                            log.warn("step1 SKU 布局生成失败(不阻断): {}", e.getMessage());
                        }
                    }, imageGen.getExecutor());
                    var mainF = java.util.concurrent.CompletableFuture.runAsync(() ->
                            genAllMains(ctx, localWhites, fMainTotal, fMainAspect, task, fCustomReq), imageGen.getExecutor());
                    java.util.concurrent.CompletableFuture.allOf(layoutF, mainF).join();
                    ctx.setStage(FlowStage.LAYOUT_DONE);
                    contextService.save(ctx);
                    task.setStatus("done");
                } catch (Exception e) {
                    log.error("step1 异步编排失败: {}", e.getMessage(), e);
                    task.addResult(Map.of("message", "step1 失败：" + e.getMessage()));
                    task.setStatus("error");
                }
            });

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("taskId", taskId);
            resp.put("total", mainTotal);
            resp.put("contextId", ctx.getId());
            resp.put("whiteImageCount", ctx.getVisual().getWhiteImages().size());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("step1 编排失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "step1 失败：" + e.getMessage()));
        }
    }

    /**
     * step1 主图工作体（M11：改用羽刃自定义模式——先视觉分析真实白底图出产品专属提示词，再逐张生图）。
     * 每张主图用各自的分析段 prompt(不再套静态模板)；首图作基调、2~N 以首图为参考保持系列一致。
     * 分析失败则降级用 buildMainPrompt。customRequest 为空则自动按品类/卖点/主件名生成。
     */
    private void genAllMains(ProductContext ctx, List<String> whiteImages, int mainTotal,
                             String mainAspect, GenerationTask task, String customRequest) {
        if (whiteImages == null || whiteImages.isEmpty()) { log.info("step1 无白底图，跳过主图"); return; }
        String white = localizeWhite(whiteImages.get(0));
        if (white == null) { log.info("step1 白底图无法本地化，跳过主图"); return; }
        File tmpOut = new File(appProperties.getPaths().getTempOutputDir(), "flow1-" + System.nanoTime());
        tmpOut.mkdirs();

        // M11：视觉分析白底图 → 产品专属多段 prompt。请求词优先用前端传入(customRequest)，否则自动生成。
        List<String> segPrompts = new ArrayList<>();
        String seriesPlan = "";   // 07.10#1 保留【总分析/系列文案规划】作全局共享上下文(原来被丢弃)
        try {
            String req = (customRequest != null && !customRequest.isBlank()) ? customRequest : autoCustomRequest(ctx);
            // 07.09#3 主图带画面卖点文案(withText=true)：羽刃主图有卖点标题/标签，本项目默认开启，自动化减人工。
            String raw = imageGen.analyzeCustomImagePrompts(req, List.of(new File(white)), mainTotal, true);
            // 返回：1段【总分析】+ N段图 prompt，--- 分隔。
            String[] parts = raw.split("(?m)^\\s*-{3,}\\s*$");
            // 07.10#1 保留首段【总分析】(含【系列文案规划】全局卖点分配表)，作共享上下文附加进每张 prompt。
            if (parts.length > 1) seriesPlan = parts[0].trim();
            for (int i = 1; i < parts.length; i++) {   // 每张图的单段 prompt
                String p = parts[i].trim();
                if (!p.isBlank()) segPrompts.add(p);
            }
            log.info("M11 自定义分析出 {} 段主图提示词，全局文案分配上下文长度 {}", segPrompts.size(), seriesPlan.length());
        } catch (Exception e) {
            log.warn("M11 自定义分析失败，降级静态模板: {}", e.getMessage());
        }
        final String fSeriesPlan = seriesPlan;

        // B1：重新生成前清空旧主图，实现"覆盖而非追加"（重复点/一键重生都靠这里）。
        synchronized (ctx) { ctx.getVisual().getMainImages().clear(); }
        contextService.save(ctx);

        // M14 并发：首图串行定基调（2~N 参考它），第 2~N 张并发生成（限流 GEN_CONC）。
        // 结果按 index 收集保序（主图顺序=详情配对顺序，不能靠 add 顺序）。
        String[] keys = new String[mainTotal];

        // Phase 1：首图串行（i=0）——出来后作 firstRef 供 2~N 参考
        String firstRef = null;
        {
            String out = new File(tmpOut, "main-0.jpg").getAbsolutePath();
            String base = !segPrompts.isEmpty() ? segPrompts.get(0) : buildMainPrompt(ctx, 1);
            String prompt = buildSeriesPrompt(base, 1, mainTotal, fSeriesPlan);
            if (genWithRetry(prompt, List.of(white), white, out, mainAspect, 2)) {
                keys[0] = uploadIfCos(out);
                firstRef = localizeWhite(keys[0]);
            }
            task.incrementProgress();
        }

        // Phase 2：第 2~N 张并发（refs=白底图+首图，双锚定）；首图失败则降级仅用白底图
        final String fFirstRef = firstRef;
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 1; i < mainTotal; i++) {
            final int idx = i;
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    GEN_CONC.acquire();
                    try {
                        String out = new File(tmpOut, "main-" + idx + ".jpg").getAbsolutePath();
                        String base = idx < segPrompts.size() ? segPrompts.get(idx) : buildMainPrompt(ctx, idx + 1);
                        String prompt = buildSeriesPrompt(base, idx + 1, mainTotal, fSeriesPlan);
                        List<String> refs = new ArrayList<>();
                        refs.add(white);
                        if (fFirstRef != null) refs.add(fFirstRef);
                        if (genWithRetry(prompt, refs, white, out, mainAspect, 2)) {
                            keys[idx] = uploadIfCos(out);
                        }
                    } finally { GEN_CONC.release(); }
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                catch (Exception e) { log.warn("主图 #{} 并发生成失败(跳过): {}", idx, e.getMessage()); }
                finally { task.incrementProgress(); }
            }, imageGen.getExecutor()));
        }
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

        // 按 index 顺序回填（跳过失败的 null），保证主图顺序稳定
        synchronized (ctx) {
            for (String k : keys) if (k != null) ctx.getVisual().getMainImages().add(k);
        }
        contextService.save(ctx);
    }

    /** 自动生成"生图要求"（M11：零人工——按品类/卖点/主件名拼一句喂给视觉分析）。 */
    private String autoCustomRequest(ProductContext ctx) {
        String cat = ctx.getCategory() == null ? "" : ctx.getCategory();
        String leaf = cat.contains(">") ? cat.substring(cat.lastIndexOf('>') + 1).trim() : cat;
        String pts = ctx.getVisual().getSellingPoints().isEmpty()
                ? "" : "核心卖点：" + String.join("、", ctx.getVisual().getSellingPoints()) + "。";
        String main = ctx.getMainItem() == null ? "" : ctx.getMainItem();
        return "为【" + leaf + "】" + (main.isBlank() ? "" : "（" + main + "）")
                + "生成一组电商营销主图，突出产品真实外观与质感。" + pts
                + "多角度差异化拍摄，风格统一、高级、干净，适合拼多多主图。";
    }

    /**
     * 改图风格库（07.09#2，从羽刃 CUSTOM_IMAGE_STYLES 移植 9 种）。
     * key=前端 styleId；value=拼进分析 request 的风格约束（参与 Gemini 分析+生图，产品主体仍以白底图为准）。
     */
    private static final Map<String, String> STYLE_PROMPTS = new LinkedHashMap<>() {{
        put("original",      "【改图风格】以白底产品图为主体基准，不强行套用夸张风格；根据需求自然扩写场景、光影、材质和卖点表达，保持产品外形、颜色、结构细节高度一致。");
        put("tech-blue",     "【改图风格·科技蓝】画面采用蓝白科技感背景、冷色调高光、玻璃/金属质感平台和精密商业棚拍布光，突出智能、高端、理性、专业。");
        put("girl-pink",     "【改图风格·少女粉】画面采用柔和粉白色调、轻盈治愈氛围、圆润道具、细腻柔光和干净背景，突出亲和、精致、甜美但不过度幼稚。");
        put("premium-gray",  "【改图风格·高级灰】画面采用浅灰到炭灰的层次背景、克制留白、柔和阴影和高端商业摄影质感，突出品质、专业、耐看和高客单价感。");
        put("natural-green", "【改图风格·自然绿】画面采用低饱和植物绿、自然光、清爽生活方式场景、木质或石材细节，突出环保、健康、舒适和生活方式价值。");
        put("sunset-orange", "【改图风格·暖阳橙】画面采用暖橙夕阳光感、温暖渐变、柔和高光和长阴影，突出活力、温度、生活幸福感和情绪感染力。");
        put("khaki",         "【改图风格·卡其色】画面采用温暖土黄卡其色调、哑光质感背景和柔和自然光，突出自然耐看的户外生活感。");
        put("light-yellow",  "【改图风格·淡黄色】画面采用柔和浅黄渐变背景、清新明快光效，突出活泼亲切的清爽氛围。");
        put("beige",         "【改图风格·米黄色】画面采用奶油米白渐变、细腻柔光和温柔哑光背景，突出温暖高级的舒适质感。");
    }};
    private final java.util.Random styleRandom = new java.util.Random();

    /**
     * 解析前端 styleId → 风格约束文案。random/空 → 从 9 种里随机抽一种（整组统一），日志记录选中的风格。
     * 未知 id 回退 original。
     */
    private String resolveStylePrompt(Object styleIdRaw) {
        String id = styleIdRaw instanceof String s ? s.trim() : "";
        if (id.isEmpty() || "random".equals(id)) {
            List<String> keys = new ArrayList<>(STYLE_PROMPTS.keySet());
            id = keys.get(styleRandom.nextInt(keys.size()));
            log.info("07.09#2 改图风格 random → 选中 [{}]", id);
        }
        return STYLE_PROMPTS.getOrDefault(id, STYLE_PROMPTS.get("original"));
    }

    /** 交错第二步的异步任务表（M10：step2 改异步，规避同步长请求 300s 超时）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, GenerationTask> flowTasks =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * M14 生图并发限流：线程池有 20，但生图并发度限到 8（对齐乐羽 CONC=8），
     * 避免打爆 gpt-image 中转站触发大面积 429。主图 2~N / 详情 / SKU 三条并发循环共用此闸门。
     */
    private static final java.util.concurrent.Semaphore GEN_CONC = new java.util.concurrent.Semaphore(8);

    /**
     * 交错第二步（07.08重构：选定方案后才跑）。入参 {@code { contextId, planIndex, accWhiteImages?:[], genDetail?, genSku?, templateId? }}。
     * 主图已在 step1 出全；本步只对【选定方案 planIndex】生 SKU 图 + 配对详情图(每张主图转一张9:16)。
     * 立即返回 {@code {taskId,total}}，前端轮询 GET /api/flow/task/{taskId}。
     */
    @PostMapping("/step2")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> step2(@RequestBody Map<String, Object> body) {
        String contextId = (String) body.get("contextId");
        if (contextId == null || contextId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "contextId 不能为空"));
        }
        ProductContext ctx = contextService.findById(contextId);
        if (ctx == null) return ResponseEntity.status(404).body(Map.of("error", "context 不存在"));

        boolean genDetail = !Boolean.FALSE.equals(body.get("genDetail"));
        boolean genSku = !Boolean.FALSE.equals(body.get("genSku"));
        String templateId = body.get("templateId") instanceof String s && !s.isBlank() ? s : "sticker-leftcard";
        List<String> accWhiteRefs = body.get("accWhiteImages") instanceof List<?> l
                ? (List<String>) l : List.of();
        // 选定方案：前端传 planIndex，只对该方案生图（07.08 两段式，避免白生没选中方案的图）
        int planIndex = body.get("planIndex") instanceof Number n ? n.intValue() : ctx.getStructure().getSelectedPlanIndex();
        if (!ctx.getStructure().getPlans().isEmpty()) {
            planIndex = Math.min(Math.max(0, planIndex), ctx.getStructure().getPlans().size() - 1);
            ctx.getStructure().setSelectedPlanIndex(planIndex);
        }

        // 进度总数 = 配对详情(=已有主图张数) + 选定方案 SKU 数
        int mainImgCount = ctx.getVisual().getMainImages().size();
        int skuTotal = 0;
        if (genSku && !ctx.getStructure().getPlans().isEmpty()) {
            skuTotal = ctx.getStructure().getPlans().get(planIndex).getItems().size();
        }
        int total = (genDetail ? mainImgCount : 0) + skuTotal;

        String taskId = "flow2-" + System.nanoTime();
        GenerationTask task = new GenerationTask(taskId, total);
        task.setStatus("running");
        flowTasks.put(taskId, task);

        final boolean fGenDetail = genDetail, fGenSku = genSku;
        final String fTemplateId = templateId;
        final List<String> fAccRefs = accWhiteRefs;
        final int fPlanIndex = planIndex;
        imageGen.getExecutor().submit(() -> {
            try {
                runStep2(ctx, task, fGenDetail, fGenSku, fTemplateId, fAccRefs, fPlanIndex);
                ctx.setStage(FlowStage.SKU_DONE);
                contextService.save(ctx);
                task.setStatus("done");
            } catch (Exception e) {
                log.error("step2 异步编排失败: {}", e.getMessage(), e);
                task.addResult(Map.of("message", "step2 失败：" + e.getMessage()));
                task.setStatus("error");
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", taskId);
        resp.put("total", total);
        resp.put("contextId", ctx.getId());
        return ResponseEntity.ok(resp);
    }

    /** step2 后台工作体（07.08重构）：配对详情图(每张主图→一张9:16) + 选定方案 SKU 图。主图已在 step1 出。 */
    private void runStep2(ProductContext ctx, GenerationTask task,
                          boolean genDetail, boolean genSku, String templateId, List<String> accWhiteRefs,
                          int planIndex) {
        List<String> whiteImages = ctx.getVisual().getWhiteImages();
        String white = (whiteImages == null || whiteImages.isEmpty()) ? null : localizeWhite(whiteImages.get(0));
        File tmpOut = new File(appProperties.getPaths().getTempOutputDir(), "flow2-" + System.nanoTime());
        tmpOut.mkdirs();

        // 已生成的主图（step1 产出）
        List<String> mainImgs = new ArrayList<>(ctx.getVisual().getMainImages());
        String refMain = mainImgs.isEmpty() ? "" :
                java.util.Optional.ofNullable(localizeWhite(mainImgs.get(0))).orElse("");

        // 详情图：配对生成——每张主图转一张 9:16 竖版，第 i 张详情以第 i 张主图为参考（内容一一对应）
        if (genDetail) {
            // B1：重新生成前清空旧详情图，覆盖而非追加。
            ctx.getVisual().getDetailImages().clear();
            // M14 并发：每张详情只依赖对应主图、彼此独立→全并发（限流 GEN_CONC）。
            // 结果按 index 收集保序（详情[i] 必须配对主图[i]），并发结束后统一回填+存一次。
            int dTotal = mainImgs.size();
            String[] dKeys = new String[dTotal];
            List<java.util.concurrent.CompletableFuture<Void>> dFutures = new ArrayList<>();
            for (int i = 0; i < dTotal; i++) {
                final int idx = i;
                dFutures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        GEN_CONC.acquire();
                        try {
                            String mainLocal = localizeWhite(mainImgs.get(idx));
                            List<String> refs = mainLocal != null ? List.of(mainLocal) : (refMain.isBlank() ? List.of() : List.of(refMain));
                            String baseForDetail = mainLocal != null ? mainLocal : white;   // 优先用对应主图,兜底白底
                            String out = new File(tmpOut, "detail-" + idx + ".jpg").getAbsolutePath();
                            String dp = "将所给的第 " + (idx + 1) + " 张主图重新排版为 9:16 竖版电商详情图，"
                                    + "产品主体、颜色、角度与该主图保持一致，纵向铺陈场景与卖点信息，适合详情页竖版展示。";
                            if (baseForDetail != null && genWithRetry(dp, refs, baseForDetail, out, "9:16", 2)) {
                                dKeys[idx] = uploadIfCos(out);
                            }
                        } finally { GEN_CONC.release(); }
                    } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    catch (Exception e) { log.warn("详情图 #{} 并发生成失败(跳过): {}", idx, e.getMessage()); }
                    finally { task.incrementProgress(); }
                }, imageGen.getExecutor()));
            }
            java.util.concurrent.CompletableFuture.allOf(dFutures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            for (String k : dKeys) if (k != null) ctx.getVisual().getDetailImages().add(k);
            contextService.save(ctx);
        }

        // SKU 图：乐羽成熟 generateSkuImage，对【选定方案】生。
        if (genSku && !ctx.getStructure().getPlans().isEmpty()) {
            var plansList = ctx.getStructure().getPlans();
            int pIdx = Math.min(Math.max(0, planIndex), plansList.size() - 1);
            var plan = plansList.get(pIdx);
            String productType = deriveProductTypeForGen(ctx.getCategory(), ctx.getMainItem());
            String batch = String.valueOf(System.nanoTime());

            // 配件白底图：把前端传来的配件图 ref 逐个本地化（缺陷A修复：不再传空 List.of()）
            List<String> accImagePaths = new ArrayList<>();
            for (String r : accWhiteRefs) {
                String lp = localizeWhite(r);
                if (lp != null) accImagePaths.add(lp);
            }
            // 共享背景：整批只分析一次首张主图（雷区：并发下别让每 SKU 各自重新分析）
            String bgStyle = "";
            if (!refMain.isBlank()) {
                try { bgStyle = lyImageGen.analyzeBackgroundStyleOnce(refMain); }
                catch (Exception e) { log.warn("背景分析失败(降级空): {}", e.getMessage()); }
            }
            if (bgStyle == null) bgStyle = "";

            // M14 并发：每个 SKU 独立、写各自 plan item（隔离），全并发（限流 GEN_CONC）。
            // bgStyle 已在循环外只分析一次（并发前置满足）；save 收敛到并发结束后一次。
            final String fBgStyle = bgStyle;
            final String fProductType = productType;
            final String fBatch = batch;
            final List<String> fAccImagePaths = accImagePaths;
            List<java.util.concurrent.CompletableFuture<Void>> sFutures = new ArrayList<>();
            var items = plan.getItems();
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                final var it = items.get(i);
                String name = it.getSkuDisplayName() != null ? it.getSkuDisplayName() : it.getName();
                String skuWhite = (it.getWhiteImgDir() != null && !it.getWhiteImgDir().isBlank())
                        ? localizeWhite(it.getWhiteImgDir()) : white;
                if (skuWhite == null) { log.info("SKU「{}」无可用白底图，跳过", name); task.incrementProgress(); continue; }
                final String fName = name;
                final String fSkuWhite = skuWhite;
                List<Map<String, Object>> accParts = new ArrayList<>();
                if (it.getAccParts() != null) {
                    for (var ap : it.getAccParts()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("code", ap.getCode()); m.put("qty", ap.getQty());
                        accParts.add(m);
                    }
                }
                final List<Map<String, Object>> fAccParts = accParts;
                sFutures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        GEN_CONC.acquire();
                        try {
                            String path = lyImageGen.generateSkuImage(refMain, fName, it.getSpec2(), fProductType,
                                    fBatch, idx + 1, "", fSkuWhite, fAccImagePaths, "", fBgStyle, it.getItemCode(), fAccParts, templateId);
                            if (path != null) {
                                // COS 上传走 uploadIfCos：失败(如账户欠费451)时回退本地路径，图不丢弃(07.08修)。
                                it.setImgDir(uploadIfCos(path));
                            }
                        } finally { GEN_CONC.release(); }
                    } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    catch (Exception e) { log.warn("SKU「{}」生图失败(跳过): {}", fName, e.getMessage()); }
                    finally { task.incrementProgress(); }
                }, imageGen.getExecutor()));
            }
            java.util.concurrent.CompletableFuture.allOf(sFutures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
            contextService.save(ctx);
        }
    }

    /** 查询 step2 异步任务进度（走 /api/flow/** 转发；本地 /api/task 不转发到云端）。 */
    @GetMapping("/task/{taskId}")
    public ResponseEntity<Map<String, Object>> flowTaskStatus(@PathVariable String taskId) {
        GenerationTask t = flowTasks.get(taskId);
        if (t == null) return ResponseEntity.notFound().build();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", t.getId());
        resp.put("status", t.getStatus());
        resp.put("progress", t.getProgress());
        resp.put("total", t.getTotal());
        resp.put("results", t.getResults());
        return ResponseEntity.ok(resp);
    }

    /**
     * 重新生成单张主图/详情图（M11：走与初次生成一致的视觉分析路径，保证重生质量不降级）。
     * 入参 {@code {contextId, kind:"main"|"detail", index, mainAspect?, customRequest?}}。
     * 同步返回 {@code {imageRef}}；原地替换 context 里第 index 张。
     */
    @PostMapping("/regen-main")
    public ResponseEntity<Map<String, Object>> regenMain(@RequestBody Map<String, Object> body) {
        String contextId = (String) body.get("contextId");
        if (contextId == null || contextId.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "contextId 必填"));
        ProductContext ctx = contextService.findById(contextId);
        if (ctx == null) return ResponseEntity.status(404).body(Map.of("error", "context 不存在"));
        String kind = String.valueOf(body.getOrDefault("kind", "main"));
        int index = body.get("index") instanceof Number n ? n.intValue() : 0;
        boolean isDetail = "detail".equals(kind);
        List<String> whites = ctx.getVisual().getWhiteImages();
        if (whites == null || whites.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "无白底图，无法重生"));
        String white = localizeWhite(whites.get(0));
        if (white == null) return ResponseEntity.badRequest().body(Map.of("error", "白底图无法本地化"));
        try {
            File tmpOut = new File(appProperties.getPaths().getTempOutputDir(), "regen-" + System.nanoTime());
            tmpOut.mkdirs();
            String out = new File(tmpOut, kind + "-regen.jpg").getAbsolutePath();
            String key;
            if (isDetail) {
                // 详情图：以对应主图为参考转 9:16 竖版（与 step2 配对逻辑一致）
                List<String> mains = ctx.getVisual().getMainImages();
                String mref = (index < mains.size()) ? localizeWhite(mains.get(index)) : null;
                List<String> refs = mref != null ? List.of(mref) : List.of();
                String base = mref != null ? mref : white;
                String dp = "将所给主图重新排版为 9:16 竖版电商详情图，产品主体/颜色/角度与该主图一致，纵向铺陈卖点，适合详情页。";
                if (!genWithRetry(dp, refs, base, out, "9:16", 2)) return ResponseEntity.internalServerError().body(Map.of("error", "详情图重生失败"));
                key = uploadIfCos(out);
                if (index < ctx.getVisual().getDetailImages().size()) ctx.getVisual().getDetailImages().set(index, key);
                else ctx.getVisual().getDetailImages().add(key);
            } else {
                // 主图：走 M11 视觉分析出单段 prompt（与 genAllMains 一致，保证质量），以首图为参考保持系列一致
                String mainAspect = normalizeAspect(body.get("mainAspect"));
                String req = body.get("customRequest") instanceof String s && !s.isBlank() ? s.trim() : autoCustomRequest(ctx);
                // 07.09#2 风格：重生也带 styleId（与初次一致）
                String styleReq = resolveStylePrompt(body.get("styleId"));
                if (!styleReq.isBlank()) req = (req + "\n" + styleReq).trim();
                String base;
                String seriesPlan = "";   // 07.10#1 重生同样保留全局文案分配上下文
                try {
                    // 07.09#3 主图重生同样开画面文案(withText=true)
                    String raw = imageGen.analyzeCustomImagePrompts(req, List.of(new File(white)), 1, true);
                    String[] parts = raw.split("(?m)^\\s*-{3,}\\s*$");
                    if (parts.length > 1) { seriesPlan = parts[0].trim(); base = parts[1].trim(); }
                    else base = buildMainPrompt(ctx, index + 1);
                } catch (Exception e) { base = buildMainPrompt(ctx, index + 1); }
                List<String> mains = ctx.getVisual().getMainImages();
                // A2：重生也包系列连贯性+角度约束（与 genAllMains 初次生成一致，避免重生比初次还散）
                int total = Math.max(mains.size(), index + 1);
                String prompt = buildSeriesPrompt(base, index + 1, total, seriesPlan);
                // A1：白底图进 refs（首图重生 ref=白底图；非首图 ref=白底图+第1张，双锚定保一致）
                String firstRef = (index > 0 && !mains.isEmpty()) ? localizeWhite(mains.get(0)) : null;
                List<String> refs = new ArrayList<>();
                refs.add(white);
                if (firstRef != null) refs.add(firstRef);
                if (!genWithRetry(prompt, refs, white, out, mainAspect, 2)) return ResponseEntity.internalServerError().body(Map.of("error", "主图重生失败"));
                key = uploadIfCos(out);
                if (index < mains.size()) mains.set(index, key); else mains.add(key);
            }
            contextService.save(ctx);
            return ResponseEntity.ok(Map.of("imageRef", key, "kind", kind, "index", index));
        } catch (Exception e) {
            log.error("重生失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "重生失败：" + e.getMessage()));
        }
    }

    /** COS 启用则上传返回 key，否则原样返回本地路径。 */
    private String uploadIfCos(String path) {
        try {
            File f = new File(path);
            if (cosService.isEnabled() && f.isFile()) return cosService.upload(f, UUID.randomUUID() + ".jpg");
        } catch (Exception e) { log.warn("COS 上传失败 {}: {}", path, e.getMessage()); }
        return path;
    }

    /**
     * 把白底图入参统一成本地文件路径（GptImageAgent 只读本地文件）。
     * 支持四类：data:URL(前端拖拽/base64)→解码落地；本地文件→原样；http URL→下载；COS key→signKey 换 URL 再下载。
     * 任何一类失败返回 null（调用方据此跳过并如实标注，不抛断整个编排）。
     */
    private String localizeWhite(String white) {
        if (white == null || white.isBlank()) return null;
        try {
            // 1) data:URL（前端拖拽/文件选择转的 base64）→ 解码写临时文件
            if (white.startsWith("data:")) {
                int comma = white.indexOf(',');
                if (comma < 0) { log.warn("白底图 data URL 格式非法"); return null; }
                String meta = white.substring(5, comma);           // e.g. image/png;base64
                String ext = meta.contains("png") ? ".png" : ".jpg";
                byte[] bytes = java.util.Base64.getDecoder().decode(white.substring(comma + 1));
                File out = File.createTempFile("flow-white-", ext);
                Files.write(out.toPath(), bytes);
                return out.getAbsolutePath();
            }
            // 2) 已是本地文件（路径不会太长，先长度保护再判断，避免超长字符串触发文件系统异常）
            if (white.length() < 260) {
                File local = new File(white);
                if (local.isFile()) return white;
            }
            // 3) http URL 直接下；4) 否则当 COS key 换签名 URL
            String url = white.startsWith("http") ? white
                    : (cosService.isEnabled() ? cosService.signKey(white) : null);
            if (url == null || !url.startsWith("http")) {
                log.warn("白底图无法本地化(非data/本地文件/URL/COS): {}", abbrev(white));
                return null;
            }
            File out = File.createTempFile("flow-white-", ".jpg");
            Request req = new Request.Builder().url(url).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return null;
                try (InputStream in = resp.body().byteStream()) {
                    Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            log.warn("白底图本地化失败 {}: {}", abbrev(white), e.getMessage());
            return null;
        }
    }

    /** 日志里截断超长字符串（如 data URL），避免刷屏。 */
    private static String abbrev(String s) {
        return s == null ? "null" : (s.length() > 60 ? s.substring(0, 60) + "…(" + s.length() + ")" : s);
    }

    /** 取已有 context 或按入参新建；无论新建/复用都同步表单的 category/mainItem（重跑改品类能生效）。 */
    private ProductContext resolveContext(Map<String, Object> body) {
        String category = (String) body.getOrDefault("category", "");
        String mainItem = (String) body.getOrDefault("mainItem", "");
        String contextId = (String) body.get("contextId");
        ProductContext ctx = null;
        if (contextId != null && !contextId.isBlank()) ctx = contextService.findById(contextId);
        if (ctx == null) ctx = new ProductContext();
        if (category != null && !category.isBlank()) ctx.setCategory(category);
        if (mainItem != null && !mainItem.isBlank()) ctx.setMainItem(mainItem);
        return ctx;
    }


    /**
     * 主图 prompt：品类+主件+本张卖点，落白底图产品到营销场景。
     * 07.10#1 降级兜底也按序号取**单个**卖点(而非 join 全部)，避免所有卖点糊进一句导致重复/塞不下。
     * @param index 本张序号(1-based)，用于从卖点列表按序取本张主打卖点
     */
    private String buildMainPrompt(ProductContext ctx, int index) {
        String cat = ctx.getCategory() == null ? "" : ctx.getCategory();
        String leaf = cat.contains(">") ? cat.substring(cat.lastIndexOf('>') + 1).trim() : cat;
        java.util.List<String> sp = ctx.getVisual().getSellingPoints();
        String point = sp.isEmpty() ? "" : sp.get((Math.max(1, index) - 1) % sp.size());
        return "为电商主图生成营销场景：将白底图中的产品（" + leaf
                + "）自然融入高级感场景，突出本图卖点：" + point + "。保持产品主体真实、居中、留白得当，适合电商主图。";
    }

    /** 生图带重试（bug1：主图/详情单张失败不静默丢，重试 maxRetry 次）。M10：撞 429 限流时指数退避后再重试。 */
    private boolean genWithRetry(String prompt, List<String> refs, String white, String out, String aspect, int maxRetry) {
        for (int a = 0; a <= maxRetry; a++) {
            try {
                if (imageGen.generateImageMulti(prompt, refs, white, out, null, aspect)) return true;
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean rateLimited = msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED")
                        || msg.contains("rate") || msg.contains("Too Many");
                log.warn("生图第 {} 次失败{}: {}", a + 1, rateLimited ? "(限流)" : "", msg);
                if (rateLimited && a < maxRetry) {
                    long waitMs = Math.min(16000, 2000L * (1L << Math.min(3, a)));
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        return false;
    }

    /** 主图比例白名单校验（后端兜底防乱传）：只接受 5 档 gpt-image-2 合法比例，其余回退 1:1。 */
    private String normalizeAspect(Object raw) {
        String a = raw instanceof String s ? s.trim() : "";
        return switch (a) {
            case "1:1", "3:4", "4:3", "9:16", "16:9" -> a;
            default -> "1:1";
        };
    }

    /**
     * category 末段派生 productType：含"花洒"→花洒；厨房挂件/架/挂钩/锅盖架等架类→"架类:&lt;叶子&gt;"
     * （下游 ImageGenService.isShelf 按前缀判定、按叶子名选品种 prompt）；其余→末段。
     */
    private String deriveProductTypeForGen(String category, String mainItem) {
        if (category == null || category.isBlank()) return "架类";
        if (category.contains("花洒")) return "花洒";
        String leaf = category.contains(">") ? category.substring(category.lastIndexOf('>') + 1).trim() : category.trim();
        // 架类品（家装主材>厨房>厨房挂件 下的刀架/挂钩/锅盖架/沥水架/收纳架/置物架等）
        boolean isShelf = category.contains("厨房挂件") || category.contains("挂钩") || category.contains("锅盖架")
                || category.contains("刀架") || category.contains("置物架") || category.contains("收纳架")
                || category.contains("沥水") || leaf.contains("架");
        // 架类品种细分（如吸盘/落地锅盖架）靠主件名区分，把主件名一并带上供下游 matchShelfKind 判定。
        if (isShelf) return ("架类:" + (leaf.isBlank() ? "" : leaf) + " " + (mainItem == null ? "" : mainItem)).trim();
        return leaf.isBlank() ? "架类" : leaf;
    }

    // ── 多主图系列差异化（自 ele-business-java GenerateController 原样迁入，M9）──
    // 同场景多角度系列：第1张建基调，2~N张不同拍摄角度+不同卖点营销文案，产品主体100%一致。

    /**
     * 在原始 prompt 后追加系列连贯性约束 + 角度差异化约束。
     * @param currentIndex 当前图序号(1-based)  @param totalCount 系列总张数
     */
    private String buildSeriesPrompt(String basePrompt, int currentIndex, int totalCount, String seriesPlan) {
        String base = basePrompt == null ? "" : basePrompt.trim();
        String seriesConstraint = String.format("""

                【系列连贯性·最高优先级】这是同一场景系列拍摄的第 %d/%d 张：
                1. 产品主体必须100%%一致：外形、颜色、材质、品牌标识、关键结构完全相同
                2. **产品原生文字必须与第1张完全相同**：产品本体上的LOGO、品牌名、型号、按钮标签、刻度等，字形/位置/颜色/清晰度一致，禁止模糊变形消失
                3. 场景类型必须完全一致（浴室保持浴室、厨房保持厨房）
                4. 整体色调、光线氛围、背景材质保持一致，营造"同一时间同一地点"的连续感
                5. 允许变化：景别(远近)、聚焦部位、场景中摆放位置、光照角度微调、**画面营销文案（每张按本图卖点不同）**
                6. 禁止：场景类型切换、色调剧变、产品变形、产品原生文字错误/模糊、风格跳跃
                7. 最终效果：像摄影师在同一场景正对产品走近走远，用不同景别拍同一产品的连续镜头，每张用不同营销文案强调不同卖点
                """.trim(), currentIndex, totalCount);
        // 07.10#1 卖点重复/不全修复：把全局【系列文案规划】作为共享上下文附加进每张 prompt，
        // 让本张既看得到全局分配(第几张讲哪个卖点)、又知道自己的边界(只渲染本张卖点、不重复其他张)。
        String planContext = "";
        if (seriesPlan != null && !seriesPlan.isBlank()) {
            planContext = String.format("""

                【本系列全局文案分配·供参考(勿把整段都渲染到画面)】
                %s
                **本张画面只渲染属于第 %d 张的卖点文案，不得重复其他张已分配的卖点。**
                """.trim(), seriesPlan.trim(), currentIndex);
        }
        return base + seriesConstraint + buildAngleConstraint(currentIndex, totalCount)
                + (planContext.isEmpty() ? "" : "\n\n" + planContext);
    }

    /** 角度差异化约束：第1张正面基调，2~N张按 selectAngleSequence 指定不同角度+强调第 i 个卖点。 */
    private String buildAngleConstraint(int currentIndex, int totalCount) {
        if (currentIndex == 1) {
            return """

                【第1张·基调建立】
                产品主视角（正面或正面45度），清晰展示产品正面特征、品牌LOGO、核心卖点。
                这张图将作为后续图片的参考基准，必须完整呈现产品全貌。
                **画面营销文案**：根据卖点设计本图营销文案（主标题、副标题、卖点标签），强调第1个核心卖点
                """;
        }
        String[] angles = selectAngleSequence(totalCount);
        String currentShot = angles[(currentIndex - 2) % angles.length];
        return String.format("""

                【第%d张·景别约束·强制执行】
                本图采用：%s。
                - 产品**必须正对镜头**（正面或正面微侧≤15度），与第1张保持同一朝向
                - 保持产品主体完整可见，不得被遮挡或裁切
                - 与前图的区别只来自**景别(远近)和聚焦部位**，不来自旋转产品
                - 光线和阴影符合该景别物理规律
                **严禁**：侧面90度/70度、俯视、仰视、背面等会改变产品侧边缘几何、导致结构变形的大角度旋转
                **严禁**：因构图改变而修改产品结构、比例、侧边缘轮廓
                **产品原生文字**：参考第1张，产品本体文字/LOGO与第1张完全一致
                **画面营销文案**：可与前面不同，设计新营销标题强调第%d个卖点或从新的近景/特写证明功能
                """, currentIndex, currentShot, currentIndex);
    }

    /**
     * 按总张数返回景别序列（不含第1张正面基调）。
     * 07.10#2 修产品侧边缘结构变形：全部改为正面系景别/微角度，杜绝侧面90/70度、俯视、仰视、背面等
     * 会旋转产品、改变侧边缘几何的大角度视角。第2~N张靠"景别(远近)+卖点聚焦区域"区分，视角始终正对镜头。
     */
    private String[] selectAngleSequence(int totalCount) {
        if (totalCount <= 3) {
            return new String[]{
                "正面平视·中景（产品正对镜头、完整居中，展示整体形态与核心卖点）",
                "正面平视·核心部件近景特写（镜头拉近，正面放大展示功能区/出水口等核心部件，产品不旋转）"
            };
        } else if (totalCount <= 5) {
            return new String[]{
                "正面平视·中景（产品正对镜头、完整居中）",
                "正面微侧15度·近景（产品仅轻微转身≤15度、仍以正面为主，近景展示主要功能区，不露完整侧面）",
                "正面平视·核心部件近景特写（正面拉近放大核心功能部件，产品不旋转）",
                "正面平视·材质局部特写（正面拉近展示表面质感/LOGO/细节，产品不旋转）"
            };
        } else {
            return new String[]{
                "正面平视·中景（产品正对镜头、完整居中）",
                "正面微侧15度·近景（产品仅轻微转身≤15度、仍以正面为主，不露完整侧面）",
                "正面平视·核心部件近景特写（正面拉近放大核心功能部件）",
                "正面平视·材质局部特写（正面拉近展示表面质感/LOGO）",
                "正面平视·全景（产品正对镜头、稍拉远展示完整全貌与留白）",
                "正面微侧15度·中景（产品仅轻微转身≤15度、仍以正面为主，中景展示整体）"
            };
        }
    }
}

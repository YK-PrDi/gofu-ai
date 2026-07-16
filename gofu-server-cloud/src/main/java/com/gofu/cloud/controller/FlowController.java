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
            final String fStyleReq = styleReq;   // M18-R5：风格单独传，供最终生图 prompt 直拼（不只喂分析）
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
                            genAllMains(ctx, localWhites, fMainTotal, fMainAspect, task, fCustomReq, fStyleReq), imageGen.getExecutor());
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
                             String mainAspect, GenerationTask task, String customRequest, String styleReq) {
        if (whiteImages == null || whiteImages.isEmpty()) { log.info("step1 无白底图，跳过主图"); return; }
        String white = localizeWhite(whiteImages.get(0));
        if (white == null) { log.info("step1 白底图无法本地化，跳过主图"); return; }
        File tmpOut = new File(appProperties.getPaths().getTempOutputDir(), "flow1-" + System.nanoTime());
        tmpOut.mkdirs();

        // M18：品类主体一致性约束/禁止项（从品类库按 category 冒泡查找，回退全局默认）+ auto 比例按白底图推断。
        String subjectLock = lyImageGen.ecSubjectLock(ctx.getCategory());
        String negative = lyImageGen.ecNegative(ctx.getCategory());
        String aspect = resolveAutoAspect(mainAspect, white);   // R3：auto→按白底图真实宽高吸附
        // M19：细杆/框架类品类（架类/挂钩）——主图强制正面系 + 白底图强锁结构，防旋转臆造变形。
        boolean structLock = isStructuralRigidCategory(ctx.getCategory());
        boolean isShower = isShowerCategory(ctx.getCategory());   // #1 花洒出水物理约束用
        final String fStyleReq = styleReq;
        log.info("主图组装: aspect={}, subjectLock={}字, negative={}字, style={}", aspect,
                subjectLock.length(), negative.length(), styleReq == null ? "" : styleReq);

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
            // M19-A1：段数<图数常因 LLM 漏写 --- 分隔符（把多张方案挤在一段里）导致降级。
            // 兜底按【第 N 张方案】标题重新切分，把段数补到接近图数，尽量不触发降级。
            if (segPrompts.size() < mainTotal) {
                List<String> reSplit = splitByPlanHeader(raw);
                if (reSplit.size() > segPrompts.size()) {
                    log.info("M19-A1 段数不足({}<{}), 按【第N张方案】标题重解析得 {} 段",
                            segPrompts.size(), mainTotal, reSplit.size());
                    segPrompts.clear();
                    segPrompts.addAll(reSplit);
                }
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
            task.setCurrentProduct("主图 1/" + mainTotal);
            String out = new File(tmpOut, "main-0.jpg").getAbsolutePath();
            String base = !segPrompts.isEmpty() ? segPrompts.get(0) : buildMainPrompt(ctx, 1, fSeriesPlan);
            String prompt = buildSeriesPrompt(base, 1, mainTotal, fSeriesPlan, subjectLock, negative, fStyleReq, true, structLock, isShower);
            if (genWithRetry(prompt, List.of(white), white, out, aspect, 2)) {
                keys[0] = uploadIfCos(out);
                firstRef = localizeWhite(keys[0]);
            } else {
                // P0-A：单张失败要可见（原来静默），前端能看到"某张没出"
                task.addResult(Map.of("message", "主图 #1 生成失败"));
                log.warn("主图 #1(首图) 生成失败");
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
                        task.setCurrentProduct("主图 " + (idx + 1) + "/" + mainTotal);
                        String out = new File(tmpOut, "main-" + idx + ".jpg").getAbsolutePath();
                        String base = idx < segPrompts.size() ? segPrompts.get(idx) : buildMainPrompt(ctx, idx + 1, fSeriesPlan);
                        String prompt = buildSeriesPrompt(base, idx + 1, mainTotal, fSeriesPlan, subjectLock, negative, fStyleReq, true, structLock, isShower);
                        List<String> refs = new ArrayList<>();
                        refs.add(white);
                        if (fFirstRef != null) refs.add(fFirstRef);
                        if (genWithRetry(prompt, refs, white, out, aspect, 2)) {
                            keys[idx] = uploadIfCos(out);
                        } else {
                            task.addResult(Map.of("message", "主图 #" + (idx + 1) + " 生成失败"));
                            log.warn("主图 #{} 生成失败(重试用尽)", idx + 1);
                        }
                    } finally { GEN_CONC.release(); }
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                catch (Exception e) {
                    task.addResult(Map.of("message", "主图 #" + (idx + 1) + " 异常: " + e.getMessage()));
                    log.warn("主图 #{} 并发生成失败(跳过): {}", idx + 1, e.getMessage());
                }
                finally { task.incrementProgress(); }
            }, imageGen.getExecutor()));
        }
        java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();

        // 按 index 顺序回填（跳过失败的 null），保证主图顺序稳定
        int okCount = 0;
        synchronized (ctx) {
            for (String k : keys) if (k != null) { ctx.getVisual().getMainImages().add(k); okCount++; }
        }
        contextService.save(ctx);
        // 诊断：存主图后打印 contextId + 主图数，便于测试后从日志直接定位该 context 是否存住图。
        log.info("[诊断] step1 存主图完成 contextId={} 主图数={}", ctx.getId(), ctx.getVisual().getMainImages().size());
        // P0-A：有白底图却一张主图都没出 = 真失败，必须抛出让 step1 置 task=error，
        // 而不是静默标 done 让前端误以为成功（"图没生成还假装完成"的黑洞根因）。
        if (okCount == 0) {
            throw new RuntimeException("主图全部生成失败（共 " + mainTotal + " 张，成功 0 张）——请查生图服务返回");
        }
        log.info("主图生成完成：{}/{} 张成功", okCount, mainTotal);
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

    /**
     * 风格迁移（②，独立能力）：对当前商品**已有成品图**（主图+详情）做 image2image 换基调，
     * 保持产品/构图/文案不变，只换风格。换完覆盖回写 context.visual（能接着上新，A/A/B 方案）。
     * 入参 {@code {contextId, styleId}}；styleId 取自 STYLE_PROMPTS（tech-blue/girl-pink…）。
     * 复用：STYLE_PROMPTS + genWithRetry(图生图) + uploadIfCos + 异步任务 + /task 轮询。
     */
    @PostMapping("/style-transfer")
    public ResponseEntity<Map<String, Object>> styleTransfer(@RequestBody Map<String, Object> body) {
        String contextId = (String) body.get("contextId");
        if (contextId == null || contextId.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "contextId 不能为空"));
        ProductContext ctx = contextService.findById(contextId);
        if (ctx == null) return ResponseEntity.status(404).body(Map.of("error", "context 不存在"));

        String styleId = body.get("styleId") instanceof String s ? s.trim() : "";
        if (styleId.isBlank() || "random".equals(styleId))
            return ResponseEntity.badRequest().body(Map.of("error", "请指定具体风格 styleId（风格迁移不用随机）"));
        String stylePrompt = STYLE_PROMPTS.get(styleId);
        if (stylePrompt == null)
            return ResponseEntity.badRequest().body(Map.of("error", "未知风格 styleId=" + styleId));

        List<String> mains = new ArrayList<>(ctx.getVisual().getMainImages());
        List<String> details = new ArrayList<>(ctx.getVisual().getDetailImages());
        if (mains.isEmpty() && details.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "当前商品没有成品图（主图/详情），无法做风格迁移"));

        String taskId = "style-" + System.nanoTime();
        GenerationTask task = new GenerationTask(taskId, mains.size() + details.size());
        task.setStatus("running");
        flowTasks.put(taskId, task);
        File tmpOut = new File(appProperties.getPaths().getTempOutputDir(), taskId);
        tmpOut.mkdirs();

        final String fStylePrompt = stylePrompt;
        imageGen.getExecutor().submit(() -> {
            try {
                // 换风格但保内容：成品图当唯一参考，prompt 强调只换基调、产品/构图/文案不动。
                String prompt = fStylePrompt + "\n\n【风格迁移·硬约束】这是对一张已完成的电商成品图换视觉基调："
                        + "产品主体的外形/颜色/结构、画面构图版式、已有的文案文字与位置，全部保持与所给原图一致，"
                        + "只改变背景色调、光影氛围、材质质感等风格层面；不得改动产品本身、不得挪动或改写文案、不得增删元素。";
                String[] newMains = transferList(mains, prompt, tmpOut, "st-main", task);
                String[] newDetails = transferList(details, prompt, tmpOut, "st-detail", task);
                // 覆盖回写（换风格后的图直接进 visual，能接着上新）。任一张失败则保留原图不动。
                synchronized (ctx) {
                    ctx.getVisual().getMainImages().clear();
                    for (int i = 0; i < newMains.length; i++)
                        ctx.getVisual().getMainImages().add(newMains[i] != null ? newMains[i] : mains.get(i));
                    ctx.getVisual().getDetailImages().clear();
                    for (int i = 0; i < newDetails.length; i++)
                        ctx.getVisual().getDetailImages().add(newDetails[i] != null ? newDetails[i] : details.get(i));
                }
                contextService.save(ctx);
                log.info("[风格迁移] contextId={} style={} 主图{}张 详情{}张 完成", ctx.getId(), styleId, newMains.length, newDetails.length);
                task.setStatus("done");
            } catch (Exception e) {
                log.error("风格迁移失败: {}", e.getMessage(), e);
                task.addResult(Map.of("type", "error", "message", "风格迁移失败：" + e.getMessage()));
                task.setStatus("error");
            }
        });

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", taskId);
        resp.put("total", mains.size() + details.size());
        resp.put("contextId", ctx.getId());
        return ResponseEntity.ok(resp);
    }

    /** 对一组成品图逐张 image2image 换风格，按序号返回新 COS key（失败位为 null，调用方回退原图）。并发受 imageGen 执行器控制。 */
    private String[] transferList(List<String> imgs, String prompt, File tmpOut, String prefix, GenerationTask task) {
        String[] out = new String[imgs.size()];
        List<java.util.concurrent.CompletableFuture<Void>> fs = new ArrayList<>();
        for (int i = 0; i < imgs.size(); i++) {
            final int idx = i;
            fs.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    String local = localizeWhite(imgs.get(idx));   // COS key/URL → 本地文件
                    if (local == null) return;
                    String outPath = new File(tmpOut, prefix + "-" + idx + ".jpg").getAbsolutePath();
                    task.setCurrentProduct("风格迁移 " + prefix + " " + (idx + 1));
                    if (genWithRetry(prompt, List.of(local), local, outPath, "auto", 2))
                        out[idx] = uploadIfCos(outPath);
                } catch (Exception e) {
                    log.warn("风格迁移单张失败(保留原图) {}#{}: {}", prefix, idx, e.getMessage());
                } finally {
                    task.incrementProgress();
                }
            }, imageGen.getExecutor()));
        }
        java.util.concurrent.CompletableFuture.allOf(fs.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        return out;
    }

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
                            task.setCurrentProduct("详情图 " + (idx + 1) + "/" + dTotal);
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
            int dOk = 0;
            for (String k : dKeys) if (k != null) { ctx.getVisual().getDetailImages().add(k); dOk++; }
            contextService.save(ctx);
            log.info("[诊断] step2 存详情图完成 contextId={} 详情生成={}/{} 累计详情={}", ctx.getId(), dOk, dTotal, ctx.getVisual().getDetailImages().size());
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
                // 修(#8)：SKU 白底图按主件匹配——原来 whiteImgDir 为空就一律回退 white(池里第一张)，
                // 导致 30cm/40cm/50cm/60cm 挂钩全用同一张(如都用60cm)。改为按 itemCode/尺寸从白底池匹配对应那张。
                String skuWhite = (it.getWhiteImgDir() != null && !it.getWhiteImgDir().isBlank())
                        ? localizeWhite(it.getWhiteImgDir())
                        : localizeWhite(matchWhiteForItem(whiteImages, it.getItemCode(), it.getSpec1()));
                if (skuWhite == null) skuWhite = white; // 匹配不到再回退池首张(不至于漏图)
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
                            task.setCurrentProduct("SKU：" + fName);
                            String path = lyImageGen.generateSkuImage(refMain, fName, it.getSpec2(), fProductType,
                                    fBatch, idx + 1, "", fSkuWhite, fAccImagePaths, "", fBgStyle, it.getItemCode(), fAccParts, templateId, it.getMainQty());
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
            long skuWithImg = plan.getItems().stream().filter(x -> x.getImgDir() != null && !x.getImgDir().isBlank()).count();
            log.info("[诊断] step2 存SKU图完成 contextId={} SKU有图={}/{}", ctx.getId(), skuWithImg, plan.getItems().size());
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
        resp.put("currentProduct", t.getCurrentProduct());
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
                    else base = buildMainPrompt(ctx, index + 1, seriesPlan);
                } catch (Exception e) { base = buildMainPrompt(ctx, index + 1, seriesPlan); }
                List<String> mains = ctx.getVisual().getMainImages();
                // A2：重生也包系列连贯性+角度约束（与 genAllMains 初次生成一致，避免重生比初次还散）
                int total = Math.max(mains.size(), index + 1);
                // M18：重生也用品类库 subjectLock/negative + 风格 + 文字渲染，与初次一致
                String subjectLock = lyImageGen.ecSubjectLock(ctx.getCategory());
                String negative = lyImageGen.ecNegative(ctx.getCategory());
                mainAspect = resolveAutoAspect(mainAspect, white);
                boolean structLock = isStructuralRigidCategory(ctx.getCategory());   // M19：重生与初次一致
                boolean isShower = isShowerCategory(ctx.getCategory());
                String prompt = buildSeriesPrompt(base, index + 1, total, seriesPlan, subjectLock, negative, styleReq, true, structLock, isShower);
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
    /**
     * (#8) 按 SKU 主件从白底图池挑对应那张：优先 itemCode 主件段命中文件名，再退化到尺寸 token(如"30CM")。
     * 命中返回该白底 ref；匹配不到返回 null(调用方再回退池首张)。防止不同尺寸 SKU 共用同一张白底。
     */
    private String matchWhiteForItem(List<String> whiteImages, String itemCode, String spec1) {
        if (whiteImages == null || whiteImages.isEmpty()) return null;
        // 候选关键词：itemCode 主件段(去掉 + 后的配件) + spec1，抽取字母数字/尺寸 token
        String key = "";
        if (itemCode != null && !itemCode.isBlank()) key = itemCode.split("\\+")[0].trim();
        if (key.isBlank() && spec1 != null) key = spec1.trim();
        if (key.isBlank()) return null;
        String keyU = key.toUpperCase();
        // 1) 文件名(去路径)包含完整主件码 → 最精确
        for (String w : whiteImages) {
            if (w == null) continue;
            String base = w.substring(Math.max(w.lastIndexOf('/'), w.lastIndexOf('\\')) + 1).toUpperCase();
            if (base.contains(keyU)) return w;
        }
        // 2) 退化：抽尺寸 token(如 30CM/50CM)分别匹配，避免整码不同但尺寸对得上
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,3}\\s*CM)").matcher(keyU);
        if (m.find()) {
            String size = m.group(1).replaceAll("\\s+", "");
            for (String w : whiteImages) {
                if (w == null) continue;
                String base = w.substring(Math.max(w.lastIndexOf('/'), w.lastIndexOf('\\')) + 1).toUpperCase().replaceAll("\\s+", "");
                if (base.contains(size)) return w;
            }
        }
        return null;
    }

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
     * M19-A1：按【第 N 张方案】标题重新切分原始分析文本（LLM 漏写 --- 分隔符时的兜底）。
     * 从第一个【第1张方案】标题起，按后续每个【第N张方案】标题切段，丢弃标题前的【总分析】部分。
     * 切不出（无标题）返回空 list，调用方保持原 --- 切分结果。
     */
    private List<String> splitByPlanHeader(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("【第\\s*\\d+\\s*张方案】").matcher(raw);
        List<Integer> starts = new ArrayList<>();
        while (m.find()) starts.add(m.start());
        if (starts.size() < 2) return out;   // 少于2段不值得重切
        for (int i = 0; i < starts.size(); i++) {
            int s = starts.get(i);
            int e = (i + 1 < starts.size()) ? starts.get(i + 1) : raw.length();
            String seg = raw.substring(s, e).trim();
            if (!seg.isBlank()) out.add(seg);
        }
        return out;
    }

    /**
     * 主图 prompt：品类+主件+本张卖点，落白底图产品到营销场景。
     * M19-A2：降级兜底不再盲取 sellingPoints[i]（会和 seriesPlan 系列文案规划的本张分配打架→重复），
     * 改为：有 seriesPlan 时明确让模型**读取系列文案规划里分给第 index 张的卖点**并只渲染它、不与其他张重复；
     * 无 seriesPlan 时才回退按序号取单个卖点。并补最小【画面文案】结构，避免降级张丢失卖点文字。
     * @param index 本张序号(1-based)  @param seriesPlan 全局【总分析】(含系列文案规划)，可为空
     */
    private String buildMainPrompt(ProductContext ctx, int index, String seriesPlan) {
        String cat = ctx.getCategory() == null ? "" : ctx.getCategory();
        String leaf = cat.contains(">") ? cat.substring(cat.lastIndexOf('>') + 1).trim() : cat;
        java.util.List<String> sp = ctx.getVisual().getSellingPoints();
        boolean hasPlan = seriesPlan != null && !seriesPlan.isBlank();
        StringBuilder b = new StringBuilder();
        b.append("为电商主图生成营销场景：将白底图中的产品（").append(leaf)
         .append("）自然融入它【真实使用场景】的高级感环境中——根据该产品的实际用途判断场景"
               + "（如花洒→浴室淋浴墙面/湿区，挂钩/置物架→对应的墙面或厨卫收纳位，厨具→厨房台面等），"
               + "让产品出现在买家真实使用它的地方，而非抽象空场景。"
               + "保持产品主体真实、居中、留白得当，且【必须有合理支撑不浮空】"
               + "（挂墙的贴墙、放置的落在台面/地面），符合物理受力，适合电商主图。");
        if (hasPlan) {
            // A2 核心：不指定具体卖点，而是锚定到全局分配表的本张条目，杜绝与 seriesPlan 冲突
            b.append("\n【本图卖点·必须服从全局分配】严格采用上文【系列文案规划】中分配给第 ")
             .append(index).append(" 张的卖点，只渲染这一个卖点，禁止与其他张的卖点重复或混用。");
        } else {
            String point = sp.isEmpty() ? "" : sp.get((Math.max(1, index) - 1) % sp.size());
            b.append("突出本图卖点：").append(point).append("。");
        }
        // A2：补最小【画面文案】结构，保证降级张也有可渲染的卖点文字（否则文字整个缺失）
        b.append("\n【画面文案】把本图卖点做成：主标题(≤8字醒目大字)+副标题(≤15字一句解释)+2个卖点标签(≤6字/个)，"
               + "排版整齐、与背景高对比、无错别字、不遮挡产品关键结构。");
        return b.toString();
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

    /**
     * 主图比例白名单校验：接受 5 档 gpt-image-2 合法比例 + "auto"（透传，由 resolveAutoAspect 按白底图推断），
     * 其余回退 auto（M18-R3：不再强制 1:1，避免非方产品被塞进方框裁切/留白）。
     */
    private String normalizeAspect(Object raw) {
        String a = raw instanceof String s ? s.trim() : "";
        return switch (a) {
            case "1:1", "3:4", "4:3", "9:16", "16:9", "auto" -> a;
            default -> "auto";
        };
    }

    /** M18-P0-C：全项目统一的花洒品类判定（花洒 或 淋浴）。消除"只花洒/花洒||淋浴"多口径分裂。 */
    static boolean isShowerCategory(String category) {
        return category != null && (category.contains("花洒") || category.contains("淋浴"));
    }

    /**
     * M19：刚性细杆/框架类品类判定（架类：锅盖架/挂钩/刀架/置物架/收纳架/沥水架等）。
     * 这类产品是细钢丝/薄框结构，主图 2~N 张一旦按多角度序列大幅旋转，AI 会重新臆造三维几何 →
     * 结构变形/穿模/违背物理（07.11 锅盖架"米奇耳朵红蝴蝶结"即此）。故对这类品类主图强制正面系 + 白底图强锁结构。
     * 与 deriveProductTypeForGen 的 isShelf 判定同源，抽出复用。
     */
    static boolean isStructuralRigidCategory(String category) {
        String c = normalizeCategory(category);
        if (c.isBlank() || isShowerCategory(c)) return false;
        String leaf = c.contains(">") ? c.substring(c.lastIndexOf('>') + 1).trim() : c;
        return c.contains("厨房挂件") || c.contains("挂钩") || c.contains("锅盖架")
            || c.contains("刀架") || c.contains("置物架") || c.contains("收纳架")
            || c.contains("沥水") || leaf.contains("架");
    }

    /** M18-P0-C：category 分隔符归一化（全角＞/›/半角混用 → 统一半角 >），防叶子解析口径不一致。 */
    static String normalizeCategory(String category) {
        return category == null ? "" : category.replace('＞', '>').replace('›', '>').trim();
    }

    /**
     * category 末段派生 productType：花洒/淋浴→"花洒"；厨房挂件/锅盖架/刀架等架类→"架类:&lt;叶子&gt;"
     * （下游 ImageGenService.isShelf 按前缀判定、按叶子名选品种 prompt）；其余→末段。
     * M18-P0-C：统一花洒判定、归一化分隔符、收紧架类判定（含"架"需排除花洒且不再空类目兜底架类）。
     */
    private String deriveProductTypeForGen(String rawCategory, String mainItem) {
        String category = normalizeCategory(rawCategory);
        if (category.isBlank()) return "未知";   // 不再静默兜底"架类"，避免未知品类走错生图分支
        if (isShowerCategory(category)) return "花洒";
        String leaf = category.contains(">") ? category.substring(category.lastIndexOf('>') + 1).trim() : category.trim();
        // 架类品（家装主材>厨房>厨房挂件 下的刀架/挂钩/锅盖架/沥水架/收纳架/置物架等）。
        // 花洒已在上面 return，这里 leaf.contains("架") 不会误吞花洒；仍显式列关键词优先。
        boolean isShelf = category.contains("厨房挂件") || category.contains("挂钩") || category.contains("锅盖架")
                || category.contains("刀架") || category.contains("置物架") || category.contains("收纳架")
                || category.contains("沥水") || leaf.contains("架");
        // 架类品种细分（如吸盘/落地锅盖架）靠主件名区分，把主件名一并带上供下游 matchShelfKind 判定。
        if (isShelf) return ("架类:" + (leaf.isBlank() ? "" : leaf) + " " + (mainItem == null ? "" : mainItem)).trim();
        return leaf.isBlank() ? "未知" : leaf;
    }

    // ── 多主图系列差异化（自 ele-business-java GenerateController 原样迁入，M9）──
    // 同场景多角度系列：第1张建基调，2~N张不同拍摄角度+不同卖点营销文案，产品主体100%一致。

    /**
     * M18-R1：文字渲染指令（照搬羽刃 index.html:1622）。withText 时每张 prompt 加，
     * 明确要求生图模型把分析产出的【画面文案】作为真实中文渲染到画面——补回"无画面文案"的根因。
     */
    private static final String TEXT_RENDER_INSTRUCTION =
        "【文字渲染要求】请把本方案【画面文案】中的主标题、副标题和卖点标签作为清晰可读的中文文字渲染在画面合适位置："
        + "字体现代简洁、排版整齐、与背景高对比、无错别字、不遮挡产品主体关键结构；"
        + "除这些卖点文案外，画面不要出现其他多余文字、水印或乱码。";

    /**
     * M18 重构：仿羽刃自定义模式最终 prompt 组装（index.html:1625-1635）。每张最终 prompt 从前到后：
     * 品类subjectLock(R5锚点) → 【总分析】前置(R4) → 本张方案(base) → 风格(R5) → 系列连贯性+角度(R2) →
     * 文字渲染指令(R1) → 品类negative。
     * @param seriesPlan 全局【总分析】(含系列文案规划)  @param subjectLock 品类主体一致性约束
     * @param negative 品类禁止项  @param styleReq 改图风格文案  @param withText 是否渲染画面文案
     * @param structLock M19：刚性细杆/框架类品类——强制正面系角度 + 白底图强锁结构（防旋转臆造变形）
     */
    private String buildSeriesPrompt(String basePrompt, int currentIndex, int totalCount, String seriesPlan,
                                     String subjectLock, String negative, String styleReq, boolean withText,
                                     boolean structLock, boolean isShower) {
        String base = basePrompt == null ? "" : basePrompt.trim();
        StringBuilder sb = new StringBuilder();
        // R5：品类主体一致性约束前置（最高优先级锚点，锁产品结构/材质/logo/物理合理性）
        if (subjectLock != null && !subjectLock.isBlank()) sb.append(subjectLock.trim()).append("\n\n");
        // M19：架类/挂钩等细杆框架结构，白底图强锁——最高优先级，紧跟 subjectLock，防AI旋转臆造把结构画变形。
        // 关键：约束是"双向忠于白底图"——白底图有的造型件(如米奇耳朵/蝴蝶结/卡通脸/印字)必须1:1保留，
        // 白底图没有的不要加；绝不是"一律删装饰件"(那会抹掉产品真实设计特征)。
        if (structLock)
            sb.append("【结构强锁·白底图为唯一结构依据·最高优先级】本产品是细杆/薄框金属结构，极易被误画变形。"
                    + "产品主体的框架走线、杆件数量与走向、层数、挂位/卡位数量与位置、底座/接水盘形状、"
                    + "以及白底图上已有的一切造型与装饰件（如顶部造型环/耳朵、蝴蝶结、卡通图案、面板印字/LOGO）、连接焊点、比例，"
                    + "必须严格照所给白底产品图 1:1 复刻——白底图有什么就画什么、白底图什么样就画什么样，一件不多、一件不少、形状不变。"
                    + "严禁改变或简化白底图已有的造型件、严禁新增白底图上没有的东西、严禁改变杆件几何走向、严禁增减层数/挂位、严禁把结构画成不能承物的形状。"
                    + "被收纳物（锅盖/毛巾/衣物等）与产品的前后遮挡、支撑受力必须符合物理，不得穿模、不得悬空、不得违背重力。\n\n");
        // R4：【总分析】前置作产品物理识别 + 全局卖点分配强锚点（不再降级到末尾"勿渲染"）
        if (seriesPlan != null && !seriesPlan.isBlank())
            sb.append("【总分析·产品与系列规划】\n").append(seriesPlan.trim()).append("\n\n");
        // 本张分析方案（含本图卖点、画面文案、场景构图）
        sb.append("【第 ").append(currentIndex).append(" 张方案】\n").append(base).append("\n");
        // R5：改图风格直拼进生图 prompt（原来只喂分析）
        if (styleReq != null && !styleReq.isBlank()) sb.append("\n【画面风格】").append(styleReq.trim()).append("\n");
        // 系列连贯性 + 角度约束（R2 恢复多角度+防变形）
        sb.append(String.format("""

                【系列连贯性·最高优先级】这是同一场景系列拍摄的第 %d/%d 张：
                1. 产品主体必须100%%一致：外形、颜色、材质、品牌标识、关键结构完全相同
                2. **产品原生文字必须与第1张完全相同**：产品本体上的LOGO、品牌名、型号、按钮标签、刻度等，字形/位置/颜色/清晰度一致，禁止模糊变形消失
                3. 场景类型必须完全一致（浴室保持浴室、厨房保持厨房）
                4. 整体色调、光线氛围、背景材质保持一致，营造"同一时间同一地点"的连续感
                5. 允许变化：拍摄角度、景别(远近)、场景中摆放位置、光照角度、**画面营销文案（每张按本图卖点不同）**
                6. 禁止：场景类型切换、色调剧变、产品变形、产品原生文字错误/模糊、风格跳跃
                7. 最终效果：同一场景不同角度/景别拍同一产品的连续镜头，每张用不同营销文案强调不同卖点
                """.trim(), currentIndex, totalCount));
        sb.append(buildAngleConstraint(currentIndex, totalCount, structLock));
        // 花洒出水物理（#1）：若本张演示出水，水流必须从喷面正下方成束喷出、强劲有力如瀑布，
        // 不得从侧面/斜向乱喷、不得软弱无力或雾化飘散。仅花洒品类追加，不污染其它品类。
        if (isShower)
            sb.append("\n\n【出水形态·仅当画面展示出水时】若本张展示花洒喷水：水流必须自喷头面板正下方垂直向下、"
                    + "成密集平行的强劲水柱束（瀑布/大水量花洒既视感），水流饱满有力、方向笔直向下；"
                    + "严禁水流从花洒侧面或斜向喷出、严禁水流软弱稀疏/雾化飘散/方向散乱；"
                    + "喷头出水面朝向合理（面朝下或斜向使用者），符合真实手持花洒的出水物理。");
        // R1：文字渲染指令（补回画面文案）
        if (withText) sb.append("\n\n").append(TEXT_RENDER_INSTRUCTION);
        // 品类禁止项收尾
        if (negative != null && !negative.isBlank()) sb.append("\n\n").append(negative.trim());
        return sb.toString();
    }

    /**
     * 角度差异化约束。第1张正面基调；2~N 张按 selectAngleSequence 走"正面系温和序列"
     * （景别近远/构图位置/微侧≤15度），强约束"仅换景别机位、产品本体结构不变形、有支撑不浮空"。
     * M19-P3：原来非架类走大角度旋转(侧/俯/45度)，对正面白底图 image2image 会致浮空/穿模/变形，
     * 现全品类统一走正面系温和策略，对齐羽刃"变化交给分析阶段场景构图驱动、不强制大旋转"。
     * structLock（架类/挂钩细杆框架）另有更严的专属 flat 序列。
     */
    private String buildAngleConstraint(int currentIndex, int totalCount, boolean structLock) {
        if (currentIndex == 1) {
            return """

                【第1张·基调建立】
                产品主视角（正面或正面45度），清晰展示产品正面特征、品牌LOGO、核心卖点。
                这张图将作为后续图片的参考基准，必须完整呈现产品全貌。
                **画面营销文案**：根据卖点设计本图营销文案（主标题、副标题、卖点标签），强调第1个核心卖点
                """;
        }
        // M19：细杆框架类——正面系微变化序列，禁止大角度旋转
        if (structLock) {
            String[] flat = {
                "正面平视中景（正对镜头，展示完整框架全貌，与第1张同视角但机位略拉远/换构图位置）",
                "核心功能区正面近景特写（镜头拉近放大挂位/卡位/接水盘等功能部位，仍正面视角）",
                "正面微侧≤15度（机位仅轻微偏移带一点立体感，产品本体结构/杆件走向不变形）",
                "材质与细节正面特写（拉近展示金属表面质感/焊点/防滑处理，正面视角）"
            };
            String shot = flat[(currentIndex - 2) % flat.length];
            return String.format("""

                【第%d张·角度约束·细杆框架防变形·强制执行】
                本图采用：%s。
                - **产品必须正对镜头**，靠景别(远近/特写)与画面构图位置区分各张，**严禁侧视/俯视/仰视/背面等大角度旋转**
                - 细杆/薄框结构在大角度下极易被AI臆造成变形几何，故本品类只做正面系微变化
                - 产品本体的框架走线、杆件数量与走向、层数、挂位/卡位、底座/接水盘形状**严格照白底图不变**，不得因换景别而变形/拉伸/增减部件
                - 保持产品主体完整可见、不被裁切；被收纳物与产品前后遮挡、支撑受力符合物理，不穿模不悬空
                **产品原生文字**：参考第1张，产品本体文字/LOGO与第1张完全一致
                **画面营销文案**：可与前面不同，设计新营销标题强调第%d个卖点
                """, currentIndex, shot, currentIndex);
        }
        String[] angles = selectAngleSequence(totalCount);
        String currentShot = angles[(currentIndex - 2) % angles.length];
        return String.format("""

                【第%d张·角度约束·强制执行】
                本图采用：%s。
                - 靠**景别(远近/特写)、画面构图位置、≤15度轻微机位偏移**与前面各张区分，形成系列展示
                - **严禁侧视/俯视/仰视/背面等大角度旋转**：对着正面参考图大旋转会让模型脑补看不见的几何，导致产品变形/穿模/浮空
                - 产品本体的结构、比例、部件数量与位置、轮廓、侧边缘几何**严格照参考图保持不变**，不得因换景别而变形、拉伸、增减部件
                - 产品必须有真实支撑（底座/挂架/接触面），不得悬空浮空；与台面/墙面接触处有清晰接触边界与合理阴影，符合物理与重力
                - 保持产品主体完整可见、不被裁切
                **产品原生文字**：参考第1张，产品本体文字/LOGO与第1张完全一致
                **画面营销文案**：可与前面不同，设计新营销标题强调第%d个卖点或从新景别证明功能
                """, currentIndex, currentShot, currentIndex);
    }

    /**
     * 按总张数返回景别序列（不含第1张正面基调）。
     * M19-P3：对齐羽刃——羽刃主图不强制大角度旋转，靠景别(远近/特写)+构图位置+轻微机位偏移区分各张，
     * 变化交给分析阶段的【场景构图】驱动。原"正面45度侧视/俯视45/左侧45斜视"等大角度旋转，对着一张
     * 正面白底图做 image2image 时会让模型脑补看不见的几何→浮空/穿模/结构变形(07.11 花洒/挂钩反馈)。
     * 故改为"正面系温和序列"：景别近远 + 构图位置 + 微侧≤15度，杜绝侧/俯/仰/背大旋转。
     */
    private String[] selectAngleSequence(int totalCount) {
        if (totalCount <= 3) {
            return new String[]{
                "正面平视中景（正对镜头，与第1张同视角但机位略拉远或换画面构图位置区分）",
                "核心部件正面近景特写（镜头拉近放大功能区/出水口等核心部件，仍正面视角）"
            };
        } else if (totalCount <= 5) {
            return new String[]{
                "正面平视中景（机位略拉远或换构图位置，展示完整全貌）",
                "核心部件正面近景特写（拉近放大核心功能部件，正面视角）",
                "材质局部特写（拉近展示表面质感/LOGO/细节，正面视角）",
                "正面微侧≤15度（机位仅轻微偏移带一点立体感，产品结构不变形）"
            };
        } else {
            return new String[]{
                "正面平视中景（机位略拉远，展示完整全貌）",
                "核心部件正面近景特写（拉近放大核心功能部件）",
                "材质局部特写（拉近展示表面质感/LOGO）",
                "正面微侧≤15度（轻微机位偏移带立体感，结构不变形）",
                "功能演示正面近景（正面展示使用/出水状态，产品朝向不变）",
                "正面平视全景（正对镜头稍拉远，展示完整全貌与场景）"
            };
        }
    }

    /**
     * M18-R3：auto 比例解析——按白底图真实宽高吸附到最近的 gpt-image-2 合法比例（仿羽刃 resolveAutoAspect）。
     * requested 为具体比例(非auto)时原样返回；auto/空时读图推断，读图失败回退 1:1。
     */
    private String resolveAutoAspect(String requested, String whiteFile) {
        if (requested != null && !requested.isBlank() && !"auto".equals(requested)) return requested;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new File(whiteFile));
            if (img == null) return "1:1";
            double r = (double) img.getWidth() / img.getHeight();
            // 吸附到最近的 5 档合法比例
            String[] cands = {"1:1", "3:4", "4:3", "9:16", "16:9"};
            double[] ratios = {1.0, 3.0 / 4, 4.0 / 3, 9.0 / 16, 16.0 / 9};
            int best = 0; double bestDiff = Double.MAX_VALUE;
            for (int i = 0; i < cands.length; i++) {
                double d = Math.abs(Math.log(r) - Math.log(ratios[i]));
                if (d < bestDiff) { bestDiff = d; best = i; }
            }
            return cands[best];
        } catch (Exception e) {
            log.warn("resolveAutoAspect 读图失败({})，回退 1:1: {}", whiteFile, e.getMessage());
            return "1:1";
        }
    }
}

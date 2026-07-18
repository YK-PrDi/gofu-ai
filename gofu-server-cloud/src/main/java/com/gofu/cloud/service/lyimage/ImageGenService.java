package com.gofu.cloud.service.lyimage;

import com.gofu.cloud.config.LyImageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import okhttp3.OkHttpClient;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SKU 生图编排服务。
 * 负责：解析模板/配件、组装提示词与参考图、按 provider/模板分派生成，再委托
 * {@link AiImageClient}（HTTP/AI 调用）与 {@link ShowerCompositor}（Java 贴图合成）。
 */
@Service
public class ImageGenService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenService.class);

    private final LyImageProperties appProperties;
    private final PromptTemplateService templateService;
    private final AiImageClient aiClient;
    private final ShowerCompositor compositor;

    public ImageGenService(LyImageProperties appProperties, PromptTemplateService templateService,
                           AiImageClient aiClient, ShowerCompositor compositor) {
        this.appProperties = appProperties;
        this.templateService = templateService;
        this.aiClient = aiClient;
        this.compositor = compositor;
    }

    /** 公开委托：对一张主图分析背景风格（控制器调用）。 */
    public String analyzeBackgroundStyleOnce(String refImagePath) {
        return aiClient.analyzeBackgroundStyleOnce(refImagePath);
    }

    /** M18 公开委托：品类主体一致性约束 / 禁止项（FlowController 主图组装用）。 */
    public String ecSubjectLock(String category) { return templateService.ecSubjectLock(category); }
    public String ecNegative(String category) { return templateService.ecNegative(category); }

    /** 公开委托：文本/多模态生成（标题/款式名/搭配方案，ListingService 调用）。 */
    public String geminiText(String prompt, List<String> imagePaths) throws Exception {
        return aiClient.geminiText(prompt, imagePaths);
    }

    // M17 重构：原 matchShelfKind(品种关键词猜测)已废弃，改为 PromptTemplateService.shelfPick
    // 按叶子类目命中 + 款式分组 + 组内配对随机（防关键词误判、防文不对图）。

    /** 从 compDesc（如"全配+5支滤芯【可用1年】"）提取滤芯数量，无匹配返回 0 */
    private static int parseFilterCount(String compDesc) {
        if (compDesc == null || compDesc.isBlank()) return 0;
        Matcher m = Pattern.compile("(\\d+)\\s*支?\\s*滤芯").matcher(compDesc);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** 款式名含「滤芯」但没写数字时的默认滤芯数 */
    private static int filterCountFor(String compDesc) {
        int n = parseFilterCount(compDesc);
        if (n > 0) return n;
        return (compDesc != null && compDesc.contains("滤芯")) ? 1 : 0;
    }

    private static boolean hasHose(String d) { return d != null && (d.contains("软管") || d.contains("水管")); }
    private static boolean hasBase(String d) { return d != null && (d.contains("底座") || d.contains("支架") || d.contains("挂座")); }

    /** 拼配件横幅信息：用配件文件名（含 1.5米/2米 软管区分）+ 滤芯数量，写进图生图指令。 */
    private static String buildAccInfo(List<File> accFiles, java.util.List<String> accLabels, int filterShow) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (int i = 0; i < accFiles.size(); i++) {
            String label = accLabels.get(i);
            String nm = accFiles.get(i).getName().replaceAll("\\.[^.]+$", "");
            if ("滤芯".equals(label)) parts.add(filterShow + "支滤芯");
            else if ("软管".equals(label)) parts.add(nm.contains("2米") ? "2米软管" : (nm.contains("1.5") ? "1.5米软管" : "软管"));
            else if ("底座".equals(label)) parts.add("底座");
            else parts.add(nm);
        }
        return parts.isEmpty() ? "无配件" : String.join(" / ", parts);
    }

    /** 数字 1-20 转英文单词，用于 prompt 双重约束（数字+文字） */
    private static String numberToWords(int n) {
        String[] words = {"zero","one","two","three","four","five","six","seven",
                          "eight","nine","ten","eleven","twelve","thirteen","fourteen",
                          "fifteen","sixteen","seventeen","eighteen","nineteen","twenty"};
        return n >= 0 && n < words.length ? words[n] : String.valueOf(n);
    }

    /** 常见颜色字，用于从中文段里识别「含颜色字的那个词」作色名。 */
    private static final String COLOR_CHARS = "黑白灰银金蓝红绿粉紫橙棕青黄米咖啡香槟空";

    /**
     * 从 SKU 名提取纯颜色名。SKU 名形如「GF-106-黑色-1 全配...」「月光银-增压」「【雅黑色】...」。
     * 规则：① 取【】内；② 按 - / 空格 拆段，跳过含字母/数字的编码段（GF/106/1），
     * 在纯中文段里优先返回「含颜色字的整词」（月光银→月光银、雅黑色→雅黑色、黑色→黑色）；
     * ③ 没有含颜色字的段则返回第一个纯中文段；④ 都没有回退首段。
     */
    private static String colorOf(String skuName) {
        if (skuName == null) return "";
        String s = skuName.trim();
        if (s.isEmpty()) return "";
        Matcher m = Pattern.compile("[【\\[]([^】\\]]+)[】\\]]").matcher(s);
        if (m.find()) return m.group(1).trim();
        String[] seg = s.split("[\\-\\s+]+");
        String firstCjk = null;
        for (String g : seg) {
            String t = g.trim();
            if (t.isEmpty() || !t.matches("[\\u4e00-\\u9fa5]+")) continue;  // 跳过编码段
            if (firstCjk == null) firstCjk = t;
            for (int i = 0; i < t.length(); i++) {
                if (COLOR_CHARS.indexOf(t.charAt(i)) >= 0) return t;  // 含颜色字→整词返回
            }
        }
        if (firstCjk != null) return firstCjk;
        return seg.length > 0 && !seg[0].isEmpty() ? seg[0].trim() : s;
    }

    /** 人像参考图：从 classpath assets/portrait.png 落地到用户目录一次，返回文件。失败返回 null。 */
    private File portraitRefFile() {
        try {
            File f = new File(appProperties.getPaths().getUserDataDir(), "assets/portrait.png");
            if (f.isFile()) return f;
            f.getParentFile().mkdirs();
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("assets/portrait.png")) {
                if (is == null) return null;
                java.nio.file.Files.write(f.toPath(), is.readAllBytes());
            }
            return f;
        } catch (Exception e) { log.warn("人像参考图落地失败: {}", e.getMessage()); return null; }
    }

    /**
     * 以 refImagePath 为参考，为某个 SKU 生成一张展示图，保存到 outputDir，返回绝对路径。
     * 失败抛异常。
     */
    public String generateSkuImage(String refImagePath, String skuName, String compDesc,
                                   String productType, String batch, int seq, String bagImagePath,
                                   String whiteImgPath, List<String> accImagePaths,
                                   String waterImagePath, String bgStyleOverride, String itemCode,
                                   List<Map<String, Object>> accParts, String templateId, int mainQty) throws Exception {
        LyImageProperties.GptImage cfg = appProperties.getGptImage();
        List<String> keys = cfg.keyList();
        if (keys.isEmpty()) throw new RuntimeException("生图密钥未配置");
        String baseUrl = cfg.getBaseUrl();
        String model   = cfg.getModel();
        boolean gemini = "gemini".equalsIgnoreCase(cfg.getProvider());
        boolean openai = "openai".equalsIgnoreCase(cfg.getProvider());
        boolean isShower = productType != null && (productType.contains("花洒") || productType.contains("淋浴"));
        // 架类品（家装主材>厨房>厨房挂件）：productType 形如 "架类:<叶子>"（见 FlowController.deriveProductTypeForGen）。
        // 整图 AI（每品种一段专属 prompt + 参考图作构图底 + 白底图锁主体），不走任何 Java 贴图合成。
        boolean isShelf = productType != null && productType.startsWith("架类");

        // 纯颜色名：从 skuName 取首段（去【】后，遇 - / 空格 截断），用于左上色标等「只写颜色」处。
        // 如「雅黑色-亲肤按摩 单品」→「雅黑色」、「【月光银】增压」→「月光银」。
        String colorOnly = colorOf(skuName);

        // 防比价模板：templateId 指定则按它决定构图；sticker/空=贴图(现有逻辑)，ai=整图AI生成。
        Map<String, Object> tpl = (templateId != null && !templateId.isBlank())
            ? templateService.findById(templateId) : null;
        boolean aiTemplate = tpl != null && "ai".equalsIgnoreCase(String.valueOf(tpl.get("type")));
        boolean stickerMode = !aiTemplate;  // 非 ai 模板（含默认/sticker）都走贴图合成
        String cacheBaseForTemplate = null;  // 非空＝本次生成的图要缓存为该模板的基准图

        int filterCount = parseFilterCount(compDesc);
        // 滤芯材质锁定（07.09 反馈#1）：只要有滤芯就约束材质=加厚 PP 棉多层过滤棉柱，禁止任何其它材质。
        boolean hasFilterInDesc = compDesc != null && compDesc.contains("滤芯");
        String filterMaterial = hasFilterInDesc
            ? "FILTER MATERIAL LOCK: every filter is a THICK MULTI-LAYER PP COTTON filter cartridge "
              + "(加厚 PP 棉多层过滤棉柱, for sediment removal & water purification / 除杂净水). "
              + "The visible fill must read as dense white PP cotton fibre. "
              + "STRICTLY FORBIDDEN: metal, ceramic, resin, activated-carbon granules, or any non-PP-cotton "
              + "filter material (严禁金属/陶瓷/树脂/活性炭颗粒等任何非 PP 棉材质的滤芯). "
            : "";
        String filterConstraint = filterCount > 0
            ? filterMaterial
              + "ABSOLUTE FILTER COUNT: exactly " + filterCount + " (" + numberToWords(filterCount) + ") "
              + "white sleek cylindrical filter sticks. "
              + "These are plain matte white tubes — NO holes, NO handle, NO black rubber, "
              + "NO water-outlet pattern, NO surface details from the main product. "
              + "Count them: there must be exactly " + filterCount + " on the left side. "
            : filterMaterial;

        String skuPromptTemplate = PromptLoader.load("prompt/image-sku-white-bg.txt");
        String prompt = skuPromptTemplate
            .replace("{{productType}}",  productType == null ? "Shower Head" : productType)
            .replace("{{skuName}}",      skuName)
            .replace("{{compDesc}}",     compDesc == null || compDesc.isBlank() ? "no accessories" : compDesc)
            .replace("{{filterConstraint}}", filterConstraint);

        File ref = new File(refImagePath);
        boolean hasRef = ref.isFile();
        // 白底产品图：SKU 白底路径用它作视觉参考（干净、无文字水印），回退到营销参考图
        File whiteBgRef = (whiteImgPath != null && !whiteImgPath.isBlank())
            ? new File(whiteImgPath) : ref;
        boolean hasWhiteBg = whiteBgRef.isFile();
        File bag = (bagImagePath != null && !bagImagePath.isBlank()) ? new File(bagImagePath) : null;
        boolean hasBag = bag != null && bag.isFile();
        // 配件白底图筛选：优先用前面【已选的配件清单 accParts】（每项 code+qty）精确匹配白底图文件名——
        // 这是用户在搭配阶段明确选的配件，最可靠。accParts 为空时回退到 itemCode 配件码 / 中文关键字。
        boolean needHose = hasHose(compDesc);
        boolean needBase = hasBase(compDesc);
        boolean needFilter = filterCountFor(compDesc) > 0;
        // 候选白底图（已由前端排除袋子/水质/主件色图）
        java.util.List<File> candFiles = new java.util.ArrayList<>();
        if (accImagePaths != null) {
            for (String p : accImagePaths) {
                if (p == null || p.isBlank()) continue;
                File f = new File(p);
                if (f.isFile()) candFiles.add(f);
            }
        }
        List<File> accFiles = new java.util.ArrayList<>();
        java.util.List<String> accLabels = new java.util.ArrayList<>();  // 与 accFiles 对应：每张配件图的中文身份
        int filterQtyFromParts = 0;  // 从已选配件清单里拿到的滤芯数量
        boolean usedParts = false;
        java.util.Set<String> usedFiles = new java.util.HashSet<>();  // 已用白底图，避免一张图被多个 part 重复命中
        // 展开组合套装码：有的 part 的 code 是一长串组合码（如「027黑单手喷+银底座+银色2米软管+001滤芯*5」），
        // 需拆成多个原子配件，各自带关键字与数量，否则只会按整串匹配 → 漏配底座/软管。
        java.util.List<Map<String, Object>> flatParts = new java.util.ArrayList<>();
        if (accParts != null) {
            for (Map<String, Object> part : accParts) {
                String code = String.valueOf(part.getOrDefault("code", "")).trim();
                String pkw = String.valueOf(part.getOrDefault("kw", "")).trim();
                int pqty = 1;
                try { pqty = Math.max(1, Integer.parseInt(String.valueOf(part.getOrDefault("qty", 1)))); } catch (Exception ignore) {}
                // part 已带明确 kw（软管/底座/滤芯）时，信任它、按 kw 匹配一张图，绝不拆 code——
                // 否则像 code=「GF-001-纯白+1.5米银软管+银底座+001滤芯*10」这种套装编码会被拆成三样，
                // 导致单软管 SKU 错误显示软管+底座+滤芯。只有 kw 为空时才回退到「按 + 拆组合码」。
                boolean knownKw = pkw.equals("软管") || pkw.equals("底座") || pkw.equals("滤芯");
                if (code.contains("+") && !knownKw) {
                    // 组合码：按 + 拆段，跳过首段（主件），每段解析 *N 数量 + 关键字
                    String[] segs = code.split("\\+");
                    for (int i = 1; i < segs.length; i++) {
                        String seg = segs[i].trim();
                        if (seg.isEmpty()) continue;
                        int segQty = 1;
                        int star = seg.indexOf('*');
                        if (star >= 0) {
                            try { segQty = Math.max(1, Integer.parseInt(seg.substring(star + 1).replaceAll("[^0-9]", ""))); } catch (Exception ignore) {}
                            seg = seg.substring(0, star).trim();
                        }
                        String segKw = seg.contains("软管") ? "软管" : seg.contains("滤芯") ? "滤芯"
                                     : (seg.contains("底座") || seg.contains("支架") || seg.contains("挂座")) ? "底座" : "";
                        Map<String, Object> fp = new java.util.HashMap<>();
                        fp.put("code", seg); fp.put("qty", segQty); fp.put("kw", segKw);
                        flatParts.add(fp);
                    }
                } else {
                    Map<String, Object> fp = new java.util.HashMap<>();
                    fp.put("code", code); fp.put("qty", pqty); fp.put("kw", pkw);
                    flatParts.add(fp);
                }
            }
        }
        if (!flatParts.isEmpty()) {
            for (Map<String, Object> part : flatParts) {
                String code = String.valueOf(part.getOrDefault("code", "")).trim();
                if (code.isEmpty()) continue;
                int qty = 1;
                try { qty = Math.max(1, Integer.parseInt(String.valueOf(part.getOrDefault("qty", 1)))); } catch (Exception ignore) {}
                // 关键字：前端传的 kw 优先；没传则从 code 推断（软管/滤芯/底座）
                String kw = String.valueOf(part.getOrDefault("kw", "")).trim();
                if (kw.isEmpty()) {
                    if (code.contains("软管") || code.toLowerCase().contains("hose")) kw = "软管";
                    else if (code.contains("滤芯") || code.toLowerCase().contains("filter")) kw = "滤芯";
                    else if (code.contains("底座") || code.toLowerCase().contains("base")) kw = "底座";
                }
                File hit = null;
                // 1) kw 优先：白底图按「软管/底座/滤芯」命名，按关键字精确配，最可靠
                if (!kw.isEmpty()) {
                    for (File f : candFiles) {
                        if (usedFiles.contains(f.getPath())) continue;
                        if (f.getName().contains(kw)) { hit = f; break; }
                    }
                }
                // 2) kw 没配上→退回按 ERP 配件码匹配文件名
                if (hit == null) {
                    for (File f : candFiles) {
                        if (usedFiles.contains(f.getPath())) continue;
                        String nmNoExt = f.getName().replaceAll("\\.[^.]+$", "");
                        if (nmNoExt.contains(code) || code.contains(nmNoExt)) { hit = f; break; }
                    }
                }
                if (hit == null) continue;
                usedFiles.add(hit.getPath());
                usedParts = true;
                String nm = hit.getName();
                String label = nm.contains("软管") || nm.toLowerCase().contains("hose") ? "软管"
                             : nm.contains("滤芯") || nm.toLowerCase().contains("filter") ? "滤芯"
                             : nm.contains("底座") || nm.toLowerCase().contains("base") ? "底座" : "配件";
                if ("滤芯".equals(label)) filterQtyFromParts = Math.max(filterQtyFromParts, qty);
                accFiles.add(hit);
                accLabels.add(label);
            }
        }
        // 回退：accParts 没命中任何图时，用旧的 itemCode 配件码 / 中文关键字匹配
        if (!usedParts) {
            java.util.List<String> accCodes = new java.util.ArrayList<>();
            if (itemCode != null && !itemCode.isBlank() && itemCode.contains("+")) {
                String[] segs = itemCode.split("\\+");
                for (int i = 1; i < segs.length; i++) {
                    String code = segs[i].split("\\*")[0].trim();
                    if (!code.isEmpty()) accCodes.add(code);
                }
            }
            boolean byCode = !accCodes.isEmpty();
            for (File f : candFiles) {
                String nm = f.getName();
                String nmNoExt = nm.replaceAll("\\.[^.]+$", "");
                boolean keep;
                if (byCode) {
                    keep = accCodes.stream().anyMatch(nmNoExt::contains);
                } else {
                    boolean isHose = nm.contains("软管") || nm.toLowerCase().contains("hose");
                    boolean isFilter = nm.contains("滤芯") || nm.toLowerCase().contains("filter");
                    boolean isBase = nm.contains("底座") || nm.toLowerCase().contains("base");
                    keep = (isHose && needHose) || (isFilter && needFilter) || (isBase && needBase);
                }
                if (keep) {
                    accFiles.add(f);
                    String label = nm.contains("软管") || nm.toLowerCase().contains("hose") ? "软管"
                                 : nm.contains("滤芯") || nm.toLowerCase().contains("filter") ? "滤芯"
                                 : nm.contains("底座") || nm.toLowerCase().contains("base") ? "底座" : "配件";
                    accLabels.add(label);
                }
            }
        }
        // 滤芯展示数量：优先用已选配件清单里的滤芯 qty，没有再用款式名解析值
        int filterShow = filterQtyFromParts > 0 ? filterQtyFromParts : filterCount;
        OkHttpClient http = aiClient.buildHttp();

        // 两阶段：先用视觉模型分析白底主件图，提取真实材质/颜色/结构，注入生图 prompt
        String refAnalysis = "";
        if (gemini && hasWhiteBg) {
            try {
                String a = aiClient.analyzeRefImage(http, baseUrl, keys.get(0), whiteBgRef,
                                           skuName, productType, compDesc);
                if (a != null && !a.isBlank()) refAnalysis = a;
            } catch (Exception e) { log.warn("SKU 主件分析失败，降级无分析生图: {}", e.getMessage()); }
        }

        // ── openai + 花洒 sticker 贴图模式：AI 生右侧主件+背景，左侧由 Java 合成贴图。
        //    （ai 模板不走这里，落到下方统一路径，享受基准图复用/img2img/配件卡/通栏等完整逻辑）──
        if (openai && isShower && stickerMode) {
            // 背景：优先用同批共享的主图背景描述；并把营销主图作为参考图喂给模型（否则背景无从参考）
            String bgStyle = (bgStyleOverride != null && !bgStyleOverride.isBlank())
                ? bgStyleOverride
                : "严格复刻所给营销主图的背景：颜色、光影层次、装饰元素与氛围一致，背景丰富有层次，不要简化成单调纯色块";
            String showerTemplate = PromptLoader.load("prompt/image-shower-main.txt");
            prompt = showerTemplate
                .replace("{{bgStyle}}",   bgStyle)
                .replace("{{colorName}}", colorOnly);

            // 参考图：本色花洒白底图 + 袋子 + 营销主图(背景参考)（配件/水质不传给 AI，左侧由合成贴图）
            List<File> showerRefs = new java.util.ArrayList<>();
            if (hasWhiteBg) showerRefs.add(whiteBgRef);
            if (hasBag) showerRefs.add(bag);
            if (hasRef) showerRefs.add(ref);

            // 一次生成。M10：撞限流(429/5xx中转站繁忙)时指数退避重试同一调用——
            // 原来只轮 key 无退避，整批 SKU 排在主图/详情之后、配额耗尽→只出前 1~2 张(07.06反馈)。
            Exception lastShower = null;
            int maxBackoff = 4;
            for (int attempt = 0; attempt < keys.size() * (1 + maxBackoff); attempt++) {
                String key = keys.get(Math.abs(aiClient.keyCursor.getAndIncrement()) % keys.size());
                try {
                    String b64 = showerRefs.isEmpty()
                        ? aiClient.callGptImage2TextOnly(http, baseUrl, key, model, prompt)
                        : aiClient.callGptImage2(http, baseUrl, key, model, prompt, showerRefs);
                    File out = aiClient.saveAsJpg(b64, batch, seq, skuName);
                    try {
                        out = compositor.compositeShowerLeft(out, accFiles, accLabels, filterShow, batch, seq, skuName, compDesc, hasRef ? ref : null, colorOnly);
                    } catch (Exception ce) {
                        log.warn("花洒左侧合成失败，返回纯主图: {}", ce.getMessage());
                    }
                    return out.getAbsolutePath();
                } catch (Exception e) {
                    lastShower = e;
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    boolean rateLimited = msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED")
                            || msg.contains("rate") || msg.contains("Too Many") || msg.contains("503") || msg.contains("繁忙");
                    if (rateLimited) {
                        long round = attempt / Math.max(1, keys.size());
                        long waitMs = Math.min(16000, 2000L * (1L << Math.min(3, round)));
                        log.warn("花洒主图撞限流，退避 {}ms 重试 (attempt {}): {}", waitMs, attempt, msg);
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    } else {
                        log.warn("花洒主图失败(密钥{}): {}", attempt, msg);
                    }
                }
            }
            throw new RuntimeException("花洒主图生成失败: " + (lastShower != null ? lastShower.getMessage() : "未知"));
        }

        // ── 架类品防比价：整图 AI（品种专属 prompt + 参考图作构图底 + 白底图锁主体），不走 Java 贴图 ──
        if (isShelf) {
            // M17 重构：类目-keyed + 款式分组 + 组内配对随机(shelfPick)。
            // productType 形如 "架类:<叶子> <主件名>"(deriveProductTypeForGen 把主件名并入)。
            // 取冒号后首段(空格前)作叶子类目键，主件名并入 skuName 供款式分组(吸盘/落地)判定。
            String ptTail = productType.contains(":") ? productType.substring(productType.indexOf(':') + 1).trim() : "";
            int sp = ptTail.indexOf(' ');
            String leaf = sp > 0 ? ptTail.substring(0, sp).trim() : ptTail;
            String shelfSkuHint = ((sp > 0 ? ptTail.substring(sp + 1) : "") + " " + (skuName == null ? "" : skuName)).trim();
            com.gofu.cloud.service.lyimage.PromptTemplateService.ShelfPick pick = templateService.shelfPick(leaf, shelfSkuHint);
            if (pick == null)
                throw new RuntimeException("架类构图缺失(叶子类目=" + leaf + ")，请检查 shelf-prompts.json 是否含该类目");
            String shelfSeg = pick.prompt();
            String shelfTpl = PromptLoader.load("prompt/image-shelf-main.txt");
            String shelfPrompt = shelfTpl
                .replace("{{shelfPrompt}}", shelfSeg)
                .replace("{{colorName}}", colorOnly);
            // 配套参考图作构图底（refs 第一张权重最高）+ 商品白底图锁主体
            File shelfBase = templateService.shelfRefFile(leaf, pick.group(), pick.ref());
            List<File> shelfRefs = new java.util.ArrayList<>();
            boolean floorlidComposed = false;   // route2 已自行按"白底图优先+构图底"组好 refs 的标记

            // ── 路线2（真·落地锅盖架专属）：Java 先合成「米奇款架体+锅盖/砧板收纳物」构图底，作最高权重参考图，
            //    prompt 强约束"保架体/收纳物 1:1、只理顺卡位遮挡与背景"。绕开 AI 重画架体必崩。
            //    ⚠️ 这条路 3 处写死锅盖架专属(米奇架体/锅盖砧板收纳物/米奇蝴蝶结prompt)，只适用真锅盖架。
            //    其它落地架品种(树菜板架/沥水架等)品类名也叫「锅盖架」但实物不同，必须绕开这条、走下方通用整图AI
            //    (白底图锁主体+品种prompt，与花洒同理)，否则会被贴成锅盖架(错图根因)。
            //    判据：主件名/skuName 真提到"锅盖"才算真锅盖架。
            String floorHint = (shelfSkuHint + " " + (skuName == null ? "" : skuName));
            boolean isRealLidRack = floorHint.contains("锅盖");
            if (leaf.contains("锅盖架") && "落地".equals(pick.group()) && isRealLidRack) {
                try {
                    File rackImg = (shelfBase != null && shelfBase.isFile()) ? shelfBase
                            : templateService.assetByPath("base/shelf-落地锅盖架-米奇款.png");
                    java.util.List<File> collectibles = new java.util.ArrayList<>();
                    File lid = templateService.assetByPath("collectibles/锅盖-侧立白底.png");
                    File board = templateService.assetByPath("collectibles/砧板-侧立白底.png");
                    if (lid != null) collectibles.add(lid);
                    if (board != null) collectibles.add(board);
                    if (lid != null) collectibles.add(lid);   // 三槽：锅盖/砧板/锅盖
                    if (rackImg != null && rackImg.isFile()) {
                        String baseImg = compositor.compositeShelfFloorLid(rackImg, collectibles, null, batch, seq, skuName + "-构图底");
                        // 改:白底图优先(主体锚,第一张最高权重),构图底只当版式参考(第二张)。
                        //    有真实白底图时白底图先进;构图底后进,prompt 已明确"架体以白底图为准,构图底只借摆法"。
                        if (hasWhiteBg) shelfRefs.add(whiteBgRef);
                        shelfRefs.add(new File(baseImg));       // 构图底作版式参考(白底图在前时它是第二张)
                        floorlidComposed = true;
                        shelfPrompt = PromptLoader.load("prompt/image-shelf-floorlid.txt")
                                .replace("{{shelfPrompt}}", shelfSeg).replace("{{colorName}}", colorOnly);
                        log.info("落地锅盖架 路线2：白底图优先={}, 构图底={}", hasWhiteBg, baseImg);
                    }
                } catch (Exception ce) {
                    log.warn("落地锅盖架构图底合成失败，回退整图AI: {}", ce.getMessage());
                }
            }

            // 参考图组装：
            //  · route2(真锅盖架)已按"白底图优先+构图底版式参考"进 shelfRefs,此处不再重复加。
            //  · 其余品种:有预制图作构图底(版式参考)+白底图锁主体;白底图存在且非route2→丢弃预制(避免实物被画成别款)。
            boolean dropPresetRef = hasWhiteBg && !(leaf.contains("锅盖架") && "落地".equals(pick.group()) && isRealLidRack);
            if (!floorlidComposed) {   // 非route2 才走通用组装(route2 已自行组好白底优先)
                if (hasWhiteBg) shelfRefs.add(whiteBgRef);   // 白底图优先(主体锚)
                if (shelfBase != null && shelfBase.isFile() && !dropPresetRef) shelfRefs.add(shelfBase);   // 预制图作版式参考,放白底之后
                if (!hasWhiteBg && hasRef) shelfRefs.add(ref);
            }
            log.info("架类生图: 叶子={}, 组={}, 预制参考图={}({}), 丢弃预制={}, 白底优先={}, route2构图底={}",
                    leaf, pick.group(), shelfBase != null, pick.ref(), dropPresetRef, hasWhiteBg, floorlidComposed);
            Exception lastShelf = null;
            int maxBackoff = 4;
            for (int attempt = 0; attempt < keys.size() * (1 + maxBackoff); attempt++) {
                String key = keys.get(Math.abs(aiClient.keyCursor.getAndIncrement()) % keys.size());
                try {
                    String b64 = shelfRefs.isEmpty()
                        ? aiClient.callGptImage2TextOnly(http, baseUrl, key, model, shelfPrompt)
                        : aiClient.callGptImage2(http, baseUrl, key, model, shelfPrompt, shelfRefs);
                    File shelfOut = aiClient.saveAsJpg(b64, batch, seq, skuName);
                    // 多件档(mainQty>1)：AI 底图上贴放大主件框 + ×N 角标（复用白底主件图）
                    if (mainQty > 1 && hasWhiteBg) {
                        try {
                            File framed = compositor.compositeMainQtyCardAt(shelfOut, whiteBgRef, mainQty, batch, seq, skuName);
                            return framed.getAbsolutePath();
                        } catch (Exception fe) {
                            log.warn("架类多件主件框合成失败，返回纯AI底图: {}", fe.getMessage());
                        }
                    }
                    return shelfOut.getAbsolutePath();
                } catch (Exception e) {
                    lastShelf = e;
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    boolean rl = msg.contains("429") || msg.contains("rate") || msg.contains("Too Many") || msg.contains("繁忙");
                    if (rl && attempt < keys.size() * (1 + maxBackoff) - 1) {
                        long round = attempt / Math.max(1, keys.size());
                        long waitMs = Math.min(16000, 2000L * (1L << Math.min(3, round)));
                        log.warn("架类图撞限流，退避 {}ms 重试 (attempt {}): {}", waitMs, attempt, msg);
                        try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    } else {
                        log.warn("架类图失败(密钥{}): {}", attempt, msg);
                    }
                }
            }
            throw new RuntimeException("架类图生成失败: " + (lastShelf != null ? lastShelf.getMessage() : "未知"));
        }

        // 参考图列表
        List<File> refs = new java.util.ArrayList<>();
        if (hasRef) refs.add(ref);

        // SKU 白底图：Flash 只提取参考图背景风格，不碰产品描述
        String bgDesc = "";
        if (!isShower) {
            if (bgStyleOverride != null && !bgStyleOverride.isBlank()) {
                bgDesc = bgStyleOverride;  // 同批共享的背景描述，优先
            } else if (gemini && hasRef) {
                try {
                    bgDesc = aiClient.analyzeBackgroundStyle(http, baseUrl, keys.get(0), ref);
                } catch (Exception e) { log.warn("SKU 背景提取失败: {}", e.getMessage()); }
            }
        }

        // 防比价模板：见方法上方已解析的 tpl / aiTemplate / stickerMode

        if (isShower) {
            // 花洒专属固定构图模板
            String bgStyle = "纯白或浅色简约影棚背景";
            if (bgStyleOverride != null && !bgStyleOverride.isBlank()) {
                bgStyle = bgStyleOverride;  // 同批共享的背景描述，优先
            } else if (gemini && hasRef) {
                try {
                    String bg = aiClient.analyzeBackgroundStyle(http, baseUrl, keys.get(0), ref);
                    if (bg != null && !bg.isBlank()) bgStyle = bg;
                } catch (Exception e) { log.warn("背景风格提取失败: {}", e.getMessage()); }
            }
            // B 兜底：useMainBg 模板若没拿到背景描述（仍是默认白底），改用「以主图为背景」话术，
            // 避免 {{bgStyle}}=纯白影棚 的文字盖过 refs 里实际传入的主图。
            if (aiTemplate && Boolean.TRUE.equals(tpl.get("useMainBg")) && hasRef
                && (bgStyleOverride == null || bgStyleOverride.isBlank())
                && "纯白或浅色简约影棚背景".equals(bgStyle)) {
                bgStyle = "严格以所给营销主图为背景参考，完整复刻主图的背景颜色、光影与氛围，不要用纯色影棚背景";
            }
            if (aiTemplate) {
                // 纯AI模板：基准图复用 + 图生图替换。有基准图→以它为底只换花洒/滤芯/背景；无→用 prompt 生成首张并缓存为基准。
                String colorNm = colorOnly;
                String accInfo = buildAccInfo(accFiles, accLabels, filterShow);
                // 按该 SKU 是否有配件，选「-有配件/-无配件」基准图变体
                boolean hasAcc = !accFiles.isEmpty();
                File baseImg = templateService.resolveBaseImg(tpl, hasAcc);
                refs.clear();
                if (baseImg != null && baseImg.isFile()) {
                    // 图生图：基准图打底（放第一张，权重最高）+ 本色花洒白底图 +（拆解类）滤芯 +（瀑布类）主图背景
                    String edit = String.valueOf(tpl.getOrDefault("editInstruction", tpl.getOrDefault("prompt", "")));
                    prompt = edit.replace("{{colorName}}", colorNm)
                                 .replace("{{bgStyle}}", bgStyle)
                                 .replace("{{accInfo}}", accInfo);
                    refs.add(baseImg);
                    if (hasWhiteBg) refs.add(whiteBgRef);
                    // 精简参考图：只喂滤芯白底图（装柄用），底座/软管不喂 AI（它们只用于 Java 贴配件卡），避免多图拖慢/超时
                    for (int k = 0; k < accFiles.size(); k++) if ("滤芯".equals(accLabels.get(k))) { refs.add(accFiles.get(k)); break; }
                    if (Boolean.TRUE.equals(tpl.get("useExplodeRef"))) {
                        File er = templateService.explodeRefFile();
                        if (er != null && er.isFile()) refs.add(er);  // 拆解结构参考，锁内部结构
                    }
                    if (Boolean.TRUE.equals(tpl.get("useMainBg")) && hasRef) refs.add(ref);
                    if (Boolean.TRUE.equals(tpl.get("usePortraitImg"))) {
                        File portrait = portraitRefFile();
                        if (portrait != null && portrait.isFile()) refs.add(portrait);
                    }
                } else {
                    // 无基准图：用 prompt 整图生成，生成后缓存为该模板基准图（供后续 SKU 复用）
                    String tplPrompt = String.valueOf(tpl.getOrDefault("prompt", ""));
                    prompt = tplPrompt.replace("{{bgStyle}}", bgStyle)
                                      .replace("{{colorName}}", colorNm)
                                      .replace("{{accInfo}}", accInfo);
                    if (hasWhiteBg) refs.add(whiteBgRef);
                    if (hasRef) refs.add(ref);
                    if (Boolean.TRUE.equals(tpl.get("usePortraitImg"))) {
                        File portrait = portraitRefFile();
                        if (portrait != null && portrait.isFile()) refs.add(portrait);
                    }
                    cacheBaseForTemplate = String.valueOf(tpl.get("id"));  // 标记：生成后缓存为基准
                }
                // 全 ai 模板共享的「花洒保真 + 防误配件」强约束（防止：花洒被换成别的样子、主体上乱接软管/底座、把花洒/喷头/手柄当配件另摆）
                prompt = prompt + "\n\n【产品一致性·强约束】"
                    + "花洒主体的外形、轮廓、比例、喷头面板孔位、手柄结构、颜色、材质必须严格复刻所给本色花洒白底图，禁止美化、禁止改变结构与配色；"
                    + "花洒主体上不得连接软管、底座或任何额外配件；"
                    + "不得把花洒、花洒喷头、花洒手柄当作配件重复出现或单独摆放；"
                    + "画面中除指定构图外不得新增任何产品或配件。";
            } else {
                String showerTemplate = PromptLoader.load("prompt/image-shower-main.txt");
                prompt = showerTemplate
                    .replace("{{bgStyle}}",   bgStyle)
                    .replace("{{colorName}}", colorOnly);
                // AI 只画右侧主件+背景，左侧配件由 Java 合成贴图。
                // 参考图：本色花洒白底图（锁颜色/样式）+ 袋子图 + 主图（背景参考）。配件/水质图不传给 AI。
                refs.clear();
                if (hasWhiteBg) refs.add(whiteBgRef);
                if (hasBag) refs.add(bag);
                if (hasRef) refs.add(ref);
            }
        }

        // SKU 白底图：填充主件分析结果占位符 + 追加背景描述到 prompt
        if (!isShower) {
            prompt = prompt.replace("{{refAnalysis}}", refAnalysis);
            if (gemini && !bgDesc.isBlank()) {
                prompt = prompt + "\n\n[BACKGROUND]: " + bgDesc;
            }
        }

        // 生图用图：花洒主图用 refs（营销参考图+袋子），SKU 白底图用白底产品图
        List<File> genRefs = new java.util.ArrayList<>();
        if (isShower) {
            genRefs.addAll(refs);  // 花洒：refs = 营销参考图 + 袋子图
        } else if (hasWhiteBg) {
            genRefs.add(whiteBgRef);  // SKU 白底：只用白底产品图
        } else if (hasRef) {
            genRefs.add(ref);  // SKU 无白底图时回退
        }

        // 轮换密钥，失败换下一个。M10：Gemini 按【项目】限流(非按key)，429 换 key 无用，
        // 必须退避重试同一调用——否则多数 SKU 撞 429 直接失败(现象：整批只出前 1~2 张)。
        // 每个 key 最多退避重试 maxBackoff 次(指数退避)，总尝试上限 keys*(1+maxBackoff)。
        Exception last = null;
        int maxBackoff = 4;
        for (int attempt = 0; attempt < keys.size() * (1 + maxBackoff); attempt++) {
            String key = keys.get(Math.abs(aiClient.keyCursor.getAndIncrement()) % keys.size());
            try {
                // 诊断耗时：记录 API 调用开始/结束，用于判断是「单张本身慢」还是「中转站限并发被串行」
                long _t0 = System.nanoTime();
                log.info("[生图计时] seq={} 第{}图 开始调用 (线程={}, 参考图{}张)", seq, seq, Thread.currentThread().getName(), genRefs.size());
                String b64;
                if (openai) {
                    b64 = genRefs.isEmpty()
                        ? aiClient.callGptImage2TextOnly(http, baseUrl, key, model, prompt)
                        : aiClient.callGptImage2(http, baseUrl, key, model, prompt, genRefs);
                } else {
                    b64 = aiClient.callGemini(http, baseUrl, key, model, prompt, genRefs);
                }
                log.info("[生图计时] seq={} API 返回, 耗时 {}s", seq, String.format("%.1f", (System.nanoTime() - _t0) / 1e9));
                File out = aiClient.saveAsJpg(b64, batch, seq, skuName);
                // 花洒贴图模式：AI 只生右侧主件+背景，左侧配件/批量件/水质对比由 Java 合成贴图。
                // 纯AI模板（aiTemplate）整图由 AI 生成，不贴图。
                if (isShower && stickerMode) {
                    try {
                        out = compositor.compositeShowerLeft(out, accFiles, accLabels, filterShow, batch, seq, skuName, compDesc, hasRef ? ref : null, colorOnly);
                    } catch (Exception ce) {
                        log.warn("花洒左侧合成失败，返回纯主图: {}", ce.getMessage());
                    }
                }
                // 纯AI模板带 accCardRegion / bottomBanner：img2img 出图后，Java 在指定区域贴配件卡/底部通栏（确定性）
                if (isShower && aiTemplate) {
                    Object region = tpl.get("accCardRegion");
                    boolean bottomBanner = Boolean.TRUE.equals(tpl.get("bottomBanner"));
                    String regionStr = region instanceof String ? (String) region : "";
                    String bannerRight = String.valueOf(tpl.getOrDefault("bannerRight", ""));
                    if (!regionStr.isBlank() || bottomBanner) {
                        try {
                            out = compositor.compositeAccCardAt(out, regionStr, accFiles, accLabels, filterShow,
                                                     bottomBanner, skuName, bannerRight, batch, seq, skuName, hasRef ? ref : null, colorOnly);
                        } catch (Exception ce) {
                            log.warn("配件卡/底部通栏合成失败，返回纯AI图: {}", ce.getMessage());
                        }
                    }
                }
                // 纯AI模板无基准图时，把这张生成图缓存为该模板基准图，供同模板后续 SKU 图生图复用
                if (cacheBaseForTemplate != null) {
                    try { templateService.saveBaseCache(cacheBaseForTemplate, out); } catch (Exception ce) { log.warn("基准图缓存失败: {}", ce.getMessage()); }
                }
                return out.getAbsolutePath();
            } catch (Exception e) {
                last = e;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                boolean rateLimited = msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED")
                        || msg.contains("rate") || msg.contains("Too Many");
                if (rateLimited) {
                    // 指数退避后重试同一调用（换 key 对项目级限流无用）。round 从 0 起：2s,4s,8s,16s
                    long round = attempt / Math.max(1, keys.size());
                    long waitMs = Math.min(16000, 2000L * (1L << Math.min(3, round)));
                    log.warn("生图撞限流(429)，退避 {}ms 后重试 (attempt {}): {}", waitMs, attempt, msg);
                    try { Thread.sleep(waitMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                } else {
                    log.warn("生图失败(密钥{}): {}", attempt, msg);
                }
            }
        }
        throw new RuntimeException("生图失败：" + (last != null ? last.getMessage() : "未知"));
    }
}

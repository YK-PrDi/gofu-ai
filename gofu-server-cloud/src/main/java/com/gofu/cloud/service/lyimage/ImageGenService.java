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

    /**
     * 产品实体描述词：颜色词、材质词、结构件名、被收纳物名。命中即丢句。
     * 08.04：由 {@link #stripProductWordsFromComposition} 的局部变量提升为静态字段——离线 prompt 台
     * （test 源集 PromptLab）要用同一张表标注"构图库文字里的别款商品实体词"，
     * 提升后两处共用一份，避免再抄一张会漂移的词表。行为不变。
     */
    static final String[] PRODUCT_WORDS = {
            // 颜色
            "枪灰", "奶白", "米白", "黑色", "白色", "银色", "银灰", "深灰", "灰色", "浅蓝", "浅黄", "浅粉",
            "黄色", "黄绿", "红色", "红白", "金色", "咖色", "杏色", "棕色", "透明", "镀铬", "原木", "浅木", "木纹", "木质",
            // 材质/工艺
            "碳钢", "不锈钢", "金属", "塑料", "硅胶", "陶瓷", "太空铝", "钢丝",
            // 结构件/款式
            "杯架", "杯位", "刀架", "刀槽", "锅盖架", "肥皂", "皂面", "收纳篮", "收纳盒", "置物架", "沥水架",
            "接水盘", "底托", "底座", "层板", "挂钩", "吸盘", "围杆", "立柱", "抹布杆", "晾布杆", "分隔支架",
            // 被收纳物
            "保温杯", "玻璃杯", "马克杯", "耳杯", "锅盖", "砧板", "菜板", "刀具", "剪刀", "锅铲", "汤勺", "餐叉",
            "筷子", "海绵", "香皂", "滤网", "钢丝球", "毛巾", "洗洁精", "洗护", "泵瓶", "喷瓶", "乳液", "沐浴球",
            "清洁刷", "打蛋器", "外套", "耳机", "手提包", "牙刷", "水龙头", "水槽",
    };

    /** 明确属于"构图/版式"的词：句中含这些且不含产品词时保留。08.04 同 {@link #PRODUCT_WORDS} 提升为静态字段。 */
    static final String[] LAYOUT_WORDS = {
            "视角", "俯拍", "俯视", "平视", "仰视", "景别", "画面", "构图", "版式", "分区", "信息栏", "信息条",
            "标题", "卖点", "标签", "功能窗", "功能小图", "小窗", "留白", "占画面", "比例为", "正方形", "1:1",
            "布光", "光影", "阴影", "高光", "无人物", "无水印", "无尺寸线", "不生成文字", "后期添加", "铺满", "背景",
    };

    /**
     * 从构图库文字里剥掉"别款商品"的产品描述，只留与产品无关的构图信息（08.03 #5）。
     *
     * <p>背景：`shelf-prompts.json` 每条构图 prompt 都是对着某张具体参考图写的，里面把那款商品
     * 写得极细（如杯架那条："枪灰色碳钢杯架…上层左侧两只白色保温杯、中右两只带棕色杯套的透明
     * 玻璃杯…"）。当白底图是筷子筒时，模型照这段文字画，就画成了杯架。两轮"保留文字+想办法压住"
     * 都失败（追加作废声明无效；挪到末尾隔离仍失败），故改为从源头剥离。
     *
     * <p>做法：按句切分（。；！以及换行），逐句判定——只要句子里出现"产品实体描述词"就整句丢掉；
     * 保留明确属于构图/版式/文案分区的句子。这是保守策略：宁可多丢几句（构图信息在参考图里本来
     * 就有），也不能漏放一句产品描述进去。
     *
     * <p>保留的信息类型：视角/俯仰角/景别、主体在画面中的位置与占比、左右上下信息栏分区比例、
     * 功能小图数量与排布、标题/卖点标签位置、布光、禁止项（无人物/无水印/不生成文字等）。
     */
    static String stripProductWordsFromComposition(String seg) {
        if (seg == null || seg.isBlank()) return "";
        String[] productWords = PRODUCT_WORDS;
        String[] layoutWords = LAYOUT_WORDS;
        StringBuilder kept = new StringBuilder();
        int dropped = 0, total = 0;
        for (String sentence : seg.split("(?<=[。；！\\n])")) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            total++;
            boolean hasProduct = false;
            for (String w : productWords) if (s.contains(w)) { hasProduct = true; break; }
            if (hasProduct) { dropped++; continue; }   // 含产品描述 → 整句丢
            boolean hasLayout = false;
            for (String w : layoutWords) if (s.contains(w)) { hasLayout = true; break; }
            if (hasLayout) kept.append(s);             // 纯构图句 → 保留
            else dropped++;                            // 既无产品词也无构图词 → 宁可丢(多为产品细节铺陈)
        }
        String out = kept.toString().trim();
        // 兜底：剥得一句不剩(该条几乎全是产品描述)时，给一句通用构图指令，别把 prompt 里的构图段留空。
        if (out.isEmpty()) {
            out = "1:1 正方形电商主图构图：主体产品居中偏右呈现，左侧或底部预留信息栏放标题与功能标签，"
                + "竖排或横排若干功能展示小图；柔和均匀布光，写实场景铺满画面，无人物、无水印、无尺寸线。";
        }
        return out;
    }

    /**
     * 08.04 生图质量攻关：把送去生图的最终 prompt 原样落进 cloud.log。
     *
     * <p>为什么必须落盘：本方法有 6 条路由（R1 花洒贴图 / R2 落地锅盖架 / R3 架类通用 / R4 花洒AI模板 /
     * R5 花洒gemini / R6 兜底），prompt 由「模板 + 若干 replace + 若干条件追加」拼成，
     * 光读代码推不出模型实际收到的那一串到底长什么样、哪段在前哪段在后。而 08.02 实测已坐实
     * <b>位置比措辞更决定出图结果</b>，所以"看得见确切 prompt"是攻关的前置条件。
     *
     * <p>组装段与 HTTP 调用在本方法里揉在一起（400+ 行、6 条路由交织），要做成离线可跑得先抽纯函数——
     * 那是另一件事的工作量。落日志能以极小改动拿到同样的能力：任何一次真实 run 之后，
     * 从 cloud.log 里就能取出确切 prompt 拿去比对。
     *
     * @param route 路由标识（R1~R6，与本方法注释里的编号对应）
     * @param refRoles 各张参考图的**角色**（如"白底图"/"袋子"/"主图作背景参考"），顺序即 refs 顺序——
     *                 光打文件名看不出哪张是干什么的，而"第几张图是什么"是 prompt 里反复指代的东西
     */
    private void dumpGenPrompt(String route, String skuName, String prompt, List<String> refRoles) {
        log.info("[SKU生图·{}] sku={} refs={} prompt={}字\n--- prompt 全文 ↓ ---\n{}\n--- prompt 全文 ↑ ---",
                route, skuName, refRoles, prompt == null ? 0 : prompt.length(), prompt);
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
        // 08.04 硬闸：没有白底图就**不生图**（用户定的规则）。白底图是本款产品的唯一真实锚点，
        //   没有它画出来的只是"一个看起来像这品类的东西"，不是这款商品——那属于虚假宣传，
        //   出了图反而更糟（用户会以为成功了）。用户按「品类-快麦编码」规范命名文件夹的目的
        //   就是让系统凭编码去快麦取白底图；取不到该让用户去快麦补，不该降级硬画。
        //   这也是废除 R2（落地锅盖架无白底兜底）的同一条理由。
        // 定位：这是**最后一道**防线。正常入口各自已有前置拦截（导入走快麦兜底+提示、批量前端硬拦、
        //   产品替换 400、单品前端闸门），且 runStep2/runSkuCross 传进来的白底图必非空。
        //   真正会撞到这里的是 /api/ly-gen/sku-images —— 它把 whiteImgPath 默认成空串且无校验，
        //   是绕过所有前置拦截的后门。抛错后 LyGenController 会按 SKU 记进 item.error，不会整批崩。
        if (!hasWhiteBg) {
            throw new IllegalStateException(
                    "SKU「" + skuName + "」没有可用的产品白底图，已停止生图。"
                  + "白底图是本款产品的唯一结构/颜色依据，缺它只能画出别款、属虚假宣传。"
                  + "请按商品编码在快麦补一张白底图后重试"
                  + "（产品替换模式：请检查所选文件夹下的「白底图」子目录）。"
                  + " [诊断] whiteImgPath=" + whiteImgPath + ", 营销参考图=" + (hasRef ? "有" : "无"));
        }
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
            List<String> r1Roles = new java.util.ArrayList<>();
            if (hasWhiteBg) r1Roles.add("白底图");
            if (hasBag) r1Roles.add("袋子");
            if (hasRef) r1Roles.add("营销主图(背景参考)");
            dumpGenPrompt("R1 花洒贴图", skuName, prompt, r1Roles);
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
            // 08.06 ②档开关：SKU 图是否摆被收纳物。false = 空架（当前）。
            //   详细理由见下方 storageItemsHint 处的长注释；恢复摆物只改这一处。
            final boolean SKU_WITH_STORAGE_ITEMS = false;
            String shelfSeg = pick.prompt();
            // 修(#5 08.03 三轮·用户指示"提示词里不用描述参考图的产品，只要参考图的构图就行了")：
            //   前两轮都在"保留别款商品描述 + 想办法压住它"，实测都压不住(一轮追加作废声明无效、
            //   二轮挪到末尾隔离仍被用户实测到 SKU 又变产品)。既然参考*图片*本身已经承载了构图信息，
            //   那段描述别款商品的*文字*就是纯负担 —— 直接从源头剥掉，只留与产品无关的构图信息。
            //   有白底图时才剥(无白底图得靠文字画,不能剥)。
            if (hasWhiteBg) {
                String stripped = stripProductWordsFromComposition(shelfSeg);
                log.info("架类构图文字剥离产品描述: {} 字 → {} 字", shelfSeg.length(), stripped.length());
                shelfSeg = stripped;
            }
            String shelfTpl = PromptLoader.load("prompt/image-shelf-main.txt");
            String shelfPrompt = shelfTpl
                .replace("{{shelfPrompt}}", shelfSeg)
                .replace("{{colorName}}", colorOnly);
            // 配套参考图作构图底（refs 第一张权重最高）+ 商品白底图锁主体
            File shelfBase = templateService.shelfRefFile(leaf, pick.group(), pick.ref());
            List<File> shelfRefs = new java.util.ArrayList<>();
            // ── 08.04 已废除「路线2 / R2」（落地锅盖架无白底图兜底）──────────────────────────
            //  原实现：无白底图时，Java 把「米奇款预制架体照 + 锅盖/砧板收纳物」合成一张构图底当
            //  最高权重参考图，配 image-shelf-floorlid.txt 让 AI 照它画（理由是"AI 从零画架体必崩"）。
            //
            //  为什么废：它的触发门槛是 `!hasWhiteBg`，而按产品定义**这个状态根本不该走到生图**——
            //  没有白底图就没有本款的真实锚点，画出来的只是"一个看起来像锅盖架的东西"，不是这款商品。
            //  用户 08.04 定的规则：快麦查不到白底图就让用户去快麦补，补不上就不生图。用户按
            //  「品类-快麦编码」规范命名文件夹的目的正是让系统能凭编码去快麦取白底图，用户自己
            //  从不提供白底图子目录。所以"无白底图"是个该被拦下来的错误态，不是一种要支持的降级模式。
            //  给一条本该被拦掉的路径优化 prompt 质量 = 给死路铺砖。
            //
            //  连带删除：`isRealLidRack`/`floorHint` 判据、`compositeShelfFloorLid` 调用、
            //  `image-shelf-floorlid.txt` 的加载、以及那段"无白底图时改写序号说明"的补丁
            //  （那个补丁本身就是在给一条不该存在的路径打图文错位的补丁）。
            //  `ShowerCompositor.compositeShelfFloorLid`、`image-shelf-floorlid.txt`、
            //  `assets/base/shelf-落地锅盖架-米奇款.png`、`assets/collectibles/*` 均已无调用方，
            //  暂留不删（属未使用资产，删除另议，不在本次改动范围）。
            //  现在无白底图会在本方法入口直接抛错，压根到不了这里。

            // 参考图组装：白底图=唯一主体锚（入口已保证非空），一律丢弃预制产品照
            // （避免实物被画成预制那款，不再对锅盖架开例外）。
            shelfRefs.add(whiteBgRef);   // 白底图 = 唯一主体锚（入口已保证非空）
            // 注：`shelfBase`（构图库里为该条目配的预制产品照）**一律不作参考图**。
            //   0c 起就是这个原则：白底图在时预制照会把实物带成预制那款（小熊锅盖架被 img2img 成米奇款）。
            //   它现在只用于上面那条日志（记录库里配了哪张），不进 refs。
            // 主图追加为背景参考：白底图已锁主体，主图放最末只用于背景色调/氛围参考，权重最低。
            // 修(#1 08.02)：原来只给图不给文字描述，模型容易忽略最后一张图的背景而自行另编场景。
            //   补上主图背景的**文字**描述(bgStyleOverride = analyzeBackgroundStyleOnce(选定主图) 的产出，
            //   整批只分析一次)，图+文双管：背景以选定主图为准，构图库只管版式。
            String mainImgBgHint = "";
            if (hasRef && hasWhiteBg) {
                shelfRefs.add(ref);
                mainImgBgHint = "【背景基调·严格参照最后一张主图】最后一张参考图是营销主图，**背景色调、光影层次与氛围必须与其一致**。白底图只锁产品主体，背景跟主图走，不要另换颜色或改成纯色影棚背景。";
                if (bgStyleOverride != null && !bgStyleOverride.isBlank()) {
                    mainImgBgHint += "\n该主图的背景基调为：" + bgStyleOverride
                                   + "\n若上面【构图·按本品种方案】的文字描述了不同的场景/背景/台面/墙面颜色，**一律以本段主图背景基调为准**。";
                }
            }
            // 构图参考图说明（{{compositionRefHint}}）：08.04 起恒为空，整段逻辑已删。
            //   原来只在"refs 里有真实预制参考图"时才提示 AI"参考图里的商品是别款、换成本款"。
            //   R2 废除 + 预制图一律丢弃后，refs 里只剩白底图(+可选主图作背景参考)，
            //   **不存在**"除白底图以外的构图参考图"，故这段话永远不该出现。
            //   ⚠ 这条约束反过来更要紧：只有白底图时若冒出这段话，AI 会把白底图产品当"别款"替换掉
            //   （正是它当年要防的错）。日后若重新引入预制参考图，必须连带恢复这段说明。
            // {{occlusionRefHint}}（08.06「路A」二轮）：把遮挡关系从"用文字讲"改成"指着实拍图看"。
            //   前两轮（卯批改写法、午批调位置+精简）都在文字里找措辞，实测均未治住穿模；
            //   而本项目唯一真正管住过模型的机制是**给图当锚**（白底图锁结构，实测有效）。
            //   最后那张主图是真实拍摄的，锅盖/砧板与杆件的前后关系天然物理正确，不需模型推理。
            // ⚠ 位置：本段占位符放在模板【判定标准】之后（约 30% 处）而**不是**跟 mainImgBgHint
            //   一起挂在 85%。一轮曾把它并进 mainImgBgHint，实测穿模未改善——按 08.02 已坐实的
            //   「位置比措辞更决定结果」，埋在 85% 的指令没有执行力，等于没公平验过。
            //   现在它与【三维层级】同处高权重区，构成"文字讲层序 + 实拍图示范"的图文双管。
            // ⚠ 护栏：该图在产品替换流程里是**被替换掉的旧款**（别款）。08.04 废弃预制参考图的原因
            //   正是"模型照着参考图画、把白底图那款替换掉"（虚假宣传，比穿模严重）。故必须把
            //   "只借遮挡关系、产品一律以白底图为准"写进同一段。本次不新增参考图，只是多榨一层信号。
            // 08.06 ②档连带：这段讲的是"照主图画**被收纳物**与杆件的前后关系"，
            //   空架模式下画面里没有收纳物 → 该指令指向一个不存在的对象，必须一起关掉，
            //   否则等于告诉模型"去照着摆放关系画"，反而可能诱导它把收纳物加回来。
            String occlusionRefHint = (hasRef && hasWhiteBg && SKU_WITH_STORAGE_ITEMS)
                ? "【遮挡关系·照最后一张主图的前后关系画】最后一张参考图是实拍营销主图，其中被收纳物（锅盖/砧板等）与架体杆件的**前后遮挡关系是真实拍摄的、物理正确的**：照它画——谁在前、谁把谁挡住、边界在哪里断开、接触点落在哪。**只借这个前后关系**；若该图里的产品与白底图不是同一款，产品的结构/颜色/款式/层数/部件数量一律以白底图为准，**绝不照它画产品本身**。"
                : "";
            String compositionRefHint = "";
            // ── 08.06 ②档：SKU 图默认**空架**（不摆被收纳物）───────────────────────────────
            //   背景：架类穿模连治四轮无效（抽象禁令→441字分层作画→写死"下部在前上部在后"→
            //   改成"跟卡槽角度走"），最后一轮盖虽然真倾斜了但**倾角乱七八糟**，比原先竖直更糟。
            //   规律：这四轮里凡「删掉错误/矛盾信息」的改动都有效（删别款吸盘描述→吸盘消失；
            //   删 COMP_TASK/负向兜底→无倒退），凡「增加几何/渲染约束」的一次都没成功
            //   （AO 接触阴影指令写了两轮，模型从不执行）。结论：不是措辞问题，
            //   是 gpt-image-2 在「细杆框架 × 板状物斜靠 × 多件叠放」这种几何上做不到遮挡推理。
            //   → 换路子：穿模全部发生在「盖/砧板与前方波浪杠交叠」处，那么 SKU 图**不摆收纳物**
            //   就从源头消掉这个交叠。SKU 图是规格展示图，08.06 那张「加厚材质更耐用」的 SKU 图
            //   本来就没摆锅盖、用户未提异议；而主图仍必须摆（用户 08.05 明确"锅盖架上必须有锅盖，
            //   那是功能演示"），主图走 buildSeriesPrompt 不经本模板，故此开关只影响 SKU。
            //   日后若要恢复"SKU 也摆收纳物"，把常量改 true 即可，两段约束原文都保留在下面。
            String storageItemsHint = SKU_WITH_STORAGE_ITEMS
                ? """
                  【物理咬合·被收纳物是怎么架在架子上的·最易出错处】
                  先想清楚收纳物**靠什么受力**，再决定谁遮住谁。
                  ① 下沿落进接水盘内或卡槽底部，与盘底/槽底**贴实**（能看见下沿被槽壁夹住、或压在盘面上留下接触阴影），不许悬在上方；
                  ② 盖面靠在框条上被顶住，接触处要有压迫感，不是虚虚地挨着；
                  ③ 框条与盖面每一处重叠都必须**二者之一被完全遮挡**，不得互相嵌入、边缘融成一条线或直接穿过去。
                  盖面上的圆形握把/提手长在盖面上、跟盖面保持同一朝向，不得单独浮在空中。
                  多件收纳物之间也有前后：靠后的那件被靠前的那件部分遮住，不得边缘融合成一体。
                  【收纳物·必须与本款功能相符】
                  画面里摆放的被收纳物必须是**与白底图这款产品的真实功能相符**的物品（筷筒放筷子/勺铲，锅盖架放锅盖，杯架放杯子，毛巾架放毛巾），不要照搬下方版式参考文字里提到的那些物品。只能架在**白底图这款产品实际存在的**承载结构上（卡位/层板/挂位/接水盘），且不得遮挡或改变产品本身的结构呈现。""".trim()
                : """
                  【空架展示·不摆任何被收纳物】
                  本图是规格展示图，**架体保持空置**：卡位/层板/挂位/接水盘里**一律不放**锅盖、砧板、杯子、毛巾等任何物品，也不放装饰道具。画面只有产品本体与场景。
                  下方版式方案文字里若提到摆了什么物品（锅盖/砧板/杯子等），**一律忽略**，只借它的版式（视角、主体位置占比、信息栏分区、文字块位置）。""".trim();
            // 收纳物相关的自检条目同样只在摆物时才需要（空架时无物可穿模）。
            String occlusionSelfCheck = SKU_WITH_STORAGE_ITEMS
                ? "\n· 凡是架体杆件与收纳物在画面上重叠的地方，**必须二者之一被另一个完全遮挡**，绝不允许出现\"杆件从收纳物中间穿过去\"\"收纳物的边缘嵌进杆件里\"\"两者边缘融成一条线\"\"杆件在收纳物表面若隐若现\"这四种情形。"
                + "\n· 每件收纳物与承载面（卡槽/层板/盘底）之间要看得见**接触**：有落实的接触点、贴合的边界、以及朝光源反方向的落地阴影。看不出它靠什么支撑住 = 画错了。"
                + "\n· 玻璃锅盖等透明件：透过它能看到后面的东西（这是透明），但它的**金属边圈与提手是不透明实体**，与杆件重叠处必须有明确的前后遮挡，不能互相透视穿插。"
                : "";
            // 08.06 A档：「挂钩/五金类·避免过度特写」那段原来**无条件**注入模板正文，
            //   但段首自己写着"若本款为挂钩类或结构简单的五金件"——对锅盖架/置物架/沥水架等
            //   非挂钩品类是每张白扔 122 字（说的是别的品类该怎么办）。改为按 leaf 条件注入：
            //   命中挂钩/五金类才给。判据与 PromptTemplateService:539 的 shelfLike 同源思路，
            //   但这里要的是"挂钩类"这个更窄的集合，故只认 挂钩/挂杆/五金/挂片，不认 架/挂件。
            boolean hookLike = leaf.contains("挂钩") || leaf.contains("挂杆")
                            || leaf.contains("五金") || leaf.contains("挂片");
            String hardwareCloseupHint = hookLike
                ? "\n【挂钩/五金类·避免过度特写细节·降售后】\n本款是挂钩类/结构简单的五金件：功能小图与细节图**只做整体或半身景别**，不要对钩头、贴纸、印字、边角、注塑毛边做微距特写放大（这些是 AI 最易出错、最易招售后的地方），用整体使用场景带过即可。\n"
                : "";
            // 08.06 A档：场景描述二选一，不再两段并列。
            //   有主图参考时（hasRef&&hasWhiteBg，最常见路径）背景基调已由 mainImgBgHint 精确指定
            //   （"背景色调/光影层次/氛围必须与主图一致"），模板原本那句"符合该品类真实使用环境
            //   （厨房台面/墙面/门板/浴室墙角等）"就成了更弱的泛述，两段并列还互相拉扯
            //   （一个说跟主图走、一个说按品类环境自选）。故有主图时只留 mainImgBgHint，
            //   无主图时才用模板那句品类泛述兜底。
            String sceneHint = mainImgBgHint.isBlank()
                ? "写实电商场景，符合该品类真实使用环境（厨房台面/墙面/门板/浴室墙角等）；"
                : mainImgBgHint + "\n";
            // 修(#1 08.02 二轮)：构图库 prompt 文本把「另一款商品」写得极细（如杯架方案写死
            //   "枪灰色碳钢杯架/上下两层/白色保温杯+玻璃杯+马克杯"）。预制*图片*在有白底图时已被
            //   dropPresetRef 丢掉，但这段*文字*仍整段留着 → 筷子筒被画成杯架(用户08.02反馈)。
            //   一轮只在文字后面追加一段"作废声明"，实测无效(shots/ab-A-current.jpg 仍是杯架+杯子)：
            //   根因是**位置**而非措辞 —— 别款商品的完整描述在第43字(最高权威位)，作废声明是第270字
            //   的事后追认，模型按先入为主执行。
            //   二轮改法：模板结构重排(白底图主体锁提到最前、别款描述隔离到末尾并包在"仅供借版式"
            //   分隔线内)，A/B 实测通过(shots/ab-B-subjectfirst.jpg 出真实筷子筒+酒红框+小熊印花，
            //   版式仍照库)。此处只保留"无白底图时"的兜底提示；有白底图的强约束已固化进模板正文。
            // {{compositionTextOverride}}：08.04 起恒为空。原来是"无白底图时"的兜底提示
            //   （"只能依据下方版式方案的文字描述作画"）——那是让模型**凭别款商品的文字描述臆造本款**，
            //   本方法入口的硬闸已经让这个状态到不了这里。日后若重新允许无白底图生图，
            //   要连带重新设计这段，并解决它自带的图文矛盾（模板要求忠于白底图、这段却说没有白底图）。
            String compositionTextOverride = "";
            shelfPrompt = shelfPrompt.replace("{{occlusionRefHint}}", occlusionRefHint)
                                     .replace("{{sceneHint}}", sceneHint)
                                     .replace("{{storageItemsHint}}", storageItemsHint)
                                     .replace("{{occlusionSelfCheck}}", occlusionSelfCheck)
                                     .replace("{{compositionRefHint}}", compositionRefHint)
                                     .replace("{{hardwareCloseupHint}}", hardwareCloseupHint)
                                     .replace("{{compositionTextOverride}}", compositionTextOverride);
            log.info("架类生图: 叶子={}, 组={}, 库里配了预制图={}({}, 08.04起一律不用作参考图), 白底图={}",
                    leaf, pick.group(), shelfBase != null, pick.ref(), hasWhiteBg);
            // 08.04：R2 已废，架类只剩这一条路（R3 架类通用，模板 image-shelf-main.txt——
            //   08.02 做过主体锁前置 + 别款描述末尾隔离的那条）。
            List<String> shelfRoles = new java.util.ArrayList<>();
            shelfRoles.add("白底图(主体锚)");   // 入口已保证非空
            if (hasRef && hasWhiteBg) shelfRoles.add("主图(背景基调参考·权重最低)");
            dumpGenPrompt("R3 架类通用", skuName, shelfPrompt, shelfRoles);
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
                    // 背景基调跟主图:基准图自带固定色背景(如科技蓝)会被img2img原样保留,与本商品主图基调不符。
                    // 有主图分析出的 bgStyle 时,强指令用该基调、明确不要沿用基准图背景色,让SKU与主图同调。
                    if (bgStyle != null && !bgStyle.isBlank()) {
                        prompt += "\n\n【背景基调·必须与商品主图一致】背景色调、光影、氛围严格采用：" + bgStyle
                                + "。**不要沿用基准图自带的背景颜色**(如科技蓝/固定影棚色),只借基准图的构图版式,背景基调换成这里指定的。";
                    }
                    refs.add(baseImg);
                    if (hasWhiteBg) refs.add(whiteBgRef);
                    if (hasRef) refs.add(ref);   // 主图进refs作背景基调参考(原来只在useMainBg时进,致SKU基调脱离主图)
                    // 精简参考图：只喂滤芯白底图（装柄用），底座/软管不喂 AI（它们只用于 Java 贴配件卡），避免多图拖慢/超时
                    for (int k = 0; k < accFiles.size(); k++) if ("滤芯".equals(accLabels.get(k))) { refs.add(accFiles.get(k)); break; }
                    if (Boolean.TRUE.equals(tpl.get("useExplodeRef"))) {
                        File er = templateService.explodeRefFile();
                        if (er != null && er.isFile()) refs.add(er);  // 拆解结构参考，锁内部结构
                    }
                    // (主图 ref 已在上方无条件加入作背景基调参考,不再按 useMainBg 重复加)
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
        // ⚠ 已知图文矛盾(08.02 核查发现,未改,待产品侧定夺)：本路径(非花洒非架类的兜底路径)用的
        //   image-sku-white-bg.txt 模板开头写死 "Product photography on pure white background"(纯白底
        //   两分区目录图),但上面 !isShower 分支又把主图分析出的场景背景描述当 [BACKGROUND] 追加进去
        //   ——一个要纯白、一个要场景,互相打架。因两者语义相反、无法同时满足,且本路径只在
        //   既非花洒又非架类的品类才会走到(现有业务几乎不触发),此处不擅自改行为,先记录。
        //   若要改：想要纯白目录图→删掉 [BACKGROUND] 追加；想要跟主图同调的场景图→换模板并把
        //   主图加进 genRefs 末尾作背景参考(参考架类 mainImgBgHint 的图+文双管写法)。

        // 08.04：共享尾部循环服务三条路由，按到达时的状态反推是哪条(R1/R2/R3 各自 return 在上面，到不了这里)
        String tailRoute = isShower
                ? (aiTemplate ? "R4 花洒AI模板" : "R5 花洒贴图(gemini)")
                : "R6 兜底(非花洒非架类)";
        List<String> tailRoles = new java.util.ArrayList<>();
        if (isShower) {
            for (int k = 0; k < genRefs.size(); k++) tailRoles.add("refs[" + k + "]");
        } else if (hasWhiteBg) {
            tailRoles.add("白底图");
        } else if (hasRef) {
            tailRoles.add("营销参考图(无白底回退)");
        }
        if ("R6 兜底(非花洒非架类)".equals(tailRoute) && gemini && !bgDesc.isBlank()) {
            // 上方 763-769 那段自承的图文矛盾就在这个组合下成立：模板要求纯白底、又追加了场景 [BACKGROUND]。
            // 落日志时直接标出来，免得看日志的人再推一遍。默认渠道是 gpt-image(非 gemini)，故一般不触发。
            log.warn("[SKU生图·R6] ⚠ 命中已知图文矛盾：image-sku-white-bg.txt 要求纯白底，"
                    + "但本次又追加了场景 [BACKGROUND] 描述（见本方法上方 08.02 注释）");
        }
        dumpGenPrompt(tailRoute, skuName, prompt, tailRoles);

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

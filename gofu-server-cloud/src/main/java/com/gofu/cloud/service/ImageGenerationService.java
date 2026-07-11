package com.gofu.cloud.service;

import com.gofu.cloud.config.AppProperties;
import com.gofu.cloud.model.GenerationTask;
import com.gofu.cloud.service.agent.ImageGeneratorAgent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);
    private static final String DEFAULT_AGENT_ID = "gpt-image";
    private static final String GEMINI_ANALYSIS_MODEL = "gemini-3-pro-preview";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final String NO_INTERSECTION_PROMPT = """
            【最高优先级·禁止穿模】
            画面中任何产品、人体、手指、衣袖、头发、道具、墙面、桌面、玻璃、置物架、支架、线缆、背景结构之间都不得互相穿透、嵌入、融合或共享边界。
            产品必须有真实接触面、支撑点、遮挡关系和接触阴影；手指只能自然握持或触碰产品表面，不能穿过产品孔洞或外壳。
            产品不能半截插入桌面、墙面、背景、支架或其他物体；所有连接、接触、阴影、透视和前后层级必须物理合理。
            """.trim();

    private final AppProperties appProperties;
    private final Map<String, ImageGeneratorAgent> agentMap;
    private final Random random = new Random();
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PromptTemplateLoader promptTemplateLoader;
    private final OkHttpClient analysisClient;

    public ImageGenerationService(AppProperties appProperties, List<ImageGeneratorAgent> agents,
                                  PromptTemplateLoader promptTemplateLoader) {
        this.appProperties = appProperties;
        this.agentMap = new LinkedHashMap<>();
        agents.forEach(a -> agentMap.put(a.getId(), a));
        this.executor = Executors.newFixedThreadPool(appProperties.getApi().getMaxConcurrent());
        this.promptTemplateLoader = promptTemplateLoader;
        this.analysisClient = buildAnalysisClient();
        log.info("已注册智能体: {}，并发数: {}", agentMap.keySet(), appProperties.getApi().getMaxConcurrent());
    }

    public ExecutorService getExecutor() { return executor; }

    /** 返回所有已注册智能体的描述列表，供前端展示 */
    public List<Map<String, String>> listAgents() {
        List<Map<String, String>> result = new ArrayList<>();
        agentMap.forEach((id, agent) -> result.add(Map.of(
                "id", id,
                "name", agent.getDisplayName()
        )));
        return result;
    }

    /**
     * 开品模式第一步：复用 Gemini 的文字/视觉能力，按用户输入动态拆成结构化卡片。
     * Gemini 负责从 prompt 中动态识别维度名称，并只返回 JSON 数组。
     */
    public List<Map<String, String>> analyzeProductText(String prompt, File imageFile, String agentId) {
        if (prompt == null || prompt.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, String>> fields = parseAnalysisFields(requestGeminiAnalysis(prompt, imageFile, false));
            if (looksLikeRefusal(fields)) {
                log.warn("Gemini 开品分析返回拒绝式结果，改用强约束提示词重试");
                fields = parseAnalysisFields(requestGeminiAnalysis(prompt, imageFile, true));
            }
            if (looksLikeRefusal(fields)) {
                log.warn("Gemini 强约束重试仍返回拒绝式结果，使用本地维度兜底生成可编辑卡片");
                fields = buildFallbackAnalysisFields(prompt);
            }
            return fields;
        } catch (Exception e) {
            log.error("开品结构化分析失败: {}", e.getMessage(), e);
            throw new RuntimeException("开品结构化分析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 自定义模式发送前的图片分析：把白底产品图 + 用户描述扩写为多段可编辑生图提示词。
     * 返回文本用 --- 分隔，前端继续复用原来的卡片编辑流程。
     * 降级链路：Gemini → 千问 → 本地兜底（本地兜底强制 withText=false）
     */
    public String analyzeCustomImagePrompts(String prompt, List<File> imageFiles, int count, boolean withText) {
        int safeCount = Math.max(1, Math.min(10, count));
        String userPrompt = prompt == null ? "" : prompt.trim();
        List<File> files = imageFiles == null ? List.of() : imageFiles;

        // 第一级：Gemini
        try {
            return requestGeminiCustomPromptAnalysis(userPrompt, files, safeCount, withText);
        } catch (Exception e) {
            log.warn("自定义分析 Gemini 失败，尝试千问: {}", e.getMessage());
        }

        // 第二级：千问
        if (appProperties.getQwen().isEnabled()) {
            try {
                return requestQwenCustomPromptAnalysis(userPrompt, files, safeCount, withText);
            } catch (Exception e) {
                log.warn("自定义分析千问失败，使用本地兜底: {}", e.getMessage());
            }
        }

        // 第三级：本地兜底，强制 withText=false
        log.warn("自定义分析使用本地兜底，withText 强制为 false");
        return buildLocalFallbackCustomPrompts(userPrompt, safeCount);
    }

    /** 千问 VL（OpenAI 兼容接口）调用分析 */
    private String requestQwenCustomPromptAnalysis(String prompt, List<File> imageFiles, int count, boolean withText) throws IOException {
        String apiKey = appProperties.getQwen().getApiKey();
        String model = appProperties.getQwen().getModel();
        String baseUrl = appProperties.getQwen().getBaseUrl();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);

        ArrayNode messages = root.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        ArrayNode content = userMsg.putArray("content");
        content.addObject().put("type", "text")
                .put("text", buildCustomPromptAnalysisPrompt(prompt, count, imageFiles != null && !imageFiles.isEmpty(), withText));

        if (imageFiles != null) {
            for (File f : imageFiles) {
                if (f == null || !f.exists()) continue;
                byte[] bytes = Files.readAllBytes(f.toPath());
                String b64 = Base64.getEncoder().encodeToString(bytes);
                String dataUrl = "data:" + getMimeType(f.getName()) + ";base64," + b64;
                ObjectNode imgPart = content.addObject();
                imgPart.put("type", "image_url");
                imgPart.putObject("image_url").put("url", dataUrl);
            }
        }


        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(root.toString(), JSON_TYPE))
                .build();

        try (Response response = analysisClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new RuntimeException("千问分析失败(" + response.code() + "): " + body);
            }
            JsonNode respNode = objectMapper.readTree(body);
            String text = respNode.path("choices").path(0).path("message").path("content").asText("");
            if (text.isBlank()) throw new RuntimeException("千问返回空内容");
            return text;
        }
    }

    /** 本地兜底：生成基础提示词卡片，强制不含文案字段 */
    private String buildLocalFallbackCustomPrompts(String prompt, int count) {
        String subject = prompt.isBlank() ? "该产品" : prompt.substring(0, Math.min(20, prompt.length()));
        StringBuilder sb = new StringBuilder();
        String[] scenes = {"纯白背景产品正面展示", "浅灰渐变背景斜侧视角", "木纹桌面生活场景", "简洁场景局部特写"};
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append("\n---\n");
            String scene = scenes[i % scenes.length];
            sb.append("【本图卖点】").append(subject).append("核心功能与外观展示\n");
            sb.append("【本图风格】简洁电商风，突出产品质感\n");
            sb.append("【产品一致性】保持产品外观、颜色与参考图完全一致\n");
            sb.append("【场景构图】").append(scene).append("，产品居中或黄金比例摆放，背景简洁不干扰主体\n");
            sb.append("【禁止项】画面不得出现任何文字、标题、标签、水印或 logo；不得出现穿模、拉伸或变形\n");
        }
        return sb.toString();
    }

    /**
     * 开品模式融合分析：基于产品 A/B、卖点、方案侧重、视觉风格，综合生成结构化分析卡片。
     * 这是两步流程的第一步，返回可编辑卡片供用户确认后二次生图。
     *
     * @param productA   产品 A 的文字描述
     * @param productB   产品 B 的文字描述
     * @param selling    核心卖点
     * @param focus      方案侧重（cost/premium/disruptive/custom）
     * @param focusText  自定义方案侧重文本（focus=custom 时使用）
     * @param style      视觉风格（dopamine/wood/cartoon/ins/minimal/cyberpunk/custom）
     * @param styleText  自定义视觉风格文本（style=custom 时使用）
     * @param imageA     产品 A 参考图（可选）
     * @param imageB     产品 B 参考图（可选）
     * @return 结构化分析卡片 [{key, value}, ...]
     */
    public List<Map<String, String>> analyzeKaiPin(
            String productA, String productB, String selling,
            String focus, String focusText, String style, String styleText,
            File imageA, File imageB) {

        try {
            // 构建综合分析 prompt
            String analysisPrompt = buildKaiPinAnalysisPrompt(productA, productB, selling, focus, focusText, style, styleText);

            // 调用 Gemini 分析（支持双图）
            List<Map<String, String>> fields = parseAnalysisFields(
                    requestGeminiKaiPinAnalysis(analysisPrompt, imageA, imageB, false));

            // 检查拒绝式结果，重试
            if (looksLikeRefusal(fields)) {
                log.warn("开品融合分析返回拒绝式结果，改用强约束提示词重试");
                fields = parseAnalysisFields(
                        requestGeminiKaiPinAnalysis(analysisPrompt, imageA, imageB, true));
            }

            // 最终兜底
            if (looksLikeRefusal(fields) || fields.isEmpty()) {
                log.warn("开品融合分析重试仍失败，使用本地维度兜底");
                fields = buildFallbackKaiPinFields(productA, productB, selling, focus, style);
            }

            return ensureKaiPinSellingPoints(fields, productA, selling);
        } catch (Exception e) {
            log.error("开品融合分析失败: {}", e.getMessage(), e);
            // 异常时返回兜底卡片
            return ensureKaiPinSellingPoints(buildFallbackKaiPinFields(productA, productB, selling, focus, style), productA, selling);
        }
    }

    /**
     * 构建开品融合分析的 prompt
     */
    private String buildKaiPinAnalysisPrompt(
            String productA, String productB, String selling,
            String focus, String focusText, String style, String styleText) {

        // 方案侧重文本
        String focusPrompt = switch (focus) {
            case "cost" -> "【方案侧重】成本与量产：CMF 与结构在不牺牲核心功能的前提下尽量降低成本与开模难度；优先采用注塑/钣金等成熟工艺；颜色与材质走简洁实用风。";
            case "premium" -> "【方案侧重】颜值与溢价：放大造型语言的高端感；CMF 选择高级材质（拉丝金属/真皮/木纹/陶瓷釉面）；表面工艺精致；体现\"摆在客厅当艺术品也不违和\"的格调。";
            case "disruptive" -> "【方案侧重】颠覆性创新：允许突破常规结构去拥抱产品 B 的造型；可加入隐藏机构、模块化、可拆解、智能化等新颖设计语言；视觉冲击优先。";
            case "custom" -> focusText != null && !focusText.isBlank()
                    ? "【方案侧重·自定义】" + focusText
                    : "【方案侧重】（用户未指定，请根据产品特性自行判断合适的设计取向）";
            default -> "【方案侧重】成本与量产：CMF 与结构在不牺牲核心功能的前提下尽量降低成本与开模难度；优先采用注塑/钣金等成熟工艺；颜色与材质走简洁实用风。";
        };

        // 视觉风格文本
        String stylePrompt = switch (style) {
            case "dopamine" -> "【视觉风格】多巴胺：高饱和撞色（柠檬黄/珊瑚红/薄荷绿）；圆润边角；活泼趣味造型细节；色彩大胆跳跃，充满视觉能量感；场景道具也选用高饱和色彩。";
            case "wood" -> "【视觉风格】木元素：产品本体的外壳/主体部分改为原木材质（橡木/胡桃木/竹材纹理）；金属或塑料区域用木纹饰面替代；保留产品功能细节；暖米色与原木棕色调，体现温润自然感。";
            case "cartoon" -> "【视觉风格】卡通：产品造型圆润可爱化；色彩亮丽柔和；增加拟人化趣味细节；场景配以简洁卡通风格背景元素；整体呈现亲切活泼的儿童/年轻用户氛围。";
            case "ins" -> "【视觉风格】ins 风：清新奶油色系（象牙白/浅粉/哑光米灰）；柔和弥散光；干净留白构图；产品与鲜花/绿植/咖啡等生活道具搭配；小红书/Instagram 高颜值打卡风格。";
            case "minimal" -> "【视觉风格】极简：背景纯净（白/浅灰/米）；产品居中大量留白；去除一切多余装饰；线条简洁；色彩克制（单色或双色）；体现\"少即是多\"的设计哲学。";
            case "cyberpunk" -> "【视觉风格】赛博朋克：深色背景配霓虹灯光（紫/青/粉）；发光线条与光效；金属质感与电路纹路；高对比度；呈现科技感十足的未来都市氛围。";
            case "custom" -> styleText != null && !styleText.isBlank()
                    ? "【视觉风格·自定义】" + styleText
                    : "";
            default -> ""; // 不指定风格
        };

        String template = promptTemplateLoader.load("prompt/kai-pin-analysis-user.txt", """
                你是"产品外观设计分析师 + 电商开品视觉策略师"。
                当前开品模式只做单个产品的外观设计结构化分析。请严格按照下面这条内置 Excel 提示词执行：
                提示词：请对这个产品从几何结构、体量感（轻盈/厚重/悬浮感）、仿生学元素、模块化程度（一体成型 / 可拆卸 / 堆叠式设计）、主色调、辅色、风格标签（科技极简/复古怀旧/赛博朋克/可爱治愈）的角度进行产品外观设计分析。

                输入材料：
                【产品描述】
                %s

                【补充要求 / 卖点 / 目标人群】
                %s

                【可选参考】
                %s
                %s

                输出要求：
                1. 必须只输出 8 个字段，顺序和 key 必须完全固定为：核心卖点清单、几何结构、体量感、仿生学元素、模块化程度、主色调、辅色、风格标签。
                1.1 核心卖点清单：必须用 3-5 条编号短句罗列，每条包含用户痛点/购买理由、产品外观或功能证据、后续画面表现方式；如果用户未写卖点，也要从图片结构、使用方式和目标场景主动提炼。
                2. 几何结构：从图片中观察产品主轮廓、基础几何、转折面、曲直线关系、对称性和视觉重心，输出 80-160 字，可直接用于后续设计。
                3. 体量感：只能从"轻盈、厚重、悬浮感"中选择 1-2 个最符合图片证据的词，value 只输出选中的词，用"、"连接。
                4. 仿生学元素：分析是否有动物、植物、骨骼、翅膀、水滴、贝壳、昆虫、流线等自然形态借鉴；如果没有明显仿生，也要写"无明显仿生，偏几何/工程化"，80-160 字。
                5. 模块化程度：只能从"一体成型、可拆卸、堆叠式设计"中选择 1-2 个最符合图片证据的词，value 只输出选中的词，用"、"连接。
                6. 主色调：输出图片中最主要的颜色名称，可带材质感，例如"哑光白""银灰金属""深黑"等，不要写长句。
                7. 辅色：输出辅助色或点缀色；如果图片中没有明显辅色，输出"无明显辅色"。
                8. 风格标签：只能从"科技极简、复古怀旧、赛博朋克、可爱治愈"中选择 1-2 个最符合图片证据的词，value 只输出选中的词，用"、"连接。
                9. 如果用户补充了卖点或目标人群，几何结构和仿生学元素两个字段必须说明这些外观特征如何支撑卖点；但固定选项字段仍只能输出选项词。
                10. 输出必须是严格 JSON 数组，格式：[{"key":"维度名","value":"分析内容"}]。不要 markdown，不要代码块，不要解释。
                """);
        return template.formatted(
                productA == null || productA.isBlank() ? "（未提供文字，请优先从上传图片分析产品外观）" : productA,
                selling == null || selling.isBlank() ? "（未提供，按图片外观自行提炼设计取向）" : selling,
                productB == null || productB.isBlank() ? "" : "补充描述：" + productB,
                (focusPrompt + "\n" + (stylePrompt.isEmpty() ? "" : stylePrompt)).trim()
        );
    }

    /**
     * 调用 Gemini 进行开品融合分析（支持双图）
     */
    private String requestGeminiKaiPinAnalysis(String prompt, File imageA, File imageB, boolean strictRetry) throws IOException {
        String apiKey = appProperties.getGemini().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API Key 未配置");
        }

        String systemText = promptTemplateLoader.load("prompt/kai-pin-analysis-system.txt", "kai-pin-analysis system fallback");
        if (strictRetry) {
            systemText += "\n\n重要：本轮不是资料完整性诊断。即使图片或文字信息不完整，也必须输出\"核心卖点清单\"加 7 个固定维度字段，禁止输出\"异常说明\"\"请补充信息\"\"无法分析\"。固定选项字段只输出选项词，不要写解释。";
        }

        // 构造 user content（文本 + 图片 base64）
        ArrayNode userContent = objectMapper.createArrayNode();
        userContent.addObject().put("type", "text").put("text", prompt);
        if (imageA != null && imageA.exists() && imageA.isFile()) {
            byte[] bytesA = Files.readAllBytes(imageA.toPath());
            String dataUrl = "data:" + getMimeType(imageA.getName()) + ";base64," + Base64.getEncoder().encodeToString(bytesA);
            userContent.addObject().put("type", "image_url")
                    .putObject("image_url").put("url", dataUrl);
        }
        if (imageB != null && imageB.exists() && imageB.isFile()) {
            byte[] bytesB = Files.readAllBytes(imageB.toPath());
            String dataUrl = "data:" + getMimeType(imageB.getName()) + ";base64," + Base64.getEncoder().encodeToString(bytesB);
            userContent.addObject().put("type", "image_url")
                    .putObject("image_url").put("url", dataUrl);
        }

        String requestJson = buildOpenAIChatRequest(GEMINI_ANALYSIS_MODEL, systemText, userContent, 9000, 0.65);
        Request request = new Request.Builder()
                .url(appProperties.getGemini().getBaseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestJson, JSON_TYPE))
                .build();

        return executeAnalysisWithRetry(request, "Gemini 开品融合分析");
    }

    /**
     * 开品融合分析的本地兜底卡片
     */
    private List<Map<String, String>> buildFallbackKaiPinFields(
            String productA, String productB, String selling, String focus, String style) {

        String subject = productA != null && !productA.isBlank()
                ? productA.substring(0, Math.min(50, productA.length()))
                : "该产品";

        return List.of(
                Map.of("key", "几何结构",
                        "value", subject + "以清晰主轮廓为基础，重点观察外壳几何、边角半径、转折面、开孔与局部组件层级；后续设计应保持主体比例稳定，并让关键结构服务产品识别和卖点表达。"),
                Map.of("key", "体量感", "value", "轻盈"),
                Map.of("key", "仿生学元素",
                        "value", subject + "未识别到明确动物、植物或自然形态借鉴，整体更偏几何化和工程化表达；如果需要强化记忆点，可从流线、水滴或骨骼支撑关系中提取轻量仿生线索。"),
                Map.of("key", "模块化程度", "value", "一体成型"),
                Map.of("key", "主色调", "value", "中性白"),
                Map.of("key", "辅色", "value", "无明显辅色"),
                Map.of("key", "风格标签", "value", "科技极简")
        );
    }

    private List<Map<String, String>> ensureKaiPinSellingPoints(
            List<Map<String, String>> fields, String productA, String selling) {
        List<Map<String, String>> normalized = new ArrayList<>();
        boolean hasSellingPointList = false;
        if (fields != null) {
            for (Map<String, String> field : fields) {
                if (field == null) continue;
                String key = field.getOrDefault("key", "").trim();
                String value = field.getOrDefault("value", "").trim();
                if (key.isBlank()) continue;
                if ("核心卖点清单".equals(key)) {
                    hasSellingPointList = true;
                    if (value.isBlank()) value = buildKaiPinSellingPointValue(productA, selling);
                }
                normalized.add(Map.of("key", key, "value", value));
            }
        }
        if (!hasSellingPointList) {
            normalized.add(0, Map.of(
                    "key", "核心卖点清单",
                    "value", buildKaiPinSellingPointValue(productA, selling)
            ));
        }
        return normalized;
    }

    private String buildKaiPinSellingPointValue(String productA, String selling) {
        String subject = productA == null || productA.isBlank()
                ? "该产品"
                : productA.trim().substring(0, Math.min(36, productA.trim().length()));
        String source = selling == null || selling.isBlank()
                ? "根据白底图可见的结构、材质、体量和使用方式主动提炼卖点"
                : selling.trim();
        return String.join("\n",
                "1. 核心购买理由：" + source + "；画面要用产品主体清晰轮廓、关键功能区和真实材质细节证明，而不是只靠文字说明。",
                "2. 使用痛点转译：" + subject + "需要让用户一眼看出好用、耐用或更省心；生成图中应展示使用动作、放置方式、尺度参照或功能状态。",
                "3. 视觉记忆点：" + subject + "的几何结构、体量感、主辅色和可见工艺要形成统一识别；后续每张图都要围绕这些卖点变化场景和构图。",
                "4. 商业转化证据：通过局部质感、整体全貌、场景布局、光影和拍摄角度，把卖点转成可被买家直接感知的画面证据。"
        );
    }

    private String requestGeminiAnalysis(String prompt, File imageFile, boolean strictRetry) throws IOException {
        String apiKey = appProperties.getGemini().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API Key 未配置");
        }

        String systemText = promptTemplateLoader.load("prompt/kai-pin-fusion-system.txt", "kai-pin-fusion system fallback");

        ArrayNode userContent = objectMapper.createArrayNode();
        userContent.addObject().put("type", "text")
                .put("text", buildProductAnalysisPrompt(prompt, imageFile != null, strictRetry));
        if (imageFile != null && imageFile.exists() && imageFile.isFile()) {
            byte[] bytes = Files.readAllBytes(imageFile.toPath());
            String dataUrl = "data:" + getMimeType(imageFile.getName()) + ";base64," + Base64.getEncoder().encodeToString(bytes);
            userContent.addObject().put("type", "image_url")
                    .putObject("image_url").put("url", dataUrl);
        }

        String requestJson = buildOpenAIChatRequest(GEMINI_ANALYSIS_MODEL, systemText, userContent, 7000, 0.55);
        Request request = new Request.Builder()
                .url(appProperties.getGemini().getBaseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestJson, JSON_TYPE))
                .build();

        return executeAnalysisWithRetry(request, "Gemini 分析");
    }

    private String requestGeminiCustomPromptAnalysis(String prompt, List<File> imageFiles, int count, boolean withText) throws IOException {
        String apiKey = appProperties.getGemini().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Gemini API Key 未配置");
        }

        String systemText = promptTemplateLoader.load("prompt/custom-analysis-system.txt", "custom-analysis system fallback");

        ArrayNode userContent = objectMapper.createArrayNode();
        userContent.addObject().put("type", "text")
                .put("text", buildCustomPromptAnalysisPrompt(prompt, count, imageFiles != null && !imageFiles.isEmpty(), withText));

        if (imageFiles != null) {
            for (File imageFile : imageFiles) {
                if (imageFile == null || !imageFile.exists() || !imageFile.isFile()) continue;
                byte[] bytes = Files.readAllBytes(imageFile.toPath());
                String dataUrl = "data:" + getMimeType(imageFile.getName()) + ";base64," + Base64.getEncoder().encodeToString(bytes);
                userContent.addObject().put("type", "image_url")
                        .putObject("image_url").put("url", dataUrl);
            }
        }

        String requestJson = buildOpenAIChatRequest(GEMINI_ANALYSIS_MODEL, systemText, userContent, 8000, 0.65);
        Request request = new Request.Builder()
                .url(appProperties.getGemini().getBaseUrl() + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestJson, JSON_TYPE))
                .build();

        return executeAnalysisWithRetry(request, "Gemini 自定义分析");
    }


    private String buildCustomPromptAnalysisPrompt(String prompt, int count, boolean hasImage, boolean withText) {
        // 画面文案相关的额外字段与约束：仅当 withText=true 时注入
        String summaryTextField = withText
                ? "【系列文案规划】统一规划每张图要渲染在画面上的文案: 第 1 张主标题负责什么(建立认知/实力), 第 2 张负责什么(使用场景/痛点解决), 第 3 张及以后负责什么(材质/细节/差异化); 各张主标题独立但围绕同一总卖点连续递进, 风格统一。\n"
                : "";
        String planTextField = withText
                ? "【画面文案】给出本图要真实渲染到画面上的中文文案: 主标题(≤8字, 醒目大字)、副标题(≤15字, 一句卖点解释)、2~3个卖点标签(每个≤6字, 适合做成小色块/圆角标签); 并说明排版位置(如左上/底部居中)、字体气质(现代简洁/科技/活泼)和文字颜色(必须与背景高对比、清晰可读); 文案要承接【系列文案规划】中本张的定位, 和其他张连续但不重复。\n"
                : "";
        String textGoal = withText
                ? "8. 本次需要在生成图上渲染卖点文案（画面营销文案）。每张方案都要给出【画面文案】，内容应该不同，分别强调不同的核心卖点；整组图片的文案风格统一但内容递进；文字必须是可直接渲染、清晰可读、无错别字的中文。\n"
                : "8. 本次只生成纯产品图，不要在画面上渲染任何营销文字、标题、标签或水印。\n";
        String textOutputRule = withText
                ? "9. **产品原生文字保持**：如果产品本身带有文字/LOGO/标识（印刷在产品外壳、屏幕、按键上的文字），所有图片都必须保持与第1张图片完全一致，不得模糊、变形或消失。画面营销文案是独立的，每张图可以不同。\n10. 【画面文案】中的主标题、副标题和卖点标签必须是可直接渲染在图面上的真实中文文字, 简短有力、无错别字; 不要写成对文字的描述, 要写出确切的文案内容。\n"
                : "9. **产品原生文字保持**：如果产品本身带有文字/LOGO/标识（印刷在产品外壳、屏幕、按键上的文字），所有图片都必须保持与第1张图片完全一致，不得模糊、变形或消失。\n";
        String textForbid = withText
                ? "除【画面文案】指定的卖点文字外, 画面不要出现其他多余文字、乱码、水印或 logo。"
                : "画面上不要出现任何文字、标题、标签、水印或 logo。";
        // 手输要求强约束：用户填了"生图要求/卖点"时，它是最高优先级硬指令，必须逐字贯彻，
        // 不得被模型自动推断的卖点覆盖（修复"手输卖点没生效+构图不变"）。留空才走自动生成。
        boolean hasUserReq = prompt != null && !prompt.isBlank();
        String userReqMandate = hasUserReq
                ? """
                【用户要求·最高优先级·必须严格执行】上面"用户对话/描述/风格要求"里的内容是用户明确下达的硬性指令, 优先级高于你自己的任何推断:
                - 若用户点名了卖点/功能点(如"突出增压""强调净水"), 【总卖点】与各张【本图卖点】必须以这些为准逐一分配落地, 禁止用你自己臆测的卖点替换或稀释它们; 用户卖点数少于图数时, 剩余张再补充功能/场景/材质卖点。
                - 若用户点名了场景/风格/构图/角度要求, 【本图风格】【场景构图】必须据此调整, 不得无视用户要求套用默认模板。
                - 只有当用户要求为空或未提及某方面时, 才由你按产品形态自动补全。
                """
                : "";
        String exampleSummary = withText
                ? "【总卖点】...\n【目标人群】...\n【产品识别】...\n【多角度拍摄策略】...\n【系列连续性】...\n【系列文案规划】...\n"
                : "【总卖点】...\n【目标人群】...\n【产品识别】...\n【多角度拍摄策略】...\n【系列连续性】...\n";
        String examplePlan = withText
                ? "【本图卖点】...\n【系列连续性】...\n【画面文案】...\n【本图风格】...\n【产品一致性】...\n【安装方式】...\n【形态结构】...\n【材质/工艺】...\n【场景构图】...\n【禁止项】...\n"
                : "【本图卖点】...\n【系列连续性】...\n【本图风格】...\n【产品一致性】...\n【安装方式】...\n【形态结构】...\n【材质/工艺】...\n【场景构图】...\n【禁止项】...\n";

        // 专业电商主图分镜范例（来自人工整理的优质提示词库）：用于教 Gemini 把【场景构图】写到专业水准。
        String compositionExamples = """

                【场景构图·专业范例参考】下列是优质电商主图的构图写法, 请学习这种"视角 + 主体位置 + 道具 + 特效标注 + 文字区位置"的精确描述粒度, 并结合当前产品灵活套用(不要照抄品类):
                - 正面微侧平视视角, 主体居中, 上下留白均衡, 陈列配套道具, 画面重心稳定。
                - 斜俯视近景特写, 聚焦产品某一功能结构(如台面/层板/接口), 用蓝色发光 + 双向箭头标注关键尺寸, 单侧留作文字区。
                - 斜仰视视角, 聚焦底部/沥水/通风结构, 搭配水流滴落或白色气流箭头特效, 展示该结构的功能价值。
                - 近景特写 + 手部演示动作(握持/按压/抽拉/拧动), 配橙色环形波纹或发光轮廓特效突出操作部件, 真实抓握不穿模。
                - 左右双列对比布局: 左列优秀款(暖色调)、右列普通款(灰色调), 每组上图下文 + 对勾/叉号图标。
                - 2×2 四宫格步骤布局, 顶部标题区, 每格带序号 + 底部说明, 展示安装/使用流程。
                - 材质分层悬浮展开/半剖视角, 内部结构配数字标签 + 材质说明 + 引线标注。
                """;
        String textboardExamples = withText
                ? """

                【画面文案·字板排版范例参考】下列是优质字板的排版写法, 请学习这种"位置 + 字号字重字色 + 对齐 + 标签样式"的描述粒度, 写进【画面文案】里:
                - 左下角深色圆角矩形文字框: 上行小号浅色细体, 下行大号加粗白色黑体; 形成主次对比。
                - 右上角无框大号加粗黑体主标题, 下方小号常规黑体副标题; 右对齐。
                - 左侧无框左对齐: 上行两行大号加粗黑体主标题, 下行小号常规黑体说明, 文字下配短横线装饰。
                - 关键尺寸用蓝色发光白色大号数字 + 单位, 搭配双向箭头, 叠加橙色发光底。
                - 卖点标签: 竖排彩色圆角标签(绿/黑/红底白字)放在主体一侧; 品牌名放左上角圆角框或角标。
                - 弧形文字: 沿圆环/吸盘弧度弯排橙黄色字标注安装时长。
                - 对比类: 左列暖底白字配黄色对勾, 右列灰底白字配灰色叉号。
                """
                : "";

        return """
                本次是否上传白底产品图: %s
                用户对话/描述/风格要求:
                %s
                %s
                请先生成 1 段【总分析】, 再生成 %d 段适合 AI 生图的中文提示词, 每段对应 1 张图, 每个区块之间用 --- 单独分隔。
                重要目标:
                1. 【总分析】只输出一次, 放在最前面。它必须总结整组图片共同的总卖点、目标人群、产品结构识别点、材质/工艺识别点和系列连续性。
                2. 如果用户没有明确卖点, 你必须根据产品形态、安装方式、使用方式、目标人群和可见结构自动生成一个总卖点。
                3. 每张图方案都必须引用同一套【总分析】, 保证整组图片营销方向、产品主体和视觉识别连续一致。
                4. 每张图都必须生成不同的【本图卖点】、【本图风格】、【场景构图】, 让第 1 张、第 2 张、第 3 张等形成连续系列: 先建立认知, 再证明功能, 再放大材质/场景/细节价值。
                5. 每张图都必须写【产品一致性】: 罗列白底图中可见的主体轮廓、比例、颜色、材质、结构层级、接口/按键/开孔/支撑件/装饰细节。后续 image2image 会逐张使用这些约束保持同一产品。
                6. **多角度拍摄策略**：【总分析】中必须包含【多角度拍摄策略】字段，分析产品形态后给出不同拍摄角度的建议：
                   - 第1张：建议主视角（正面/3/4视角/侧面），用于建立产品认知和展示核心卖点
                   - 第2张及以后：建议其他补充视角（俯视/仰视/背面/底部/特写/使用场景等），每个角度说明展示什么功能/细节/使用方式
                   - 每个角度建议要考虑产品的安装方式、使用方式、材质工艺、功能分区
                   - 确保多个角度形成连续叙事：从整体认知→功能证明→材质细节→使用场景
                7. **两类文字的区分与要求**：
                   - **产品原生文字**（必须所有图保持一致）：产品本体上印刷/显示的LOGO、品牌名、型号标识、按钮标签、刻度数字、接口标识等
                   - **画面营销文案**（每张图可以不同）：漂浮在场景中的营销标题、副标题、卖点标签，用于强调本图的差异化卖点
                   - 第1张必须清晰展示产品原生文字，后续所有图片的产品原生文字必须与第1张完全相同
                   - 如果 withText=true，每张图的画面营销文案应该不同，分别强调不同的核心卖点或价值点
                %s
                【总分析】必须严格保留以下字段名:
                【总卖点】一句话概括整组图片共同要证明的核心购买理由，必须来自用户对话或图像推断。如果用户提供了多个卖点，这里总结统一的购买理由，具体卖点分配到各张图的【本图卖点】中。
                【目标人群】说明该产品主要打动谁，以及他们的核心痛点。
                【产品识别·物理细节分析】**这是最关键的约束字段**，必须从以下维度逐项分析白底图中的产品：
                   1. **整体尺寸比例**：长宽高的视觉比例（如1:2:1.5），哪个维度最长/最短，整体是扁平还是立体，是紧凑型还是修长型
                   2. **粗细厚度**：主体厚度（薄型/标准/厚实），边框粗细（窄边框/宽边框/无边框），部件的粗细对比（如手柄粗细、支架粗细、按键厚度）
                   3. **前后层次关系**：产品是单层平面还是多层结构，哪些部件在前景、哪些在后景，是否有悬浮/嵌套/包裹关系，透视深度如何
                   4. **零部件空间关系**：各部件之间的连接方式（卡扣/螺丝/嵌入/磁吸/焊接），相对位置（上下/左右/内外），是否可拆卸，间隙大小
                   5. **支撑与受力点**：产品如何站立/悬挂/固定，重心位置，底座形态，接触面积，是否需要支撑件
                   6. **开孔与接口**：所有可见的孔位（通风孔/螺丝孔/接口/按键孔）的位置、大小、形状、排列规律
                   7. **边角与转折**：边缘是直角/倒角/圆角，转折是硬朗还是柔和，有无斜切面/弧面过渡
                   8. **产品原生文字**：如果产品上有LOGO/品牌名/型号/按钮标签等印刷文字，明确列出其位置、内容、字体特征、大小比例、颜色

                   **重要性说明**：这些物理细节的精确描述是保证后续多张图中产品保持完全一致的核心依据。必须基于白底图的实际观察，用具体的数量、位置、比例描述，不能写"保持原样"等空泛表述。
                【多角度拍摄策略】根据产品形态和上述物理结构，规划不同拍摄角度：第1张用什么视角建立认知，第2-N张分别从什么角度展示什么功能/细节/场景，每个角度要说明能展示哪些物理细节（如俯视角度展示顶部开孔分布，侧视角度展示厚度和层次关系）。
                【系列连续性】说明多张图如何连续：第1张负责什么卖点，第2张负责什么卖点，第3张负责什么卖点；若用户提供了多个卖点，每张图分配一个主要卖点进行深度证明；若卖点数量少于图片数量，后续按功能证明、场景证明、材质证明、局部质感递进。
                %s
                每张方案必须用标题【第 1 张方案】、【第 2 张方案】... 开头, 并严格保留以下字段名和顺序:
                【本图卖点】只服务当前这一张图的差异化卖点, 不能和其他段完全重复; 要写清用户痛点、功能利益、购买理由和画面证明方式。
                【系列连续性】说明本图承接总分析中的哪一环, 和前后方案如何形成一组连续视觉叙事。
                %s【本图风格】为当前图指定独立视觉风格/背景基调, 可以是科技蓝、少女粉、高级灰、自然绿、暖阳橙、赛博黑、居家生活、专业办公、户外便携等, 并写明色彩、道具、空间气质。
                【产品一致性·物理约束】**必须逐项引用【总分析】中的物理细节**：
                   - 尺寸比例约束：重申长宽高比例，禁止拉伸/压扁/改变主体形态
                   - 粗细厚度约束：重申关键部件的粗细/厚度，禁止变粗/变细/变薄
                   - 前后层次约束：重申部件的前后遮挡关系和深度层次，禁止层次错乱
                   - 零部件位置约束：重申各部件的空间位置和连接方式，禁止位置偏移/连接方式改变
                   - 支撑受力约束：重申底座/支架/接触面形态，禁止悬空不合理或受力点错误
                   - 开孔接口约束：重申所有可见孔位的位置和数量，禁止孔位消失/增加/位移
                   - 边角转折约束：重申边角处理方式，禁止直角变圆角或圆角变直角
                   - 产品原生文字约束：重申LOGO/文字的位置、内容、大小，禁止模糊/变形/消失

                   本字段不能只写"保持白底图一致"，必须具体列出本图需要保持的物理细节项，让生图模型有明确的约束参照。
                【安装方式】说明该产品适合壁挂、台置、落地、嵌入、夹持、悬挂、手持或免安装等方式, 并写清固定点、承重点、接触面或使用动作，要与【产品识别】中的支撑受力分析一致。
                【形态结构】分析产品主轮廓、体块比例、功能区、开孔/接口/按键/支撑件、边角转折和结构层级，要与【产品识别】中的物理细节分析保持一致并进一步细化到本图的展示角度。
                【材质/工艺】写清外壳、金属/塑胶/玻璃/木纹/硅胶等材质, 表面是哑光、亮面、磨砂、拉丝、透明、喷涂、倒角、接缝还是细纹理，材质质感要与【产品识别】中的描述一致。
                【场景构图】写明具体使用场景、构图、拍摄角度、功能展示方式、道具、产品状态、产品全貌/局部质感、光影策略。注意：选择的拍摄角度要能清晰展示【产品一致性·物理约束】中强调的关键物理细节。
                【禁止项】禁止改变产品主体造型、生成多个无关产品、畸变、低清晰度、过曝、遮挡关键结构; 严禁穿模、穿帮、产品与道具/手部/支撑面相互穿插、悬空不合理或阴影受力不符; **特别禁止**：改变尺寸比例、改变粗细厚度、改变前后层次、改变零部件位置、改变开孔数量或位置、改变边角处理方式; %s

                输出规则:
                1. 只输出【总分析】和【第 N 张方案】正文, 不要 markdown, 不要解释。
                2. 【总分析】180-400 个中文字符（因为增加了物理细节分析，字数上限提高）; 每张方案 320-600 个中文字符, 信息要具体可执行。
                3. 多张方案之间必须差异化: 本图卖点、本图风格、场景构图、道具、镜头角度或光影至少变化 3 项, 但产品主体的物理细节（尺寸比例、粗细厚度、前后层次、零部件位置等）必须在所有方案中保持完全一致。
                4. **【产品识别·物理细节分析】和【产品一致性·物理约束】是核心字段**，每张方案的【产品一致性·物理约束】必须逐项引用【总分析】中的物理细节，不能只写"保持一致"或"参考白底图"等空泛表述，必须具体列出：尺寸比例X:Y:Z、厚度Nmm、前景部件A+后景部件B、开孔N个位于XX位置等可量化描述。
                5. 禁止空泛词堆叠, 比如"高端、精致、好看、实用"; 必须转成可见结构、材质反光、操作动作、场景痛点或对比证据。
                6. **物理细节约束优先级最高**：如果本图风格、场景构图与物理细节约束冲突（如俯视角度会改变产品的视觉比例），优先保证物理细节准确，调整拍摄角度或构图方式。
                %s%s7. 输出格式示例:
                【总分析】
                %s---
                【第 1 张方案】
                %s---
                【第 2 张方案】
                ...
                """.formatted(
                        hasImage ? "是" : "否",
                        prompt == null || prompt.isBlank() ? "无" : prompt,
                        userReqMandate,
                        count,
                        textGoal,
                        summaryTextField,
                        planTextField,
                        textForbid,
                        textOutputRule,
                        compositionExamples + textboardExamples + "\n",
                        exampleSummary,
                        examplePlan);
    }

    public boolean generateImage(String prompt, String refImagePath,
                                 String whiteBgPath, String outputPath, String agentId) {
        ImageGeneratorAgent agent = resolveAgent(agentId);
        log.info("使用智能体 [{}] 生成图片", agent.getId());
        String enforcedPrompt = enforceNoIntersectionPrompt(prompt);
        return agent.generate(enforcedPrompt, refImagePath, whiteBgPath, outputPath);
    }

    /**
     * 多参考图重载（品牌/产品一致性场景）。
     * 当 agent 未覆写 generateMulti 时，默认实现会自动降级到单参考图版本。
     */
    public boolean generateImageMulti(String prompt, List<String> refImagePaths,
                                      String whiteBgPath, String outputPath,
                                      String agentId, String aspect) {
        return generateImageMulti(prompt, refImagePaths, whiteBgPath, outputPath, agentId, aspect, null);
    }

    public boolean generateImageMulti(String prompt, List<String> refImagePaths,
                                      String whiteBgPath, String outputPath,
                                      String agentId, String aspect, GenerationTask task) {
        // 【检查点】调用Agent前检查
        if (task != null && task.isCancelled()) {
            log.info("任务已取消，跳过图片生成");
            return false;
        }

        ImageGeneratorAgent agent = resolveAgent(agentId);
        log.info("使用智能体 [{}] 生成图片（refs={}, aspect={}）", agent.getId(),
                refImagePaths == null ? 0 : refImagePaths.size(), aspect);
        String enforcedPrompt = enforceNoIntersectionPrompt(prompt);

        // ADR-009：GenerationTask 已提进接口，统一调 generateMulti(...task)，无需 instanceof 类型转换。
        // 不支持中断的 Agent 走接口 default（忽略 task）；Gemini/Wan 覆写此方法做分段中断。
        return agent.generateMulti(enforcedPrompt, refImagePaths, whiteBgPath, outputPath, aspect, task);
    }

    private String enforceNoIntersectionPrompt(String prompt) {
        String base = prompt == null ? "" : prompt.trim();
        if (base.contains("最高优先级·禁止穿模") || base.contains("禁止穿模")) {
            return base;
        }
        if (base.isBlank()) {
            return NO_INTERSECTION_PROMPT;
        }
        return base + "\n\n" + NO_INTERSECTION_PROMPT;
    }

    public void generateSkuImages(String whiteBgUrl, String refPath,
                                  String outputFolder, List<String> skuList, String agentId, String userPrompt) {
        File skuRefDir = new File(refPath, "SKU");
        if (!skuRefDir.exists()) { log.warn("SKU 参考图目录不存在: {}", skuRefDir.getAbsolutePath()); return; }
        List<File> skuRefs = listImages(skuRefDir);
        if (skuRefs.isEmpty()) { log.warn("SKU 参考图目录为空"); return; }

        File skuOutputDir = new File(outputFolder, "SKU");
        skuOutputDir.mkdirs();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < skuList.size(); i++) {
            final int idx = i;
            final File ref = skuRefs.get(random.nextInt(skuRefs.size()));
            final String prompt = (userPrompt != null && !userPrompt.isBlank())
                    ? userPrompt
                    : "保留参考图的背景，移除参考图中的物品主体，将白底图中的产品替换到背景中。" + skuList.get(i);
            final String outputPath = new File(skuOutputDir, (idx + 1) + ".jpg").getAbsolutePath();
            futures.add(CompletableFuture.runAsync(() -> {
                boolean ok = generateImage(prompt, ref.getAbsolutePath(), whiteBgUrl, outputPath, agentId);
                log.info("SKU 图 {}: {}", idx + 1, ok ? "成功" : "失败");
            }, executor));
        }
        futures.forEach(f -> { try { f.join(); } catch (Exception e) { log.warn("SKU 图生成异常: {}", e.getMessage()); } });
    }

    public List<String> generateMainImages(String whiteBgUrl, String refPath,
                                           String outputFolder, List<String> mainList, String agentId, String userPrompt) {
        if (mainList == null || mainList.isEmpty()) return List.of();

        File mainRefDir = new File(refPath, "主图");
        if (!mainRefDir.exists()) { log.warn("主图参考图目录不存在: {}", mainRefDir.getAbsolutePath()); return List.of(); }
        List<File> mainRefs = listImages(mainRefDir);
        if (mainRefs.isEmpty()) { log.warn("主图参考图目录为空"); return List.of(); }

        File mainOutputDir = new File(outputFolder, "主图");
        mainOutputDir.mkdirs();

        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < mainList.size(); i++) {
            final int idx = i;
            final File ref = mainRefs.get(random.nextInt(mainRefs.size()));
            final String prompt = (userPrompt != null && !userPrompt.isBlank())
                    ? userPrompt
                    : "保留参考图的背景，移除参考图中的物品主体，将白底图中的产品替换到背景中。" + mainList.get(i);
            final String outputPath = new File(mainOutputDir, (idx + 1) + ".jpg").getAbsolutePath();
            futures.add(CompletableFuture.supplyAsync(() -> {
                boolean ok = generateImage(prompt, ref.getAbsolutePath(), whiteBgUrl, outputPath, agentId);
                log.info("主图 {}: {}", idx + 1, ok ? "成功" : "失败");
                return ok ? outputPath : null;
            }, executor));
        }

        List<String> outputPaths = new ArrayList<>();
        futures.forEach(f -> {
            try {
                String p = f.join();
                if (p != null) outputPaths.add(p);
            } catch (Exception e) { log.warn("主图生成异常: {}", e.getMessage()); }
        });
        return outputPaths;
    }

    public void generateDetailImages(String mainImgPath, String outputFolder,
                                     String whiteBgUrl, String refPath, String agentId, String userPrompt) {
        if (mainImgPath == null || !new File(mainImgPath).exists()) return;

        File detailRefDir = new File(refPath, "详情图");
        if (!detailRefDir.exists()) { log.warn("详情图参考目录不存在: {}", detailRefDir.getAbsolutePath()); return; }
        List<File> detailRefs = listImages(detailRefDir);
        if (detailRefs.isEmpty()) return;

        File detailOutputDir = new File(outputFolder, "详情图");
        detailOutputDir.mkdirs();

        File randomRef = detailRefs.get(random.nextInt(detailRefs.size()));
        String prompt = (userPrompt != null && !userPrompt.isBlank())
                ? userPrompt
                : "将白底图中的产品重新布局为9:16竖版格式，保持产品主体突出，合理压缩和排布内容，适合详情页展示";
        String outputPath = new File(detailOutputDir, new File(mainImgPath).getName()).getAbsolutePath();
        boolean ok = generateImage(prompt, randomRef.getAbsolutePath(), whiteBgUrl, outputPath, agentId);
        log.info("详情图: {}", ok ? "成功" : "失败");
    }

    public int getNextOutputNumber(String categoryOutputDir, String productName) {
        File dir = new File(categoryOutputDir);
        if (!dir.exists()) return 1;
        File[] folders = dir.listFiles(f -> f.isDirectory() && f.getName().startsWith(productName));
        if (folders == null || folders.length == 0) return 1;
        int maxNum = 0;
        Pattern p = Pattern.compile("_(\\d+)$");
        for (File folder : folders) {
            Matcher m = p.matcher(folder.getName());
            if (m.find()) maxNum = Math.max(maxNum, Integer.parseInt(m.group(1)));
        }
        return maxNum + 1;
    }

    private ImageGeneratorAgent resolveAgent(String agentId) {
        if (agentId != null && agentMap.containsKey(agentId)) {
            return agentMap.get(agentId);
        }
        ImageGeneratorAgent fallback = agentMap.get(DEFAULT_AGENT_ID);
        if (fallback == null) fallback = agentMap.values().iterator().next();
        return fallback;
    }

    private List<File> listImages(File dir) {
        File[] files = dir.listFiles(f -> {
            String name = f.getName().toLowerCase();
            return f.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"));
        });
        return files == null ? List.of() : Arrays.asList(files);
    }

    private String executeAnalysisWithRetry(Request request, String label) throws IOException {
        int maxRetries = 3;
        int[] retryDelaySecs = {3, 6};
        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try (Response response = analysisClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                int code = response.code();
                if (response.isSuccessful()) {
                    return extractGeminiOutputText(body);
                }
                boolean retryable = code == 429 || code == 500 || code == 503;
                if (!retryable || attempt == maxRetries - 1) {
                    throw new RuntimeException(label + "失败(" + code + "): " + body);
                }
                int delay = retryDelaySecs[Math.min(attempt, retryDelaySecs.length - 1)];
                log.warn("{} 返回 {} (尝试 {}/{})，{}s 后重试", label, code, attempt + 1, maxRetries, delay);
                try { Thread.sleep(delay * 1000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (IOException e) {
                if (attempt == maxRetries - 1) throw e;
                log.warn("{} 网络异常 (尝试 {}/{})，3s 后重试: {}", label, attempt + 1, maxRetries, e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw new RuntimeException(label + " 超过最大重试次数");
    }

    private OkHttpClient buildAnalysisClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS);
        AppProperties.Proxy proxy = appProperties.getProxy();
        if (proxy.isEnabled()) {
            builder.proxy(new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new InetSocketAddress(proxy.getHost(), proxy.getPort())));
        } else {
            builder.proxySelector(java.net.ProxySelector.getDefault());
        }
        return builder.build();
    }

    private String extractGeminiOutputText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        // OpenAI 兼容格式：choices[0].message.content
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            String text = choices.get(0).path("message").path("content").asText("").trim();
            if (!text.isBlank()) return text;
        }
        throw new IOException("Gemini 未返回文本内容: " + responseBody);
    }

    /** 构造 OpenAI 兼容格式的 chat/completions 请求 JSON */
    private String buildOpenAIChatRequest(String model, String systemText, ArrayNode userContent,
                                          int maxTokens, double temperature) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", maxTokens);
        root.put("temperature", temperature);
        ArrayNode messages = root.putArray("messages");
        if (systemText != null && !systemText.isBlank()) {
            messages.addObject().put("role", "system").put("content", systemText);
        }
        messages.addObject().put("role", "user").set("content", userContent);
        return root.toString();
    }

    private String buildProductAnalysisPrompt(String prompt, boolean hasImage, boolean strictRetry) {
        String dimensionHint = extractDimensionHint(prompt);
        String subjectHint = extractSubjectHint(prompt);
        String strictText = strictRetry ? """

                重要纠偏：
                上一次输出像是在要求补充资料或内容过浅，这是错误的。本轮不是资料完整性诊断，而是把用户文字和图片直接转换为深度开品分析卡片。
                如果没有图片，用户文字中的产品对象就是分析对象；必须输出可编辑卡片，禁止输出"异常说明""请补充信息""无法分析"。
                如果用户写了"从 A、B 角度分析一个 Z"，必须输出 A、B 两个 key，并围绕 Z 填写 value；每个 value 都要包含设计判断、生图落点，并严格拓写用户文字中隐含或显性的卖点。
                后端已识别维度提示：%s
                后端已识别产品对象提示：%s
                """.formatted(dimensionHint.isBlank() ? "按用户原文自行提取" : dimensionHint,
                subjectHint.isBlank() ? "按用户原文自行识别" : subjectHint) : "";
        String template = promptTemplateLoader.load("prompt/product-analysis-user.txt", """
                你是资深工业设计与电商爆品开品分析师。
                请参考自定义模式的图片分析思路理解输入：先看图像证据，再拆结构和卖点，最后把分析写成可编辑、可二次生图的卡片。

                本次是否上传图片：%s
                用户分析提示词：
                %s
                %s
                输出要求：
                1. 从用户提示词中动态提取维度名称，不要硬编码维度；如果出现"从 A、B、C 角度分析"，A/B/C 就是 key。
                2. 输出必须是严格 JSON 数组，格式：[{"key":"维度名","value":"分析内容"}]。
                3. 只输出 JSON，不要 markdown，不要代码块，不要解释。
                4. key 使用中文短标签；value 必须 100-200 个中文字符，包含"观察依据/文字依据 + 设计判断 + 生图可执行细节"，不能只写形容词。
                5. 如果有图片，每个 value 优先引用图中可见信息：几何轮廓、比例、部件层级、接口按键、纹理、材质、颜色、场景线索；至少 4 个卡片显式写出"从图中可见..."或同义表达。
                6. 卖点必须严格拓写：即使用户没有单独写卖点，也要从产品结构、使用痛点、视觉差异中提炼购买理由；每个动态维度都要说明该维度如何证明、放大或承接卖点。
                7. value 要能直接拼入后续生图提示词，聚焦可观察的结构、比例、体量、材质、功能线索、使用场景、镜头角度、光影或造型语言，并把卖点转成画面证据。
                8. 即使没有图片，也必须基于用户文字做结构化拆解；不要要求补充资料。
                """);
        return template.formatted(hasImage ? "是" : "否", prompt, strictText);
    }

    private String extractDimensionHint(String prompt) {
        if (prompt == null) return "";
        Matcher matcher = Pattern.compile("从(.{1,120}?)(?:角度|维度)").matcher(prompt);
        if (!matcher.find()) return "";
        String raw = matcher.group(1)
                .replace("等", "")
                .replace("方面", "")
                .trim();
        List<String> parts = Arrays.stream(raw.split("[、，,；;和与/\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .limit(8)
                .toList();
        return String.join("、", parts);
    }

    private String extractSubjectHint(String prompt) {
        if (prompt == null) return "";
        Matcher matcher = Pattern.compile("分析(.{1,120}?)(?:，|,|。|；|;|并|$)").matcher(prompt);
        if (!matcher.find()) return "";
        return matcher.group(1).trim();
    }

    private boolean looksLikeRefusal(List<Map<String, String>> fields) {
        if (fields == null || fields.isEmpty()) return true;
        if (fields.size() > 1) return false;
        Map<String, String> only = fields.get(0);
        String key = only.getOrDefault("key", "");
        String value = only.getOrDefault("value", "");
        String text = key + " " + value;
        return text.contains("异常") || text.contains("无法") || text.contains("不能")
                || text.contains("缺少") || text.contains("未提供") || text.contains("请补充")
                || text.contains("重新发起") || text.contains("重试");
    }

    private List<Map<String, String>> buildFallbackAnalysisFields(String prompt) {
        String subject = extractSubjectHint(prompt);
        if (subject.isBlank()) subject = "该产品";
        String dimensionHint = extractDimensionHint(prompt);
        List<String> dimensions = dimensionHint.isBlank()
                ? List.of("几何结构", "体量感", "材质工艺", "使用场景")
                : Arrays.stream(dimensionHint.split("、"))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .limit(8)
                    .toList();
        List<Map<String, String>> fields = new ArrayList<>();
        for (String dimension : dimensions) {
            fields.add(Map.of("key", dimension, "value", fallbackValueForDimension(subject, dimension)));
        }
        return fields;
    }

    private String fallbackValueForDimension(String subject, String dimension) {
        if (dimension.contains("几何") || dimension.contains("结构") || dimension.contains("造型")) {
            return subject + "需要先建立清晰可辨的主轮廓，主体、支撑件、开孔和操作区要形成明确层级。生图时用 3/4 视角展示边缘转折、厚薄关系和关键结构，让用户一眼理解它是可制造、可使用的真实产品，而不是概念拼贴。";
        }
        if (dimension.contains("材质") || dimension.contains("工艺")) {
            return subject + "的材质要服务核心卖点，表面可用哑光塑胶、拉丝金属、透明件、软胶防滑区或细腻纹理形成分区对比。生图时必须看见接缝、边缘收口、按键开孔和表面反光差异，证明产品具备真实工艺而非滤镜质感。";
        }
        if (dimension.contains("体量") || dimension.contains("比例")) {
            return subject + "的体量关系要稳定，主体、握持区、底座或功能头部之间保持真实尺度，视觉重心不能漂浮。生图时通过桌面、手部、生活道具或环境参照物表现尺寸，让产品显得可信、顺手并具备购买判断依据。";
        }
        if (dimension.contains("功能") || dimension.contains("分区")) {
            return subject + "的功能区域需要围绕真实使用动作排布，操作区、显示区、工作区、支撑区和维护区要有清晰边界。生图时展示按压、打开、握持、放置或工作状态，用可见交互件证明卖点，而不是只靠文字说明。";
        }
        if (dimension.contains("场景") || dimension.contains("使用")) {
            return subject + "要放入真实使用场景中展示，环境由产品功能决定，可选择桌面、家居、厨房、浴室、车内或户外等空间。生图时用自然光、少量道具和人物手部动作强化购买理由，画面既展示产品主体，也证明它能解决具体生活问题。";
        }
        return subject + "围绕\"" + dimension + "\"进行视觉强化，不能停留在抽象形容。分析要落到可见结构、材质分区、比例尺度、交互动作和真实场景上，并把该维度转译成后续生图能执行的镜头、光影、道具或视觉符号。";
    }

    private List<Map<String, String>> parseAnalysisFields(String rawText) throws IOException {
        String json = stripJsonFence(rawText);
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) {
            throw new IOException("Gemini 未返回 JSON 数组: " + rawText);
        }
        List<Map<String, String>> fields = new ArrayList<>();
        for (JsonNode item : root) {
            String key = item.path("key").asText("").trim();
            String value = item.path("value").asText("").trim();
            if (!key.isBlank() && !value.isBlank()) {
                fields.add(Map.of("key", key, "value", value));
            }
        }
        if (fields.isEmpty()) {
            throw new IOException("Gemini 返回的分析字段为空: " + rawText);
        }
        return fields;
    }

    private String stripJsonFence(String text) {
        if (text == null) return "[]";
        String s = text.trim();
        if (s.startsWith("```")) {
            s = s.replaceFirst("^```(?:json)?\\s*", "");
            s = s.replaceFirst("\\s*```$", "");
        }
        int start = s.indexOf('[');
        int end = s.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }
}

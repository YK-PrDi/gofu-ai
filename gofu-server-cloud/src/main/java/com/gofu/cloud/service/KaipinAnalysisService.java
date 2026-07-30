package com.gofu.cloud.service;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 开品模式 · 外观融合分析服务。
 * 迁移自 ele-business-java / KaipinAnalysisService（解耦独立版），调 Gemini 生成结构化分析卡片。
 * Spring Bean：由 @Value 注入 apiKey/baseUrl，复用 application.yml 里已有的 gemini 配置。
 */
@Service
public class KaipinAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(KaipinAnalysisService.class);

    private static final String ANALYSIS_MODEL = "gemini-3-pro-preview";
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_TOKENS = 9000;
    private static final double TEMPERATURE = 0.65;

    private final String apiKey;
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public KaipinAnalysisService(
            @Value("${app.gemini.api-key:}") String apiKey,
            @Value("${app.gemini.base-url:https://api.linapi.net}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .proxySelector(java.net.ProxySelector.getDefault())
                .build();
    }

    /**
     * 开品融合分析：返回结构化卡片 [{key, value}, ...]，首项固定为「核心卖点清单」。
     * 内置三层降级：正常分析 → 强约束重试 → 本地兜底卡片，接口不抛 500。
     */
    public List<Map<String, String>> analyze(
            String productA, String productB, String selling,
            String focus, String focusText, String style, String styleText,
            File imageA, File imageB) {
        try {
            String analysisPrompt = buildAnalysisPrompt(productA, productB, selling, focus, focusText, style, styleText);
            List<Map<String, String>> fields = parseAnalysisFields(
                    requestGeminiAnalysis(analysisPrompt, imageA, imageB, false));
            if (looksLikeRefusal(fields)) {
                log.warn("[开品分析] 拒绝式结果，改用强约束提示词重试");
                fields = parseAnalysisFields(requestGeminiAnalysis(analysisPrompt, imageA, imageB, true));
            }
            if (looksLikeRefusal(fields) || fields.isEmpty()) {
                log.warn("[开品分析] 重试仍失败，使用本地兜底卡片");
                fields = buildFallbackFields(productA);
            }
            return ensureSellingPoints(fields, productA, selling);
        } catch (Exception e) {
            log.error("[开品分析] 分析失败: {}", e.getMessage(), e);
            return ensureSellingPoints(buildFallbackFields(productA), productA, selling);
        }
    }

    private String buildAnalysisPrompt(String productA, String productB, String selling,
                                        String focus, String focusText, String style, String styleText) {
        String focusPrompt = switch (focus == null ? "" : focus) {
            case "cost" -> "【方案侧重】成本与量产：CMF 与结构在不牺牲核心功能的前提下尽量降低成本与开模难度；优先采用注塑/钣金等成熟工艺；颜色与材质走简洁实用风。";
            case "premium" -> "【方案侧重】颜值与溢价：放大造型语言的高端感；CMF 选择高级材质（拉丝金属/真皮/木纹/陶瓷釉面）；表面工艺精致；体现\"摆在客厅当艺术品也不违和\"的格调。";
            case "disruptive" -> "【方案侧重】颠覆性创新：允许突破常规结构去拥抱产品 B 的造型；可加入隐藏机构、模块化、可拆解、智能化等新颖设计语言；视觉冲击优先。";
            case "custom" -> focusText != null && !focusText.isBlank() ? "【方案侧重·自定义】" + focusText
                    : "【方案侧重】（用户未指定，请根据产品特性自行判断合适的设计取向）";
            default -> "【方案侧重】成本与量产：CMF 与结构在不牺牲核心功能的前提下尽量降低成本与开模难度；优先采用注塑/钣金等成熟工艺；颜色与材质走简洁实用风。";
        };
        String stylePrompt = switch (style == null ? "" : style) {
            case "dopamine" -> "【视觉风格】多巴胺：高饱和撞色（柠檬黄/珊瑚红/薄荷绿）；圆润边角；活泼趣味造型细节；色彩大胆跳跃，充满视觉能量感。";
            case "wood" -> "【视觉风格】木元素：产品本体外壳/主体部分改为原木材质；暖米色与原木棕色调，体现温润自然感。";
            case "cartoon" -> "【视觉风格】卡通：产品造型圆润可爱化；色彩亮丽柔和；增加拟人化趣味细节；整体亲切活泼。";
            case "ins" -> "【视觉风格】ins 风：清新奶油色系（象牙白/浅粉/哑光米灰）；柔和弥散光；干净留白构图；小红书/Instagram 高颜值打卡风格。";
            case "minimal" -> "【视觉风格】极简：背景纯净；产品居中大量留白；去除一切多余装饰；线条简洁；色彩克制；体现\"少即是多\"的设计哲学。";
            case "cyberpunk" -> "【视觉风格】赛博朋克：深色背景配霓虹灯光（紫/青/粉）；发光线条与光效；金属质感；高对比度；科技感十足的未来都市氛围。";
            case "custom" -> styleText != null && !styleText.isBlank() ? "【视觉风格·自定义】" + styleText : "";
            default -> "";
        };
        String template = loadPrompt("prompt/kai-pin-analysis-user.txt", FALLBACK_USER_TEMPLATE);
        return template.formatted(
                productA == null || productA.isBlank() ? "（未提供文字，请优先从上传图片分析产品外观）" : productA,
                selling == null || selling.isBlank() ? "（未提供，按图片外观自行提炼设计取向）" : selling,
                productB == null || productB.isBlank() ? "" : "补充描述：" + productB,
                (focusPrompt + "\n" + (stylePrompt.isEmpty() ? "" : stylePrompt)).trim()
        );
    }

    private static final String FALLBACK_USER_TEMPLATE = """
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
            2. 输出必须是严格 JSON 数组，格式：[{"key":"维度名","value":"分析内容"}]。不要 markdown，不要代码块，不要解释。
            """;

    private String requestGeminiAnalysis(String prompt, File imageA, File imageB, boolean strictRetry) throws IOException {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Gemini API Key 未配置(kaipin)");
        String systemText = loadPrompt("prompt/kai-pin-analysis-system.txt", FALLBACK_SYSTEM_TEXT);
        if (strictRetry) {
            systemText += "\n\n重要：本轮不是资料完整性诊断。即使图片或文字信息不完整，也必须输出\"核心卖点清单\"加 7 个固定维度字段，禁止输出\"异常说明\"\"请补充信息\"\"无法分析\"。固定选项字段只输出选项词，不要写解释。";
        }
        ArrayNode userContent = objectMapper.createArrayNode();
        userContent.addObject().put("type", "text").put("text", prompt);
        appendImage(userContent, imageA);
        appendImage(userContent, imageB);
        String requestJson = buildChatRequest(ANALYSIS_MODEL, systemText, userContent);
        Request request = new Request.Builder()
                .url(baseUrl + "/v1/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestJson, JSON_TYPE))
                .build();
        return executeWithRetry(request, "开品融合分析");
    }

    private static final String FALLBACK_SYSTEM_TEXT =
            "你是产品外观设计分析师和电商开品视觉策略师。你必须严格按照固定 7 个维度分析产品外观：几何结构、体量感、仿生学元素、模块化程度、主色调、辅色、风格标签。"
                    + "体量感只能从轻盈/厚重/悬浮感中选择；模块化程度只能从一体成型/可拆卸/堆叠式设计中选择；风格标签只能从科技极简/复古怀旧/赛博朋克/可爱治愈中选择。"
                    + "必须只返回严格 JSON 数组，格式为 [{\"key\":\"维度名\",\"value\":\"分析内容\"}]。";

    private void appendImage(ArrayNode userContent, File image) throws IOException {
        if (image == null || !image.exists() || !image.isFile()) return;
        byte[] bytes = Files.readAllBytes(image.toPath());
        String dataUrl = "data:" + getMimeType(image.getName()) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        userContent.addObject().put("type", "image_url").putObject("image_url").put("url", dataUrl);
    }

    private String buildChatRequest(String model, String systemText, ArrayNode userContent) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model);
        root.put("max_tokens", MAX_TOKENS);
        root.put("temperature", TEMPERATURE);
        ArrayNode messages = root.putArray("messages");
        if (systemText != null && !systemText.isBlank())
            messages.addObject().put("role", "system").put("content", systemText);
        messages.addObject().put("role", "user").set("content", userContent);
        return root.toString();
    }

    private String executeWithRetry(Request request, String label) throws IOException {
        int[] delays = {3, 6};
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Response response = httpClient.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (response.isSuccessful()) return extractOutputText(body);
                int code = response.code();
                if (code != 429 && code != 500 && code != 503 || attempt == 2)
                    throw new RuntimeException(label + " 失败(" + code + "): " + body);
                log.warn("[开品分析] {} 返回 {}，{}s 后重试", label, code, delays[Math.min(attempt, 1)]);
                Thread.sleep(delays[Math.min(attempt, 1)] * 1000L);
            } catch (IOException e) {
                if (attempt == 2) throw e;
                log.warn("[开品分析] 网络异常，3s 后重试: {}", e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        throw new RuntimeException(label + " 超过最大重试次数");
    }

    private String extractOutputText(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            String text = choices.get(0).path("message").path("content").asText("").trim();
            if (!text.isBlank()) return text;
        }
        throw new IOException("模型未返回文本内容: " + responseBody);
    }

    private List<Map<String, String>> parseAnalysisFields(String rawText) throws IOException {
        String json = stripJsonFence(rawText);
        JsonNode root = objectMapper.readTree(json);
        if (!root.isArray()) throw new IOException("模型未返回 JSON 数组: " + rawText);
        List<Map<String, String>> fields = new ArrayList<>();
        for (JsonNode item : root) {
            String key = item.path("key").asText("").trim();
            String value = item.path("value").asText("").trim();
            if (!key.isBlank() && !value.isBlank()) fields.add(Map.of("key", key, "value", value));
        }
        if (fields.isEmpty()) throw new IOException("分析字段为空: " + rawText);
        return fields;
    }

    private String stripJsonFence(String text) {
        if (text == null) return "[]";
        String s = text.trim();
        if (s.startsWith("```")) { s = s.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", ""); }
        int start = s.indexOf('['), end = s.lastIndexOf(']');
        return (start >= 0 && end > start) ? s.substring(start, end + 1) : s;
    }

    private boolean looksLikeRefusal(List<Map<String, String>> fields) {
        if (fields == null || fields.isEmpty()) return true;
        if (fields.size() > 1) return false;
        String text = fields.get(0).getOrDefault("key", "") + " " + fields.get(0).getOrDefault("value", "");
        return text.contains("异常") || text.contains("无法") || text.contains("不能")
                || text.contains("缺少") || text.contains("未提供") || text.contains("请补充")
                || text.contains("重新发起") || text.contains("重试");
    }

    private List<Map<String, String>> buildFallbackFields(String productA) {
        String subject = productA != null && !productA.isBlank()
                ? productA.substring(0, Math.min(50, productA.length())) : "该产品";
        return List.of(
                Map.of("key", "几何结构", "value", subject + "以清晰主轮廓为基础，重点观察外壳几何、边角半径、转折面、开孔与局部组件层级；后续设计应保持主体比例稳定。"),
                Map.of("key", "体量感", "value", "轻盈"),
                Map.of("key", "仿生学元素", "value", "无明显仿生，偏几何/工程化表达。"),
                Map.of("key", "模块化程度", "value", "一体成型"),
                Map.of("key", "主色调", "value", "中性白"),
                Map.of("key", "辅色", "value", "无明显辅色"),
                Map.of("key", "风格标签", "value", "科技极简")
        );
    }

    private List<Map<String, String>> ensureSellingPoints(List<Map<String, String>> fields, String productA, String selling) {
        List<Map<String, String>> normalized = new ArrayList<>();
        boolean has = false;
        if (fields != null) {
            for (Map<String, String> f : fields) {
                if (f == null) continue;
                String key = f.getOrDefault("key", "").trim();
                String value = f.getOrDefault("value", "").trim();
                if (key.isBlank()) continue;
                if ("核心卖点清单".equals(key)) {
                    has = true;
                    if (value.isBlank()) value = buildSellingPointValue(productA, selling);
                }
                normalized.add(Map.of("key", key, "value", value));
            }
        }
        if (!has) normalized.add(0, Map.of("key", "核心卖点清单", "value", buildSellingPointValue(productA, selling)));
        return normalized;
    }

    private String buildSellingPointValue(String productA, String selling) {
        String subject = productA == null || productA.isBlank() ? "该产品"
                : productA.trim().substring(0, Math.min(36, productA.trim().length()));
        String source = selling == null || selling.isBlank() ? "根据白底图可见的结构、材质、体量和使用方式主动提炼卖点" : selling.trim();
        return "1. 核心购买理由：" + source + "\n2. 使用痛点转译：" + subject + "需要让用户一眼看出好用、耐用或更省心。\n3. 视觉记忆点：围绕卖点变化场景和构图。\n4. 商业转化证据：把卖点转成可被买家直接感知的画面证据。";
    }

    private String loadPrompt(String resourcePath, String fallback) {
        try (var in = KaipinAnalysisService.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) return fallback;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceFirst("^﻿", "").stripTrailing();
        } catch (Exception e) { return fallback; }
    }

    private String getMimeType(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }
}

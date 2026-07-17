package com.gofu.cloud.controller;

import com.gofu.cloud.service.context.ContextService;
import com.gofu.cloud.service.lytext.LyTextService;
import com.gofu.shared.context.ProductContext;
import com.gofu.shared.context.SkuItem;
import com.gofu.shared.context.SkuPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 双轨打通端点（M5，小端点前端串）。读写 ProductContext，串起视觉流→结构流数据链。
 *
 * <p>典型调用序：
 * 1) POST /api/gen/selling-points  提卖点 → 写 ProductContext.visual.sellingPoints
 * 2) POST /api/gen/sku-plans        读卖点反哺 → 写 ProductContext.structure.plans
 * 3) POST /api/gen/title            生成标题 → 写 ProductContext.visual.title
 */
@RestController
@RequestMapping("/api/gen")
public class DualTrackController {

    private static final Logger log = LoggerFactory.getLogger(DualTrackController.class);

    private final LyTextService lyTextService;
    private final ContextService contextService;

    public DualTrackController(LyTextService lyTextService, ContextService contextService) {
        this.lyTextService = lyTextService;
        this.contextService = contextService;
    }

    /**
     * 卖点提取（M5b）：从标题/产品/图提取核心卖点，写入 context.visual.sellingPoints。
     * 入参：{ contextId, title?, productType?, imagePaths?[] }
     * 出参：{ sellingPoints:[...] }
     */
    @PostMapping("/selling-points")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> sellingPoints(@RequestBody Map<String, Object> body) {
        String contextId = (String) body.get("contextId");
        String title = (String) body.getOrDefault("title", "");
        String productType = (String) body.getOrDefault("productType", "");
        List<String> imagePaths = (List<String>) body.getOrDefault("imagePaths", List.of());

        // 标题缺省时从 context 取
        ProductContext ctx = contextId == null ? null : contextService.findById(contextId);
        if ((title == null || title.isBlank()) && ctx != null) title = ctx.getVisual().getTitle();

        List<String> points = lyTextService.extractSellingPoints(title, productType, imagePaths);

        if (ctx != null) {
            ctx.getVisual().setSellingPoints(points);
            contextService.save(ctx);
        }
        return ResponseEntity.ok(Map.of("sellingPoints", points));
    }

    /**
     * SKU 规划（M5c，双轨打通核心）：读 context.visual.sellingPoints 反哺，产出写 context.structure.plans。
     * 入参：{ contextId, category, productName?, brand?, material?, planCount?, skus:[...] }
     * 出参：LLM 原始 plans 结果 + 已写入 context 的方案数。
     */
    @PostMapping("/sku-plans")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> skuPlans(@RequestBody Map<String, Object> body) {
        String contextId = (String) body.get("contextId");
        ProductContext ctx = contextId == null ? null : contextService.findById(contextId);

        // 从 context 读卖点反哺（核心：结构流读视觉流产物）
        if (ctx != null && !ctx.getVisual().getSellingPoints().isEmpty()) {
            body.put("sellingPoints", ctx.getVisual().getSellingPoints());
        }

        Map<String, Object> result;
        try {
            result = lyTextService.generateSkuPlans(body);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "SKU 规划失败：" + e.getMessage()));
        }

        // 拍平 LLM 二维产出（mainItems×models）为带结构的 plans[].items[] 写回 context（M6 补链）
        if (ctx != null) {
            List<SkuPlan> plans = lyTextService.flattenPlans(result);
            ctx.getStructure().setPlans(plans);
            contextService.save(ctx);
            result.put("savedPlanCount", plans.size());
            result.put("savedItemCount", plans.stream().mapToInt(p -> p.getItems().size()).sum());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 标题生成（M5a 迁入）：三种策略之一，写入 context.visual.title。
     * 入参：{ contextId, mode(ai|vision|titlelib), category/productType, productName, brand, material, skuNames[], mainImgPaths[] }
     */
    @PostMapping("/title")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> title(@RequestBody Map<String, Object> body) {
        String contextId = (String) body.get("contextId");
        String mode = (String) body.getOrDefault("mode", "titlelib");
        String category = (String) body.getOrDefault("category", "");
        String productType = (String) body.getOrDefault("productType", category);
        String productName = (String) body.getOrDefault("productName", "");
        String brand = (String) body.getOrDefault("brand", "");
        String material = (String) body.getOrDefault("material", "");
        List<String> skuNames = (List<String>) body.getOrDefault("skuNames", List.of());
        List<String> mainImgPaths = (List<String>) body.getOrDefault("mainImgPaths", List.of());

        Map<String, Object> result = switch (mode) {
            case "ai" -> lyTextService.prepareWithAI(productType, productName, brand, skuNames);
            case "vision" -> lyTextService.prepareWithVision(category, material, brand, skuNames, mainImgPaths);
            default -> lyTextService.prepareFromTitleLib(category, material, brand, skuNames);
        };

        if (contextId != null) {
            ProductContext ctx = contextService.findById(contextId);
            if (ctx != null && result.get("title") != null) {
                ctx.getVisual().setTitle(String.valueOf(result.get("title")));
                contextService.save(ctx);
            }
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 导入重构：把上传的 sku 成品图按尺寸名次挂到选定方案的 item.imgDir。
     * 入参：{ contextId, skuImages:[{name(文件名,带尺寸),ref(已上传URL)}] }。
     * 匹配：方案 item 与 sku 图各自从(名/尺寸)提数字、各自升序、按名次一一配(容忍AI名与文件名刻度差)。
     * 数量不齐时按序尽量配，不乱配剩余。
     */
    @PostMapping("/attach-sku-images")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> attachSkuImages(@RequestBody Map<String, Object> body) {
        String contextId = (String) body.get("contextId");
        List<Map<String, Object>> skuImages = (List<Map<String, Object>>) body.getOrDefault("skuImages", List.of());
        ProductContext ctx = contextId == null ? null : contextService.findById(contextId);
        if (ctx == null) return ResponseEntity.badRequest().body(Map.of("error", "context 不存在"));
        int planIdx = Math.max(0, ctx.getStructure().getSelectedPlanIndex());
        List<SkuPlan> plans = ctx.getStructure().getPlans();
        if (plans == null || plans.isEmpty() || planIdx >= plans.size())
            return ResponseEntity.ok(Map.of("attached", 0, "note", "无方案可挂"));
        List<SkuItem> items = plans.get(planIdx).getItems();

        // H4修：各自提尺寸。仅在【数量相等 + 两边每个都能提到真实尺寸】时才按名次配(对齐 rankPairWhites 的保护)，
        // 否则宁可不挂也不乱挂——原来数量不等按 min 静默截断、提不到尺寸并列 MAX_VALUE 按上传序瞎配，会错挂图。
        List<int[]> itemRank = new ArrayList<>();   // [itemIndex, size]
        for (int i = 0; i < items.size(); i++) {
            Integer s = sizeOf(items.get(i).getSkuDisplayName());
            if (s == null) s = sizeOf(items.get(i).getSpec1());
            if (s == null) s = sizeOf(items.get(i).getItemCode());
            if (s != null) itemRank.add(new int[]{i, s});
        }
        List<Object[]> imgRank = new ArrayList<>();  // [ref, size]
        for (Map<String, Object> im : skuImages) {
            Integer s = sizeOf(String.valueOf(im.get("name")));
            if (s != null) imgRank.add(new Object[]{String.valueOf(im.get("ref")), s});
        }
        if (itemRank.size() != items.size() || imgRank.size() != skuImages.size()
                || itemRank.size() != imgRank.size() || itemRank.isEmpty()) {
            log.warn("[导入·挂SKU图] 尺寸配对放弃(方案SKU={} 可提尺寸={}, sku图={} 可提尺寸={}) → 不乱挂,留空待人工/生成",
                    items.size(), itemRank.size(), skuImages.size(), imgRank.size());
            return ResponseEntity.ok(Map.of("attached", 0,
                    "note", "SKU数与图数不等或尺寸提取不全，未自动挂图（避免错挂），可人工指定或走生成"));
        }
        itemRank.sort(java.util.Comparator.comparingInt(a -> a[1]));
        imgRank.sort(java.util.Comparator.comparingInt(a -> (int) a[1]));
        int attached = 0;
        for (int k = 0; k < itemRank.size(); k++) {
            items.get(itemRank.get(k)[0]).setImgDir((String) imgRank.get(k)[0]);
            attached++;
        }
        contextService.save(ctx);
        log.info("[导入·挂SKU图] contextId={} 方案SKU={} sku图={} 挂上={}", contextId, items.size(), skuImages.size(), attached);
        return ResponseEntity.ok(Map.of("attached", attached));
    }

    /** 从字符串提尺寸数字：优先 \d+CM/厘米，退化取 2~3 位数字；提不到 null。 */
    private Integer sizeOf(String s) {
        if (s == null || s.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{2,3})\\s*(?:CM|cm|厘米)").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = java.util.regex.Pattern.compile("(?<!\\d)(\\d{2,3})(?!\\d)").matcher(s);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }
}

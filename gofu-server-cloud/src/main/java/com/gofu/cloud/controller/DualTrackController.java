package com.gofu.cloud.controller;

import com.gofu.cloud.service.context.ContextService;
import com.gofu.cloud.service.lytext.LyTextService;
import com.gofu.shared.context.ProductContext;
import com.gofu.shared.context.SkuPlan;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}

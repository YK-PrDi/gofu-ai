package com.gofu.cloud.controller;

import com.gofu.cloud.service.CosService;
import com.gofu.cloud.service.context.ContextService;
import com.gofu.cloud.service.lyimage.ImageGenService;
import com.gofu.cloud.service.lyimage.PromptTemplateService;
import com.gofu.shared.context.ProductContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LY 生图线收敛入口（花洒防比价 / 6 模式）。与 ele 生图线的 {@link GenController} 隔离。
 *
 * <p>路径 /api/ly-gen/*，请求/响应契约与 LY-Automation 原 /api/listing/{gen-sku-images,analyze-bg}
 * 保持一致——本地 cloudgw 直接对接，未来上游同步最省。
 *
 * <p>产物当前返回云端本地路径（sku-gen/<batch>/）。接 ProductContext + COS 永久 key（ADR-008）
 * 留到 M5 双轨打通时统一处理。
 */
@RestController
@RequestMapping("/api/ly-gen")
public class LyGenController {

    private static final Logger log = LoggerFactory.getLogger(LyGenController.class);

    private final ImageGenService imageGenService;
    private final PromptTemplateService templateService;
    private final CosService cosService;
    private final ContextService contextService;

    public LyGenController(ImageGenService imageGenService, PromptTemplateService templateService,
                           CosService cosService, ContextService contextService) {
        this.imageGenService = imageGenService;
        this.templateService = templateService;
        this.cosService = cosService;
        this.contextService = contextService;
    }

    /**
     * 批量生成 SKU 图（花洒防比价核心）。
     * 入参：{ refImagePath, productType, bagImagePath, waterImagePath, accImagePaths[],
     *        skus:[{idx,name,compDesc,itemCode,whiteImgPath,accParts[]}], templateId, batch, bgStyle }
     * 出参：{ images:[{name, idx, path | error}] }
     */
    @PostMapping("/sku-images")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> genSkuImages(@RequestBody Map<String, Object> body) {
        try {
            String refImagePath = (String) body.getOrDefault("refImagePath", "");
            String productType  = (String) body.getOrDefault("productType", "");
            String bagImagePath = (String) body.getOrDefault("bagImagePath", "");
            String waterImagePath = (String) body.getOrDefault("waterImagePath", "");
            List<String> accImagePaths = (List<String>) body.getOrDefault("accImagePaths", List.of());
            List<Map<String, Object>> skus = (List<Map<String, Object>>) body.getOrDefault("skus", List.of());
            String templateId = (String) body.getOrDefault("templateId", "");
            String batch = String.valueOf(body.getOrDefault("batch", ""));
            if (batch == null || batch.isBlank() || "null".equals(batch)) {
                batch = String.valueOf(System.currentTimeMillis());
            }

            String bgStyle = (String) body.getOrDefault("bgStyle", "");
            if (bgStyle == null || bgStyle.isBlank()) {
                bgStyle = imageGenService.analyzeBackgroundStyleOnce(refImagePath);
            }

            // M5d：生图产物写回 ProductContext（若带 contextId）
            String contextId = (String) body.get("contextId");
            ProductContext ctx = (contextId == null || contextId.isBlank()) ? null : contextService.findById(contextId);

            List<Map<String, Object>> images = new java.util.ArrayList<>();
            int loop = 0;
            for (Map<String, Object> s : skus) {
                String name = String.valueOf(s.getOrDefault("name", ""));
                String comp = String.valueOf(s.getOrDefault("compDesc", ""));
                Object idx  = s.getOrDefault("idx", loop);
                int seq;
                try { seq = Integer.parseInt(String.valueOf(idx)) + 1; } catch (Exception ex) { seq = loop + 1; }
                Map<String, Object> item = new java.util.LinkedHashMap<>();
                item.put("name", name);
                item.put("idx", idx);
                try {
                    String whiteImgPath = String.valueOf(s.getOrDefault("whiteImgPath", ""));
                    String itemCode = String.valueOf(s.getOrDefault("itemCode", ""));
                    List<Map<String, Object>> accParts = (List<Map<String, Object>>) s.getOrDefault("accParts", List.of());
                    log.info("[LY生图配件] seq={} idx={} name=「{}」 accParts={}", seq, idx, name, accParts);
                    String path = imageGenService.generateSkuImage(refImagePath, name, comp, productType,
                            batch, seq, bagImagePath, whiteImgPath, accImagePaths, waterImagePath,
                            bgStyle, itemCode, accParts, templateId);
                    item.put("path", path);
                    // M5d：配 COS 则存永久 key（ADR-008）写回 context.visual.mainImages；否则存本地路径
                    if (ctx != null) {
                        String imageRef = path;
                        if (cosService.isEnabled()) {
                            try {
                                imageRef = cosService.upload(new File(path), UUID.randomUUID() + ".jpg");
                                item.put("cosKey", imageRef);
                            } catch (Exception ce) {
                                log.warn("COS 上传失败，context 存本地路径: {}", ce.getMessage());
                            }
                        }
                        ctx.getVisual().getMainImages().add(imageRef);
                    }
                } catch (Exception e) {
                    item.put("error", e.getMessage());
                }
                images.add(item);
                loop++;
            }
            if (ctx != null) contextService.save(ctx);  // M5d：整批生图后一次性写回 context
            return ResponseEntity.ok(Map.of("images", images));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "LY 生图失败：" + e.getMessage()));
        }
    }

    /** 分析主图背景风格（整批生图前调一次，前端分发给并发 SKU 保证背景一致）。 */
    @PostMapping("/analyze-bg")
    public ResponseEntity<Map<String, Object>> analyzeBg(@RequestBody Map<String, Object> body) {
        String refImagePath = (String) body.getOrDefault("refImagePath", "");
        String bgStyle = imageGenService.analyzeBackgroundStyleOnce(refImagePath);
        return ResponseEntity.ok(Map.of("bgStyle", bgStyle == null ? "" : bgStyle));
    }

    /** 防比价模板库读取（前端下拉/编辑用）。 */
    @GetMapping("/antiprice-templates")
    public ResponseEntity<String> getAntiPriceTemplates() {
        return ResponseEntity.ok()
                .header("Content-Type", "application/json; charset=UTF-8")
                .body(templateService.loadJson());
    }
}

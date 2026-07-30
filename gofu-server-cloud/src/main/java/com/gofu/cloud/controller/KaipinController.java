package com.gofu.cloud.controller;

import com.gofu.cloud.service.KaipinAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 开品模式 · 外观融合分析接口。
 * 迁移自 ele-business-java / KaipinController（解耦独立版）。
 * 物理隔离：此 Controller 及 KaipinAnalysisService 可整体删除。
 *
 * <p>端点：POST /api/kaipin_analyze（multipart/form-data）
 */
@RestController
public class KaipinController {

    private static final Logger log = LoggerFactory.getLogger(KaipinController.class);

    private final KaipinAnalysisService kaipinAnalysisService;

    public KaipinController(KaipinAnalysisService kaipinAnalysisService) {
        this.kaipinAnalysisService = kaipinAnalysisService;
    }

    /**
     * 开品模式外观分析（两步流程第一步）。
     * 入参见接口文档；返回 {fields:[{key,value},...]}，首项固定为「核心卖点清单」。
     */
    @PostMapping("/api/kaipin_analyze")
    public ResponseEntity<Map<String, Object>> kaipinAnalyze(
            @RequestParam(value = "imageA", required = false) MultipartFile imageA,
            @RequestParam(value = "imageB", required = false) MultipartFile imageB,
            @RequestParam(value = "productA", defaultValue = "") String productA,
            @RequestParam(value = "productB", defaultValue = "") String productB,
            @RequestParam(value = "selling", defaultValue = "") String selling,
            @RequestParam(value = "focus", defaultValue = "cost") String focus,
            @RequestParam(value = "focusText", required = false) String focusText,
            @RequestParam(value = "style", defaultValue = "") String style,
            @RequestParam(value = "styleText", required = false) String styleText) {

        boolean hasA = (productA != null && !productA.isBlank()) || (imageA != null && !imageA.isEmpty());
        boolean hasB = (productB != null && !productB.isBlank()) || (imageB != null && !imageB.isEmpty());
        if (!hasA && !hasB) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "请至少提供产品 A 或产品 B 的文字描述或参考图"));
        }

        File imageATmp = null, imageBTmp = null;
        try {
            if (imageA != null && !imageA.isEmpty()) {
                imageATmp = File.createTempFile("kaipin_A_", getSuffix(imageA.getOriginalFilename()));
                imageA.transferTo(imageATmp);
            }
            if (imageB != null && !imageB.isEmpty()) {
                imageBTmp = File.createTempFile("kaipin_B_", getSuffix(imageB.getOriginalFilename()));
                imageB.transferTo(imageBTmp);
            }
            List<Map<String, String>> fields = kaipinAnalysisService.analyze(
                    productA, productB, selling, focus, focusText, style, styleText, imageATmp, imageBTmp);
            return ResponseEntity.ok(Map.of("fields", fields));
        } catch (Exception e) {
            log.error("[开品分析] kaipin_analyze 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            if (imageATmp != null && imageATmp.exists()) imageATmp.delete();
            if (imageBTmp != null && imageBTmp.exists()) imageBTmp.delete();
        }
    }

    private String getSuffix(String filename) {
        if (filename == null) return ".jpg";
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".gif")) return ".gif";
        return ".jpg";
    }
}

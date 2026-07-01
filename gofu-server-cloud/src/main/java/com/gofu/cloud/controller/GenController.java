package com.gofu.cloud.controller;

import com.gofu.cloud.service.CosService;
import com.gofu.cloud.service.ImageGenerationService;
import com.gofu.cloud.service.PromptTemplateLoader;
import com.gofu.cloud.service.context.ContextService;
import com.gofu.shared.context.ProductContext;
import com.gofu.shared.dto.ImageGenRequest;
import com.gofu.shared.dto.ImageGenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * 收敛生图接口（M3d / ADR-002）。本地 cloudgw 只对接这里，不暴露 ele 原来 8 个散乱端点。
 *
 * <p>编排：生图（ImageGenerationService）→ 存永久 COS key（CosService，ADR-008）→
 * 写回 ProductContext.visual（ContextService，ADR-003）。
 */
@RestController
@RequestMapping("/api/gen")
public class GenController {

    private static final Logger log = LoggerFactory.getLogger(GenController.class);

    private final ImageGenerationService imageGenerationService;
    private final CosService cosService;
    private final ContextService contextService;
    private final PromptTemplateLoader promptTemplateLoader;

    public GenController(ImageGenerationService imageGenerationService, CosService cosService,
                         ContextService contextService, PromptTemplateLoader promptTemplateLoader) {
        this.imageGenerationService = imageGenerationService;
        this.cosService = cosService;
        this.contextService = contextService;
        this.promptTemplateLoader = promptTemplateLoader;
    }

    /**
     * 生成一张 SKU 图，产物写回 ProductContext.visual.mainImages（存永久 COS key）。
     */
    @PostMapping("/images")
    public ResponseEntity<ImageGenResponse> generate(@RequestBody ImageGenRequest req) {
        ImageGenResponse resp = new ImageGenResponse();
        try {
            String prompt = buildSkuPrompt(req);
            List<String> refs = (req.getWhiteImgPath() == null || req.getWhiteImgPath().isBlank())
                    ? List.of() : List.of(req.getWhiteImgPath());

            // 输出到临时文件
            File tmp = File.createTempFile("gen-", ".jpg");
            String outputPath = tmp.getAbsolutePath();

            boolean ok = imageGenerationService.generateImageMulti(
                    prompt, refs, null, outputPath, req.getAgentId(), "1:1", null);
            if (!ok) {
                resp.setSuccess(false);
                resp.setError("生图失败（Agent 未生成，检查密钥/配额）");
                return ResponseEntity.ok(resp);
            }

            // 存永久 key（ADR-008），未配 COS 时降级为本地路径
            String imageRef;
            if (cosService.isEnabled()) {
                imageRef = cosService.upload(tmp, UUID.randomUUID() + ".jpg");
            } else {
                imageRef = outputPath;
                log.warn("COS 未启用，ProductContext 暂存本地路径: {}", imageRef);
            }

            // 写回 ProductContext.visual
            if (req.getContextId() != null && !req.getContextId().isBlank()) {
                ProductContext ctx = contextService.findById(req.getContextId());
                if (ctx != null) {
                    ctx.getVisual().getMainImages().add(imageRef);
                    contextService.save(ctx);
                }
            }

            resp.setSuccess(true);
            resp.setImageUrl(imageRef);
            resp.setStickerMode(req.getTemplateId() == null || req.getTemplateId().isBlank()
                    || "sticker".equalsIgnoreCase(req.getTemplateId()));
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("生图编排异常: {}", e.getMessage(), e);
            resp.setSuccess(false);
            resp.setError(e.getMessage());
            return ResponseEntity.ok(resp);
        }
    }

    /**
     * 局部重绘：对已有上下文重新生成单张（对应 ele inpaint 语义的收敛入口）。
     * MVP 复用 /images 逻辑，后续可接 mask。
     */
    @PostMapping("/regenerate")
    public ResponseEntity<ImageGenResponse> regenerate(@RequestBody ImageGenRequest req) {
        return generate(req);
    }

    /**
     * 把永久 COS key 换成短期签名 URL（ADR-008），供展示层按需调用。
     */
    @GetMapping("/sign")
    public ResponseEntity<String> sign(@RequestParam String key) {
        if (!cosService.isEnabled()) {
            return ResponseEntity.ok(key); // 未启用 COS，key 即本地路径
        }
        return ResponseEntity.ok(cosService.signKey(key));
    }

    /** 用 SKU 生图模板拼 prompt。填充 productType/skuName/compDesc。 */
    private String buildSkuPrompt(ImageGenRequest req) {
        String tpl = promptTemplateLoader.load("prompt/image-sku-generation.txt",
                "Product photography on pure white background. SKU: {{skuName}}. {{compDesc}}");
        return tpl.replace("{{productType}}", "Shower Head")
                  .replace("{{skuName}}", req.getName() == null ? "" : req.getName())
                  .replace("{{compDesc}}", req.getCompDesc() == null ? "" : req.getCompDesc());
    }
}

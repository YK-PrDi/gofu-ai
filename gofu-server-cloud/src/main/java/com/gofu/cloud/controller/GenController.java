package com.gofu.cloud.controller;

import com.gofu.cloud.service.CosService;
import com.gofu.cloud.service.ImageGenerationService;
import com.gofu.cloud.service.PromptTemplateLoader;
import com.gofu.cloud.service.agent.GptImageAgent;
import com.gofu.cloud.service.context.ContextService;
import com.gofu.shared.context.ProductContext;
import com.gofu.shared.dto.ImageGenRequest;
import com.gofu.shared.dto.ImageGenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.Map;
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
    private final GptImageAgent gptImageAgent;

    public GenController(ImageGenerationService imageGenerationService, CosService cosService,
                         ContextService contextService, PromptTemplateLoader promptTemplateLoader,
                         GptImageAgent gptImageAgent) {
        this.imageGenerationService = imageGenerationService;
        this.cosService = cosService;
        this.contextService = contextService;
        this.promptTemplateLoader = promptTemplateLoader;
        this.gptImageAgent = gptImageAgent;
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

            // 存永久 key（ADR-008），未配 COS 或上传失败(如账户欠费451)时降级为本地路径(07.08修:不因COS失败丢图)
            String imageRef = outputPath;
            if (cosService.isEnabled()) {
                try {
                    imageRef = cosService.upload(tmp, UUID.randomUUID() + ".jpg");
                } catch (Exception ce) {
                    log.warn("COS 上传失败，暂存本地路径: {}", ce.getMessage());
                }
            } else {
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
     * 局部重绘（M8-B2）：原图 + 蒙版 + 指令 → 单张重绘，同步返回。包装 GptImageAgent.generateWithMask。
     * 供预览页对单张图做局部编辑。multipart: image, mask, prompt, aspect?。
     * 返回 {@code { imageRef, signedUrl }}：imageRef 是 COS key(或本地路径)，signedUrl 可直接展示。
     */
    @PostMapping("/inpaint")
    public ResponseEntity<Map<String, Object>> inpaint(@RequestParam("image") MultipartFile image,
                                                       @RequestParam("mask") MultipartFile mask,
                                                       @RequestParam("prompt") String prompt,
                                                       @RequestParam(value = "aspect", defaultValue = "auto") String aspect) {
        File imgTmp = null, maskTmp = null, outTmp = null;
        try {
            imgTmp = File.createTempFile("inpaint-img-", ".png");
            maskTmp = File.createTempFile("inpaint-mask-", ".png");
            outTmp = File.createTempFile("inpaint-out-", ".png");
            image.transferTo(imgTmp);
            mask.transferTo(maskTmp);

            boolean ok = gptImageAgent.generateWithMask(prompt, imgTmp, maskTmp, outTmp.getAbsolutePath(), aspect);
            if (!ok) {
                return ResponseEntity.internalServerError().body(Map.of("error", "局部重绘失败（检查密钥/配额）"));
            }

            // COS 上传失败(如欠费451)回退本地路径,不丢图(07.08修)
            String imageRef = outTmp.getAbsolutePath();
            boolean uploaded = false;
            if (cosService.isEnabled()) {
                try { imageRef = cosService.upload(outTmp, UUID.randomUUID() + ".png"); uploaded = true; }
                catch (Exception ce) { log.warn("COS 上传失败，返回本地路径: {}", ce.getMessage()); }
            }
            String signedUrl = uploaded ? cosService.signKey(imageRef) : imageRef;
            return ResponseEntity.ok(Map.of("imageRef", imageRef, "signedUrl", signedUrl));
        } catch (Exception e) {
            log.error("局部重绘异常: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } finally {
            if (imgTmp != null) imgTmp.delete();
            if (maskTmp != null) maskTmp.delete();
            // outTmp 已上传 COS 或作为本地路径返回，不删
        }
    }

    /** 列出可用生图 Agent（M9：前端模型下拉用，经 cloudgw 转发到 5021）。 */
    @GetMapping("/agents")
    public ResponseEntity<List<Map<String, String>>> agents() {
        return ResponseEntity.ok(imageGenerationService.listAgents());
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

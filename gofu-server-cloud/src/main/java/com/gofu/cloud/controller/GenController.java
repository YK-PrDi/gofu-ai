package com.gofu.cloud.controller;

import com.gofu.cloud.config.AppProperties;
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
import java.nio.file.Files;
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
    private final AppProperties appProperties;

    public GenController(ImageGenerationService imageGenerationService, CosService cosService,
                         ContextService contextService, PromptTemplateLoader promptTemplateLoader,
                         GptImageAgent gptImageAgent, AppProperties appProperties) {
        this.imageGenerationService = imageGenerationService;
        this.cosService = cosService;
        this.contextService = contextService;
        this.promptTemplateLoader = promptTemplateLoader;
        this.gptImageAgent = gptImageAgent;
        this.appProperties = appProperties;
    }

    /**
     * 通用图片上传（07.08）：本地导入的白底图需回传快麦(快麦 picPath 要 URL 不收二进制)，
     * 先把图上传 COS 拿公网 URL。入参 {@code {dataUrl: "data:image/..;base64,.."} 或 {base64,ext}}。
     * 返回 {@code {imageRef(COS key), signedUrl(可公网访问)}}。COS 未启用/失败则报错(回传本就需要 URL)。
     */
    @PostMapping("/upload-image")
    public ResponseEntity<Map<String, Object>> uploadImage(@RequestBody Map<String, Object> body) {
        try {
            String dataUrl = String.valueOf(body.getOrDefault("dataUrl", ""));
            String b64; String ext = "jpg";
            if (dataUrl.startsWith("data:")) {
                int comma = dataUrl.indexOf(',');
                String head = dataUrl.substring(5, comma);   // image/png;base64
                if (head.contains("png")) ext = "png";
                b64 = dataUrl.substring(comma + 1);
            } else {
                b64 = String.valueOf(body.getOrDefault("base64", ""));
                if ("png".equalsIgnoreCase(String.valueOf(body.getOrDefault("ext", "")))) ext = "png";
            }
            if (b64.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "无图片数据"));
            if (!cosService.isEnabled()) return ResponseEntity.badRequest().body(Map.of("error", "COS 未启用，无法生成公网 URL 供回传"));
            byte[] bytes = java.util.Base64.getDecoder().decode(b64);
            File tmp = File.createTempFile("upload-", "." + ext);
            java.nio.file.Files.write(tmp.toPath(), bytes);
            // 永久公网 URL：快麦会长期存 picPath，不能用 7 天签名 URL
            String publicUrl = cosService.uploadPublic(tmp, UUID.randomUUID() + "." + ext);
            tmp.delete();
            return ResponseEntity.ok(Map.of("imageRef", publicUrl, "signedUrl", publicUrl));
        } catch (Exception e) {
            log.error("图片上传 COS 失败: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "上传失败：" + e.getMessage()));
        }
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
        // #4 根因B：COS 启用但生图产物上传失败会回退存**本地绝对路径**(FlowController.uploadIfCos)。
        // 若对本地路径调 signKey 会返回指向不存在对象的坏签名URL→前端黑图。故先识别本地路径原样返回，
        // 让前端走 /api/gen/local-image 兜底。COS key 形如 "目录/名"(无盘符、无反斜杠、非绝对路径)。
        if (!cosService.isEnabled() || isLocalPath(key)) {
            return ResponseEntity.ok(key);
        }
        return ResponseEntity.ok(cosService.signKey(key));
    }

    /** 判断 ref 是本地文件绝对路径(Windows 盘符/反斜杠 或 已存在的文件)而非 COS key。 */
    private boolean isLocalPath(String key) {
        if (key == null || key.isBlank()) return false;
        if (key.contains("\\") || key.matches("^[A-Za-z]:.*")) return true;  // Windows 盘符/反斜杠
        if (key.startsWith("/")) return true;                                 // *nix 绝对路径
        return false;
    }

    /**
     * #4 预览图：COS 不可用时生图产物 ref 是**云端进程目录**下的本地绝对路径，
     * 本地 5021 的 /api/erp/local-image 读不到云端目录(且不在其安全基目录内→404 空白)。
     * 故云端自己提供图片服务：读 output-dir/temp/history 内的文件返回字节，前端经 cloudgw 转发到这里。
     * 安全：canonical 路径必须落在这三个允许目录之内，否则 403，防路径穿越。
     */
    @GetMapping("/local-image")
    public ResponseEntity<byte[]> localImage(@RequestParam String path) {
        try {
            File f = new File(path).getCanonicalFile();
            if (!f.isFile()) return ResponseEntity.notFound().build();
            AppProperties.Paths p = appProperties.getPaths();
            boolean allowed = false;
            for (String dir : new String[]{p.getOutputDir(), p.getTempOutputDir(), p.getHistoryRefsDir()}) {
                if (dir == null || dir.isBlank()) continue;
                if (f.getPath().startsWith(new File(dir).getCanonicalFile().getPath())) { allowed = true; break; }
            }
            if (!allowed) { log.warn("local-image 拒绝越界路径: {}", path); return ResponseEntity.status(403).build(); }
            String name = f.getName().toLowerCase();
            String ct = name.endsWith(".png") ? "image/png" : "image/jpeg";
            return ResponseEntity.ok()
                    .header("Content-Type", ct)
                    .body(Files.readAllBytes(f.toPath()));
        } catch (Exception e) {
            log.warn("local-image 读取失败({}): {}", path, e.getMessage());
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * #1 统一预览图代理：前端一切预览图(主图/详情/SKU/白底)都走这里，不再直连 COS。
     * - ref 是本地绝对路径 → 读云端产物目录返回(复用 localImage 的安全校验)。
     * - ref 是 COS key / COS URL → 用凭证服务端 getObject 取字节(绕过防盗链/ACL——桶开了防盗链时浏览器直连会 403 图变黑，这是 #1 根因)。
     */
    @GetMapping("/img")
    public ResponseEntity<byte[]> img(@RequestParam String ref) {
        if (ref == null || ref.isBlank()) return ResponseEntity.notFound().build();
        if (isLocalPath(ref)) return localImage(ref);   // 本地路径走读盘(含越界校验)
        if (!cosService.isEnabled()) {
            // COS 未启用却是 http URL：无凭证代理，让浏览器自行直连(退化)
            return ResponseEntity.status(302).header("Location", ref).build();
        }
        try {
            byte[] bytes = cosService.fetch(ref);
            String lower = ref.toLowerCase();
            String ct = lower.contains(".png") ? "image/png" : "image/jpeg";
            return ResponseEntity.ok()
                    .header("Content-Type", ct)
                    .header("Cache-Control", "max-age=3600")
                    .body(bytes);
        } catch (Exception e) {
            log.warn("img 代理取 COS 失败({}): {}", ref, e.getMessage());
            return ResponseEntity.status(502).build();
        }
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

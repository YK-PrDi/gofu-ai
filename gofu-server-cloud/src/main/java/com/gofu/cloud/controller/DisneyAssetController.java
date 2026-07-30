package com.gofu.cloud.controller;

import com.gofu.cloud.service.DisneyAssetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 迪士尼素材库管理接口。
 * 物理隔离：此 Controller 及 DisneyAssetService/Entity 可整体删除。
 *
 * <p>端点：
 * POST /api/disney/import  — 批量导入（脚本调用，不建前端页面）
 * GET  /api/disney/tags    — 标签列表
 * GET  /api/disney/sample  — 按标签随机抽 N 张
 */
@RestController
@RequestMapping("/api/disney")
public class DisneyAssetController {

    private static final Logger log = LoggerFactory.getLogger(DisneyAssetController.class);

    private final DisneyAssetService service;

    public DisneyAssetController(DisneyAssetService service) {
        this.service = service;
    }

    /**
     * 批量导入素材图片。入参：files[]=图片文件（可多张），tag=标签名（如"米奇"）。
     * 每张上传 COS + 写元数据表。返回 {imported, failed, errors}。
     */
    @PostMapping("/import")
    public ResponseEntity<Map<String, Object>> importAssets(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("tag") String tag) {

        if (tag == null || tag.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "tag 不能为空"));

        int imported = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            File tmp = null;
            try {
                String ext = getExt(f.getOriginalFilename());
                tmp = File.createTempFile("disney_import_", ext);
                f.transferTo(tmp);
                service.importAsset(tmp, f.getOriginalFilename(), tag);
                imported++;
            } catch (Exception e) {
                failed++;
                errors.add(f.getOriginalFilename() + ": " + e.getMessage());
                log.warn("[迪士尼导入] 失败 {}: {}", f.getOriginalFilename(), e.getMessage());
            } finally {
                if (tmp != null && tmp.exists()) tmp.delete();
            }
        }
        log.info("[迪士尼导入] tag={} 导入{}张, 失败{}张", tag, imported, failed);
        return ResponseEntity.ok(Map.of("imported", imported, "failed", failed, "errors", errors));
    }

    /** 返回所有标签列表及各标签素材数量 */
    @GetMapping("/tags")
    public ResponseEntity<Map<String, Object>> listTags() {
        List<String> tags = service.listTags();
        List<Map<String, Object>> result = tags.stream().map(t ->
                Map.<String, Object>of("tag", t, "count", service.count(t))
        ).toList();
        return ResponseEntity.ok(Map.of("tags", result));
    }

    /**
     * 按标签随机抽 N 张素材，返回 [{id,tag,cosKey,signedUrl}]。
     * n 默认 3，最大 20。
     */
    @GetMapping("/sample")
    public ResponseEntity<Map<String, Object>> sample(
            @RequestParam("tag") String tag,
            @RequestParam(value = "n", defaultValue = "3") int n) {

        if (tag == null || tag.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "tag 不能为空"));
        n = Math.min(Math.max(1, n), 20);
        List<Map<String, String>> samples = service.sample(tag, n);
        return ResponseEntity.ok(Map.of("tag", tag, "n", n, "samples", samples));
    }

    /** 清空所有素材（重新导入时用）。 */
    @DeleteMapping("/all")
    public ResponseEntity<Map<String, Object>> deleteAll() {
        service.deleteAll();
        return ResponseEntity.ok(Map.of("ok", true, "message", "已清空所有迪士尼素材记录"));
    }

    private String getExt(String filename) {
        if (filename == null) return ".jpg";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".webp")) return ".webp";
        if (lower.endsWith(".gif")) return ".gif";
        return ".jpg";
    }
}

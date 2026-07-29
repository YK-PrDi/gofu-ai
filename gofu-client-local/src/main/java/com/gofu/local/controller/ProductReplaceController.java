package com.gofu.local.controller;

import com.gofu.local.service.listing.ProductReplaceService;
import com.gofu.local.service.listing.StyleImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【权宜模块·产品替换】本地入口。生图质量不足时的临时手段，物理隔离、独立命名，后续删除时一把摘干净。
 *
 * <p>链路：抽卡启动(建context+上传参考图+调云端replace-mains) → 前端轮询进度+云端逐张出图 →
 * 人工筛6 覆写 mainImages → 复用现有 /api/semi-auto/generate-layout + /api/listing/from-context 下游全自动。
 * 下游不在本 controller 重写，前端直接调既有端点，删本模块不影响下游。
 */
@RestController
@RequestMapping("/api/product-replace")
public class ProductReplaceController {

    private final ProductReplaceService service;

    public ProductReplaceController(ProductReplaceService service) {
        this.service = service;
    }

    /**
     * 启动抽卡。入参 {@code { folderName, white:[{name,b64,ext}], refs:[{name,b64,ext}], count?=100 }}。
     * white=产品白底图(要替换进去的主体)；refs=N 张构图参考图(被替换)。异步，返回 replaceId 轮询 /progress。
     */
    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body) {
        String folderName = String.valueOf(body.getOrDefault("folderName", ""));
        if (folderName.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "folderName 不能为空"));
        List<StyleImportService.UpImg> white = toUpImgs(body.get("white"));
        List<StyleImportService.UpImg> refs = toUpImgs(body.get("refs"));
        if (white.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "缺产品白底图(white)"));
        if (refs.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "缺构图参考图(refs，至少 1 张)"));
        int count = body.get("count") instanceof Number n ? Math.max(1, n.intValue()) : 100;
        String replaceId = java.util.UUID.randomUUID().toString();
        service.startAsync(replaceId, folderName, white, refs, count);
        return ResponseEntity.ok(Map.of("replaceId", replaceId));
    }

    /** 抽卡启动进度。done 后带 {contextId, cloudTaskId, total, recognized}；cloudTaskId 供前端轮询云端 /api/flow/task 看逐张出图。 */
    @GetMapping("/progress/{replaceId}")
    public ResponseEntity<?> progress(@PathVariable String replaceId) {
        ProductReplaceService.Progress pg = service.getProgress(replaceId);
        if (pg == null) return ResponseEntity.ok(Map.of("phase", "未知任务", "pct", 0, "done", true, "error", "任务不存在或已过期"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", pg.phase); out.put("pct", pg.pct); out.put("done", pg.done);
        out.put("total", pg.total);
        if (pg.contextId != null) out.put("contextId", pg.contextId);
        if (pg.cloudTaskId != null) out.put("cloudTaskId", pg.cloudTaskId);
        if (pg.recognized != null) out.put("recognized", pg.recognized);
        if (pg.error != null) out.put("error", pg.error);
        return ResponseEntity.ok(out);
    }

    /** 人工筛：把选中的图 key 覆写进 context.visual.mainImages。入参 {@code { contextId, keys:[...] }}。 */
    @PostMapping("/pick")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> pick(@RequestBody Map<String, Object> body) {
        String contextId = String.valueOf(body.getOrDefault("contextId", ""));
        List<String> keys = body.get("keys") instanceof List
                ? ((List<Object>) body.get("keys")).stream().map(String::valueOf).toList() : List.of();
        try {
            service.pickMains(contextId, keys);
            return ResponseEntity.ok(Map.of("ok", true, "picked", keys.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private List<StyleImportService.UpImg> toUpImgs(Object raw) {
        List<StyleImportService.UpImg> out = new ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String name = String.valueOf(m.getOrDefault("name", ""));
            String b64 = String.valueOf(m.getOrDefault("b64", ""));
            String ext = String.valueOf(m.getOrDefault("ext", "jpg"));
            if (!b64.isBlank()) out.add(new StyleImportService.UpImg(name, b64, ext));
        }
        return out;
    }
}

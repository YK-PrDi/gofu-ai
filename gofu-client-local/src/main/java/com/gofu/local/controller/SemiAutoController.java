package com.gofu.local.controller;

import com.gofu.local.model.SemiAutoScan;
import com.gofu.local.service.listing.SemiAutoService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 半自动批量上新接口（P1）。独立于 /api/listing 全自动流。
 *
 * <p>P1 只提供"扫描"能力：扫大文件夹两级遍历、扫单目录图片列表。
 * 后续 SKU 补全（P2）、完整性校验（P3）、多店（P4）、编排（P5）各自加端点。
 */
@RestController
@RequestMapping("/api/semi-auto")
public class SemiAutoController {

    private final SemiAutoService semiAutoService;

    public SemiAutoController(SemiAutoService semiAutoService) {
        this.semiAutoService = semiAutoService;
    }

    /**
     * 扫大文件夹：两级遍历（店铺→商品），识别各商品的图片角色目录。
     * 入参 {@code { rootPath }}，出参 {@link SemiAutoScan.Result}。
     * P1 店铺匹配未接（shopResolver=null，全部 matched=false），P4 注入。
     */
    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestBody Map<String, Object> body) {
        String rootPath = body.get("rootPath") != null ? String.valueOf(body.get("rootPath")) : "";
        if (rootPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rootPath 不能为空"));
        }
        try {
            // P4 将替换为 storeService::resolveProfileByName
            return ResponseEntity.ok(semiAutoService.scanRoot(rootPath, null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "扫描失败：" + e.getMessage()));
        }
    }

    /** 扫某目录下图片，自然序返回绝对路径列表。入参 {@code { dir }}。 */
    @PostMapping("/list-images")
    public ResponseEntity<?> listImages(@RequestBody Map<String, Object> body) {
        String dir = body.get("dir") != null ? String.valueOf(body.get("dir")) : "";
        if (dir.isBlank() || !new File(dir).isDirectory()) {
            return ResponseEntity.badRequest().body(Map.of("error", "dir 不是有效目录"));
        }
        List<String> imgs = semiAutoService.listImages(dir);
        return ResponseEntity.ok(Map.of("images", imgs, "count", imgs.size()));
    }

    /**
     * SKU 图反推（P2）：对某商品的 SKU 图目录，逐图从文件名提编码→查快麦 ERP。
     * 入参 {@code { skuImgDir, productType? }}；出参 {@code { rows:[{file,code,matched,...}], unmatched:[...] }}。
     * unmatched 非空即命名不规范，前端据此提示用户；接北极星，不静默跳过。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/reverse-sku")
    public ResponseEntity<?> reverseSku(@RequestBody Map<String, Object> body) {
        String skuImgDir = body.get("skuImgDir") != null ? String.valueOf(body.get("skuImgDir")) : "";
        String productType = body.get("productType") != null ? String.valueOf(body.get("productType")) : "架类";
        if (skuImgDir.isBlank() || !new File(skuImgDir).isDirectory()) {
            return ResponseEntity.badRequest().body(Map.of("error", "skuImgDir 不是有效目录"));
        }
        List<String> imgs = semiAutoService.listImages(skuImgDir);
        List<Map<String, Object>> rows = semiAutoService.reverseSkuFromImages(imgs, productType);
        List<Map<String, Object>> unmatched = rows.stream()
                .filter(r -> !Boolean.TRUE.equals(r.get("matched"))).toList();
        return ResponseEntity.ok(Map.of("rows", rows, "unmatched", unmatched,
                "allMatched", unmatched.isEmpty()));
    }

    /**
     * 商品上新前完整性强校验（P3 北极星）。上新编排前必过此关。
     * 入参 {@code { productName, mainImgDir, detailImgDir, skus:[{name,imgPath,price}] }}；
     * 出参 {@link com.gofu.local.model.SemiAutoScan.Completeness}（ready + 缺失清单）。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/check-completeness")
    public ResponseEntity<?> checkCompleteness(@RequestBody Map<String, Object> body) {
        String productName = String.valueOf(body.getOrDefault("productName", ""));
        String mainImgDir = String.valueOf(body.getOrDefault("mainImgDir", ""));
        String detailImgDir = String.valueOf(body.getOrDefault("detailImgDir", ""));
        List<Map<String, Object>> raw = (List<Map<String, Object>>) body.getOrDefault("skus", List.of());
        List<com.gofu.local.model.SemiAutoScan.SkuCheck> skus = raw.stream()
                .map(m -> new com.gofu.local.model.SemiAutoScan.SkuCheck(
                        m.get("name") == null ? "" : String.valueOf(m.get("name")),
                        m.get("imgPath") == null ? "" : String.valueOf(m.get("imgPath")),
                        m.get("price") instanceof Number n ? n.doubleValue()
                                : parseD(m.get("price"))))
                .toList();
        return ResponseEntity.ok(semiAutoService.checkCompleteness(productName, mainImgDir, detailImgDir, skus));
    }

    private static double parseD(Object v) {
        try { return v == null ? 0 : Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }
}

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
    private final com.gofu.local.service.listing.StoreService storeService;
    private final com.gofu.local.service.listing.ListingService listingService;
    private final com.gofu.local.service.listing.SemiAutoOrchestrator orchestrator;
    private final com.gofu.local.service.listing.StyleImportService styleImportService;

    public SemiAutoController(SemiAutoService semiAutoService,
                              com.gofu.local.service.listing.StoreService storeService,
                              com.gofu.local.service.listing.ListingService listingService,
                              com.gofu.local.service.listing.SemiAutoOrchestrator orchestrator,
                              com.gofu.local.service.listing.StyleImportService styleImportService) {
        this.semiAutoService = semiAutoService;
        this.storeService = storeService;
        this.listingService = listingService;
        this.orchestrator = orchestrator;
        this.styleImportService = styleImportService;
    }

    /**
     * 导入外部成品图文件夹 → 建云端 context（风格迁移②/上新用）。
     * 入参 {@code {folderPath, productName?, category?}}；出参 {@code {contextId, mainCount, detailCount, warnings}}。
     * 文件夹需含 主图/详情 子目录（复用 scanProduct 角色识别）。
     */
    @PostMapping("/import-to-context")
    public ResponseEntity<?> importToContext(@RequestBody Map<String, Object> body) {
        // 重构(webkitdirectory 上传模型)：入参 { folderName, main/detail/white/sku:[{name,b64,ext}] }。
        // 异步：上传16张+反推+云端出方案≈90秒，同步会让前端像卡死。这里起后台任务立即返回 importId，前端轮询进度。
        String folderName = String.valueOf(body.getOrDefault("folderName", ""));
        if (folderName.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "folderName 不能为空"));
        String importId = java.util.UUID.randomUUID().toString();
        styleImportService.importAsync(importId, folderName,
                toUpImgs(body.get("main")), toUpImgs(body.get("detail")),
                toUpImgs(body.get("white")), toUpImgs(body.get("sku")));
        return ResponseEntity.ok(Map.of("importId", importId));
    }

    /**
     * 批量流"缺图·可AI生成"：给一个商品文件夹(本地绝对路径)走导入链补生SKU图(复用 importAsync)。
     * 入参 { folderPath }，返回 importId，前端复用 /import-progress 轮询。
     */
    @PostMapping("/gen-sku")
    public ResponseEntity<?> genSku(@RequestBody Map<String, Object> body) {
        String folderPath = String.valueOf(body.getOrDefault("folderPath", ""));
        if (folderPath.isBlank() || !new File(folderPath).isDirectory())
            return ResponseEntity.badRequest().body(Map.of("error", "folderPath 无效：" + folderPath));
        String importId = java.util.UUID.randomUUID().toString();
        styleImportService.importAsyncFromFolder(importId, folderPath);
        return ResponseEntity.ok(Map.of("importId", importId));
    }

    /** 轮询导入进度。出参 { phase, pct, done, error?, result?(done时的contextId等) }。 */
    @GetMapping("/import-progress/{importId}")
    public ResponseEntity<?> importProgress(@PathVariable String importId) {
        com.gofu.local.service.listing.StyleImportService.Progress pg = styleImportService.getProgress(importId);
        if (pg == null) return ResponseEntity.ok(Map.of("phase", "未知任务", "pct", 0, "done", true, "error", "任务不存在或已过期"));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("phase", pg.phase); out.put("pct", pg.pct); out.put("done", pg.done);
        if (pg.error != null) out.put("error", pg.error);
        if (pg.result != null) out.put("result", pg.result);
        return ResponseEntity.ok(out);
    }

    /** 把前端传的图数组解析成 UpImg 列表。 */
    @SuppressWarnings("unchecked")
    private List<com.gofu.local.service.listing.StyleImportService.UpImg> toUpImgs(Object raw) {
        List<com.gofu.local.service.listing.StyleImportService.UpImg> out = new java.util.ArrayList<>();
        if (!(raw instanceof List)) return out;
        for (Object o : (List<Object>) raw) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> m = (Map<String, Object>) o;
            String name = String.valueOf(m.getOrDefault("name", ""));
            String b64 = String.valueOf(m.getOrDefault("b64", ""));
            String ext = String.valueOf(m.getOrDefault("ext", "jpg"));
            if (!b64.isBlank()) out.add(new com.gofu.local.service.listing.StyleImportService.UpImg(name, b64, ext));
        }
        return out;
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
            // 店铺名→profile 解析：扫描时即标注每个店铺目录是否在 stores.json 匹配到
            return ResponseEntity.ok(semiAutoService.scanRoot(rootPath, storeService::resolveProfileByName));
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

    // ── 多店管理（P4）──────────────────────────────────────────────

    /** 列出所有店铺 + 每店登录态（cookie 文件是否存在）。 */
    @GetMapping("/stores")
    public ResponseEntity<?> listStores() {
        var stores = storeService.loadStores();
        var rows = stores.stream().map(s -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", s.getName());
            m.put("profile", s.getProfile());
            m.put("loggedIn", new File(storeService.cookiesPathOf(s.getProfile())).isFile());
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("stores", rows));
    }

    /**
     * 新增/更新店铺。入参 {@code { name, profile? }}。
     * profile 缺省时系统自动分配 store_N（运营只填店铺名即可），返回分配的 profile 供前端接着扫码登录。
     */
    @PostMapping("/stores")
    public ResponseEntity<?> upsertStore(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "")).trim();
        String profile = String.valueOf(body.getOrDefault("profile", "")).trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "店铺名不能为空"));
        }
        try {
            var stores = new java.util.ArrayList<>(storeService.loadStores());
            if (profile.isEmpty()) profile = nextProfile(stores);   // 自动分配 store_N
            final String p = profile;
            stores.removeIf(s -> p.equals(s.getProfile()));
            stores.add(new com.gofu.local.model.Store(name, p));
            storeService.saveStores(stores);
            return ResponseEntity.ok(Map.of("ok", true, "count", stores.size(), "profile", p));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "保存店铺失败：" + e.getMessage()));
        }
    }

    /** 下一个可用 store_N（取现有 store_ 编号最大值+1，从 1 起）。 */
    private String nextProfile(java.util.List<com.gofu.local.model.Store> stores) {
        int max = 0;
        for (var s : stores) {
            String p = s.getProfile();
            if (p != null && p.startsWith("store_")) {
                try { max = Math.max(max, Integer.parseInt(p.substring(6))); } catch (NumberFormatException ignore) {}
            }
        }
        return "store_" + (max + 1);
    }

    /** 登录指定店铺（按 profile 用独立 cookie/profile 路径触发 --login-only）。入参 {@code { profile }}。 */
    @PostMapping("/stores/login")
    public ResponseEntity<?> loginStore(@RequestBody Map<String, Object> body) {
        String profile = String.valueOf(body.getOrDefault("profile", "")).trim();
        if (profile.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "profile 不能为空"));
        try {
            String taskId = listingService.runLoginOnly(
                    storeService.cookiesPathOf(profile), storeService.userDataDirOf(profile), profile);
            return ResponseEntity.ok(Map.of("taskId", taskId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "登录启动失败：" + e.getMessage()));
        }
    }

    // ── 编排（P5）──────────────────────────────────────────────

    /** 预检：扫描+按店匹配+完整性校验，不真上新。入参 {@code { rootPath, skusByProduct? }}。 */
    @PostMapping("/preflight")
    public ResponseEntity<?> preflight(@RequestBody Map<String, Object> body) {
        String rootPath = String.valueOf(body.getOrDefault("rootPath", ""));
        if (rootPath.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "rootPath 不能为空"));
        return ResponseEntity.ok(Map.of("outcomes", orchestrator.preflight(rootPath, parseSkusByProduct(body))));
    }

    /** 正式批量上新：预检通过的商品串行错开上新。入参同 preflight。 */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body) {
        String rootPath = String.valueOf(body.getOrDefault("rootPath", ""));
        if (rootPath.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "rootPath 不能为空"));
        return ResponseEntity.ok(Map.of("outcomes", orchestrator.run(rootPath, parseSkusByProduct(body))));
    }

    /** 解析 {@code skusByProduct: { 商品名: [{name,imgPath,price}] }}。 */
    @SuppressWarnings("unchecked")
    private Map<String, List<com.gofu.local.model.SemiAutoScan.SkuCheck>> parseSkusByProduct(Map<String, Object> body) {
        Map<String, List<com.gofu.local.model.SemiAutoScan.SkuCheck>> out = new java.util.LinkedHashMap<>();
        Object raw = body.get("skusByProduct");
        if (!(raw instanceof Map)) return out;
        for (var e : ((Map<String, Object>) raw).entrySet()) {
            if (!(e.getValue() instanceof List)) continue;
            List<com.gofu.local.model.SemiAutoScan.SkuCheck> list = new java.util.ArrayList<>();
            for (Object o : (List<Object>) e.getValue()) {
                if (!(o instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) o;
                list.add(new com.gofu.local.model.SemiAutoScan.SkuCheck(
                        m.get("name") == null ? "" : String.valueOf(m.get("name")),
                        m.get("imgPath") == null ? "" : String.valueOf(m.get("imgPath")),
                        m.get("price") instanceof Number n ? n.doubleValue() : parseD(m.get("price"))));
            }
            out.put(e.getKey(), list);
        }
        return out;
    }
}

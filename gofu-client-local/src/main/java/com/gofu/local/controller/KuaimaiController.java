package com.gofu.local.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.local.config.AppProperties;
import com.gofu.local.service.erp.KuaimaiService;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 快麦 ERP 成本接口（自 LY-Automation 迁入，精简为成本/配置相关端点）。
 *
 * <p>本地收口成本来源（ADR-002 结构流归本地）：预览页/上新按 itemCode 调这里取真实采购价。
 * 上新相关的白底图下载、商品分页浏览端点未迁入（M7 再按需迁）。
 */
@RestController
@RequestMapping("/api/erp")
public class KuaimaiController {

    private static final Logger log = LoggerFactory.getLogger(KuaimaiController.class);
    private final KuaimaiService kuaimaiService;
    private final com.gofu.local.service.listing.CostService costService;
    private final AppProperties appProperties;
    private final String cloudBase;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();

    public KuaimaiController(KuaimaiService kuaimaiService,
                             com.gofu.local.service.listing.CostService costService,
                             AppProperties appProperties,
                             @Value("${gofu.cloud.base-url:http://localhost:5020}") String cloudBase) {
        this.kuaimaiService = kuaimaiService;
        this.costService = costService;
        this.appProperties = appProperties;
        this.cloudBase = cloudBase;
    }

    /**
     * 回传白底图到快麦（07.08）：入参 {@code {outerId, dataUrl}}。
     * 先把导入图上传云端 COS 拿永久公网 URL，再调快麦 addorupdate 写回该编码的 picPath。
     * ⚠️ 会真改快麦线上商品档案，前端已加二次确认。
     */
    @PostMapping("/upload-white-image")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> uploadWhiteImage(@RequestBody Map<String, Object> body) {
        String outerId = String.valueOf(body.getOrDefault("outerId", ""));
        String dataUrl = String.valueOf(body.getOrDefault("dataUrl", ""));
        if (outerId.isBlank() || dataUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "outerId 和 dataUrl 必填"));
        }
        try {
            // 1) 上传云端 COS 拿公网 URL
            String reqJson = objectMapper.writeValueAsString(Map.of("dataUrl", dataUrl));
            Request up = new Request.Builder().url(cloudBase + "/api/gen/upload-image")
                    .post(okhttp3.RequestBody.create(reqJson, MediaType.parse("application/json"))).build();
            String picUrl;
            try (Response r = http.newCall(up).execute()) {
                String rb = r.body() != null ? r.body().string() : "{}";
                Map<String, Object> rm = objectMapper.readValue(rb, Map.class);
                if (!r.isSuccessful() || rm.get("signedUrl") == null) {
                    return ResponseEntity.internalServerError().body(Map.of("error", "上传COS失败：" + rm.getOrDefault("error", rb)));
                }
                picUrl = String.valueOf(rm.get("signedUrl"));
            }
            // 2) 回写快麦
            Map<String, Object> km = kuaimaiService.uploadItemImage(outerId, picUrl);
            boolean ok = Boolean.TRUE.equals(km.get("success")) || km.get("id") != null || km.get("skus") != null;
            if (!ok) return ResponseEntity.internalServerError().body(Map.of("error", "快麦回写失败：" + km.getOrDefault("msg", km), "picUrl", picUrl));
            return ResponseEntity.ok(Map.of("success", true, "picUrl", picUrl, "outerId", outerId));
        } catch (Exception e) {
            log.error("回传白底图失败 {}: {}", outerId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", "回传失败：" + e.getMessage()));
        }
    }

    /** 刷新快麦 accessToken（30 天过期）。 POST /api/erp/refresh-token */
    @PostMapping("/refresh-token")
    public ResponseEntity<Map<String, Object>> refreshToken() {
        try {
            return ResponseEntity.ok(kuaimaiService.refreshToken());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "刷新失败：" + e.getMessage()));
        }
    }

    /** 获取当前快麦配置。 GET /api/erp/config */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        AppProperties.Kuaimai km = appProperties.getKuaimai();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("appKey",       km.getAppKey());
        m.put("appSecret",    km.getAppSecret());
        m.put("accessToken",  km.getAccessToken());
        m.put("refreshToken", km.getRefreshToken());
        m.put("companyId",    km.getCompanyId());
        m.put("appTitle",     km.getAppTitle());
        return ResponseEntity.ok(m);
    }

    /**
     * 更新快麦配置字段并持久化（token 每 30 天过期需可改）。
     * POST /api/erp/config  { appKey, appSecret, accessToken, refreshToken, companyId, appTitle }
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, String> body) {
        AppProperties.Kuaimai km = appProperties.getKuaimai();
        if (body.containsKey("appKey"))       km.setAppKey(body.get("appKey"));
        if (body.containsKey("appSecret"))    km.setAppSecret(body.get("appSecret"));
        if (body.containsKey("accessToken"))  km.setAccessToken(body.get("accessToken"));
        if (body.containsKey("refreshToken")) km.setRefreshToken(body.get("refreshToken"));
        if (body.containsKey("companyId"))    km.setCompanyId(body.get("companyId"));
        if (body.containsKey("appTitle"))     km.setAppTitle(body.get("appTitle"));
        kuaimaiService.persistAll();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * 查询单品列表，支持关键词过滤。首次触发并发预加载（约6-10秒），之后从缓存瞬时返回。
     * GET /api/erp/sku-items?keyword=银底座
     */
    @GetMapping("/sku-items")
    public ResponseEntity<Map<String, Object>> skuItems(@RequestParam(defaultValue = "") String keyword) {
        try {
            List<Map<String, Object>> all = kuaimaiService.getAllSkuItemsCached();
            if (!keyword.isBlank()) {
                String kw = keyword.trim().toLowerCase();
                all = all.stream().filter(item -> {
                    String t = String.valueOf(item.getOrDefault("title", "")).toLowerCase();
                    String o = String.valueOf(item.getOrDefault("outerId", "")).toLowerCase();
                    return t.contains(kw) || o.contains(kw);
                }).sorted((a, b) -> matchRank(a, kw) - matchRank(b, kw))
                  .collect(java.util.stream.Collectors.toList());
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("items", all);
            result.put("total", all.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "查询失败：" + e.getMessage()));
        }
    }

    /** 强制刷新单品缓存。 POST /api/erp/sku-items/refresh */
    @PostMapping("/sku-items/refresh")
    public ResponseEntity<Map<String, Object>> refreshSkuCache() {
        try {
            List<Map<String, Object>> items = kuaimaiService.reloadSkuItems();
            return ResponseEntity.ok(Map.of("ok", true, "total", items.size()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "刷新失败：" + e.getMessage()));
        }
    }

    /**
     * 检测编码在快麦是否有白底图（M9-3，不下载，仅查）。
     * 入参 { codes:[...] } 出参 { has:[code...], missing:[code...] }
     */
    @PostMapping("/check-white-images")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> checkWhiteImages(@RequestBody Map<String, Object> body) {
        try {
            List<String> codes = (List<String>) body.getOrDefault("codes", List.of());
            List<String> has = new ArrayList<>(), missing = new ArrayList<>();
            for (String code : codes) {
                if (code == null || code.isBlank()) continue;
                // per-item 保护：单个编码查询异常不拖垮整批，记为 missing 降级
                try {
                    if (kuaimaiService.findWhiteImageUrl(code) != null) has.add(code); else missing.add(code);
                } catch (Exception e) { log.warn("查白底图失败 {}: {}", code, e.getMessage()); missing.add(code); }
            }
            return ResponseEntity.ok(Map.of("has", has, "missing", missing));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "检测失败：" + e.getMessage()));
        }
    }

    /**
     * 从快麦下载白底图到本地目录（M9-3 迁自 LY）。快麦优先，缺图在 missing 里返回让用户手动补。
     * 入参 { codes:[...] } 出参 { whiteDir, matched:[{code,file}], missing:[code...] }
     */
    @PostMapping("/fetch-white-images")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> fetchWhiteImages(@RequestBody Map<String, Object> body) {
        try {
            List<String> codes = (List<String>) body.getOrDefault("codes", List.of());
            if (codes.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "codes 不能为空"));
            String userDir = appProperties.getPaths().getUserDataDir();
            if (userDir == null || userDir.isBlank()) userDir = System.getProperty("user.dir");
            java.io.File whiteDir = new java.io.File(userDir, "erp-white/" + System.currentTimeMillis());
            whiteDir.mkdirs();
            List<Map<String, Object>> matched = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String code : codes) {
                if (code == null || code.isBlank() || !seen.add(code)) continue;
                // findWhiteImageUrl 也放进 try：单个编码查询/下载异常都降级为 missing，不拖垮整批
                try {
                    String url = kuaimaiService.findWhiteImageUrl(code);
                    if (url == null) { missing.add(code); continue; }
                    String safe = code.replaceAll("[\\\\/:*?\"<>|]", "_");
                    String ext = url.toLowerCase().contains(".png") ? ".png" : ".jpg";
                    java.io.File out = new java.io.File(whiteDir, safe + ext);
                    // 带超时的下载(避免慢链接无限阻塞 servlet 线程)
                    java.net.URLConnection conn = java.net.URI.create(url).toURL().openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(30000);
                    try (java.io.InputStream in = conn.getInputStream()) {
                        java.nio.file.Files.copy(in, out.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", code); m.put("file", out.getAbsolutePath());
                    matched.add(m);
                } catch (Exception e) {
                    log.warn("下载快麦白底图失败 {}: {}", code, e.getMessage());
                    missing.add(code);
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("whiteDir", whiteDir.getAbsolutePath());
            result.put("matched", matched);
            result.put("missing", missing);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "获取白底图失败：" + e.getMessage()));
        }
    }

    /**
     * 本地图片预览（R8）：把从快麦下载的白底图本地文件当图片返回，供前端 <img> 预览。
     * 安全：只允许 userDataDir 下的文件（防路径穿越读任意文件）。
     * GET /api/erp/local-image?path=<绝对路径>
     */
    @GetMapping("/local-image")
    public ResponseEntity<byte[]> localImage(@RequestParam String path) {
        try {
            String userDir = appProperties.getPaths().getUserDataDir();
            if (userDir == null || userDir.isBlank()) userDir = System.getProperty("user.dir");
            java.io.File base = new java.io.File(userDir).getCanonicalFile();
            java.io.File f = new java.io.File(path).getCanonicalFile();
            // 限制在 userDataDir 之内，防穿越
            if (!f.getPath().startsWith(base.getPath()) || !f.isFile()) {
                return ResponseEntity.status(404).build();
            }
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String lower = f.getName().toLowerCase();
            String ct = lower.endsWith(".png") ? "image/png" : "image/jpeg";
            return ResponseEntity.ok().header("Content-Type", ct).body(bytes);
        } catch (Exception e) {
            return ResponseEntity.status(404).build();
        }
    }

    /**
     * 批量计算单品成本（含运费）。
     * POST /api/erp/calc-cost
     * 入参: { skuOuterIds: ["A001","B002"], productType: "花洒"|"架类" }
     * 出参: { items: [{skuOuterId,name,purchasePrice,weight,hasSupplier,cost,isFixed}], totalCost }
     */
    @PostMapping("/calc-cost")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> calcCost(@RequestBody Map<String, Object> body) {
        try {
            List<String> outerIds = (List<String>) body.getOrDefault("skuOuterIds", List.of());
            String productType    = (String) body.getOrDefault("productType", "架类");
            return ResponseEntity.ok(costService.calcCost(outerIds, productType));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "成本计算失败：" + e.getMessage()));
        }
    }

    /**
     * 计算组合 SKU 成本。
     * POST /api/erp/calc-combo-cost
     * 入参: { productType, fixedAccessories:[{itemCode,cost}], skus:[{name, components:[{itemCode,qty,cost,weight}]}] }
     * 规则: 材料成本=Σ(组件cost×qty)+Σ固定项cost；总重=Σ(组件weight×qty)；
     *       运费=按品类(花洒+3 / 代发鹏盛阶梯 / 其他300g=2.3每100g+0.15)算一次；SKU成本=材料+运费
     */
    @PostMapping("/calc-combo-cost")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> calcComboCost(@RequestBody Map<String, Object> body) {
        try {
            String productType = (String) body.getOrDefault("productType", "架类");
            List<Map<String, Object>> fixedAccessories = (List<Map<String, Object>>) body.getOrDefault("fixedAccessories", List.of());
            List<Map<String, Object>> skus = (List<Map<String, Object>>) body.getOrDefault("skus", List.of());
            return ResponseEntity.ok(costService.calcComboCost(productType, fixedAccessories, skus));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "组合成本计算失败：" + e.getMessage()));
        }
    }

    /** 搜索匹配优先级：精确匹配=0 > 前缀匹配=1 > 包含=2。 */
    private int matchRank(Map<String, Object> item, String kw) {
        String t = String.valueOf(item.getOrDefault("title", "")).toLowerCase();
        if (t.equals(kw)) return 0;
        if (t.startsWith(kw)) return 1;
        return 2;
    }
}

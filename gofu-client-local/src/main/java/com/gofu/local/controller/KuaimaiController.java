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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final AppProperties appProperties;
    private final String cloudBase;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build();

    public KuaimaiController(KuaimaiService kuaimaiService, AppProperties appProperties,
                             @Value("${gofu.cloud.base-url:http://localhost:5020}") String cloudBase) {
        this.kuaimaiService = kuaimaiService;
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

            // 花洒品类：自动按名称补充固定包材（手喷袋/好评卡/胶纸），与已选去重
            java.util.LinkedHashSet<String> codes = new java.util.LinkedHashSet<>(outerIds);
            if ("花洒".equals(productType)) {
                try {
                    List<Map<String, Object>> packaging =
                        kuaimaiService.findItemsByNameKeywords(List.of("手喷袋", "好评卡", "胶纸"));
                    for (Map<String, Object> p : packaging) {
                        codes.add(String.valueOf(p.get("skuOuterId")));
                    }
                } catch (Exception e) { /* 补充失败不阻断 */ }
            }

            List<Map<String, Object>> items = new ArrayList<>();
            double totalCost = 0;

            for (String code : codes) {
                Map<String, Object> row = unitCost(code, productType);
                boolean fixed = isFixedCostName(String.valueOf(row.get("name")));
                row.put("isFixed", fixed);
                if (fixed) {
                    row.put("freight", 0.0);
                    row.put("cost", round2(toDouble(row.get("purchasePrice"))));
                }
                totalCost += toDouble(row.get("cost"));
                items.add(row);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("items",     items);
            result.put("totalCost", round2(totalCost));
            return ResponseEntity.ok(result);
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
     *       运费=花洒?3:(总重<=0?0:阶梯)；SKU成本=材料+运费
     */
    @PostMapping("/calc-combo-cost")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> calcComboCost(@RequestBody Map<String, Object> body) {
        try {
            String productType = (String) body.getOrDefault("productType", "架类");
            List<Map<String, Object>> fixedAccessories = (List<Map<String, Object>>) body.getOrDefault("fixedAccessories", List.of());
            List<Map<String, Object>> skus = (List<Map<String, Object>>) body.getOrDefault("skus", List.of());

            // 固定包材成本（仅采购价，不含运费、不计重量），全局算一次
            double accessoryCost = 0;
            for (Map<String, Object> acc : fixedAccessories) {
                accessoryCost += toDouble(acc.get("cost"));
            }
            accessoryCost = round2(accessoryCost);

            List<Map<String, Object>> outSkus = new ArrayList<>();
            for (Map<String, Object> sku : skus) {
                String name = String.valueOf(sku.getOrDefault("name", ""));
                List<Map<String, Object>> components = (List<Map<String, Object>>) sku.getOrDefault("components", List.of());

                double materialCost = 0, totalWeight = 0;
                List<Map<String, Object>> breakdown = new ArrayList<>();
                int compIdx = 0;
                for (Map<String, Object> comp : components) {
                    String code = String.valueOf(comp.get("itemCode"));
                    int qty = Math.max(1, toInt(comp.getOrDefault("qty", 1)));
                    double unit = toDouble(comp.get("cost"));     // 材料价（核对后）
                    double w    = toDouble(comp.get("weight"));
                    // 成本修复：滤芯类配件在 ERP 里以「整包」为单品存在，编码尾部 *N 即包装数
                    // （如 052滤芯*15 采购价 3.9=15个整包价），而 qty 语义是"要几个"。
                    // 整包价直接 ×qty 会把成本放大 ~N 倍，故先按 *N 折算成单个价再乘。
                    int packSize = parsePackSize(code);
                    if (packSize > 1) {
                        unit /= packSize;
                        w    /= packSize;
                    }
                    // 成本异常保护：除首个主件外，配件若本身是「整支花洒/整机」（编码或名称含 单手喷/单花洒/整机）
                    // 说明被误当配件拼进了组合，记日志并不计入成本，避免拼单价离谱。
                    String cn = code + " " + String.valueOf(comp.getOrDefault("name", ""));
                    boolean isWholeShower = compIdx > 0 && (cn.contains("单手喷") || cn.contains("单花洒") || cn.contains("整机"));
                    if (isWholeShower) {
                        log.warn("组合成本保护：SKU「{}」的组件 {} 疑似整支花洒被误当配件，已不计入成本", name, code);
                    } else {
                        materialCost += unit * qty;
                        totalWeight  += w * qty;
                    }
                    compIdx++;

                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("itemCode", code);
                    b.put("qty",      qty);
                    b.put("unitCost", unit);
                    breakdown.add(b);
                }

                // 运费：组合层算一次
                double freight;
                if ("花洒".equals(productType)) {
                    freight = 3.0;
                } else if (totalWeight <= 0) {
                    freight = 0;
                } else {
                    long over = (long) Math.ceil(Math.max(0, totalWeight - 0.3) / 0.1);
                    freight = round2(2.4 + over * 0.15);
                }

                double cost = round2(materialCost + accessoryCost + freight);

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("name",          name);
                out.put("cost",          cost);
                out.put("freight",       freight);
                out.put("totalWeight",   round2(totalWeight));
                out.put("accessoryCost", accessoryCost);
                out.put("breakdown",     breakdown);
                out.put("components",    components);
                out.put("stock",         sku.getOrDefault("stock", 8888));
                outSkus.add(out);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("skus", outSkus);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "组合成本计算失败：" + e.getMessage()));
        }
    }

    /** 计算单个 skuOuterId 的成本。优先用列表缓存（无规格商品成本/重量在列表里），降级查 SKU 明细。 */
    private Map<String, Object> unitCost(String code, String productType) {
        double purchasePrice = 0, weight = 0;
        int hasSupplier = 0;
        String name = code;

        Map<String, Object> cached = null;
        try { cached = kuaimaiService.getCachedItemByOuterId(code); } catch (Exception ignore) {}
        if (cached != null) {
            purchasePrice = toDouble(cached.get("purchasePrice"));
            weight        = toDouble(cached.get("weight"));
            hasSupplier   = toInt(cached.get("hasSupplier"));
            String t = String.valueOf(cached.getOrDefault("title", code));
            if (!t.isBlank()) name = t;
        } else {
            try {
                Map<String, Object> detail = kuaimaiService.getSkuDetail(code);
                purchasePrice = toDouble(detail.get("purchasePrice"));
                weight        = toDouble(detail.get("weight"));
                hasSupplier   = toInt(detail.get("hasSupplier"));
                name = String.valueOf(detail.getOrDefault("shortTitle",
                         detail.getOrDefault("skuOuterId", code)));
            } catch (Exception ignore) {}
        }

        // 单品成本只含材料价；运费在组合层按整个 SKU 算一次
        double cost = round2(purchasePrice);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("skuOuterId",    code);
        row.put("name",          name);
        row.put("purchasePrice", purchasePrice);
        row.put("weight",        weight);
        row.put("hasSupplier",   hasSupplier);
        row.put("cost",          cost);
        return row;
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 解析配件编码尾部的「整包数量」*N（如 052滤芯*15 → 15），无则返回 1。
     * ERP 里滤芯等批量件以整包为单品、采购价是整包价，需据此把整包价折算成单个价。
     * 只认末尾的 *N（组合码如 A+B*5 取最后一段的 *5），防止误伤中间的乘号。
     */
    static int parsePackSize(String code) {
        if (code == null) return 1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\*(\\d+)\\s*$").matcher(code.trim());
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                return n > 0 ? n : 1;
            } catch (NumberFormatException e) { return 1; }
        }
        return 1;
    }

    /** 搜索匹配优先级：精确匹配=0 > 前缀匹配=1 > 包含=2。 */
    private int matchRank(Map<String, Object> item, String kw) {
        String t = String.valueOf(item.getOrDefault("title", "")).toLowerCase();
        if (t.equals(kw)) return 0;
        if (t.startsWith(kw)) return 1;
        return 2;
    }

    /** 名称含包材关键词则为固定成本项（不进搭配布局，不加运费）。 */
    private boolean isFixedCostName(String name) {
        if (name == null) return false;
        return name.contains("手喷袋") || name.contains("好评卡")
            || name.contains("胶纸") || name.contains("纸箱");
    }
}

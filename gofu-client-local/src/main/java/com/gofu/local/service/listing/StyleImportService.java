package com.gofu.local.service.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.local.model.SemiAutoScan;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 导入外部成品图 → 建云端 ProductContext（供风格迁移②/上新用）。
 *
 * <p>职责分工：读本地文件是本地的活；建 context / 存图归云端。本 service 在本地读图 → base64
 * → 调云端 {@code /api/gen/upload-image}(转 COS 拿公网 URL) → 组 context → 调云端 {@code POST /api/context}。
 * 复用 SemiAutoService.scanProduct 的角色目录识别（主图/详情）。
 */
@Service
public class StyleImportService {

    private static final Logger log = LoggerFactory.getLogger(StyleImportService.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String cloudBase;
    private final SemiAutoService semiAutoService;
    private final ObjectMapper om = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public StyleImportService(@Value("${gofu.cloud.base-url:http://127.0.0.1:5020}") String cloudBase,
                              SemiAutoService semiAutoService) {
        this.cloudBase = cloudBase != null ? cloudBase.replace("localhost", "127.0.0.1") : "http://127.0.0.1:5020";
        this.semiAutoService = semiAutoService;
    }

    /** 前端上传的一张图：name=原文件名(带尺寸/编码,用于反推与匹配)、b64=base64、ext=png/jpg。 */
    public record UpImg(String name, String b64, String ext) {}

    /**
     * 重构版导入(webkitdirectory 前端上传模型)：前端选文件夹→按子目录分组读 base64 上传，
     * 后端不再扫盘。文件夹名按「品类-主件名」解析(与批量上新一致)，白底图名反推快麦建主件，
     * 调云端出 SKU 方案 + AI 标题，sku 图按尺寸名次挂到方案。返回 contextId + 计数。
     *
     * @param folderName 顶层文件夹名，如「锅盖架-圣诞树收纳架」
     * @param mainImgs/detailImgs/whiteImgs/skuImgs 各子目录图片(name+base64)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importToContext(String folderName, List<UpImg> mainImgs, List<UpImg> detailImgs,
                                               List<UpImg> whiteImgs, List<UpImg> skuImgs) throws Exception {
        if ((mainImgs == null || mainImgs.isEmpty()) && (detailImgs == null || detailImgs.isEmpty())
                && (whiteImgs == null || whiteImgs.isEmpty()))
            throw new IllegalStateException("没找到主图/详情图/白底图（文件夹需含 主图/ 详情/ 白底图/ 子目录）");

        FolderMeta meta = parseFolderName(folderName);
        String category = meta.category;
        String productName = meta.productName;

        // 1) 上传图 → 云端 ref
        List<String> mainKeys = uploadAll(mainImgs);
        List<String> detailKeys = uploadAll(detailImgs);
        List<String> whiteKeys = uploadAll(whiteImgs);

        // 2) 白底图名(=ERP编码)反推快麦 → 主件清单(matched 的作主件)。reverseSku 只用文件名不读盘。
        List<Map<String, Object>> mainSkus = new ArrayList<>();
        List<String> unmatchedHints = new ArrayList<>();
        if (whiteImgs != null && !whiteImgs.isEmpty()) {
            List<String> whiteNames = new ArrayList<>();
            for (UpImg w : whiteImgs) whiteNames.add(w.name());
            List<Map<String, Object>> rows = semiAutoService.reverseSkuFromImages(whiteNames, category == null ? "架类" : category);
            for (Map<String, Object> r : rows) {
                if (Boolean.TRUE.equals(r.get("matched"))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("itemCode", r.get("code")); m.put("name", r.get("name")); m.put("role", "main");
                    mainSkus.add(m);
                } else if (r.get("error") != null) unmatchedHints.add(String.valueOf(r.get("error")));
            }
        }

        // 3) 建 context（先存图+品类，标题临时用主件名，后面 AI 覆盖）
        Map<String, Object> visual = new LinkedHashMap<>();
        visual.put("mainImages", mainKeys);
        visual.put("detailImages", detailKeys);
        visual.put("whiteImages", whiteKeys);
        visual.put("title", productName);
        visual.put("sellingPoints", List.of());
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("mainItem", productName);
        if (category != null && !category.isBlank()) ctx.put("category", category);
        ctx.put("visual", visual);
        Map<String, Object> saved = postJson("/api/context", ctx);
        String contextId = String.valueOf(saved.get("id"));

        // 4) 出 SKU 方案 + AI 标题（云端），并把 sku 图按尺寸名次挂到方案
        int planItemCount = generatePlansAndTitle(contextId, category, productName, mainSkus, skuImgs);

        log.info("[导入重构] 文件夹={} → contextId={} 主图{} 详情{} 白底{} 主件{} 方案SKU{}",
                folderName, contextId, mainKeys.size(), detailKeys.size(), whiteKeys.size(), mainSkus.size(), planItemCount);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contextId", contextId);
        out.put("mainCount", mainKeys.size()); out.put("detailCount", detailKeys.size());
        out.put("whiteCount", whiteKeys.size()); out.put("skuPlanCount", planItemCount);
        out.put("category", category == null ? "" : category); out.put("productName", productName);
        out.put("warnings", unmatchedHints);
        return out;
    }

    /** 逐张 base64 → 云端 upload-image → 收集公网 URL。 */
    private List<String> uploadAll(List<UpImg> imgs) throws Exception {
        List<String> keys = new ArrayList<>();
        if (imgs == null) return keys;
        for (UpImg img : imgs) {
            if (img.b64() == null || img.b64().isBlank()) continue;
            String ext = "png".equalsIgnoreCase(img.ext()) ? "png" : "jpg";
            Map<String, Object> resp = postJson("/api/gen/upload-image", Map.of("base64", img.b64(), "ext", ext));
            String ref = resp.get("imageRef") != null ? String.valueOf(resp.get("imageRef")) : null;
            if (ref != null && !ref.isBlank()) keys.add(ref);
            else log.warn("导入上传失败(无 imageRef): {}", img.name());
        }
        return keys;
    }

    /** 文件夹名解析结果：品类(可空) + 主件名。 */
    private record FolderMeta(String category, String productName) {}

    /** 解析「品类-主件名」(与 SemiAutoOrchestrator 一致)：第一个"-"分隔，无"-"则整名为主件名、品类空。 */
    private FolderMeta parseFolderName(String folderName) {
        if (folderName == null || folderName.isBlank()) return new FolderMeta(null, folderName);
        // M3：全角连字符－/全角空格归一化(中文输入法常打全角横线)
        String s = folderName.trim().replace('－', '-').replace('　', ' ');
        int dash = s.indexOf('-');
        if (dash <= 0 || dash >= s.length() - 1) return new FolderMeta(null, s.replaceAll("^-+|-+$", "").trim());
        return new FolderMeta(s.substring(0, dash).trim(), s.substring(dash + 1).trim());
    }
    /**
     * 云端出 SKU 方案(/api/gen/sku-plans)+AI标题(/api/gen/title)，再把 sku 图按尺寸名次挂到方案 item.imgDir。
     * @return 方案里的 SKU item 总数(0=没建出方案，前端仍能手动选品/生成)
     */
    @SuppressWarnings("unchecked")
    private int generatePlansAndTitle(String contextId, String category, String productName,
                                      List<Map<String, Object>> mainSkus, List<UpImg> skuImgs) {
        int itemCount = 0;
        List<String> skuNames = new ArrayList<>();
        // 4a) SKU 方案：有反推到主件才出（没主件无从规划，留空，前端手动补）
        if (!mainSkus.isEmpty()) {
            try {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("contextId", contextId); req.put("category", category == null ? "" : category);
                req.put("productName", productName); req.put("brand", "GOFU");
                req.put("skus", mainSkus); req.put("planCount", 3);
                Map<String, Object> r = postJson("/api/gen/sku-plans", req);
                if (r.get("savedItemCount") instanceof Number n) itemCount = n.intValue();
            } catch (Exception e) { log.warn("导入·SKU方案生成失败(不阻断): {}", e.getMessage()); }
        }
        for (Map<String, Object> m : mainSkus) skuNames.add(String.valueOf(m.get("name")));
        // 4b) AI 标题（与自动流程同款 mode=ai）
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("contextId", contextId); req.put("mode", "ai");
            req.put("category", category == null ? "" : category); req.put("productType", category == null ? "" : category);
            req.put("productName", productName); req.put("brand", "GOFU"); req.put("skuNames", skuNames);
            postJson("/api/gen/title", req);
        } catch (Exception e) { log.warn("导入·AI标题生成失败(不阻断，留主件名): {}", e.getMessage()); }
        // 4c) sku 图按尺寸名次挂方案：上传 sku 图→云端接口按尺寸配对写回 item.imgDir（复用云端 #2 名次配对思路）
        if (skuImgs != null && !skuImgs.isEmpty() && itemCount > 0) {
            try { attachSkuImages(contextId, skuImgs); }
            catch (Exception e) { log.warn("导入·SKU图挂载失败(不阻断，可后续生成): {}", e.getMessage()); }
        }
        return itemCount;
    }

    /** 上传 sku 图并调云端把它们按尺寸名次挂到方案 item.imgDir（云端 /api/gen/attach-sku-images）。 */
    private void attachSkuImages(String contextId, List<UpImg> skuImgs) throws Exception {
        List<Map<String, Object>> uploaded = new ArrayList<>();
        for (UpImg s : skuImgs) {
            if (s.b64() == null || s.b64().isBlank()) continue;
            String ext = "png".equalsIgnoreCase(s.ext()) ? "png" : "jpg";
            Map<String, Object> resp = postJson("/api/gen/upload-image", Map.of("base64", s.b64(), "ext", ext));
            String ref = resp.get("imageRef") != null ? String.valueOf(resp.get("imageRef")) : null;
            if (ref != null && !ref.isBlank()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.name()); m.put("ref", ref);
                uploaded.add(m);
            }
        }
        if (uploaded.isEmpty()) return;
        postJson("/api/gen/attach-sku-images", Map.of("contextId", contextId, "skuImages", uploaded));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String path, Object bodyObj) throws Exception {
        String json = om.writeValueAsString(bodyObj);
        Request req = new Request.Builder().url(cloudBase + path)
                .post(RequestBody.create(json, JSON)).build();
        try (Response resp = http.newCall(req).execute()) {
            String s = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) throw new RuntimeException("云端 " + path + " HTTP " + resp.code() + ": " + s);
            return om.readValue(s, Map.class);
        }
    }
}

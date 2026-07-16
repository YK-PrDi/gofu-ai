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

    /**
     * 导入一个商品文件夹（含 主图/详情 子目录）→ 建 context，返回 contextId。
     * @param folderPath 商品文件夹绝对路径
     * @param productName 商品名（空则用文件夹名）
     * @param category 品类（可空，上新前可再补）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> importToContext(String folderPath, String productName, String category) throws Exception {
        File dir = new File(folderPath);
        if (!dir.isDirectory()) throw new IllegalArgumentException("路径不是文件夹：" + folderPath);

        SemiAutoScan.Product p = semiAutoService.scanProduct(dir);
        List<String> mainImgs = p.mainImgDir() == null || p.mainImgDir().isEmpty()
                ? List.of() : semiAutoService.listImages(p.mainImgDir());
        List<String> detailImgs = p.detailImgDir() == null || p.detailImgDir().isEmpty()
                ? List.of() : semiAutoService.listImages(p.detailImgDir());
        // #1 修：原来只扫主图/详情、白底图目录被丢弃(whiteImages写死空)→"文件夹里有sku(白底)却没检测到"。
        // 现在也扫 白底图/，作为 SKU 白底带进 context，后续上新/风格迁移可用。
        List<String> whiteImgs = p.whiteImgDir() == null || p.whiteImgDir().isEmpty()
                ? List.of() : semiAutoService.listImages(p.whiteImgDir());
        if (mainImgs.isEmpty() && detailImgs.isEmpty() && whiteImgs.isEmpty())
            throw new IllegalStateException("文件夹里没找到主图/详情图/白底图（需含\"主图\"/\"详情\"/\"白底图\"子目录）");

        // 逐张上传云端 → 拿公网 URL（作为 context 图 key）
        List<String> mainKeys = uploadAll(mainImgs);
        List<String> detailKeys = uploadAll(detailImgs);
        List<String> whiteKeys = uploadAll(whiteImgs);

        // 组 context（visual 填上传后的 URL），调云端建
        Map<String, Object> visual = new LinkedHashMap<>();
        visual.put("mainImages", mainKeys);
        visual.put("detailImages", detailKeys);
        visual.put("title", productName == null || productName.isBlank() ? dir.getName() : productName);
        visual.put("sellingPoints", List.of());
        visual.put("whiteImages", whiteKeys);

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("mainItem", productName == null || productName.isBlank() ? dir.getName() : productName);
        if (category != null && !category.isBlank()) ctx.put("category", category);
        ctx.put("visual", visual);

        Map<String, Object> saved = postJson("/api/context", ctx);
        String contextId = String.valueOf(saved.get("id"));
        log.info("[导入建context] 文件夹={} → contextId={} 主图{}张 详情{}张 白底{}张",
                folderPath, contextId, mainKeys.size(), detailKeys.size(), whiteKeys.size());
        return Map.of("contextId", contextId, "mainCount", mainKeys.size(), "detailCount", detailKeys.size(),
                "whiteCount", whiteKeys.size(),
                "warnings", p.warnings() == null ? List.of() : p.warnings());
    }

    /** 逐张读本地图 → base64 → 云端 upload-image → 收集返回的公网 URL。 */
    private List<String> uploadAll(List<String> localPaths) throws Exception {
        List<String> keys = new ArrayList<>();
        for (String path : localPaths) {
            File f = new File(path);
            if (!f.isFile()) { log.warn("导入跳过(文件不存在): {}", path); continue; }
            String ext = path.toLowerCase().endsWith(".png") ? "png" : "jpg";
            String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(f.toPath()));
            Map<String, Object> resp = postJson("/api/gen/upload-image", Map.of("base64", b64, "ext", ext));
            String ref = resp.get("imageRef") != null ? String.valueOf(resp.get("imageRef")) : null;
            if (ref != null && !ref.isBlank()) keys.add(ref);
            else log.warn("导入上传失败(无 imageRef): {}", path);
        }
        return keys;
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

package com.gofu.local.service.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 【权宜模块·产品替换】本地串链服务。生图质量不足时的临时手段：拿现成好构图参考图，只替换其中产品主体，
 * 抽卡凑 100 张 → 人工筛 6 → 复用导入链出详情/SKU/定价 → from-context 上新。
 *
 * <p><b>物理隔离</b>：本 service 独立命名、独立文件，后续生图质量起来后整体删除时一把摘干净。
 * 仅复用 {@link StyleImportService} 的 recognize/generateLayout（不改它），生图仍只在云端(5020)，
 * 本 service 只做编排 + 上传参考图 + 调云端 {@code /api/flow/replace-mains}。
 */
@Service
public class ProductReplaceService {

    private static final Logger log = LoggerFactory.getLogger(ProductReplaceService.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String cloudBase;
    private final StyleImportService styleImportService;
    private final ObjectMapper om = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Map<String, Progress> progressMap = new ConcurrentHashMap<>();

    public ProductReplaceService(@Value("${gofu.cloud.base-url:http://127.0.0.1:5020}") String cloudBase,
                                 StyleImportService styleImportService) {
        this.cloudBase = cloudBase != null ? cloudBase.replace("localhost", "127.0.0.1") : "http://127.0.0.1:5020";
        this.styleImportService = styleImportService;
    }

    /** 一次抽卡编排的进度。done 后 result 带 {contextId, cloudTaskId, total}；前端据 cloudTaskId 轮询云端 /api/flow/task 看逐张出图。 */
    public static class Progress {
        public volatile String phase = "排队中";
        public volatile int pct = 0;
        public volatile boolean done = false;
        public volatile String error;
        public volatile String contextId;
        public volatile String cloudTaskId;
        public volatile int total;
        // 识别段带出的品类/主件名/SKU清单(主件+配件)，前端筛6后回传 /api/semi-auto/generate-layout 用。
        public volatile Map<String, Object> recognized;
    }

    public Progress getProgress(String id) { return progressMap.get(id); }

    /**
     * 异步启动抽卡：建 context(白底→whiteImages、反推SKU) → 上传参考图 → 调云端 replace-mains 抽卡凑 count 张。
     * 返回后前端拿 replaceId 轮询 getProgress；done 后拿 cloudTaskId 直接轮询云端 /api/flow/task/{id} 看逐张出图，
     * 从 context.visual.mainImages 读 100 张预览。
     *
     * @param folderName 「品类-主件名」，用于反推 SKU/品类(下游算价/上新要)
     * @param whiteImgs  产品白底图(要替换进去的主体)，入 whiteImages
     * @param refImgs    N 张构图参考图(被替换的成品图)，仅上传作 refs 透传，不进 mainImages
     * @param count      总张数(默认 100)
     */
    public void startAsync(String replaceId, String folderName, List<StyleImportService.UpImg> whiteImgs,
                           List<StyleImportService.UpImg> refImgs, int count) {
        Progress pg = new Progress();
        pg.total = count;
        progressMap.put(replaceId, pg);
        pool.submit(() -> {
            try {
                // 1) 建 context：白底作产品主体入 whiteImages；main/detail/sku 空(参考图不进 mainImages)。
                //    复用 recognizeToContext 顺带反推 SKU/配件/品类，供下游 generate-layout+from-context。
                pg.phase = "建立商品档案 + 反推 SKU…"; pg.pct = 5;
                StyleImportService.Progress recPg = new StyleImportService.Progress();
                Map<String, Object> rec = styleImportService.recognizeToContext(
                        folderName, List.of(), List.of(), whiteImgs, List.of(), recPg);
                String contextId = String.valueOf(rec.get("contextId"));
                pg.contextId = contextId;
                pg.recognized = rec;   // {contextId,category,productName,skus,warnings} 供下游 generate-layout

                // 2) 上传参考图 → 云端 ref（不进 context，仅当 replace-mains 的 refImages 透传）
                pg.phase = "上传参考图到云端 (共" + refImgs.size() + "张)…"; pg.pct = 35;
                List<String> refKeys = new ArrayList<>();
                for (StyleImportService.UpImg r : refImgs) {
                    if (r.b64() == null || r.b64().isBlank()) continue;
                    String ext = "png".equalsIgnoreCase(r.ext()) ? "png" : "jpg";
                    Map<String, Object> resp = postJson("/api/gen/upload-image", Map.of("base64", r.b64(), "ext", ext));
                    String ref = resp.get("imageRef") != null ? String.valueOf(resp.get("imageRef")) : null;
                    if (ref != null && !ref.isBlank()) refKeys.add(ref);
                    else log.warn("[产品替换] 参考图上传失败(无 imageRef): {}", r.name());
                }
                if (refKeys.isEmpty()) throw new IllegalStateException("参考图全部上传失败");

                // 3) 调云端 replace-mains：抽卡凑 count 张，云端异步返回 taskId。不传 whiteBg(s)，
                //    云端缺省取 context.visual.whiteImages 全部(多SKU商品多白底会按白底数摊分 count)。
                pg.phase = "云端抽卡替换主体中 (共" + count + "张)…"; pg.pct = 60;
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("contextId", contextId);
                req.put("refImages", refKeys);
                req.put("count", count);
                Map<String, Object> resp = postJson("/api/flow/replace-mains", req);
                String cloudTaskId = resp.get("taskId") != null ? String.valueOf(resp.get("taskId")) : null;
                if (cloudTaskId == null || cloudTaskId.isBlank())
                    throw new IllegalStateException("云端未返回 taskId: " + resp);
                pg.cloudTaskId = cloudTaskId;

                log.info("[产品替换] replaceId={} contextId={} 参考图{} → 云端抽卡{}张 cloudTaskId={}",
                        replaceId, contextId, refKeys.size(), count, cloudTaskId);
                pg.phase = "抽卡已启动，逐张生成中…"; pg.pct = 100; pg.done = true;
            } catch (Exception e) {
                log.warn("[产品替换] 启动失败: {}", e.getMessage(), e);
                pg.error = e.getMessage() == null ? e.toString() : e.getMessage();
                pg.done = true;
            }
        });
    }

    /**
     * 人工筛 6：把选中的图 key 覆写进 context.visual.mainImages(读全量→改→POST 覆盖)。
     * 覆写后 mainImages 只剩这几张，下游 generate-layout/from-context 就按这几张走。
     */
    @SuppressWarnings("unchecked")
    public void pickMains(String contextId, List<String> keys) throws Exception {
        if (contextId == null || contextId.isBlank()) throw new IllegalArgumentException("contextId 不能为空");
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("至少选 1 张");
        Map<String, Object> ctx = getJson("/api/context/" + contextId);
        Map<String, Object> visual = (Map<String, Object>) ctx.get("visual");
        if (visual == null) { visual = new LinkedHashMap<>(); ctx.put("visual", visual); }
        visual.put("mainImages", new ArrayList<>(keys));
        postJson("/api/context", ctx);
        log.info("[产品替换] contextId={} 筛定主图 {} 张", contextId, keys.size());
    }

    private Map<String, Object> getJson(String path) throws Exception {
        Request req = new Request.Builder().url(cloudBase + path).get().build();
        try (Response resp = http.newCall(req).execute()) {
            String s = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) throw new RuntimeException("云端 " + path + " HTTP " + resp.code() + ": " + s);
            return om.readValue(s, Map.class);
        }
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

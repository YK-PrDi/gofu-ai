package com.gofu.local.service.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.local.model.SemiAutoScan;
import com.gofu.local.service.erp.KuaimaiService;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final KuaimaiService kuaimaiService;
    private final ObjectMapper om = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public StyleImportService(@Value("${gofu.cloud.base-url:http://127.0.0.1:5020}") String cloudBase,
                              SemiAutoService semiAutoService, KuaimaiService kuaimaiService) {
        this.cloudBase = cloudBase != null ? cloudBase.replace("localhost", "127.0.0.1") : "http://127.0.0.1:5020";
        this.semiAutoService = semiAutoService;
        this.kuaimaiService = kuaimaiService;
    }

    // ── 异步导入进度：导入是单次阻塞调用(上传16张+反推刷ERP缓存+云端出21个SKU方案+AI标题≈90秒)，
    //    同步等会让前端看着像卡死。改后台线程跑、按阶段写 Progress，前端轮询 /import-progress 显示"跑到哪步+已用时"。
    private final ExecutorService importPool = Executors.newFixedThreadPool(2);
    private final Map<String, Progress> importProgress = new ConcurrentHashMap<>();

    /** 一次导入的进度快照。phase=当前阶段中文名，pct=0..100，done/error/result 终态用。 */
    public static class Progress {
        public volatile String phase = "排队中";
        public volatile int pct = 0;
        public volatile boolean done = false;
        public volatile String error;            // 非空=失败
        public volatile Map<String, Object> result;   // done=true 时的 importToContext 返回值
        public final long startedAt = System.currentTimeMillis();
        void set(String ph, int p) { this.phase = ph; this.pct = p; }
    }

    /** 起一次异步导入，立即返回。前端拿 importId 轮询 getProgress。 */
    public void importAsync(String importId, String folderName, List<UpImg> mainImgs, List<UpImg> detailImgs,
                            List<UpImg> whiteImgs, List<UpImg> skuImgs) {
        Progress pg = new Progress();
        importProgress.put(importId, pg);
        importPool.submit(() -> {
            try {
                Map<String, Object> out = importToContext(folderName, mainImgs, detailImgs, whiteImgs, skuImgs, pg);
                pg.result = out; pg.set("完成", 100); pg.done = true;
            } catch (Exception e) {
                log.warn("[导入异步] 失败: {}", e.getMessage(), e);
                pg.error = e.getMessage() == null ? e.toString() : e.getMessage();
                pg.done = true;
            }
        });
    }

    public Progress getProgress(String importId) { return importProgress.get(importId); }

    /**
     * 批量流复用入口：给一个商品文件夹(本地路径)走导入链(建context→快麦拉白底→云端出方案+补SKU图)。
     * 目的:批量流"缺图·可AI生成"按钮复用已验证的导入补生逻辑，不重写生图。读本地图→base64→转 importAsync。
     * @param folderPath 商品文件夹绝对路径(内含 主图/详情/白底图/sku 子目录)；folderName 取其目录名(供品类/主件解析)
     */
    public void importAsyncFromFolder(String importId, String folderPath) {
        File dir = new File(folderPath);
        String folderName = dir.getName();
        List<UpImg> main = readDirImgs(dir, "主图", "main");
        List<UpImg> detail = readDirImgs(dir, "详情", "detail");
        List<UpImg> white = readDirImgs(dir, "白底", "white");
        List<UpImg> sku = readDirImgs(dir, "sku", "款式", "颜色");
        importAsync(importId, folderName, main, detail, white, sku);
    }

    /** 读商品文件夹下匹配任一关键词的子目录里的图，转 UpImg(base64)。找不到子目录返回空。 */
    private List<UpImg> readDirImgs(File productDir, String... keywords) {
        List<UpImg> out = new ArrayList<>();
        File[] subs = productDir.listFiles(File::isDirectory);
        if (subs == null) return out;
        for (File sub : subs) {
            String n = sub.getName().toLowerCase();
            boolean hit = false;
            for (String kw : keywords) if (n.contains(kw.toLowerCase())) { hit = true; break; }
            if (!hit) continue;
            File[] imgs = sub.listFiles(f -> f.isFile() && f.getName().toLowerCase().matches(".*\\.(jpe?g|png|webp)$"));
            if (imgs == null) continue;
            for (File f : imgs) {
                try {
                    String ext = f.getName().toLowerCase().endsWith(".png") ? "png" : "jpg";
                    String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(f.toPath()));
                    out.add(new UpImg(f.getName(), b64, ext));
                } catch (Exception e) { log.warn("[批量补生] 读图失败 {}: {}", f.getName(), e.getMessage()); }
            }
        }
        return out;
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
    /** 同步导入(保留供直接调用)：内部转发到带进度版，进度写到一个丢弃的 Progress。 */
    public Map<String, Object> importToContext(String folderName, List<UpImg> mainImgs, List<UpImg> detailImgs,
                                               List<UpImg> whiteImgs, List<UpImg> skuImgs) throws Exception {
        return importToContext(folderName, mainImgs, detailImgs, whiteImgs, skuImgs, new Progress());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> importToContext(String folderName, List<UpImg> mainImgs, List<UpImg> detailImgs,
                                               List<UpImg> whiteImgs, List<UpImg> skuImgs, Progress pg) throws Exception {
        if ((mainImgs == null || mainImgs.isEmpty()) && (detailImgs == null || detailImgs.isEmpty())
                && (whiteImgs == null || whiteImgs.isEmpty()))
            throw new IllegalStateException("没找到主图/详情图/白底图（文件夹需含 主图/ 详情/ 白底图/ 子目录）");

        FolderMeta meta = parseFolderName(folderName);
        String category = meta.category;
        String productName = meta.productName;

        // 1) 上传图 → 云端 ref（占进度 5~30%，逐张推进，让用户看到"正在传第几张"）
        int totalImgs = (mainImgs == null ? 0 : mainImgs.size()) + (detailImgs == null ? 0 : detailImgs.size())
                + (whiteImgs == null ? 0 : whiteImgs.size());
        pg.set("上传图片到云端 (共" + totalImgs + "张)", 5);
        int[] uploaded = {0};
        List<String> mainKeys = uploadAll(mainImgs, pg, uploaded, totalImgs);
        List<String> detailKeys = uploadAll(detailImgs, pg, uploaded, totalImgs);
        List<String> whiteKeys = uploadAll(whiteImgs, pg, uploaded, totalImgs);
        pg.set("反推 SKU 编码…", 32);

        // 2) 反推快麦编码 → 主件清单。名字来源按可靠度回退(用户约定:编码/人话主件名混用)：
        //    白底图名(=ERP编码,最准) → 缺则 SKU 图名 → 再缺则文件夹名「品类-」后按 + 拆的段。
        //    逐源试、命中即止：白底图缺失时也能靠 SKU 图名/文件夹名段出方案(修:sku=3 white=0 出不了方案的漏)。
        String cat = category == null ? "架类" : category;
        // 有序候选源(名称+值)，逐源试便于日志追踪到底哪一源命中/为何都没命中。
        List<String> srcTags = new ArrayList<>();
        List<List<String>> sources = new ArrayList<>();
        if (whiteImgs != null && !whiteImgs.isEmpty()) { srcTags.add("白底图名"); sources.add(namesOf(whiteImgs)); }
        if (skuImgs != null && !skuImgs.isEmpty())     { srcTags.add("sku图名");  sources.add(namesOf(skuImgs)); }
        List<String> folderSegs = splitCodeSegments(productName);
        if (!folderSegs.isEmpty()) { srcTags.add("文件夹名+段"); sources.add(folderSegs); }
        log.info("[导入·反推SKU] 文件夹={} 品类={} 候选源={} (白底{}·sku{}·文件夹段{})",
                folderName, cat, srcTags, whiteImgs == null ? 0 : whiteImgs.size(),
                skuImgs == null ? 0 : skuImgs.size(), folderSegs.size());

        // 跨源补齐(B)：合并所有源的候选名字一次反推(只刷一次 ERP 缓存)，命中按编码去重累积。
        //   例：sku图名命中2个 + 文件夹名段命中第3个 → 凑满3个；同编码来自多源只算一次(去重)。
        LinkedHashSet<String> allNames = new LinkedHashSet<>();
        for (List<String> names : sources) allNames.addAll(names);
        List<Map<String, Object>> rows = semiAutoService.reverseSkuFromImages(new ArrayList<>(allNames), cat);

        List<Map<String, Object>> mainSkus = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();   // 跨源去重:同一编码只建一个 SKU
        List<String> unmatchedHints = new ArrayList<>();
        int hit = 0;
        for (Map<String, Object> r : rows) {
            if (Boolean.TRUE.equals(r.get("matched"))) {
                String code = String.valueOf(r.get("code"));
                if (seenCodes.add(code)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("itemCode", r.get("code")); m.put("name", r.get("name")); m.put("role", "main");
                    mainSkus.add(m);
                }
                hit++;
            }
        }
        log.info("[导入·反推SKU] 合并{}源{}个候选 → 命中{}个(去重后唯一编码{}个)",
                sources.size(), allNames.size(), hit, seenCodes.size());
        if (mainSkus.isEmpty()) {   // 全部候选皆未命中:报"请按 ERP 编码命名"提示(部分命中则不吵,B补齐语义)
            log.warn("[导入·反推SKU] 全部候选均未命中快麦编码 → 出不了 SKU 方案(前端可手动选品)");
            for (Map<String, Object> r : rows)
                if (!Boolean.TRUE.equals(r.get("matched")) && r.get("error") != null)
                    unmatchedHints.add(String.valueOf(r.get("error")));
        }

        // 2.5) 白底图兜底：文件夹没放白底图子目录时，用反推命中的编码从快麦拉白底图 URL
        //      (白底图本就在快麦里，编码已知就该自动取，不该要求用户在文件夹里再放一份)。
        //      白底图是下游 SKU 图补生(step2)的结构/颜色参考，缺它整条自动链会停。
        if (whiteKeys.isEmpty() && !mainSkus.isEmpty()) {
            pg.set("从快麦拉取白底图…", 36);
            int got = 0;
            for (Map<String, Object> m : mainSkus) {
                String code = String.valueOf(m.get("itemCode"));
                try {
                    // 快麦白底图是 pdd 图床 http URL；云端 localizeWhite 支持 http URL(生图前自动下载)，
                    // 直接把 URL 存进 whiteImages 即可，无需本地转存 COS。
                    String url = kuaimaiService.findWhiteImageUrl(code);
                    if (url == null || url.isBlank()) { log.info("[导入·白底图] 编码 {} 快麦无白底图", code); continue; }
                    whiteKeys.add(url); got++;
                } catch (Exception e) { log.warn("[导入·白底图] 编码 {} 拉取失败: {}", code, e.getMessage()); }
            }
            log.info("[导入·白底图] 快麦兜底拉取白底图 {}/{} 张(命中编码{}个)", got, mainSkus.size(), mainSkus.size());
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
        pg.set("创建商品档案…", 42);
        Map<String, Object> saved = postJson("/api/context", ctx);
        String contextId = String.valueOf(saved.get("id"));

        // 4) 云端出 SKU 方案(默认3套)+AI标题，再把 sku 图按尺寸名次挂到方案(与自动上新同一条链)
        int planItemCount = generatePlansAndTitle(contextId, category, productName, mainSkus, skuImgs, pg);

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

    /** 逐张 base64 → 云端 upload-image → 收集公网 URL。带进度：上传阶段占 5~30%，按已传张数线性推进。 */
    private List<String> uploadAll(List<UpImg> imgs, Progress pg, int[] uploaded, int totalImgs) throws Exception {
        List<String> keys = new ArrayList<>();
        if (imgs == null) return keys;
        for (UpImg img : imgs) {
            if (img.b64() == null || img.b64().isBlank()) continue;
            String ext = "png".equalsIgnoreCase(img.ext()) ? "png" : "jpg";
            Map<String, Object> resp = postJson("/api/gen/upload-image", Map.of("base64", img.b64(), "ext", ext));
            String ref = resp.get("imageRef") != null ? String.valueOf(resp.get("imageRef")) : null;
            if (ref != null && !ref.isBlank()) keys.add(ref);
            else log.warn("导入上传失败(无 imageRef): {}", img.name());
            uploaded[0]++;
            if (totalImgs > 0) pg.set("上传图片到云端 (" + uploaded[0] + "/" + totalImgs + ")", 5 + (int) (25.0 * uploaded[0] / totalImgs));
        }
        return keys;
    }

    /** 无进度重载(同步旧调用兜底)。 */
    private List<String> uploadAll(List<UpImg> imgs) throws Exception {
        return uploadAll(imgs, new Progress(), new int[]{0}, imgs == null ? 0 : imgs.size());
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

    /** 取一批上传图的原文件名(供 reverseSku 提编码用)。 */
    private static List<String> namesOf(List<UpImg> imgs) {
        List<String> ns = new ArrayList<>();
        for (UpImg i : imgs) ns.add(i.name());
        return ns;
    }

    /** 主件名按「+」拆成候选编码段(全角＋归一化)，如 A+B+C → [A,B,C]；空段丢弃。用作反推兜底源。 */
    private static List<String> splitCodeSegments(String productName) {
        List<String> segs = new ArrayList<>();
        if (productName == null || productName.isBlank()) return segs;
        for (String p : productName.replace('＋', '+').split("\\+")) {
            String t = p.trim();
            if (!t.isEmpty()) segs.add(t);
        }
        return segs;
    }
    /**
     * 云端出 SKU 方案(默认3套) + AI标题，再把 sku 图按尺寸名次挂到方案 item.imgDir。
     * 走的是与自动上新同一条链(反推主件→快麦查信息→LLM规划器出多套方案)，只是入口是导入。
     * @return 方案里的 SKU item 总数(3套×每套型号数；0=没建出方案，前端仍能手动选品/生成)
     */
    @SuppressWarnings("unchecked")
    private int generatePlansAndTitle(String contextId, String category, String productName,
                                      List<Map<String, Object>> mainSkus, List<UpImg> skuImgs, Progress pg) {
        int itemCount = 0;
        List<String> skuNames = new ArrayList<>();
        // 4a) SKU 方案：有反推到主件才出(默认3套供挑选)。这步最慢(云端LLM规划)。
        if (!mainSkus.isEmpty()) {
            pg.set("AI 生成 SKU 方案中（默认3套，主件" + mainSkus.size() + "个，约需 1 分钟）…", 52);
            try {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("contextId", contextId); req.put("category", category == null ? "" : category);
                req.put("productName", productName); req.put("brand", "GOFU");
                req.put("skus", mainSkus); req.put("planCount", 3);   // 默认3套(后续前端再让用户选套数)
                Map<String, Object> r = postJson("/api/gen/sku-plans", req);
                if (r.get("savedItemCount") instanceof Number n) itemCount = n.intValue();
            } catch (Exception e) { log.warn("导入·SKU方案生成失败(不阻断): {}", e.getMessage()); }
        }
        for (Map<String, Object> m : mainSkus) skuNames.add(String.valueOf(m.get("name")));
        // 4b) AI 标题（与自动流程同款 mode=ai）
        pg.set("AI 生成标题…", 88);
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("contextId", contextId); req.put("mode", "ai");
            req.put("category", category == null ? "" : category); req.put("productType", category == null ? "" : category);
            req.put("productName", productName); req.put("brand", "GOFU"); req.put("skuNames", skuNames);
            postJson("/api/gen/title", req);
        } catch (Exception e) { log.warn("导入·AI标题生成失败(不阻断，留主件名): {}", e.getMessage()); }
        // 4c) sku 图按尺寸名次挂方案：上传 sku 图→云端接口按尺寸配对写回 item.imgDir（复用云端 #2 名次配对思路）
        if (skuImgs != null && !skuImgs.isEmpty() && itemCount > 0) {
            pg.set("挂载 SKU 成品图…", 94);
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

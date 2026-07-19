package com.gofu.local.service.listing;

import com.gofu.local.config.AppProperties;
import com.gofu.local.model.ListingConfig;
import com.gofu.shared.context.ProductContext;
import com.gofu.shared.context.SkuItem;
import com.gofu.shared.context.SkuPlan;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ProductContext → ListingConfig 转换 + 云端图产物下载落地（M7 串联核心）。
 *
 * <p>纯转换 + IO，无 Playwright。两个硬约束（见 M7 方案）：
 * <ol>
 *   <li>pdd_listing.js 只读<b>本地文件路径</b>，云端 COS key/URL 图产物须先下载落地。</li>
 *   <li>价格<b>元→分</b>：ProductContext.SkuItem.groupPrice(元,double) → ListingConfig(分,int)。</li>
 * </ol>
 */
@Service
public class ContextToListingMapper {

    private static final Logger log = LoggerFactory.getLogger(ContextToListingMapper.class);

    private final String cloudBase;
    private final AppProperties appProperties;
    private final ListingService listingService;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    public ContextToListingMapper(@Value("${gofu.cloud.base-url:http://localhost:5020}") String cloudBase,
                                  AppProperties appProperties,
                                  ListingService listingService) {
        this.cloudBase = cloudBase;
        this.appProperties = appProperties;
        this.listingService = listingService;
    }

    /**
     * 把 context 的指定方案转成上新配置。下载商品级主图/详情图到本地目录。
     *
     * @param ctx       商品全局上下文（含 visual 图 + structure 方案）
     * @param planIndex 选用的方案下标
     * @return 可直接喂给 {@link ListingService#runListing} 的配置
     */
    public ListingConfig toListingConfig(ProductContext ctx, int planIndex) throws Exception {
        return toListingConfig(ctx, planIndex, null, null);
    }

    /**
     * 同上，额外透传 material/brand（ProductContext 无这两个字段，由上新请求 body 提供）。
     * 补全 productType（category 末段派生）、whiteImgDir（context 白底图落地）、attributes（品类必填属性预设）。
     */
    public ListingConfig toListingConfig(ProductContext ctx, int planIndex,
                                         String material, String brand) throws Exception {
        if (ctx == null) throw new IllegalArgumentException("context 为空");

        ListingConfig cfg = new ListingConfig();
        cfg.setCategory(ctx.getCategory());
        cfg.setTitle(ctx.getVisual() != null ? ctx.getVisual().getTitle() : null);
        cfg.setProductName(ctx.getMainItem());
        cfg.setSkuSpecType("款式");
        cfg.setDiscount("9.9折");
        cfg.setDeliveryPromise("48小时发货及揽收");
        // productType：category 末段派生（含"花洒"→花洒，否则末段/架类），供脚本的花洒/架类分支判断
        cfg.setProductType(deriveProductType(ctx.getCategory()));
        if (material != null && !material.isBlank()) cfg.setMaterial(material);
        if (brand != null && !brand.isBlank()) cfg.setBrand(brand);

        // 图产物下载落地（COS key → 签名 URL → 本地目录）
        File root = new File(userDataDir(), "listing-tmp/" + safe(ctx.getId()));
        if (ctx.getVisual() != null) {
            cfg.setMainImgDir(downloadImages(ctx.getVisual().getMainImages(), new File(root, "main")));
            cfg.setDetailImgDir(downloadImages(ctx.getVisual().getDetailImages(), new File(root, "detail")));
            // 白底图落地：脚本上传"商品素材/白底图"区用（可选，缺则脚本跳过不报错）
            cfg.setWhiteImgDir(downloadImages(ctx.getVisual().getWhiteImages(), new File(root, "white")));
        }

        // SKU 组装（价格元→分）。首版 SKU 图沿用主图目录（M6 items 无 per-SKU 图，见方案子缺口）
        List<ListingConfig.SkuItem> skus = new ArrayList<>();
        SkuPlan plan = selectPlan(ctx, planIndex);
        int zeroPriceCount = 0;
        File skuImgRoot = new File(root, "sku");
        if (plan != null) {
            int si = 0;
            for (SkuItem it : plan.getItems()) {
                ListingConfig.SkuItem s = new ListingConfig.SkuItem();
                // 规格属性字符约束（07.06反馈）：拼多多规格值≤40字且仅允许白名单字符，
                // 否则脚本无法输入。此处 sanitize 保证上新拿到的 spec 合法。
                s.setName(sanitizeSpec(firstNonBlank(it.getSkuDisplayName(), it.getName())));
                s.setSpec1(sanitizeSpec(it.getSpec1()));
                s.setSpec2(sanitizeSpec(it.getSpec2()));
                s.setItemCode(it.getItemCode());
                s.setGroupPrice(toCents(it.getGroupPrice()));
                s.setSinglePrice(toCents(it.getSinglePrice()));
                s.setStock(it.getStock() > 0 ? it.getStock() : 8888);
                // SKU 图（07.06修）：用该 SKU 自己的生图产物 it.getImgDir()，下载成单文件传给脚本；
                // 缺失才回退商品级主图目录（原来写死主图目录→所有 SKU 都用第一张主图）。
                String skuFile = it.getImgDir() != null && !it.getImgDir().isBlank()
                        ? downloadOne(it.getImgDir(), new File(skuImgRoot, String.format("sku-%02d.jpg", si + 1)))
                        : null;
                s.setImgDir(skuFile != null ? skuFile : cfg.getMainImgDir());
                if (it.getGroupPrice() <= 0) zeroPriceCount++;
                skus.add(s);
                si++;
            }
        }
        cfg.setSkus(skus);
        // 价格兜底（修 bug4）：该方案有 SKU 价格为 0 → 拒绝上新，提示先在预览页定价（避免拼多多输 0 价）。
        if (!skus.isEmpty() && zeroPriceCount > 0) {
            throw new IllegalStateException("方案[" + planIndex + "]有 " + zeroPriceCount + "/" + skus.size()
                    + " 个 SKU 价格为 0，请先在预览页完成定价（切到该方案点\"按此重算定价\"）再上新");
        }

        // 属性：从品类预设填拼多多必填属性（有确定值的自动填，"人工选择"项留给脚本/人工）。
        // 材质单列一项覆盖（前端手填优先）。缺属性不阻断，脚本对未匹配项跳过并 log。
        cfg.setAttributes(buildAttributes(ctx.getCategory(), material));
        return cfg;
    }

    /** category 末段派生 productType：含"花洒"→花洒；否则取末段（如"卫浴置物架"）。 */
    private static String deriveProductType(String category) {
        if (category == null || category.isBlank()) return "架类";
        if (category.contains("花洒")) return "花洒";
        String leaf = category.contains(">") ? category.substring(category.lastIndexOf('>') + 1).trim() : category.trim();
        return leaf.isBlank() ? "架类" : leaf;
    }

    /** 品类预设属性 → {name:value} map（跳过 manual=人工选择项与空值）；material 非空则补/覆盖"材质"。 */
    private Map<String, String> buildAttributes(String category, String material) {
        Map<String, String> attrs = new LinkedHashMap<>();
        try {
            for (Map<String, Object> a : listingService.productInfoFor(category)) {
                boolean manual = Boolean.TRUE.equals(a.get("manual"));
                String name = String.valueOf(a.getOrDefault("name", "")).trim();
                String value = String.valueOf(a.getOrDefault("value", "")).trim();
                if (!manual && !name.isEmpty() && !value.isEmpty()) attrs.put(name, value);
            }
        } catch (Exception e) {
            log.warn("读取品类预设属性失败(留空继续): {}", e.getMessage());
        }
        if (material != null && !material.isBlank()) attrs.put("材质", material.trim());
        return attrs;
    }

    /** 价格元(double)→分(int)，四舍五入。 */
    static int toCents(double yuan) {
        return (int) Math.round(Math.max(0, yuan) * 100);
    }

    private SkuPlan selectPlan(ProductContext ctx, int planIndex) {
        if (ctx.getStructure() == null) return null;
        List<SkuPlan> plans = ctx.getStructure().getPlans();
        if (plans == null || plans.isEmpty()) return null;
        int idx = (planIndex >= 0 && planIndex < plans.size()) ? planIndex : 0;
        return plans.get(idx);
    }

    /**
     * 下载一组图产物到目标目录，返回目录绝对路径（无图返回 null）。
     * 每个 key 先调云端 /api/gen/sign 换签名 URL（未启用 COS 时返回的就是本地路径/原值）。
     */
    private String downloadImages(List<String> keys, File targetDir) {
        if (keys == null || keys.isEmpty()) return null;
        targetDir.mkdirs();
        int ok = 0;
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (key == null || key.isBlank()) continue;
            try {
                File out = new File(targetDir, String.format("%02d.jpg", i + 1));
                // key 是本地绝对路径(如 C:\...\erp-white\...png)：直接判存在，不去 sign/HTTP 下载
                // （本地缓存换机/清理后会不存在，当 URL 下载注定 404 且误导）。
                if (isLocalPath(key)) {
                    File localSrc = new File(key);
                    if (localSrc.isFile()) { Files.copy(localSrc.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING); ok++; }
                    else log.warn("白底图本地缓存缺失，跳过(不影响主图/详情，仅回传快麦少这张): {}", key);
                    continue;
                }
                // key 已是完整 http(s) URL(如快麦白底图/uploadPublic的公网永久URL)：直接下载。
                //  不能再调 sign→signKey 会把整条URL当成COS object key去私有桶签名→桶里无此对象→404
                //  (症状:主图/白底"下载HTTP 404"、上新轮播图为空)。只有裸 COS key 才需 sign 换签名URL。
                String url = isHttpUrl(key) ? key : sign(key);
                File localSrc = new File(url);
                if (localSrc.isFile()) {
                    Files.copy(localSrc.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    downloadUrl(url, out);
                }
                ok++;
            } catch (Exception e) {
                log.warn("下载图失败 key={}: {}", key, e.getMessage());
            }
        }
        return ok > 0 ? targetDir.getAbsolutePath() : null;
    }

    /** 下载单个 key 到指定文件，返回绝对路径（失败返回 null）。用于每个 SKU 挂自己的生图。 */
    private String downloadOne(String key, File out) {
        try {
            out.getParentFile().mkdirs();
            if (isLocalPath(key)) {
                File localSrc = new File(key);
                if (localSrc.isFile()) { Files.copy(localSrc.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING); return out.getAbsolutePath(); }
                log.warn("SKU 图本地缓存缺失，跳过: {}", key);
                return null;
            }
            // 同 downloadImages：完整 http(s) URL 直接下载，不 sign(避免对URL二次COS签名→404)。
            String url = isHttpUrl(key) ? key : sign(key);
            File localSrc = new File(url);
            if (localSrc.isFile()) {
                Files.copy(localSrc.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                downloadUrl(url, out);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            log.warn("下载 SKU 图失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    /** key 是否本地绝对路径（Windows 盘符 C:\ / 或 Unix 绝对路径 /），区别于 COS key / http URL。 */
    private boolean isLocalPath(String key) {
        if (key == null) return false;
        if (key.startsWith("http://") || key.startsWith("https://")) return false;
        return key.matches("^[A-Za-z]:[\\\\/].*") || key.startsWith("/") || key.contains("\\");
    }

    /** key 是否已是完整 http(s) URL（快麦白底图/uploadPublic 公网永久URL）：这类直接下载，不再 sign。 */
    private boolean isHttpUrl(String key) {
        return key != null && (key.startsWith("http://") || key.startsWith("https://"));
    }

    /** 调云端把永久 key 换成签名 URL（ADR-008）。 */
    private String sign(String key) throws Exception {
        String url = cloudBase + "/api/gen/sign?key=" + java.net.URLEncoder.encode(key, "UTF-8");
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new RuntimeException("sign HTTP " + resp.code());
            return resp.body() != null ? resp.body().string().trim() : key;
        }
    }

    private void downloadUrl(String url, File out) throws Exception {
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new RuntimeException("下载 HTTP " + resp.code());
            }
            try (InputStream in = resp.body().byteStream()) {
                Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private String userDataDir() {
        String dir = appProperties.getPaths().getUserDataDir();
        return (dir == null || dir.isBlank()) ? System.getProperty("user.dir") : dir;
    }

    private static String safe(String s) {
        return s == null ? "unknown" : s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    /**
     * 规格属性 sanitize（07.06反馈）：拼多多规格值最多 40 字，且仅允许
     * 中英文/数字/空格 + 部分英文符号(#:%'+-/.) + 部分中文符号(（）【】)。
     * 去掉不允许字符（如营销词里的 ✓、·、~、&、\ 等），再截断到 40 字，避免脚本无法输入。
     */
    static String sanitizeSpec(String s) {
        if (s == null || s.isBlank()) return s == null ? "" : s;
        // 允许：中文(含（）【】)、英文字母、数字、空格、# : % ' + - / .
        String cleaned = s.replaceAll("[^\\u4e00-\\u9fa5A-Za-z0-9 （）【】#:%'+\\-/.]", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40).trim();
        return cleaned;
    }
}

package com.gofu.local.service.listing;

import com.gofu.local.model.ListingConfig;
import com.gofu.local.model.SemiAutoScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 半自动批量上新编排（P5）。串起 P1~P4：扫描 → 按店匹配 profile → 逐商品过完整性
 * 强校验（P3 北极星）→ ready 才组本地 ListingConfig（带该店独立 cookie/profile）→
 * 串行错开调 pdd_listing.js 上新。
 *
 * <p>边界（诚实）：本编排**不内联** AI 选品/生 SKU 图（那是重流程）。缺 SKU 图/信息时
 * 由完整性校验拦下、明确报缺，交用户用现有生图/选品流补齐再重跑——绝不静默上残图。
 * 图为本地绝对路径，不走云端下载（与 ContextToListingMapper 的 ProductContext 路径不同）。
 */
@Service
public class SemiAutoOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SemiAutoOrchestrator.class);

    /** 店铺间/商品间错开启动间隔（毫秒），参考 DSR 防瞬时并发挤崩。 */
    private static final long STAGGER_MS = 8000;

    private final SemiAutoService semiAutoService;
    private final StoreService storeService;
    private final ListingService listingService;
    private final PricingService pricingService;

    public SemiAutoOrchestrator(SemiAutoService semiAutoService, StoreService storeService,
                                ListingService listingService, PricingService pricingService) {
        this.semiAutoService = semiAutoService;
        this.storeService = storeService;
        this.listingService = listingService;
        this.pricingService = pricingService;
    }

    /** 单商品的编排结果（供前端展示：上新了/被拦了/为什么）。 */
    public record ProductOutcome(
            String shopName, String productName, String status,   // listing_started / blocked / shop_unmatched / not_logged_in
            String taskId, List<String> missing) {}

    /**
     * 预检（dry）：只扫描+校验，不真上新。返回每商品的 outcome，让用户先看齐不齐、店铺匹没匹配。
     * @param skusByProduct 商品名→SKU列表(名/图/价，来自前端反推或选品)；无则视作缺 SKU。
     */
    public List<ProductOutcome> preflight(String rootPath,
                                          Map<String, List<SemiAutoScan.SkuCheck>> skusByProduct) {
        return walk(rootPath, skusByProduct, false);
    }

    /** 正式批量上新：预检通过的商品串行错开上新。 */
    public List<ProductOutcome> run(String rootPath,
                                    Map<String, List<SemiAutoScan.SkuCheck>> skusByProduct) {
        return walk(rootPath, skusByProduct, true);
    }

    private List<ProductOutcome> walk(String rootPath,
                                      Map<String, List<SemiAutoScan.SkuCheck>> skusByProduct,
                                      boolean doListing) {
        List<ProductOutcome> outcomes = new ArrayList<>();
        SemiAutoScan.Result scan = semiAutoService.scanRoot(rootPath, storeService::resolveProfileByName);
        boolean firstListing = true;

        for (SemiAutoScan.ShopGroup shop : scan.shops()) {
            for (SemiAutoScan.Product prod : shop.products()) {
                // 1) 店铺未匹配 stores.json → 无法上新
                if (!shop.matched()) {
                    outcomes.add(new ProductOutcome(shop.shopName(), prod.name(), "shop_unmatched", null,
                            List.of("店铺「" + shop.shopName() + "」未在 stores.json 匹配，请先在半自动页新增/登录该店铺")));
                    continue;
                }
                // 2) 该店未登录（无 cookie）→ 无法上新
                String cookiesPath = storeService.cookiesPathOf(shop.profile());
                if (!new java.io.File(cookiesPath).isFile()) {
                    outcomes.add(new ProductOutcome(shop.shopName(), prod.name(), "not_logged_in", null,
                            List.of("店铺「" + shop.shopName() + "」未登录，请先在半自动页登录")));
                    continue;
                }
                // 3) H2修：品类必填。文件夹名缺"-"(解析不出品类)→类目/属性会残缺,拼多多必填属性缺失
                //    会拖到 Playwright 提交才炸。前置拦下,别静默放行上残品。
                FolderMeta meta0 = parseFolderName(prod.name());
                if (meta0.category() == null || meta0.category().isBlank()) {
                    outcomes.add(new ProductOutcome(shop.shopName(), prod.name(), "blocked", null,
                            List.of("商品文件夹名「" + prod.name() + "」缺『品类-』前缀，无法确定拼多多类目/属性。请按「品类-主件名」命名(如 锅盖架-圣诞树收纳架)")));
                    continue;
                }
                // 4) 完整性强校验（北极星）
                List<SemiAutoScan.SkuCheck> skus = skusByProduct == null ? List.of()
                        : skusByProduct.getOrDefault(prod.name(), List.of());
                // (#5.1/#7 + C1定价断链) 前端没传 SKU 但商品有 SKU 图目录 → 自动按文件名反推快麦编码：
                //  · 命中的 → 用进价 cost 按默认利润率自动算拼单价 → 组成带定价的 SkuCheck 直接参与上新(全自动核心)。
                //  · 对不上的 → 明确报"请按 ERP 编码规范命名"。这样"人工只选大文件夹"才真能自动上新。
                List<String> nameHints = new ArrayList<>();
                if (skus.isEmpty() && prod.skuImgDir() != null && !prod.skuImgDir().isBlank()) {
                    List<String> skuImgs = semiAutoService.listImages(prod.skuImgDir());
                    if (!skuImgs.isEmpty()) {
                        List<Map<String, Object>> rows = semiAutoService.reverseSkuFromImages(skuImgs, "架类");
                        List<SemiAutoScan.SkuCheck> reversed = new ArrayList<>();
                        for (Map<String, Object> r : rows) {
                            if (Boolean.TRUE.equals(r.get("matched"))) {
                                double cost = r.get("cost") instanceof Number n ? n.doubleValue() : 0;
                                double price = pricingService.autoGroupPrice(cost);   // 进价→默认利润率算售价
                                if (price <= 0) {
                                    nameHints.add("SKU「" + r.get("name") + "」快麦无进价，无法自动定价，请在快麦补进价或预览页手动定价");
                                    continue;
                                }
                                reversed.add(new SemiAutoScan.SkuCheck(
                                        String.valueOf(r.get("name")), String.valueOf(r.get("file")), price));
                            } else {
                                nameHints.add(String.valueOf(r.get("error")));
                            }
                        }
                        if (!reversed.isEmpty()) skus = reversed;   // 反推成功的作为该商品 SKU
                    }
                }
                SemiAutoScan.Completeness c = semiAutoService.checkCompleteness(
                        prod.name(), prod.mainImgDir(), prod.detailImgDir(), skus);
                if (!c.ready()) {
                    // 有命名/定价提示则用它替换笼统的"没有任何 SKU"，让用户知道具体怎么改
                    List<String> missing = new ArrayList<>(c.missing());
                    if (!nameHints.isEmpty()) {
                        missing.removeIf(m -> m.contains("没有任何 SKU"));
                        missing.addAll(nameHints);
                    }
                    outcomes.add(new ProductOutcome(shop.shopName(), prod.name(), "blocked", null, missing));
                    continue;
                }
                // 4) 齐全 → 组本地 config 上新（预检模式只标 ready 不真上）
                if (!doListing) {
                    outcomes.add(new ProductOutcome(shop.shopName(), prod.name(), "ready", null, List.of()));
                    continue;
                }
                try {
                    if (!firstListing) Thread.sleep(STAGGER_MS);   // 错开
                    firstListing = false;
                    ListingConfig cfg = buildConfig(prod, skus, shop);
                    String taskId = listingService.runListing(cfg, false);
                    outcomes.add(new ProductOutcome(shop.shopName(), prod.name(), "listing_started", taskId, List.of()));
                } catch (Exception e) {
                    log.warn("[半自动] 商品「{}」上新启动失败: {}", prod.name(), e.getMessage());
                    outcomes.add(new ProductOutcome(shop.shopName(), prod.name(), "blocked", null,
                            List.of("上新启动失败：" + e.getMessage())));
                }
            }
        }
        return outcomes;
    }

    /** 从本地目录 + 校验通过的 SKU 组 ListingConfig，注入该店独立 cookie/profile 路径。 */
    private ListingConfig buildConfig(SemiAutoScan.Product prod, List<SemiAutoScan.SkuCheck> skus,
                                      SemiAutoScan.ShopGroup shop) {
        ListingConfig cfg = new ListingConfig();
        // (C #3) 从文件夹名解析 品类-主件名 / 品类-主件1+主件2，批量上新不再需要人工选品。
        FolderMeta meta = parseFolderName(prod.name());
        cfg.setProductName(meta.productName);
        if (meta.category != null && !meta.category.isBlank()) {
            String fullCat = listingService.resolveCategoryPath(meta.category);
            cfg.setCategory(fullCat);
            cfg.setProductType(fullCat.contains(">") ? fullCat.substring(fullCat.lastIndexOf('>') + 1).trim() : fullCat);
            // 品类预设属性(材质/表面工艺/产地…)自动填，与单品上新一致
            Map<String, String> attrs = new java.util.LinkedHashMap<>();
            try {
                for (Map<String, Object> a : listingService.productInfoFor(fullCat)) {
                    boolean manual = Boolean.TRUE.equals(a.get("manual"));
                    String n = String.valueOf(a.getOrDefault("name", "")).trim();
                    String v = String.valueOf(a.getOrDefault("value", "")).trim();
                    if (!manual && !n.isEmpty() && !v.isEmpty()) attrs.put(n, v);
                }
            } catch (Exception e) { log.warn("批量上新读品类预设属性失败(留空继续): {}", e.getMessage()); }
            if (!attrs.isEmpty()) cfg.setAttributes(attrs);
        }
        cfg.setSkuSpecType("款式");
        cfg.setMainImgDir(prod.mainImgDir());
        cfg.setDetailImgDir(prod.detailImgDir());
        cfg.setCookiesPath(storeService.cookiesPathOf(shop.profile()));
        cfg.setUserDataDir(storeService.userDataDirOf(shop.profile()));

        List<ListingConfig.SkuItem> items = new ArrayList<>();
        for (SemiAutoScan.SkuCheck s : skus) {
            ListingConfig.SkuItem it = new ListingConfig.SkuItem();
            it.setName(s.name());
            it.setImgDir(s.imgPath() != null && !s.imgPath().isBlank() ? s.imgPath() : prod.mainImgDir());
            it.setGroupPrice((int) Math.round(s.price() * 100));   // 元→分
            items.add(it);
        }
        cfg.setSkus(items);
        return cfg;
    }

    /** 文件夹名解析结果：品类(可空) + 商品名(多主件用+连接)。 */
    private record FolderMeta(String category, String productName) {}

    /**
     * (C #3) 解析商品文件夹名的「品类-主件名」约定：
     *  - "锅盖架-落地锅盖架"        → category=锅盖架, productName=落地锅盖架
     *  - "锅盖架-落地款+吸盘款"      → category=锅盖架, productName=落地款+吸盘款(多主件保留+)
     *  - "家装主材>厨房>厨房挂件>锅盖架-落地款" → category=全路径, productName=落地款
     *  - 无"-"(旧命名)              → category=null, productName=整个文件夹名(退化，仍需人工选品)
     * 规则：**第一个"-"**分隔品类与主件（品类名一般不含"-"，主件名可含"+"表多主件）。
     */
    private FolderMeta parseFolderName(String folderName) {
        if (folderName == null || folderName.isBlank()) return new FolderMeta(null, folderName);
        // M3：全角连字符－/全角空格 归一化，中文输入法常打出全角横线导致 indexOf('-') 落空→误判无品类
        String s = folderName.trim().replace('－', '-').replace('　', ' ');
        int dash = s.indexOf('-');
        if (dash <= 0 || dash >= s.length() - 1) {
            return new FolderMeta(null, s.replaceAll("^-+|-+$", "").trim());   // 无有效"-"：退化,且去掉首尾脏横线
        }
        String cat = s.substring(0, dash).trim();
        String name = s.substring(dash + 1).trim();
        return new FolderMeta(cat, name);
    }
}

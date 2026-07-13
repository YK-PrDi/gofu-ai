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

    public SemiAutoOrchestrator(SemiAutoService semiAutoService, StoreService storeService,
                                ListingService listingService) {
        this.semiAutoService = semiAutoService;
        this.storeService = storeService;
        this.listingService = listingService;
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
                // 3) 完整性强校验（北极星）
                List<SemiAutoScan.SkuCheck> skus = skusByProduct == null ? List.of()
                        : skusByProduct.getOrDefault(prod.name(), List.of());
                SemiAutoScan.Completeness c = semiAutoService.checkCompleteness(
                        prod.name(), prod.mainImgDir(), prod.detailImgDir(), skus);
                if (!c.ready()) {
                    outcomes.add(new ProductOutcome(shop.shopName(), prod.name(), "blocked", null, c.missing()));
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
        cfg.setProductName(prod.name());
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
}

package com.gofu.local.service.listing;

import com.gofu.local.config.AppProperties;
import com.gofu.shared.context.ProductContext;
import com.gofu.shared.context.SkuItem;
import com.gofu.shared.context.SkuPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContextToListingMapper 转换单测（M7）。纯数据变换，无图（避免触发网络下载）。
 * 验价格元→分、字段映射、方案选择、SKU 组装。
 */
class ContextToListingMapperTest {

    private ContextToListingMapper mapper() {
        AppProperties props = new AppProperties();
        // ListingService 仅用于取品类预设属性（无 xlsx 时返回空、不抛错），构造用假 TaskService
        ListingService listingService = new ListingService(props, new TaskService(props), new StoreService(props));
        return new ContextToListingMapper("http://localhost:5020", props, listingService);
    }

    @Test
    void toCents_yuanToCentRounding() {
        assertEquals(3990, ContextToListingMapper.toCents(39.9));
        assertEquals(11, ContextToListingMapper.toCents(0.114));   // 0.114→0.11元→11分(四舍五入)
        assertEquals(0, ContextToListingMapper.toCents(0));
        assertEquals(0, ContextToListingMapper.toCents(-5));       // 负数兜底 0
    }

    @Test
    void toListingConfig_mapsFieldsAndPrices() throws Exception {
        ProductContext ctx = new ProductContext();
        ctx.setId("t1");
        ctx.setCategory("家装主材>卫浴>花洒喷头");
        ctx.setMainItem("GF-099");
        ctx.getVisual().setTitle("增压花洒标题");

        SkuItem a = new SkuItem();
        a.setSkuDisplayName("银色-单品"); a.setName("银色"); a.setSpec1("银色花洒"); a.setSpec2("单品");
        a.setItemCode("GF-099-银"); a.setGroupPrice(39.9); a.setSinglePrice(45.0); a.setStock(8888);
        SkuItem b = new SkuItem();
        b.setSkuDisplayName("银色-配支架"); b.setSpec2("+支架");
        b.setItemCode("GF-099-银+052底座*1"); b.setGroupPrice(59.9); b.setStock(0);  // stock 0 → 兜底 8888

        SkuPlan plan = new SkuPlan();
        plan.setPlanName("官方标配版");
        plan.getItems().addAll(List.of(a, b));
        ctx.getStructure().getPlans().add(plan);

        var cfg = mapper().toListingConfig(ctx, 0);

        assertEquals("家装主材>卫浴>花洒喷头", cfg.getCategory());
        assertEquals("增压花洒标题", cfg.getTitle());
        assertEquals("款式", cfg.getSkuSpecType());
        assertEquals("9.9折", cfg.getDiscount());
        assertNull(cfg.getMainImgDir());   // 无图 → null

        assertEquals(2, cfg.getSkus().size());
        var s0 = cfg.getSkus().get(0);
        assertEquals("银色-单品", s0.getName());
        assertEquals("GF-099-银", s0.getItemCode());
        assertEquals(3990, s0.getGroupPrice());   // 39.9元→3990分
        assertEquals(4500, s0.getSinglePrice());
        assertEquals(8888, s0.getStock());

        var s1 = cfg.getSkus().get(1);
        assertEquals(5990, s1.getGroupPrice());
        assertEquals(8888, s1.getStock());        // stock 0 兜底
    }

    @Test
    void toListingConfig_planIndexOutOfRange_fallbackToFirst() throws Exception {
        ProductContext ctx = new ProductContext();
        ctx.setId("t2");
        SkuPlan p = new SkuPlan(); p.setPlanName("唯一方案");
        SkuItem it = new SkuItem(); it.setName("x"); it.setGroupPrice(10);
        p.getItems().add(it);
        ctx.getStructure().getPlans().add(p);

        var cfg = mapper().toListingConfig(ctx, 99);   // 越界 → 回退方案 0
        assertEquals(1, cfg.getSkus().size());
        assertEquals(1000, cfg.getSkus().get(0).getGroupPrice());
    }

    @Test
    void toListingConfig_emptyStructure_safe() throws Exception {
        ProductContext ctx = new ProductContext();
        ctx.setId("t3");
        var cfg = mapper().toListingConfig(ctx, 0);
        assertTrue(cfg.getSkus().isEmpty());
    }

    @Test
    void sanitizeSpec_stripsDisallowedAndTruncates() {
        // 去掉不允许字符（✓·~&反斜杠），保留白名单（中英文数字空格 #:%'+-/. （）【】）
        assertEquals("【雅黑色】过滤增压按摩",
                ContextToListingMapper.sanitizeSpec("【雅黑色】过滤增压✓按摩"));
        assertEquals("喷头+稳固银底座+5支滤芯【一年装】",
                ContextToListingMapper.sanitizeSpec("喷头+稳固银底座+5支滤芯【一年装】"));
        assertEquals("月光银净水防爆软管测试",
                ContextToListingMapper.sanitizeSpec("月光银·净水~防爆&软管\\测试"));
        // 超 40 字截断
        assertEquals(40, ContextToListingMapper.sanitizeSpec("字".repeat(50)).length());
        // null/空安全
        assertEquals("", ContextToListingMapper.sanitizeSpec(null));
    }
}

package com.gofu.local.service.listing;

import com.gofu.local.model.SemiAutoScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 半自动扫描单测（P1）。纯文件系统逻辑，用临时目录造两级结构验证。
 * 核心：目录名识别角色、两级遍历、SKU缺失不告警(走AI补)、主图/详情缺则告警、自然序。
 */
class SemiAutoServiceTest {

    // 扫描/自然序不碰 ERP，传 null KuaimaiService 即可；反推逻辑的编码提取单独测 static 方法。
    private final SemiAutoService svc = new SemiAutoService(null);

    private void mkImg(File dir, String name) throws IOException {
        assertTrue(dir.mkdirs() || dir.isDirectory());
        assertTrue(new File(dir, name).createNewFile());
    }

    @Test
    void scanProduct_按目录名识别角色_SKU缺失不告警(@TempDir Path tmp) throws IOException {
        File prod = tmp.resolve("商品A").toFile();
        mkImg(new File(prod, "主图"), "1.jpg");
        mkImg(new File(prod, "详情"), "1.jpg");
        // 不建 sku 目录

        SemiAutoScan.Product p = svc.scanProduct(prod);
        assertTrue(p.mainImgDir().endsWith("主图"));
        assertTrue(p.detailImgDir().endsWith("详情"));
        assertEquals("", p.skuImgDir(), "SKU 目录缺 → 空（走 AI 生成）");
        // 主图/详情齐 → 无这两类告警；SKU 缺不告警
        assertTrue(p.warnings().isEmpty(), "主图详情齐、SKU缺不算警告：" + p.warnings());
    }

    @Test
    void scanProduct_缺主图详情_告警(@TempDir Path tmp) throws IOException {
        File prod = tmp.resolve("商品B").toFile();
        mkImg(new File(prod, "sku"), "1.jpg");   // 只有 sku
        SemiAutoScan.Product p = svc.scanProduct(prod);
        assertTrue(p.skuImgDir().endsWith("sku"));
        assertEquals(2, p.warnings().size(), "缺主图+详情两条告警");
    }

    @Test
    void scanRoot_两级遍历_店铺未匹配告警(@TempDir Path tmp) throws IOException {
        // 大文件夹/店铺X/商品1/{主图,详情}
        File shopX = tmp.resolve("GOFU厨卫配件官方旗舰店").toFile();
        File prod1 = new File(shopX, "商品1");
        mkImg(new File(prod1, "主图"), "1.jpg");
        mkImg(new File(prod1, "详情"), "1.jpg");

        // shopResolver=null → 全部未匹配
        SemiAutoScan.Result r = svc.scanRoot(tmp.toString(), null);
        assertEquals(1, r.shops().size());
        SemiAutoScan.ShopGroup g = r.shops().get(0);
        assertEquals("GOFU厨卫配件官方旗舰店", g.shopName());
        assertFalse(g.matched(), "P1 无 resolver → 未匹配");
        assertEquals(1, g.products().size());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("未匹配")));
    }

    @Test
    void scanRoot_resolver匹配到profile(@TempDir Path tmp) throws IOException {
        File shop = tmp.resolve("GOFU厨卫配件官方旗舰店").toFile();
        mkImg(new File(shop, "商品1/主图"), "1.jpg");
        // resolver：按名返回 profile
        SemiAutoScan.Result r = svc.scanRoot(tmp.toString(),
                name -> name.contains("厨卫配件") ? "store_13" : null);
        SemiAutoScan.ShopGroup g = r.shops().get(0);
        assertTrue(g.matched());
        assertEquals("store_13", g.profile());
    }

    @Test
    void listImages_自然序(@TempDir Path tmp) throws IOException {
        File dir = tmp.resolve("imgs").toFile();
        mkImg(dir, "10.jpg"); mkImg(dir, "2.jpg"); mkImg(dir, "1.jpg");
        mkImg(dir, "notes.txt");   // 非图片应被过滤
        List<String> imgs = svc.listImages(dir.getAbsolutePath());
        assertEquals(3, imgs.size(), "只收图片，txt 被过滤");
        assertTrue(imgs.get(0).endsWith("1.jpg"));
        assertTrue(imgs.get(1).endsWith("2.jpg"));
        assertTrue(imgs.get(2).endsWith("10.jpg"), "自然序 1<2<10");
    }

    @Test
    void listImages_非法目录返回空() {
        assertTrue(svc.listImages("").isEmpty());
        assertTrue(svc.listImages("/no/such/dir/xyz").isEmpty());
    }

    @Test
    void codeFromFilename_去前缀序号与配件描述取编码主体() {
        // 乐羽导出命名：'1_GF-106-银色-1 喷头+1.5米防爆软管.jpg' → 'GF-106-银色-1'
        assertEquals("GF-106-银色-1", SemiAutoService.codeFromFilename("1_GF-106-银色-1 喷头+1.5米防爆软管.jpg"));
        // 纯编码文件名（用户按规范命名）
        assertEquals("GF-奶白-湿纸巾架", SemiAutoService.codeFromFilename("GF-奶白-湿纸巾架.jpg"));
        // 前缀数字+短横
        assertEquals("GF-枪灰-湿纸巾架", SemiAutoService.codeFromFilename("2-GF-枪灰-湿纸巾架.png"));
        // 含 + 的编码，无空格则整体保留
        assertEquals("GF-灰花洒-820+1.5米软管", SemiAutoService.codeFromFilename("GF-灰花洒-820+1.5米软管.jpg"));
        // 边界
        assertEquals("", SemiAutoService.codeFromFilename(null));
        assertEquals("", SemiAutoService.codeFromFilename(".jpg"));
    }
}

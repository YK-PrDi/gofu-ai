package com.gofu.local.service.listing;

import com.gofu.local.model.SemiAutoScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 半自动批量上新 · 文件夹扫描与两级遍历（P1）。
 *
 * <p>独立于全自动流（不碰 workbench/选品）。职责：
 * <ul>
 *   <li>扫单商品文件夹 → 按目录名识别 主图/详情/sku/白底 角色（迁自乐羽 scanFolder）</li>
 *   <li>扫大文件夹 → 两级遍历：一级=店铺名子目录、二级=商品文件夹</li>
 * </ul>
 * 店铺名↔stores.json 匹配在 P4 由 StoreService 注入，本类先留 matched=false。
 * 全程本地绝对路径，供后续 AI 补全 / 完整性校验 / 上新使用。
 */
@Service
public class SemiAutoService {

    private static final Logger log = LoggerFactory.getLogger(SemiAutoService.class);

    private static final String IMG_EXT = ".*\\.(jpg|jpeg|png|webp|bmp|gif)$";

    /** 扫单商品文件夹，按子目录名识别各角色目录（纯目录名约定，无内容识别）。 */
    public SemiAutoScan.Product scanProduct(File dir) {
        String mainImgDir = "", detailImgDir = "", skuImgDir = "", whiteImgDir = "";
        List<String> warnings = new ArrayList<>();

        File[] subs = dir.listFiles(File::isDirectory);
        if (subs != null) {
            for (File d : subs) {
                String n = d.getName().toLowerCase();
                if (n.contains("主图") || n.equals("main")) mainImgDir = d.getAbsolutePath();
                else if (n.contains("sku") || n.contains("款式") || n.contains("颜色")) skuImgDir = d.getAbsolutePath();
                else if (n.contains("详情") || n.contains("detail")) detailImgDir = d.getAbsolutePath();
                else if (n.contains("白底") || n.contains("white")) whiteImgDir = d.getAbsolutePath();
            }
        }
        if (mainImgDir.isEmpty()) warnings.add("缺主图目录（命名需含\"主图\"/\"main\"）");
        if (detailImgDir.isEmpty()) warnings.add("缺详情图目录（命名需含\"详情\"/\"detail\"）");
        // SKU 图缺失不告警：允许走 AI 生成（P2）。

        return new SemiAutoScan.Product(dir.getName(), dir.getAbsolutePath(),
                mainImgDir, detailImgDir, skuImgDir, whiteImgDir, warnings);
    }

    /**
     * 扫大文件夹 → 两级遍历。一级子目录=店铺，其下每个子目录=一个商品。
     * @param shopResolver 店铺名→profile 解析器（P4 注入；P1 传 null → 全部 matched=false）
     */
    public SemiAutoScan.Result scanRoot(String rootPath, java.util.function.Function<String, String> shopResolver) {
        File root = new File(rootPath);
        List<SemiAutoScan.ShopGroup> shops = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!root.isDirectory()) {
            warnings.add("路径不是文件夹：" + rootPath);
            return new SemiAutoScan.Result(rootPath, shops, warnings);
        }

        File[] shopDirs = root.listFiles(File::isDirectory);
        if (shopDirs == null || shopDirs.length == 0) {
            warnings.add("大文件夹下没有店铺子目录");
            return new SemiAutoScan.Result(rootPath, shops, warnings);
        }
        Arrays.sort(shopDirs, Comparator.comparing(File::getName, SemiAutoService::naturalCompare));

        for (File shopDir : shopDirs) {
            String shopName = shopDir.getName();
            String profile = shopResolver != null ? shopResolver.apply(shopName) : null;
            boolean matched = profile != null && !profile.isBlank();
            if (!matched) warnings.add("店铺目录「" + shopName + "」未匹配到 stores.json，将无法上新（请核对店铺名或先登录该店铺）");

            List<SemiAutoScan.Product> products = new ArrayList<>();
            File[] prodDirs = shopDir.listFiles(File::isDirectory);
            if (prodDirs != null) {
                Arrays.sort(prodDirs, Comparator.comparing(File::getName, SemiAutoService::naturalCompare));
                for (File p : prodDirs) products.add(scanProduct(p));
            }
            if (products.isEmpty()) warnings.add("店铺「" + shopName + "」下没有商品文件夹");

            shops.add(new SemiAutoScan.ShopGroup(shopName, matched, matched ? profile : "", products));
        }
        log.info("[半自动扫描] {} → {} 店铺, 共 {} 商品", rootPath, shops.size(),
                shops.stream().mapToInt(s -> s.products().size()).sum());
        return new SemiAutoScan.Result(rootPath, shops, warnings);
    }

    /** 扫某目录下的图片，按文件名自然数字序返回绝对路径（迁自乐羽）。 */
    public List<String> listImages(String folderPath) {
        List<String> out = new ArrayList<>();
        File dir = new File(folderPath);
        if (folderPath == null || folderPath.isBlank() || !dir.isDirectory()) return out;
        File[] imgs = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().matches(IMG_EXT));
        if (imgs != null) {
            Arrays.sort(imgs, Comparator.comparing(File::getName, SemiAutoService::naturalCompare));
            for (File f : imgs) out.add(f.getAbsolutePath());
        }
        return out;
    }

    /** 文件名自然排序：1 < 2 < 10（按文件名数字段比较，迁自乐羽）。 */
    static int naturalCompare(String a, String b) {
        String na = a.replaceAll("\\.[^.]+$", "").replaceAll("\\D", "");
        String nb = b.replaceAll("\\.[^.]+$", "").replaceAll("\\D", "");
        if (!na.isEmpty() && !nb.isEmpty()) {
            try {
                int cmp = Long.compare(Long.parseLong(na), Long.parseLong(nb));
                if (cmp != 0) return cmp;
            } catch (NumberFormatException ignore) {}
        }
        return a.compareTo(b);
    }
}

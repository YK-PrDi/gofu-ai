package com.gofu.local.service.listing;

import com.gofu.local.model.SemiAutoScan;
import com.gofu.local.service.erp.KuaimaiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final KuaimaiService kuaimaiService;

    public SemiAutoService(KuaimaiService kuaimaiService) {
        this.kuaimaiService = kuaimaiService;
    }

    /** 扫单商品文件夹，按子目录名识别各角色目录（纯目录名约定，无内容识别）。 */
    public SemiAutoScan.Product scanProduct(File dir) {
        String mainImgDir = "", detailImgDir = "", skuImgDir = "", whiteImgDir = "";
        List<String> warnings = new ArrayList<>();

        File[] subs = dir.listFiles(File::isDirectory);
        List<String> unknownDirs = new ArrayList<>();   // M1：未识别为任何角色的子目录，收集后告警(别静默吞掉用户放错的图)
        if (subs != null) {
            for (File d : subs) {
                String n = d.getName().toLowerCase();
                if (n.contains("主图") || n.contains("main")) mainImgDir = d.getAbsolutePath();   // M2:equals→contains,认得 main-images/mainpic
                else if (n.contains("sku") || n.contains("款式") || n.contains("颜色")) skuImgDir = d.getAbsolutePath();
                else if (n.contains("详情") || n.contains("detail")) detailImgDir = d.getAbsolutePath();
                else if (n.contains("白底") || n.contains("white")) whiteImgDir = d.getAbsolutePath();
                else if (!d.isHidden() && !d.getName().startsWith(".")) unknownDirs.add(d.getName());
            }
        }
        if (mainImgDir.isEmpty()) warnings.add("缺主图目录（命名需含\"主图\"/\"main\"）");
        if (detailImgDir.isEmpty()) warnings.add("缺详情图目录（命名需含\"详情\"/\"detail\"）");
        // SKU 图缺失不告警：允许走 AI 生成（P2）。
        // M1：未识别子目录告警——用户可能把主图放在"效果图""场景图"里，别静默丢让他以为缺图
        if (!unknownDirs.isEmpty())
            warnings.add("以下子目录未被识别为任何角色(主图/详情/白底/sku)，已忽略：" + String.join("、", unknownDirs)
                    + "。若里面有图请改名含 主图/详情/白底/sku 关键词");

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

    /**
     * 从 SKU 图文件名提取编码候选：去扩展名、去前缀"数字_"序号、去空格后配件描述。
     * ERP 编码含中文无固定分隔（如 GF-奶白-湿纸巾架、GF-灰花洒-820+1.5米软管），
     * 故不按段切，取"主体串"作候选：去掉乐羽导出常见的"N_"前缀，空格前主体。
     * 例：'1_GF-106-银色-1 喷头+1.5米防爆软管.jpg' → 'GF-106-银色-1'
     */
    static String codeFromFilename(String fileName) {
        if (fileName == null) return "";
        String s = fileName.replaceAll("\\.[^.]+$", "").trim();      // 去扩展名
        s = s.replaceFirst("^\\d+[_\\-\\s]+", "");                   // 去前缀 "1_" / "1-" / "1 "
        int sp = s.indexOf(' ');                                     // 空格后是配件描述，截主体
        if (sp > 0) s = s.substring(0, sp);
        return s.trim();
    }

    /**
     * SKU 图反推：对一批 SKU 图文件名，逐个提编码 → 查快麦 ERP 缓存。
     * 命中 → {matched:true, code, name, cost, weight}；
     * 未命中(ERP无此编码=命名不规范) → {matched:false, code, file, error}。
     * 接北极星：未命中不静默跳过，交由调用方明确提示用户规范命名。
     */
    public List<Map<String, Object>> reverseSkuFromImages(List<String> imagePaths, String productType) {
        List<Map<String, Object>> out = reverseOnce(imagePaths);
        // 自动刷缓存重试：首轮有未命中(可能是刚在快麦建的新编码，本地缓存旧)→强制 reload 一次 ERP 缓存再重试。
        // 导入是自动链，不该停下让人工手动点"刷新缓存"(用户要求)。只在确有 unmatched 时刷，避免每次都拉全量。
        boolean anyUnmatched = out.stream().anyMatch(r -> !Boolean.TRUE.equals(r.get("matched")));
        if (anyUnmatched) {
            try {
                log.info("[反推SKU] 有编码未命中缓存，自动刷新一次 ERP 缓存后重试…");
                kuaimaiService.reloadSkuItems();
                out = reverseOnce(imagePaths);
            } catch (Exception e) {
                log.warn("[反推SKU] 自动刷新 ERP 缓存失败(用旧缓存结果): {}", e.getMessage());
            }
        }
        return out;
    }

    /** 单轮反推：逐图提编码→查快麦缓存(不刷新)。 */
    private List<Map<String, Object>> reverseOnce(List<String> imagePaths) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String path : imagePaths) {
            File f = new File(path);
            String code = codeFromFilename(f.getName());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", path);
            row.put("code", code);
            Map<String, Object> cached = null;
            if (!code.isBlank()) {
                try { cached = kuaimaiService.getCachedItemByOuterId(code); } catch (Exception ignore) {}
            }
            if (cached != null) {
                double cost = toD(cached.get("purchasePrice"));
                row.put("matched", true);
                row.put("name", String.valueOf(cached.getOrDefault("title", code)));
                row.put("cost", cost);
                row.put("weight", toD(cached.get("weight")));
            } else {
                row.put("matched", false);
                row.put("error", "快麦 ERP 未找到编码「" + code + "」，请把该 SKU 图按 ERP 商品编码规范命名（如 GF-奶白-湿纸巾架.jpg）");
            }
            out.add(row);
        }
        return out;
    }

    private static double toD(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        try { return v == null ? 0 : Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    /**
     * 商品上新前完整性强校验（P3 北极星）。
     * 齐全条件：主图目录非空 + 详情目录非空 + ≥1 个 SKU 且每个 SKU 图/名/价(>0)俱全。
     * 不齐 → ready=false + missing 明确列出缺什么，供调用方补齐或拒绝上新，绝不静默上残图。
     *
     * @param mainImgDir   主图目录（扫描得，空/无图=缺）
     * @param detailImgDir 详情目录
     * @param skus         待上新 SKU（图/名/价），来自 P2 反推或 AI 方案
     */
    public SemiAutoScan.Completeness checkCompleteness(String productName, String mainImgDir,
                                                       String detailImgDir, List<SemiAutoScan.SkuCheck> skus) {
        List<String> missing = new ArrayList<>();

        boolean hasMain = !listImages(mainImgDir).isEmpty();
        if (!hasMain) missing.add("缺主图（主图目录为空或不存在）");
        boolean hasDetail = !listImages(detailImgDir).isEmpty();
        if (!hasDetail) missing.add("缺详情图（详情目录为空或不存在）");

        int skuTotal = skus == null ? 0 : skus.size();
        int missImg = 0, missInfo = 0;
        if (skuTotal == 0) {
            missing.add("没有任何 SKU（需选品/AI 出方案或导入 SKU 图反推）");
        } else {
            for (SemiAutoScan.SkuCheck s : skus) {
                String tag = (s.name() == null || s.name().isBlank()) ? "(未命名SKU)" : s.name();
                boolean noImg = s.imgPath() == null || s.imgPath().isBlank() || !new File(s.imgPath()).isFile();
                boolean noInfo = s.name() == null || s.name().isBlank() || s.price() <= 0;
                if (noImg) { missImg++; missing.add("SKU「" + tag + "」缺图（可走 AI 生成补齐）"); }
                if (noInfo) { missInfo++; missing.add("SKU「" + tag + "」缺名或价（价须>0）"); }
            }
        }
        boolean ready = missing.isEmpty();
        return new SemiAutoScan.Completeness(productName, ready, hasMain, hasDetail,
                skuTotal, missImg, missInfo, missing);
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

package com.gofu.local.model;

import java.util.List;

/**
 * 半自动批量上新 · 大文件夹扫描结果。
 *
 * <p>目录约定（两级）：
 * <pre>
 * 大文件夹/
 * ├─ 店铺名子目录/          ← 一级 = 店铺名（匹配 stores.json.name）
 * │  ├─ 商品文件夹/         ← 二级 = 一个商品（一套图）
 * │  │  ├─ 主图/ *.jpg
 * │  │  ├─ 详情/ *.jpg
 * │  │  └─ (sku/ 可选，缺则走 AI 生成)
 * </pre>
 *
 * <p>纯目录名约定识别角色（同乐羽 scanFolder），无内容识别。全程本地绝对路径。
 */
public final class SemiAutoScan {

    private SemiAutoScan() {}

    /** 一个商品（一套图）：各角色目录的绝对路径 + 缺失警告。 */
    public record Product(
            String name,          // 商品文件夹名
            String path,          // 商品文件夹绝对路径
            String mainImgDir,    // 主图目录（空=缺）
            String detailImgDir,  // 详情图目录（空=缺）
            String skuImgDir,     // SKU 图目录（空=缺，走 AI 生成）
            String whiteImgDir,   // 白底图目录（空=缺）
            List<String> warnings // 该商品的缺失/命名提示
    ) {}

    /** 一个店铺分组：店铺名 + 是否在 stores.json 匹配到 + 其下所有商品。 */
    public record ShopGroup(
            String shopName,      // 一级子目录名
            boolean matched,      // 是否在 stores.json 匹配到该店铺名
            String profile,       // 匹配到的 profile（store_NN），未匹配为空
            List<Product> products
    ) {}

    /** 整个大文件夹的扫描结果。 */
    public record Result(
            String rootPath,
            List<ShopGroup> shops,
            List<String> warnings // 全局警告（如某店铺名未匹配 stores.json）
    ) {}

    /** 一个待上新 SKU 的完整性输入：图/名/价三要素。 */
    public record SkuCheck(
            String name,      // SKU 名
            String imgPath,   // SKU 图路径（空=缺）
            double price      // 售价（元，<=0 视为缺）
    ) {}

    /**
     * 商品上新前完整性校验结果（P3 北极星）。
     * ready=true 才允许上新；否则 missing 明确列出缺什么，绝不静默上残图。
     */
    public record Completeness(
            String productName,
            boolean ready,
            boolean hasMain,       // 主图目录非空
            boolean hasDetail,     // 详情目录非空
            int skuTotal,
            int skuMissingImg,     // 缺图的 SKU 数
            int skuMissingInfo,    // 缺名/价的 SKU 数
            List<String> missing   // 人类可读的缺失清单（拒绝上新时展示）
    ) {}
}

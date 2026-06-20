package com.gofu.shared.enums;

/**
 * SKU 条目在搭配方案中的角色。对应 LY 上新里 ERP 导入时的 role 标记。
 *
 * <p>用于成本计算和 SKU 变种过滤：FIXED（如包材）不参与变种、只累加成本；
 * MAIN 是主件维度（颜色并排）；ACCESSORY 是配件维度（型号阶梯组合）。
 */
public enum SkuRole {
    /** 主件：商品本体，规格维度一 */
    MAIN,
    /** 配件/批量件：参与型号阶梯组合，规格维度二 */
    ACCESSORY,
    /** 固定成本项：如包材，不参与 SKU 变种，仅累加成本 */
    FIXED
}

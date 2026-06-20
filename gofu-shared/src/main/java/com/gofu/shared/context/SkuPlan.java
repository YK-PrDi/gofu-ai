package com.gofu.shared.context;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 一套 SKU 搭配方案。映射 LY AI 规划返回的 plans[] 结构
 * （已核实 ListingService.java:713 提示词、app.js:849 转换逻辑）。
 *
 * <p>方案 = 一个完整商品 = 全部主件 × 一组共享型号阶梯。
 */
@Data
@NoArgsConstructor
public class SkuPlan {

    /** 方案名（如"官方标配版"、"年度囤货装"）。 */
    private String planName;

    /** 策略说明（30字内，AI 生成）。 */
    private String description;

    /** 该方案下展开的所有 SKU 条目（主件 × 型号阶梯的笛卡尔积）。 */
    private List<SkuItem> items = new ArrayList<>();
}

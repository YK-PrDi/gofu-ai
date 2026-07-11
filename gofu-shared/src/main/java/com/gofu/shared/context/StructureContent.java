package com.gofu.shared.context;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构流产物（流程 2）。AI 规划 SKU 方案后写入。
 *
 * <p>SKU 规划读取 {@link VisualContent#getSellingPoints()} 反哺决策（围绕卖点策划款式）。
 */
@Data
@NoArgsConstructor
public class StructureContent {

    /** 多套 SKU 搭配方案（精简款/全阶梯/套餐等）。 */
    private List<SkuPlan> plans = new ArrayList<>();

    /** 当前选中的方案下标（预览页人工可切换），缺省 0。 */
    private int selectedPlanIndex;
}

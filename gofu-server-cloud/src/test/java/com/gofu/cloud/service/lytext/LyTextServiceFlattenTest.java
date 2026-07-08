package com.gofu.cloud.service.lytext;

import com.gofu.shared.context.SkuItem;
import com.gofu.shared.context.SkuPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * flattenPlans 拍平逻辑单测（M6 补链）。纯数据变换，不依赖 LLM/网络。
 * 构造一个仿真 LLM 结果（mainItems×models 二维），验证笛卡尔积展开、itemCode 拼接、accParts 结构。
 */
class LyTextServiceFlattenTest {

    private final LyTextService svc = new LyTextService(null, null);

    @Test
    void flatten_cartesianProduct_and_itemCode() {
        // 仿真 LLM 产出：1 个方案，2 主件 × 2 型号（单品 + 带2配件）
        Map<String, Object> llm = Map.of("plans", List.of(Map.of(
            "planName", "官方标配版",
            "description", "走量款",
            "mainItems", List.of(
                Map.of("itemCode", "GF-099-银", "specName", "银色花洒"),
                Map.of("itemCode", "GF-099-金", "specName", "金色花洒")
            ),
            "models", List.of(
                Map.of("specName", "单品", "components", List.of()),
                Map.of("specName", "增压套装", "components", List.of(
                    Map.of("itemCode", "052底座", "qty", 1),
                    Map.of("itemCode", "088滤芯", "qty", 5)
                ))
            )
        )));

        List<SkuPlan> plans = svc.flattenPlans(llm);

        assertEquals(1, plans.size());
        SkuPlan p = plans.get(0);
        assertEquals("官方标配版", p.getPlanName());
        // 2 主件 × 2 型号 = 4 条
        assertEquals(4, p.getItems().size());

        // 第一条：银色 × 单品，itemCode 无配件后缀，accParts 空
        SkuItem first = p.getItems().get(0);
        assertEquals("银色花洒", first.getSpec1());
        assertEquals("单品", first.getSpec2());
        assertEquals("银色花洒-单品", first.getSkuDisplayName());
        assertEquals("GF-099-银", first.getItemCode());
        assertTrue(first.getAccParts().isEmpty());
        assertEquals(0.0, first.getGroupPrice(), 0.001);  // 价格留 0

        // 第二条：银色 × 增压套装，itemCode 带 +配件*N，accParts 2 项结构化
        SkuItem second = p.getItems().get(1);
        assertEquals("GF-099-银+052底座*1+088滤芯*5", second.getItemCode());
        assertEquals(2, second.getAccParts().size());
        assertEquals("088滤芯", second.getAccParts().get(1).getCode());
        assertEquals(5, second.getAccParts().get(1).getQty());
    }

    @Test
    void flatten_emptyModels_fallbackToSingle() {
        // 型号缺省时兜底"单品"，主件仍展开一行
        Map<String, Object> llm = Map.of("plans", List.of(Map.of(
            "planName", "极简",
            "mainItems", List.of(Map.of("itemCode", "GF-1", "specName", "白色")),
            "models", List.of()
        )));
        List<SkuPlan> plans = svc.flattenPlans(llm);
        assertEquals(1, plans.get(0).getItems().size());
        assertEquals("单品", plans.get(0).getItems().get(0).getSpec2());
    }

    @Test
    void flatten_nullAndEmpty_safe() {
        assertTrue(svc.flattenPlans(null).isEmpty());
        assertTrue(svc.flattenPlans(Map.of()).isEmpty());
        assertTrue(svc.flattenPlans(Map.of("plans", "not-a-list")).isEmpty());
    }
}

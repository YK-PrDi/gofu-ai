package com.gofu.cloud.controller;

import com.gofu.cloud.service.lytext.LyTextService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M19 07.11 修复的三处确定性逻辑验证（不依赖生图/LLM API）：
 * ① isStructuralRigidCategory 角度闸门判定 ② stripShowerOnlyAcc 非花洒配件剔除 ③ splitByPlanHeader 段数兜底重切。
 */
class M19LogicTest {

    // ① 架类/挂钩角度闸门：这些品类主图应走正面系防变形
    @Test
    void structuralRigidCategory_gatesShelfAndHook() {
        assertTrue(FlowController.isStructuralRigidCategory("家装主材>厨房>厨房挂件>锅盖架"));
        assertTrue(FlowController.isStructuralRigidCategory("家装主材>厨房>厨房挂件>挂钩"));
        assertTrue(FlowController.isStructuralRigidCategory("家装主材>厨房>厨房挂件>刀架"));
        assertTrue(FlowController.isStructuralRigidCategory("家装主材>厨房>沥水收纳架"));
        assertTrue(FlowController.isStructuralRigidCategory("浴室转角置物架"));
        // 花洒/淋浴不进闸门（保留多角度）
        assertFalse(FlowController.isStructuralRigidCategory("家装主材>卫浴用品>淋浴花洒套装"));
        assertFalse(FlowController.isStructuralRigidCategory("家装主材>卫浴配件>花洒配件>花洒喷头"));
        // 空/无关品类不进
        assertFalse(FlowController.isStructuralRigidCategory(""));
        assertFalse(FlowController.isStructuralRigidCategory(null));
        assertFalse(FlowController.isStructuralRigidCategory("床上用品"));
    }

    // ② 非花洒配件闸门：花洒专属配件行被剔除，架类正常配件保留
    @Test
    void stripShowerOnlyAcc_removesShowerAccKeepsOthers() throws Exception {
        Method m = LyTextService.class.getDeclaredMethod("stripShowerOnlyAcc", String.class);
        m.setAccessible(true);
        String in = "A001 | 1.5米防爆软管\n"
                  + "A002 | 001滤芯*5\n"
                  + "A003 | 增压喷头\n"
                  + "B001 | S型挂钩\n"
                  + "B002 | 不锈钢沥水盘\n";
        String out = (String) m.invoke(null, in);
        assertFalse(out.contains("软管"), "软管应被剔除");
        assertFalse(out.contains("滤芯"), "滤芯应被剔除");
        assertFalse(out.contains("喷头"), "喷头应被剔除");
        assertTrue(out.contains("S型挂钩"), "挂钩应保留");
        assertTrue(out.contains("沥水盘"), "沥水盘应保留");
    }

    // ③ 段数兜底重切：LLM 漏写 --- 时按【第N张方案】标题切出正确段数
    @Test
    void splitByPlanHeader_recoversSegmentsWhenNoDelimiter() throws Exception {
        FlowController fc = new FlowController(null, null, null, null, null, null);
        Method m = FlowController.class.getDeclaredMethod("splitByPlanHeader", String.class);
        m.setAccessible(true);
        String raw = "【总分析】总卖点：稳固收纳\n"
                   + "【第 1 张方案】正面基调，主标题稳固承重\n"
                   + "【第 2 张方案】近景特写，主标题多层收纳\n"
                   + "【第 3 张方案】材质特写，主标题加厚防锈\n";
        @SuppressWarnings("unchecked")
        java.util.List<String> segs = (java.util.List<String>) m.invoke(fc, raw);
        assertEquals(3, segs.size(), "应切出3段方案（丢弃总分析）");
        assertTrue(segs.get(0).startsWith("【第 1 张方案】"));
        assertTrue(segs.get(2).contains("加厚防锈"));
    }
}

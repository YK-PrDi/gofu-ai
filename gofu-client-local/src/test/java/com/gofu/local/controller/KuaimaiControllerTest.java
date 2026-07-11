package com.gofu.local.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * KuaimaiController 成本计算单测。
 * 核心验证 parsePackSize：滤芯等批量件在 ERP 以「整包」为单品、采购价是整包价，
 * 编码尾部 *N 即包装数，成本须先折算成单个价再 ×qty（否则放大 ~N 倍）。
 */
class KuaimaiControllerTest {

    @Test
    void parsePackSize_整包编码取尾部数量() {
        assertEquals(15, KuaimaiController.parsePackSize("052滤芯*15"));
        assertEquals(5,  KuaimaiController.parsePackSize("017滤芯*5"));
        assertEquals(6,  KuaimaiController.parsePackSize("猫爪滤芯*6"));
    }

    @Test
    void parsePackSize_无N后缀返回1() {
        assertEquals(1, KuaimaiController.parsePackSize("猫爪滤芯"));
        assertEquals(1, KuaimaiController.parsePackSize("GF-052-纯白"));
        assertEquals(1, KuaimaiController.parsePackSize("银色027滤芯桶"));
    }

    @Test
    void parsePackSize_取末尾N作包装数() {
        // 组件码进 calcComboCost 前，主件码已被 split('+')[0] 剥离后缀，
        // 故组件要么是纯主件码(无*N)、要么是单个配件码(末尾*N=包装数)。
        // 单个配件码末尾 *N 正是包装数，正确取到。
        assertEquals(4, KuaimaiController.parsePackSize("116滤芯*4"));
        // 末尾无 *N 的主件码 → 1（不折算）
        assertEquals(1, KuaimaiController.parsePackSize("GF-116-纯白-1"));
    }

    @Test
    void parsePackSize_边界与非法输入() {
        assertEquals(1, KuaimaiController.parsePackSize(null));
        assertEquals(1, KuaimaiController.parsePackSize(""));
        assertEquals(1, KuaimaiController.parsePackSize("滤芯*0"));   // *0 视为无效→1
        assertEquals(1, KuaimaiController.parsePackSize("滤芯*"));     // 无数字→1
    }

    @Test
    void 折算数学_整包价除以包装数得单价() {
        // 052滤芯*15 整包价 3.9，要 5 个：3.9/15×5 = 1.30（而非 3.9×5=19.5）
        double packPrice = 3.9;
        int packSize = KuaimaiController.parsePackSize("052滤芯*15");
        int qty = 5;
        double cost = packPrice / packSize * qty;
        assertEquals(1.30, cost, 0.001);
        // 对照错误算法（放大 15 倍）
        assertEquals(19.5, packPrice * qty, 0.001);
    }
}

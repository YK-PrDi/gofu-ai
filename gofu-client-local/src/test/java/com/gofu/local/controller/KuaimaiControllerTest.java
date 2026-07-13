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

    @Test
    void 鹏盛阶梯_档位命中取对应价() {
        assertEquals(2.0, KuaimaiController.pengshengFreight(0.3), 0.001);
        assertEquals(2.3, KuaimaiController.pengshengFreight(0.5), 0.001);
        assertEquals(2.9, KuaimaiController.pengshengFreight(1.0), 0.001);
        assertEquals(4.1, KuaimaiController.pengshengFreight(1.5), 0.001);
        assertEquals(4.8, KuaimaiController.pengshengFreight(2.0), 0.001);
        assertEquals(6.2, KuaimaiController.pengshengFreight(3.0), 0.001);
    }

    @Test
    void 鹏盛阶梯_中点向上取贵档() {
        // 用户口径：2~3kg 间 <2.5 取 2kg 价(4.8)，≥2.5 取 3kg 价(6.2)
        assertEquals(4.8, KuaimaiController.pengshengFreight(2.49), 0.001);
        assertEquals(6.2, KuaimaiController.pengshengFreight(2.5),  0.001);
        // 其余相邻档中点同规则：0.5↔1.0 中点 0.75
        assertEquals(2.3, KuaimaiController.pengshengFreight(0.74), 0.001);
        assertEquals(2.9, KuaimaiController.pengshengFreight(0.75), 0.001);
    }

    @Test
    void 鹏盛阶梯_边界与超重兜底() {
        assertEquals(0,   KuaimaiController.pengshengFreight(0),   0.001); // 无重量→0
        assertEquals(2.0, KuaimaiController.pengshengFreight(0.1), 0.001); // 低于最小档→最小档价
        assertEquals(6.2, KuaimaiController.pengshengFreight(5.0), 0.001); // 超 3kg→封顶
    }

    @Test
    void 其他品类运费_满100g才加_不满不算() {
        // 其他（非花洒非代发）：300g=2.3，之后【满100g才+0.15】，不满不加（07.13修正）
        assertEquals(2.30, KuaimaiController.otherFreight(0.3),  0.001); // 300g 基础
        assertEquals(2.30, KuaimaiController.otherFreight(0.35), 0.001); // 350g 不满100g→不加
        assertEquals(2.30, KuaimaiController.otherFreight(0.399),0.001); // 399g 仍不满→2.3
        assertEquals(2.45, KuaimaiController.otherFreight(0.4),  0.001); // 400g 满1个100g→+0.15
        assertEquals(2.45, KuaimaiController.otherFreight(0.499),0.001); // 499g 仍只满1个
        assertEquals(2.60, KuaimaiController.otherFreight(0.5),  0.001); // 500g 满2个
        assertEquals(0,    KuaimaiController.otherFreight(0),    0.001);
    }
}

package com.gofu.shared.context;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 视觉流产物（流程 1）。云端生图后写入。
 *
 * <p>关键脊柱作用：sellingPoints（核心营销卖点）由 AI 从标题/产品提取，
 * 供结构流（SKU 规划）读取反哺——这是打通双轨数据孤岛的关键字段（ADR-003）。
 */
@Data
@NoArgsConstructor
public class VisualContent {

    /** 主图 URL/路径列表（通常 6 张）。可能是 COS URL 或本地路径（雷区 10）。 */
    private List<String> mainImages = new ArrayList<>();

    /** 详情图 URL/路径列表（通常 6 张）。 */
    private List<String> detailImages = new ArrayList<>();

    /** AI 生成的商品标题。 */
    private String title;

    /**
     * 核心营销卖点（如 ["过滤","大流量","抑菌"]）。
     * ⚠️ 由视觉流提取，结构流 SKU 规划读取反哺。双轨打通的耦合点（ADR-003）。
     */
    private List<String> sellingPoints = new ArrayList<>();
}

package com.gofu.shared.context;

import com.gofu.shared.enums.SkuRole;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个 SKU 条目。直接映射 LY 前端全局数组 lstSkuItems 的真实字段契约
 * （已核实 app.js:48 起、生图请求体 app.js:561/770）。
 *
 * <p>⚠️ 这是上新链路的命脉对象（ARCHITECTURE.md 雷区 4）。字段一路透传
 * ERP → 定价 → 生图 → 上架，漏一个就断链。新增/改名字段属于大改动，
 * 需同步前端渲染、生图请求体、上架脚本三处。
 */
@Data
@NoArgsConstructor
public class SkuItem {

    /** SKU 基础名称（如"奶油色"）。生图/上架兜底显示名。 */
    private String name;

    /** 营销化显示名（如"奶油色-过滤增压"）。优先于 name 显示；缺省回退 name。 */
    private String skuDisplayName;

    /** 规格维度一：主件（颜色/型号），对应拼多多二维 SKU 第一维。 */
    private String spec1;

    /** 规格维度二：配件组合（底座/滤芯组合），对应拼多多二维 SKU 第二维。 */
    private String spec2;

    /**
     * 主件数量（几件装）。默认 1=单件；多件档(如"三件装")由 LLM 结构化输出，
     * 下游成本按 mainQty×主件单价、SKU 图按 mainQty 贴 N 个主件框。
     * 老数据/未设置天然为 1，不断链（雷区 4）。取代前端 mainQtyFromSpec 正则猜文本。
     */
    private int mainQty = 1;

    /**
     * 组合规格编码，格式 `主件码+配件码*N`（如 "GF-099-灰+052底座*1+088滤芯*5"）。
     * ⚠️ 格式错误会导致配件白底图匹配失败、上架被拒（雷区 5）。
     */
    private String itemCode;

    /** 配件清单 [{code,qty}]。生图据此精确匹配配件白底图、定滤芯数量。 */
    private List<AccPart> accParts = new ArrayList<>();

    /** 拼单价（元）。上架时 ×100 转分。 */
    private double groupPrice;

    /** 单买价（元，≥groupPrice）。全表统一，由批量设置决定。 */
    private double singlePrice;

    /** 成本价（元）。来自 ERP，用于定价反推。 */
    private double cost;

    /** 库存，默认 8888。 */
    private int stock = 8888;

    /** SKU 展示图目录。⚠️ 缺失会卡住上架前校验（雷区 4）。 */
    private String imgDir;

    /** 白底图目录。生图参考、颜色匹配用。 */
    private String whiteImgDir;

    /** 角色：主件/配件/固定成本。影响成本累加与变种过滤。 */
    private SkuRole role;
}

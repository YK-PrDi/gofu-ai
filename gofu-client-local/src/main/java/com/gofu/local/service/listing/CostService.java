package com.gofu.local.service.listing;

import com.gofu.local.service.erp.KuaimaiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * 成本计算单一真相源（自 KuaimaiController 私有方法抽出，行为逐字保留）。
 *
 * <p>原本 calc-cost / calc-combo-cost 的算法内联在 controller，导入流后端算价无法复用。
 * 抽成可注入 service 后：KuaimaiController 委托本类（出参不变），StyleImportService 直接注入调用，
 * 不再本地 HTTP 自调。⚠️ 成本/运费公式禁止"优化"，与乐羽保持逐字一致。
 */
@Service
public class CostService {

    private static final Logger log = LoggerFactory.getLogger(CostService.class);
    private final KuaimaiService kuaimaiService;

    public CostService(KuaimaiService kuaimaiService) {
        this.kuaimaiService = kuaimaiService;
    }

    /**
     * 批量计算单品成本（含运费在组合层，单品只含材料价）。
     * 入参 outerIds + productType；出参 { items:[{skuOuterId,name,purchasePrice,weight,hasSupplier,cost,isFixed,freight?}], totalCost }。
     * 花洒品类自动补固定包材（手喷袋/好评卡/胶纸），与已选去重。
     */
    public Map<String, Object> calcCost(List<String> outerIds, String productType) {
        // 花洒品类：自动按名称补充固定包材（手喷袋/好评卡/胶纸），与已选去重
        LinkedHashSet<String> codes = new LinkedHashSet<>(outerIds);
        if ("花洒".equals(productType)) {
            try {
                List<Map<String, Object>> packaging =
                    kuaimaiService.findItemsByNameKeywords(List.of("手喷袋", "好评卡", "胶纸"));
                for (Map<String, Object> p : packaging) {
                    codes.add(String.valueOf(p.get("skuOuterId")));
                }
            } catch (Exception e) { /* 补充失败不阻断 */ }
        }

        List<Map<String, Object>> items = new ArrayList<>();
        double totalCost = 0;
        for (String code : codes) {
            Map<String, Object> row = unitCost(code, productType);
            boolean fixed = isFixedCostName(String.valueOf(row.get("name")));
            row.put("isFixed", fixed);
            if (fixed) {
                row.put("freight", 0.0);
                row.put("cost", round2(toDouble(row.get("purchasePrice"))));
            }
            totalCost += toDouble(row.get("cost"));
            items.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("totalCost", round2(totalCost));
        return result;
    }

    /**
     * 计算组合 SKU 成本。入参 productType + fixedAccessories + skus(每个含 components:[{itemCode,qty,cost,weight}])。
     * 规则: 材料成本=Σ(组件cost×qty)+Σ固定项cost；总重=Σ(组件weight×qty)；运费按品类算一次；SKU成本=材料+运费。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> calcComboCost(String productType,
                                             List<Map<String, Object>> fixedAccessories,
                                             List<Map<String, Object>> skus) {
        // 固定包材成本（仅采购价，不含运费、不计重量），全局算一次
        double accessoryCost = 0;
        for (Map<String, Object> acc : fixedAccessories) {
            accessoryCost += toDouble(acc.get("cost"));
        }
        accessoryCost = round2(accessoryCost);

        List<Map<String, Object>> outSkus = new ArrayList<>();
        for (Map<String, Object> sku : skus) {
            String name = String.valueOf(sku.getOrDefault("name", ""));
            List<Map<String, Object>> components = (List<Map<String, Object>>) sku.getOrDefault("components", List.of());

            double materialCost = 0, totalWeight = 0;
            List<Map<String, Object>> breakdown = new ArrayList<>();
            int compIdx = 0;
            for (Map<String, Object> comp : components) {
                String code = String.valueOf(comp.get("itemCode"));
                int qty = Math.max(1, toInt(comp.getOrDefault("qty", 1)));
                double unit = toDouble(comp.get("cost"));     // 材料价（核对后）
                double w    = toDouble(comp.get("weight"));
                // 成本修复：滤芯类配件在 ERP 里以「整包」为单品存在，编码尾部 *N 即包装数
                // （如 052滤芯*15 采购价 3.9=15个整包价），而 qty 语义是"要几个"。
                // 整包价直接 ×qty 会把成本放大 ~N 倍，故先按 *N 折算成单个价再乘。
                int packSize = parsePackSize(code);
                if (packSize > 1) {
                    unit /= packSize;
                    w    /= packSize;
                }
                // 成本异常保护：除首个主件外，配件若本身是「整支花洒/整机」（编码或名称含 单手喷/单花洒/整机）
                // 说明被误当配件拼进了组合，记日志并不计入成本，避免拼单价离谱。
                String cn = code + " " + String.valueOf(comp.getOrDefault("name", ""));
                boolean isWholeShower = compIdx > 0 && (cn.contains("单手喷") || cn.contains("单花洒") || cn.contains("整机"));
                if (isWholeShower) {
                    log.warn("组合成本保护：SKU「{}」的组件 {} 疑似整支花洒被误当配件，已不计入成本", name, code);
                } else {
                    materialCost += unit * qty;
                    totalWeight  += w * qty;
                }
                compIdx++;

                Map<String, Object> b = new LinkedHashMap<>();
                b.put("itemCode", code);
                b.put("qty",      qty);
                b.put("unitCost", unit);
                breakdown.add(b);
            }

            // 运费：组合层按品类算一次
            double freight = calcFreight(productType, totalWeight);
            double cost = round2(materialCost + accessoryCost + freight);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name",          name);
            out.put("cost",          cost);
            out.put("freight",       freight);
            out.put("totalWeight",   round2(totalWeight));
            out.put("accessoryCost", accessoryCost);
            out.put("breakdown",     breakdown);
            out.put("components",    components);
            out.put("stock",         sku.getOrDefault("stock", 8888));
            outSkus.add(out);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skus", outSkus);
        return result;
    }

    // ── 以下为从 KuaimaiController 原样搬入的私有算法（逐字保留）────

    /** 计算单个 skuOuterId 的成本。优先用列表缓存（无规格商品成本/重量在列表里），降级查 SKU 明细。 */
    private Map<String, Object> unitCost(String code, String productType) {
        double purchasePrice = 0, weight = 0;
        int hasSupplier = 0;
        String name = code;

        Map<String, Object> cached = null;
        try { cached = kuaimaiService.getCachedItemByOuterId(code); } catch (Exception ignore) {}
        if (cached != null) {
            purchasePrice = toDouble(cached.get("purchasePrice"));
            weight        = toDouble(cached.get("weight"));
            hasSupplier   = toInt(cached.get("hasSupplier"));
            String t = String.valueOf(cached.getOrDefault("title", code));
            if (!t.isBlank()) name = t;
        } else {
            try {
                Map<String, Object> detail = kuaimaiService.getSkuDetail(code);
                purchasePrice = toDouble(detail.get("purchasePrice"));
                weight        = toDouble(detail.get("weight"));
                hasSupplier   = toInt(detail.get("hasSupplier"));
                name = String.valueOf(detail.getOrDefault("shortTitle",
                         detail.getOrDefault("skuOuterId", code)));
            } catch (Exception ignore) {}
        }

        // 单品成本只含材料价；运费在组合层按整个 SKU 算一次
        double cost = round2(purchasePrice);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("skuOuterId",    code);
        row.put("name",          name);
        row.put("purchasePrice", purchasePrice);
        row.put("weight",        weight);
        row.put("hasSupplier",   hasSupplier);
        row.put("cost",          cost);
        return row;
    }

    /**
     * 解析配件编码尾部的「整包数量」*N（如 052滤芯*15 → 15），无则返回 1。
     * ERP 里滤芯等批量件以整包为单品、采购价是整包价，需据此把整包价折算成单个价。
     * 只认末尾的 *N（组合码如 A+B*5 取最后一段的 *5），防止误伤中间的乘号。
     */
    static int parsePackSize(String code) {
        if (code == null) return 1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\*(\\d+)\\s*$").matcher(code.trim());
        if (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                return n > 0 ? n : 1;
            } catch (NumberFormatException e) { return 1; }
        }
        return 1;
    }

    /**
     * 鹏盛代发阶梯运费表：{档位重量kg, 运费元}。0.2kg/2.5kg 在原表中为空列，不是档位。
     * 取档规则（用户口径）：按总重就近取档，落在两相邻档中点时向上取贵的那档——
     * 如 2~3kg 之间，<2.5kg 取 2kg 价(4.8)，≥2.5kg 取 3kg 价(6.2)。超 3kg 暂封顶 6.2。
     */
    private static final double[][] PENGSHENG_TIERS = {
        {0.3, 2.0}, {0.5, 2.3}, {1.0, 2.9}, {1.5, 4.1}, {2.0, 4.8}, {3.0, 6.2}
    };

    static double pengshengFreight(double weight) {
        if (weight <= 0) return 0;
        for (int i = 0; i < PENGSHENG_TIERS.length; i++) {
            double tierW = PENGSHENG_TIERS[i][0];
            double nextW = (i + 1 < PENGSHENG_TIERS.length) ? PENGSHENG_TIERS[i + 1][0] : Double.MAX_VALUE;
            double splitUp = (nextW == Double.MAX_VALUE) ? Double.MAX_VALUE : (tierW + nextW) / 2.0;
            if (weight < splitUp) return PENGSHENG_TIERS[i][1];
        }
        return PENGSHENG_TIERS[PENGSHENG_TIERS.length - 1][1]; // 兜底：>3kg 封顶
    }

    /**
     * 其他品类（非花洒非代发）基础运费：300g=2.3，之后【每满 100g】才 +0.15，不满 100g 不加。
     * 用克整数 + floor：如 350g→满0个100g→2.3；400g→满1个→2.45；399g→仍2.3。
     */
    static double otherFreight(double weight) {
        if (weight <= 0) return 0;
        long grams = Math.round(weight * 1000);
        long over  = Math.max(0, (grams - 300) / 100);   // 整数除法=floor，满100g才进一级
        return 2.3 + over * 0.15;
    }

    /**
     * 按品类算运费（组合层算一次）。
     * 花洒：固定 +3；代发：加鹏盛阶梯运费（按总重查表）；其他：基础递增（otherFreight）。
     */
    double calcFreight(String productType, double totalWeight) {
        if ("花洒".equals(productType)) return 3.0;
        if ("代发".equals(productType)) return round2(pengshengFreight(totalWeight));
        return round2(otherFreight(totalWeight));
    }

    /** 名称含包材关键词则为固定成本项（不进搭配布局，不加运费）。 */
    private boolean isFixedCostName(String name) {
        if (name == null) return false;
        return name.contains("手喷袋") || name.contains("好评卡")
            || name.contains("胶纸") || name.contains("纸箱");
    }

    private double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return 0; }
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return 0; }
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}

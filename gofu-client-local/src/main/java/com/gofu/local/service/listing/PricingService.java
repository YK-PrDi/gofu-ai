package com.gofu.local.service.listing;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定价引擎核心（自 PricingController 抽出，供 controller + 批量流/导入流复用同一份公式，单一真相源）。
 *
 * <p>加价率反推售价：price = cost / costRatio（costRatio 默认 0.35）。利润率 m 反解 costRatio=0.93-m。
 * 拼单价 pinPrice=price+20、单买价=maxPin+1、参考价=maxPin+2。
 *
 * <p>⚠️ 定价公式禁止"优化"，与乐羽逐字一致（原样自 PricingController.doCalculate 迁入）。
 */
@Service
public class PricingService {

    /** 默认利润率（对齐前端滑块默认 profitRate=0.58）。全自动流程无人工定价时用它。 */
    public static final double DEFAULT_PROFIT_RATE = 0.58;

    /**
     * 定价计算。入参 { profitRate 或 costRatio, skus:[{itemCode,name,cost}] }，
     * 出参 { skus:[{...pinPrice等}], singlePrice, refPrice }。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> calculate(Map<String, Object> body) {
        double costRatio;
        if (body.get("profitRate") instanceof Number pr) {
            double m = pr.doubleValue();
            costRatio = 0.93 - m;
            if (costRatio < 0.28) costRatio = 0.28;   // 对齐公式表百分比区间 0.28~0.78
            if (costRatio > 0.78) costRatio = 0.78;
        } else {
            costRatio = ((Number) body.getOrDefault("costRatio", 0.35)).doubleValue();
        }
        if (costRatio < 0.01) costRatio = 0.01;
        List<Map<String, Object>> skus = (List<Map<String, Object>>) body.getOrDefault("skus", List.of());

        List<Map<String, Object>> out = new ArrayList<>();
        double maxPinPrice = 0;
        for (Map<String, Object> s : skus) {
            double cost = ((Number) s.getOrDefault("cost", 0)).doubleValue();
            String itemCode = String.valueOf(s.getOrDefault("itemCode", ""));
            String name     = String.valueOf(s.getOrDefault("name", ""));

            double price       = round2(cost / costRatio);
            double profit      = round2(price - cost);
            double deduction   = round2(price * 0.07);
            double profit2     = round2(profit - deduction);
            double marginRate  = price > 0 ? round2(profit2 / price) : 0;
            double breakeven   = marginRate > 0 ? round2(1.0 / marginRate) : 0;
            double pinPrice    = round2(price + 20);
            if (pinPrice > maxPinPrice) maxPinPrice = pinPrice;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemCode", itemCode); row.put("name", name); row.put("cost", cost);
            row.put("price", price); row.put("profit", profit); row.put("deduction", deduction);
            row.put("profit2", profit2); row.put("marginRate", marginRate);
            row.put("breakeven", breakeven); row.put("pinPrice", pinPrice);
            out.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("skus", out);
        result.put("singlePrice", round2(maxPinPrice + 1));
        result.put("refPrice", round2(maxPinPrice + 2));
        return result;
    }

    /**
     * 便捷：按默认利润率把单个进价 cost 算成拼单价（元）。全自动流程给反推的 SKU 定价用。
     * cost<=0 返回 0（调用方据此判定"无成本→不自动定价"）。
     */
    public double autoGroupPrice(double cost) {
        return autoGroupPrice(cost, DEFAULT_PROFIT_RATE);
    }

    /**
     * 便捷（带利润率）：全自动跑批把前端本批随机到的利润率透传进来算拼单价。
     * profitRate<=0 时回退默认（等价旧行为）。修：原全自动链写死 DEFAULT_PROFIT_RATE，
     * 前端 33/45/53 随机档形同虚设、恒定 58%→后端百分比 0.35，用户实测"利润率永远 35%"的真因。
     */
    public double autoGroupPrice(double cost, double profitRate) {
        if (cost <= 0) return 0;
        double m = profitRate > 0 ? profitRate : DEFAULT_PROFIT_RATE;
        Map<String, Object> sku = new LinkedHashMap<>();
        sku.put("cost", cost);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profitRate", m);
        body.put("skus", List.of(sku));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) calculate(body).get("skus");
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("pinPrice")).doubleValue();
    }

    private double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}

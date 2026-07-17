package com.gofu.local.controller;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 定价引擎（自 LY-Automation 原样迁入，ADR-002：定价属结构流归本地）。
 *
 * <p>加价率反推售价：price = cost / costRatio（costRatio 默认 0.35，即成本占售价 35%）。
 * 再算活动扣点 7%、二级利润、利润率、保本投产、拼单价(+20)、单买价(maxPin+1)、参考价(maxPin+2)。
 * 成本来源是快麦 purchasePrice（经 KuaimaiController.calc-cost/calc-combo-cost 算得），此处只做定价换算。
 *
 * <p>⚠️ 定价公式禁止"优化"，与乐羽保持逐字一致。
 */
@RestController
@RequestMapping("/api/pricing")
public class PricingController {

    // 定价公式抽到 PricingService（单一真相源，批量流/导入流也复用）。本 controller 只做 HTTP+xlsx。
    private final com.gofu.local.service.listing.PricingService pricingService;

    public PricingController(com.gofu.local.service.listing.PricingService pricingService) {
        this.pricingService = pricingService;
    }

    /**
     * POST /api/pricing/calculate
     * 入参: { costRatio: 0.35, skus: [{itemCode, name, cost}] }
     * 出参: { skus: [{...计算字段}], singlePrice, refPrice }
     */
    @PostMapping("/calculate")
    public ResponseEntity<Map<String, Object>> calculate(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(doCalculate(body));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/pricing/export
     * 同 calculate 入参，返回 xlsx 文件下载。
     */
    @PostMapping("/export")
    public ResponseEntity<byte[]> export(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> result = doCalculate(body);
            byte[] xlsx = buildXlsx(result);
            String filename = "定价表_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(filename, "UTF-8").replace("+", "%20"))
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(xlsx);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── 核心计算（委托 PricingService，公式单一真相源）──

    private Map<String, Object> doCalculate(Map<String, Object> body) {
        return pricingService.calculate(body);
    }

    // ── 生成 xlsx ──

    @SuppressWarnings("unchecked")
    private byte[] buildXlsx(Map<String, Object> result) throws Exception {
        List<Map<String, Object>> skus = (List<Map<String, Object>>) result.get("skus");
        double singlePrice = ((Number) result.get("singlePrice")).doubleValue();
        double refPrice    = ((Number) result.get("refPrice")).doubleValue();

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("定价表");

            // 表头样式
            CellStyle hStyle = wb.createCellStyle();
            hStyle.setFillForegroundColor(IndexedColors.CORNFLOWER_BLUE.getIndex());
            hStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font hFont = wb.createFont();
            hFont.setBold(true);
            hFont.setColor(IndexedColors.WHITE.getIndex());
            hStyle.setFont(hFont);
            hStyle.setBorderBottom(BorderStyle.THIN);

            String[] headers = {"商品编码","款式名","总成本","价格(活动价)","利润","活动扣点","二级利润","利润率","毛保本投产","拼单价","单买价","参考价"};
            Row hRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell c = hRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(hStyle);
                sheet.setColumnWidth(i, 4200);
            }
            sheet.setColumnWidth(0, 6000);
            sheet.setColumnWidth(1, 5000);

            // 百分比样式
            CellStyle pctStyle = wb.createCellStyle();
            DataFormat fmt = wb.createDataFormat();
            pctStyle.setDataFormat(fmt.getFormat("0.00%"));

            // 数据行
            int rowIdx = 1;
            for (Map<String, Object> s : skus) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(str(s, "itemCode"));
                row.createCell(1).setCellValue(str(s, "name"));
                row.createCell(2).setCellValue(num(s, "cost"));
                row.createCell(3).setCellValue(num(s, "price"));
                row.createCell(4).setCellValue(num(s, "profit"));
                row.createCell(5).setCellValue(num(s, "deduction"));
                row.createCell(6).setCellValue(num(s, "profit2"));
                Cell mrCell = row.createCell(7);
                mrCell.setCellValue(num(s, "marginRate"));
                mrCell.setCellStyle(pctStyle);
                row.createCell(8).setCellValue(num(s, "breakeven"));
                row.createCell(9).setCellValue(num(s, "pinPrice"));
                row.createCell(10).setCellValue(singlePrice);
                row.createCell(11).setCellValue(refPrice);
            }

            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : v.toString();
    }

    private double num(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        return ((Number) v).doubleValue();
    }
}

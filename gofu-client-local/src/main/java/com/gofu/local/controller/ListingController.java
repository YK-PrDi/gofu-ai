package com.gofu.local.controller;

import com.gofu.local.model.ListingConfig;
import com.gofu.local.service.listing.AccessoryRuleService;
import com.gofu.local.service.listing.ContextToListingMapper;
import com.gofu.local.service.listing.ListingService;
import com.gofu.local.service.sync.ContextSyncService;
import com.gofu.shared.context.ProductContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 上新接口。自 LY-Automation 迁入，仅保留不依赖 AI 的端点。
 *
 * <p>⚠️ 已剥离（ADR-002，待云端 AI 就绪 + M3）：
 * /prepare（标题生成）、/gen-sku-images（生图）、/analyze-bg（背景分析）、
 * 配件规则/防比价模板端点（依赖 AccessoryRuleService/PromptTemplateService）。
 */
@RestController
@RequestMapping("/api/listing")
public class ListingController {

    private final ListingService listingService;
    private final ContextSyncService contextSyncService;
    private final ContextToListingMapper contextToListingMapper;
    private final AccessoryRuleService accessoryRuleService;

    public ListingController(ListingService listingService,
                             ContextSyncService contextSyncService,
                             ContextToListingMapper contextToListingMapper,
                             AccessoryRuleService accessoryRuleService) {
        this.listingService = listingService;
        this.contextSyncService = contextSyncService;
        this.contextToListingMapper = contextToListingMapper;
        this.accessoryRuleService = accessoryRuleService;
    }

    /**
     * 配件自动搭配（自 LY 迁入）：只选了主件，按规则库解析出该搭配哪些配件/批量件 + 阶梯型号。
     * 入参：{ category, mainItemCode, erpSkus:[{itemCode,name}...] }
     * 出参：{ ladders:[...], accSkus:[{itemCode,name,role,keyword,type,defaultQty}...] }
     */
    @PostMapping("/auto-resolve")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> autoResolve(@RequestBody Map<String, Object> body) {
        try {
            String category = String.valueOf(body.getOrDefault("category", ""));
            String mainCode = String.valueOf(body.getOrDefault("mainItemCode", ""));
            List<Map<String, Object>> erpSkus = (List<Map<String, Object>>) body.getOrDefault("erpSkus", List.of());
            return ResponseEntity.ok(accessoryRuleService.resolveForMain(category, mainCode, erpSkus));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "规则解析失败：" + e.getMessage()));
        }
    }

    /**
     * 一键上新（M7 串联）：从云端拉 context → 转 ListingConfig（下载图+价格元转分）→ 启动上新。
     * 入参 {@code { contextId, planIndex?, dryRun? }}，出参 {@code { taskId }}。
     */
    @PostMapping("/from-context")
    public ResponseEntity<Map<String, Object>> fromContext(@RequestBody Map<String, Object> body) {
        String contextId = (String) body.get("contextId");
        if (contextId == null || contextId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "contextId 不能为空"));
        }
        int planIndex = body.get("planIndex") instanceof Number n ? n.intValue() : 0;
        boolean dryRun = Boolean.TRUE.equals(body.get("dryRun"));
        // material/brand 在 ProductContext 无字段承载，由上新请求 body 透传（前端 entry.brand 等）
        String material = body.get("material") != null ? String.valueOf(body.get("material")) : null;
        String brand = body.get("brand") != null ? String.valueOf(body.get("brand")) : null;
        try {
            ProductContext ctx = contextSyncService.fetch(contextId);
            ListingConfig config = contextToListingMapper.toListingConfig(ctx, planIndex, material, brand);
            String taskId = listingService.runListing(config, dryRun);
            return ResponseEntity.ok(Map.of("taskId", taskId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "一键上新启动失败：" + e.getMessage()));
        }
    }

    /** 启动自动化上新 / 仅登录（反风控核心）。 */
    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        try {
            boolean loginOnly = Boolean.TRUE.equals(body.get("loginOnly"));
            if (loginOnly) {
                String taskId = listingService.runLoginOnly();
                return ResponseEntity.ok(Map.of("taskId", taskId));
            }
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            om.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            ListingConfig config = om.convertValue(body, ListingConfig.class);
            boolean dryRun = Boolean.TRUE.equals(body.get("dryRun"));
            String taskId = listingService.runListing(config, dryRun);
            return ResponseEntity.ok(Map.of("taskId", taskId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "启动自动化失败：" + e.getMessage()));
        }
    }

    /** 扫描商品大文件夹，自动识别子文件夹。入参 {folderPath}。 */
    @PostMapping("/scan-folder")
    public ResponseEntity<Map<String, Object>> scanFolder(@RequestBody Map<String, Object> body) {
        String folderPath = (String) body.get("folderPath");
        if (folderPath == null || folderPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "folderPath 不能为空"));
        }
        try {
            return ResponseEntity.ok(listingService.scanFolder(folderPath));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "扫描失败：" + e.getMessage()));
        }
    }

    /** 扫描文件夹根目录图片，按数字顺序返回。入参 {folderPath} 出参 {images}。 */
    @PostMapping("/list-images")
    public ResponseEntity<Map<String, Object>> listImages(@RequestBody Map<String, Object> body) {
        String folderPath = (String) body.get("folderPath");
        if (folderPath == null || folderPath.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "folderPath 不能为空"));
        }
        try {
            return ResponseEntity.ok(Map.of("images", listingService.listImagesInFolder(folderPath)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "扫描失败：" + e.getMessage()));
        }
    }

    /** 取某品类预设属性。入参 ?category=...，出参 {attributes}。 */
    @GetMapping("/product-info")
    public ResponseEntity<Map<String, Object>> productInfo(@RequestParam(defaultValue = "") String category) {
        try {
            return ResponseEntity.ok(Map.of("attributes", listingService.productInfoFor(category)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "查询失败：" + e.getMessage()));
        }
    }
}

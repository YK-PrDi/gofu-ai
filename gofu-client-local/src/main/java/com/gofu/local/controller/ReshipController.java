package com.gofu.local.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.local.config.AppProperties;
import com.gofu.local.service.reship.ReshipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 快麦 ERP 订单补发。POST /run 起补发(返 taskId,前端复用 /api/task/{id} 轮询)。
 * ERP 账号/公司/密码存本地 reship-config.json(同快麦凭证待遇,本地不上云;密码不入日志)。
 */
@RestController
@RequestMapping("/api/reship")
public class ReshipController {

    private static final Logger log = LoggerFactory.getLogger(ReshipController.class);

    private final ReshipService reshipService;
    private final AppProperties appProperties;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReshipController(ReshipService reshipService, AppProperties appProperties) {
        this.reshipService = reshipService;
        this.appProperties = appProperties;
    }

    private File configFile() {
        String dir = appProperties.getPaths().getUserDataDir();
        if (dir == null || dir.isBlank()) dir = System.getProperty("user.dir");
        return new File(dir, "reship-config.json");
    }

    /** 读 ERP 配置(密码回传前端用于密文回填;不写日志)。 */
    @GetMapping("/config")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> getConfig() {
        File f = configFile();
        Map<String, Object> out = new LinkedHashMap<>();
        if (f.exists()) {
            try {
                Map<String, Object> m = mapper.readValue(f, Map.class);
                out.putAll(m);
            } catch (Exception e) { log.warn("[补发] 读配置失败: {}", e.getMessage()); }
        }
        return ResponseEntity.ok(out);
    }

    /** 存 ERP 配置到 reship-config.json(明文本地,同快麦)。 */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("erpCompany", String.valueOf(body.getOrDefault("erpCompany", "")));
            cfg.put("erpAccount", String.valueOf(body.getOrDefault("erpAccount", "")));
            cfg.put("erpPassword", String.valueOf(body.getOrDefault("erpPassword", "")));
            Files.write(configFile().toPath(), mapper.writeValueAsBytes(cfg));
            log.info("[补发] ERP 配置已保存(公司/账号已写,密码不记录)");
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "保存失败：" + e.getMessage()));
        }
    }

    /**
     * 启动补发。入参 { sourcePath, targetPath }(ERP 凭据从 reship-config.json 读)。返回 { taskId }。
     */
    @PostMapping("/run")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        String sourcePath = String.valueOf(body.getOrDefault("sourcePath", "")).trim();
        String targetPath = String.valueOf(body.getOrDefault("targetPath", "")).trim();
        // 模式:local(路径,来自上传临时文件) / wps(kdocs.cn 云文档 URL)。默认 local。
        String sourceKind = "wps".equals(body.get("sourceKind")) ? "wps" : "local";
        String targetKind = "wps".equals(body.get("targetKind")) ? "wps" : "local";
        if (sourcePath.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "缺少补发表" + ("wps".equals(sourceKind) ? " URL" : "路径")));
        if (targetPath.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "缺少 GOFU 补发表" + ("wps".equals(targetKind) ? " URL" : "路径")));
        // 仅 local 校验文件存在(wps 是 URL 不校验)。否则 node 读时报看不懂的 EISDIR。
        if ("local".equals(sourceKind) && !new File(sourcePath).isFile())
            return ResponseEntity.badRequest().body(Map.of("error", "补发表不是有效文件:" + sourcePath));
        if ("local".equals(targetKind) && !new File(targetPath).isFile())
            return ResponseEntity.badRequest().body(Map.of("error", "GOFU补发表不是有效文件:" + targetPath));

        // 读 ERP 凭据
        File f = configFile();
        if (!f.exists()) return ResponseEntity.badRequest().body(Map.of("error", "未配置 ERP 账号,请先在设置里填写"));
        Map<String, Object> cfg;
        try { cfg = mapper.readValue(f, Map.class); }
        catch (Exception e) { return ResponseEntity.internalServerError().body(Map.of("error", "读取 ERP 配置失败：" + e.getMessage())); }

        Map<String, Object> runCfg = new LinkedHashMap<>();
        runCfg.put("erpCompany", cfg.getOrDefault("erpCompany", ""));
        runCfg.put("erpAccount", cfg.getOrDefault("erpAccount", ""));
        runCfg.put("erpPassword", cfg.getOrDefault("erpPassword", ""));
        runCfg.put("sourcePath", sourcePath);
        runCfg.put("targetPath", targetPath);
        runCfg.put("sourceKind", sourceKind);
        runCfg.put("targetKind", targetKind);
        // WPS 登录态目录(任一端 wps 才用)
        if ("wps".equals(sourceKind) || "wps".equals(targetKind)) {
            String dir = appProperties.getPaths().getUserDataDir();
            if (dir == null || dir.isBlank()) dir = System.getProperty("user.dir");
            File wd = new File(dir, "wps_cloud_profile"); wd.mkdirs();
            runCfg.put("wpsUserDataDir", wd.getAbsolutePath());
        }

        try {
            String taskId = reshipService.runReship(runCfg);
            return ResponseEntity.ok(Map.of("taskId", taskId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "启动补发失败：" + e.getMessage()));
        }
    }

    /** 补发临时目录:上传的表存这,补发就地标红/追加也在这,下载从这取。 */
    private File reshipTempDir() {
        String dir = appProperties.getPaths().getUserDataDir();
        if (dir == null || dir.isBlank()) dir = System.getProperty("user.dir");
        File d = new File(dir, "reship-tmp");
        d.mkdirs();
        return d;
    }

    /**
     * 方案2:上传补发表(base64)→存临时文件→返回临时路径。前端选文件后调,拿到路径再传给 /run。
     * 入参 { role:'source'|'target', name, b64 }。返回 { path }。
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(@RequestBody Map<String, Object> body) {
        try {
            String role = String.valueOf(body.getOrDefault("role", "source"));
            String b64 = String.valueOf(body.getOrDefault("b64", ""));
            if (b64.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "空文件"));
            byte[] bytes = java.util.Base64.getDecoder().decode(b64);
            // 固定文件名(按role),同会话覆盖;避免堆积。source标红回写它、target追加迁移写它。
            String fn = ("target".equals(role) ? "gofu_target" : "reship_source") + ".xlsx";
            File out = new File(reshipTempDir(), fn);
            java.nio.file.Files.write(out.toPath(), bytes);
            return ResponseEntity.ok(Map.of("path", out.getAbsolutePath()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "上传失败：" + e.getMessage()));
        }
    }

    /**
     * 方案2:下载处理后的结果表。role=source(标红版补发表)/target(迁移后GOFU表)。
     */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(@RequestParam String role) {
        String fn = "target".equals(role) ? "gofu_target.xlsx" : "reship_source.xlsx";
        File f = new File(reshipTempDir(), fn);
        if (!f.isFile()) return ResponseEntity.notFound().build();
        try {
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            String dl = "target".equals(role) ? "GOFU补发表_迁移结果.xlsx" : "补发表_标红结果.xlsx";
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + java.net.URLEncoder.encode(dl, "UTF-8").replace("+", "%20"))
                    .header(org.springframework.http.HttpHeaders.CONTENT_TYPE,
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .body(data);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}

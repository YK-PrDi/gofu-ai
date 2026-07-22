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
        if (sourcePath.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "缺少补发表路径 sourcePath"));
        if (targetPath.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "缺少 GOFU 补发表路径 targetPath"));
        // 路径校验:必须是存在的 .xlsx 文件(不是目录/不存在)。否则 node 读时报看不懂的 EISDIR。
        File srcF = new File(sourcePath), tgtF = new File(targetPath);
        if (!srcF.isFile()) return ResponseEntity.badRequest().body(Map.of("error",
                "补发表路径不是文件(是目录或不存在):" + sourcePath + " —— 请在设置里填到具体 .xlsx 文件"));
        if (!tgtF.isFile()) return ResponseEntity.badRequest().body(Map.of("error",
                "GOFU补发表路径不是文件(是目录或不存在):" + targetPath + " —— 请在设置里填到具体 .xlsx 文件"));

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

        try {
            String taskId = reshipService.runReship(runCfg);
            return ResponseEntity.ok(Map.of("taskId", taskId));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "启动补发失败：" + e.getMessage()));
        }
    }
}

package com.gofu.local.service.reship;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.local.config.AppProperties;
import com.gofu.local.model.GenerationTask;
import com.gofu.local.service.listing.ListingService;
import com.gofu.local.service.listing.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 快麦 ERP 订单补发:起便携 node 跑 tools/reship/reship-cli.js,读 stdout 每行 JSON 进度写进 TaskService。
 * 复用 ListingService 的 node 解析(resolveNodeExe)。与上新同为本地自动化(Playwright 驱动真实网页+真实登录态)。
 * ERP 密码只透传给子进程,不写日志。
 */
@Service
public class ReshipService {

    private static final Logger log = LoggerFactory.getLogger(ReshipService.class);

    private final TaskService taskService;
    private final ListingService listingService;   // 复用 resolveNodeExe / 定位 tools
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReshipService(TaskService taskService, ListingService listingService, AppProperties appProperties) {
        this.taskService = taskService;
        this.listingService = listingService;
        this.appProperties = appProperties;
    }

    /**
     * 定位 tools/reship/reship-cli.js。与上新 resolvePlaywrightScript 对齐,并复用它已跑通的
     * 脚本目录做锚点(上新脚本在 tools/pdd_listing.js,补发在 tools/reship/reship-cli.js,同一个 tools 根)——
     * 这样只要上新脚本能定位,补发就能靠它的父目录 tools/ 拼出来,不受打包态 user.dir 差异影响。
     */
    public File resolveReshipScript() {
        java.util.List<File> candidates = new java.util.ArrayList<>();
        String resourcesPath = System.getProperty("app.resources-path");
        if (resourcesPath != null && !resourcesPath.isBlank())
            candidates.add(new File(resourcesPath, "tools/reship/reship-cli.js"));
        // 关键锚点:借上新脚本(打包态已跑通)的 tools 目录,拼 reship 子目录。
        File listingScript = listingService.resolvePlaywrightScript();
        if (listingScript != null) {
            File toolsDir = listingScript.getParentFile();   // .../tools
            if (toolsDir != null) candidates.add(new File(toolsDir, "reship/reship-cli.js"));
        }
        String userDir = System.getProperty("user.dir");
        candidates.add(new File(userDir, "tools/reship/reship-cli.js"));
        candidates.add(new File(userDir, "gofu-client-local/tools/reship/reship-cli.js"));

        for (File f : candidates) if (f.exists()) return f;
        // 诊断:打包态报"找不到"时,把试过的绝对路径全打出来,一眼看出是路径没锚对还是脚本真没打进包。
        log.warn("[诊断·reship脚本] 未找到 reship-cli.js。app.resources-path={} user.dir={} 上新脚本={} 试过: {}",
                resourcesPath, userDir,
                listingScript != null ? listingScript.getAbsolutePath() : "(null)",
                candidates.stream().map(File::getAbsolutePath).toList());
        return null;
    }

    /** ERP 浏览器持久化目录:独立于上新的 pdd_browser_profile,存 ERP 登录态。 */
    private String erpUserDataDir() {
        String dir = appProperties.getPaths().getUserDataDir();
        if (dir == null || dir.isBlank()) dir = System.getProperty("user.dir");
        File d = new File(dir, "erp_reship_profile");
        d.mkdirs();
        return d.getAbsolutePath();
    }

    /** WPS 登录目录:与补发运行时同一目录,登录态复用。 */
    private String wpsUserDataDir() {
        String dir = appProperties.getPaths().getUserDataDir();
        if (dir == null || dir.isBlank()) dir = System.getProperty("user.dir");
        File d = new File(dir, "wps_cloud_profile"); d.mkdirs();
        return d.getAbsolutePath();
    }

    /**
     * 先登录 WPS 云文档(异步任务,弹 Edge 让用户登录,等文档就绪)。与补发分开,避免首次登录慢撞超时。
     * 返回 taskId,前端轮询 /api/task/{id}。docUrl=补发表云文档链接(登录态按域名共享,登一个即可)。
     */
    public String wpsLogin(String docUrl) throws Exception {
        File scriptFile = resolveReshipScript();
        if (scriptFile == null || !scriptFile.exists()) throw new RuntimeException("找不到补发脚本");
        java.util.Map<String, Object> cfg = new java.util.LinkedHashMap<>();
        cfg.put("sourcePath", docUrl);
        cfg.put("wpsUserDataDir", wpsUserDataDir());
        String cfgJson = objectMapper.writeValueAsString(cfg);

        GenerationTask task = taskService.createTask(1);
        File projectRoot = scriptFile.getParentFile();
        taskService.submit(task, () -> {
            Process proc = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        listingService.resolveNodeExe(), scriptFile.getAbsolutePath(), "--wps-login")
                        .directory(projectRoot).redirectErrorStream(false);
                proc = pb.start();
                try (java.io.OutputStream os = proc.getOutputStream()) {
                    os.write(cfgJson.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignore) {}
                boolean ok = false;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        log.info("[wps-login] {}", line);
                        Map<String, Object> ev;
                        try { ev = objectMapper.readValue(line, Map.class); } catch (Exception ex) { continue; }
                        String type = String.valueOf(ev.get("type"));
                        if ("progress".equals(type)) task.addResult(Map.of("type", "progress", "message", String.valueOf(ev.getOrDefault("message", ""))));
                        else if ("done".equals(type)) { ok = Boolean.TRUE.equals(ev.get("ok")); task.addResult(Map.of("type", ok ? "done" : "error", "message", String.valueOf(ev.getOrDefault("message", "")))); }
                        else if ("error".equals(type)) task.addResult(Map.of("type", "error", "message", String.valueOf(ev.getOrDefault("message", ""))));
                    }
                }
                int code = proc.waitFor();
                if (!(ok && code == 0)) throw new RuntimeException("WPS 登录未就绪");
            } catch (Exception e) {
                task.addResult(Map.of("type", "error", "message", "WPS登录失败：" + e.getMessage()));
                if (proc != null && proc.isAlive()) proc.destroy();
                throw new RuntimeException(e.getMessage(), e);
            } finally {
                if (proc != null && proc.isAlive()) proc.destroy();
            }
        });
        return task.getId();
    }

    /**
     * 启动补发。config:{erpCompany,erpAccount,erpPassword,sourcePath,targetPath}。返回 taskId,前端轮询 /api/task/{id}。
     */
    public String runReship(Map<String, Object> reqConfig) throws Exception {
        File scriptFile = resolveReshipScript();
        if (scriptFile == null || !scriptFile.exists()) {
            throw new RuntimeException("找不到补发脚本 tools/reship/reship-cli.js");
        }

        // 组装 CLI 配置(补 userDataDir)。密码只进 JSON 传子进程,不打日志。
        java.util.Map<String, Object> cliCfg = new java.util.LinkedHashMap<>(reqConfig);
        cliCfg.put("userDataDir", erpUserDataDir());
        String cfgJson = objectMapper.writeValueAsString(cliCfg);

        log.info("[补发] 启动:source={} target={} (公司/账号已配,密码不记录)",
                reqConfig.get("sourcePath"), reqConfig.get("targetPath"));

        GenerationTask task = taskService.createTask(100);
        File projectRoot = scriptFile.getParentFile();

        taskService.submit(task, () -> {
            Process proc = null;
            try {
                // JSON 配置走 stdin 传(不走命令行参数):避开 Windows ProcessBuilder 对含双引号参数的转义,
                // 密码也不出现在进程命令行。cli 从 stdin 读。
                ProcessBuilder pb = new ProcessBuilder(
                        listingService.resolveNodeExe(), scriptFile.getAbsolutePath())
                        .directory(projectRoot).redirectErrorStream(false);
                proc = pb.start();
                // 写配置到子进程 stdin 后关闭(cli 的 stdin 'end' 事件触发读取)
                try (java.io.OutputStream os = proc.getOutputStream()) {
                    os.write(cfgJson.getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignore) {}

                boolean sawDone = false;
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[reship] {}", line);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> ev;
                        try { ev = objectMapper.readValue(line, Map.class); }
                        catch (Exception parseErr) { continue; }   // 非JSON行(依赖告警等)忽略
                        String type = String.valueOf(ev.get("type"));
                        if ("progress".equals(type)) {
                            task.addResult(Map.of("type", "progress",
                                    "message", String.valueOf(ev.getOrDefault("message", ""))));
                            task.incrementProgress();
                        } else if ("done".equals(type)) {
                            sawDone = Boolean.TRUE.equals(ev.get("ok"));
                            task.addResult(Map.of("type", sawDone ? "done" : "error",
                                    "message", String.valueOf(ev.getOrDefault("message", "补发完成"))));
                        } else if ("error".equals(type)) {
                            task.addResult(Map.of("type", "error",
                                    "message", String.valueOf(ev.getOrDefault("message", "补发失败"))));
                        }
                    }
                }
                int code = proc.waitFor();
                // 失败必须抛出:TaskService.submit 在 work 正常返回时会强制置 done,只有抛异常才会置 error。
                if (!(sawDone && code == 0)) {
                    throw new RuntimeException("补发未成功(exit=" + code + ")");
                }
                // 成功:不手动置 done,交给 submit 统一置(避免与其状态机冲突)。
            } catch (Exception e) {
                log.error("[补发] 执行异常: {}", e.getMessage(), e);
                task.addResult(Map.of("type", "error", "message", "补发失败：" + e.getMessage()));
                if (proc != null && proc.isAlive()) proc.destroy();
                throw new RuntimeException(e.getMessage(), e);   // 抛出→submit 置 error
            } finally {
                if (proc != null && proc.isAlive()) proc.destroy();
            }
        });

        return task.getId();
    }
}

package com.gofu.local.service.listing;

import com.gofu.local.config.AppProperties;
import com.gofu.local.model.ListingConfig;
import com.gofu.local.model.GenerationTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.*;

/**
 * 上新流程调度。自 LY-Automation 迁入，仅保留纯上新核心（Playwright 驱动 + 文件/xlsx 解析）。
 *
 * <p>⚠️ 已剥离的部分（ADR-002：AI 算力归云端）：
 * <ul>
 *   <li>标题生成（prepareWithAI/prepareWithVision/prepareFromTitleLib）—— 调豆包/Gemini，待云端封装</li>
 *   <li>SKU 规划（generateSkuPlans）—— 调豆包，待云端封装</li>
 *   <li>原构造器依赖的 ImageGenService —— 生图归云端</li>
 * </ul>
 * 这些能力在云端 AI 服务（M3+）就绪后，由本地 cloudgw 调用，不在本类重建。
 *
 * <p>反风控自动化（runListing/runLoginOnly）原样保留，禁止重写（雷区 7、ADR-002）。
 */
@Service
public class ListingService {

    private static final Logger log = LoggerFactory.getLogger(ListingService.class);

    private final AppProperties appProperties;
    private final TaskService taskService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** M15：上新/登录进程是否在跑。保活与它争用同一 pdd_browser_profile，需互斥（保活跑时若上新占用则跳过）。 */
    private final java.util.concurrent.atomic.AtomicBoolean browserBusy =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final StoreService storeService;

    public ListingService(AppProperties appProperties, TaskService taskService, StoreService storeService) {
        this.appProperties = appProperties;
        this.taskService = taskService;
        this.storeService = storeService;
    }

    public boolean isBrowserBusy() { return browserBusy.get(); }

    // ──────────────────────────────────────────────────────────────────────
    // 上新执行（Playwright 反风控）—— 原样迁入，禁止重写
    // ──────────────────────────────────────────────────────────────────────

    public String runListing(ListingConfig config) throws Exception {
        return runListing(config, false);
    }

    public String runListing(ListingConfig config, boolean dryRun) throws Exception {
        GenerationTask task = taskService.createTask(10);

        File scriptFile = resolvePlaywrightScript();
        if (scriptFile == null || !scriptFile.exists()) {
            throw new RuntimeException("找不到 Playwright 脚本 pdd_listing.js，请确认 tools/ 目录下已安装");
        }

        String userDataDir = appProperties.getPaths().getUserDataDir();
        if (userDataDir == null || userDataDir.isBlank()) userDataDir = System.getProperty("user.dir");
        // 多店：config 已带 cookiesPath/userDataDir(P5 编排按 store 注入)则尊重之；否则回退单店默认。
        if (config.getCookiesPath() == null || config.getCookiesPath().isBlank()) {
            config.setCookiesPath(userDataDir + "/pdd_cookies.json");
        }
        // userDataDir 为空时不填：pdd_listing.js 默认放 cookie 同目录的 pdd_browser_profile（保持单店旧行为）。

        String configJson = objectMapper.writeValueAsString(config);
        File projectRoot = scriptFile.getParentFile();

        taskService.submit(task, () -> {
            Process proc = null;
            // 成功语义（修 bug6）：脚本用 DONE:/ERROR: 标记真实结果，这里传导到任务状态。
            // 之前 submit 只要进程正常退出就标 done，与"真实发品成功"脱钩 → 前端误报成功。
            final boolean[] sawDone = {false};
            final String[] lastError = {null};
            browserBusy.set(true);   // M15：占用 profile，保活此时会跳过
            try {
                ProcessBuilder pb = dryRun
                    ? new ProcessBuilder(resolveNodeExe(), scriptFile.getAbsolutePath(), "--dry-run")
                        .directory(projectRoot).redirectErrorStream(false)
                    : new ProcessBuilder(resolveNodeExe(), scriptFile.getAbsolutePath())
                        .directory(projectRoot).redirectErrorStream(false);
                pb.environment().putAll(buildPlaywrightEnv(configJson));
                proc = pb.start();

                try { proc.getOutputStream().close(); } catch (Exception ignore) {}

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[pdd_listing] {}", line);
                        if (line.startsWith("PROGRESS:")) {
                            String[] parts = line.split(":", 3);
                            String msg = parts.length >= 3 ? parts[2] : line;
                            task.addResult(Map.of("type", "progress", "message", msg));
                            task.incrementProgress();
                        } else if (line.startsWith("DONE:")) {
                            sawDone[0] = true;
                            task.addResult(Map.of("type", "done", "message", "上新完成"));
                        } else if (line.startsWith("ERROR:")) {
                            lastError[0] = line.substring(6);
                            task.addResult(Map.of("type", "error", "message", line.substring(6)));
                        } else if (!line.isBlank()) {
                            task.addResult(Map.of("type", "log", "message", line));
                        }
                    }
                }

                try (BufferedReader err = new BufferedReader(
                        new InputStreamReader(proc.getErrorStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        if (!line.isBlank()) task.addResult(Map.of("type", "log", "message", "[err] " + line));
                    }
                }

                boolean done = proc.waitFor(30, java.util.concurrent.TimeUnit.MINUTES);
                if (!done) {
                    proc.destroyForcibly();
                    task.addResult(Map.of("type", "error", "message", "自动化超时（30分钟）"));
                    throw new RuntimeException("自动化超时（30分钟）");
                }
                // 脚本报了 ERROR 或从未报 DONE → 判定失败（抛异常让 TaskService 标 error），前端才不会误报成功
                if (lastError[0] != null) {
                    throw new RuntimeException("上新失败：" + lastError[0]);
                }
                if (!sawDone[0]) {
                    throw new RuntimeException("上新未完成：脚本未输出成功标志（可能停在登录/校验或被拦截）");
                }
            } catch (RuntimeException re) {
                throw re;   // 上面主动抛的失败，透传给 submit 标 error
            } catch (Exception e) {
                log.error("Playwright 子进程异常: {}", e.getMessage(), e);
                task.addResult(Map.of("type", "error", "message", "自动化异常: " + e.getMessage()));
                throw new RuntimeException(e);
            } finally {
                if (proc != null) try { proc.destroyForcibly(); } catch (Exception ignored) {}
                browserBusy.set(false);
            }
        });

        return task.getId();
    }

    /**
     * M15 登录态保活：同步调 pdd_listing.js --keep-alive，headed 静默访问后台刷 token 后退出。
     * 与上新互斥（上新占用 profile 时跳过）。供 PddKeepAliveService 定时调用。
     * 返回 true=已保活/已刷新，false=跳过或未登录/失败。
     */
    public boolean runKeepAlive() {
        String userDataDir = appProperties.getPaths().getUserDataDir();
        if (userDataDir == null || userDataDir.isBlank()) userDataDir = System.getProperty("user.dir");
        return runKeepAlive(userDataDir + "/pdd_cookies.json", null);
    }

    /**
     * 保活指定店铺（多店，P4）：按 store 的 cookie/profile 路径刷 token。
     * @param cookiesPath 该店 cookie 路径  @param storeUserDataDir 该店 user-data-dir（null=脚本默认）
     */
    public boolean runKeepAlive(String cookiesPath, String storeUserDataDir) {
        if (browserBusy.get()) { log.info("[保活] 上新进程占用浏览器，本次跳过"); return false; }
        File scriptFile = resolvePlaywrightScript();
        if (scriptFile == null || !scriptFile.exists()) { log.warn("[保活] 找不到 pdd_listing.js，跳过"); return false; }

        if (!browserBusy.compareAndSet(false, true)) { log.info("[保活] 浏览器忙，跳过"); return false; }
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(resolveNodeExe(), scriptFile.getAbsolutePath(), "--keep-alive")
                    .directory(scriptFile.getParentFile()).redirectErrorStream(true);
            Map<String, Object> cfg = new java.util.HashMap<>();
            cfg.put("cookiesPath", cookiesPath);
            if (storeUserDataDir != null && !storeUserDataDir.isBlank()) cfg.put("userDataDir", storeUserDataDir);
            pb.environment().putAll(buildPlaywrightEnv(objectMapper.writeValueAsString(cfg)));
            proc = pb.start();
            boolean kept = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    log.info("[保活] {}", line);
                    if (line.contains("kept_alive")) kept = true;
                }
            }
            boolean finished = proc.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
            if (!finished) { proc.destroyForcibly(); log.warn("[保活] 超时(5分钟)，强制结束"); return false; }
            log.info("[保活] 完成，登录态刷新={}", kept);
            return kept;
        } catch (Exception e) {
            log.warn("[保活] 异常(忽略): {}", e.getMessage());
            return false;
        } finally {
            if (proc != null) try { proc.destroyForcibly(); } catch (Exception ignored) {}
            browserBusy.set(false);
        }
    }

    /** 仅触发登录流程（--login-only），保存 cookies 后退出。单店默认路径。 */
    public String runLoginOnly() throws Exception {
        String userDataDir = appProperties.getPaths().getUserDataDir();
        if (userDataDir == null || userDataDir.isBlank()) userDataDir = System.getProperty("user.dir");
        return runLoginOnly(userDataDir + "/pdd_cookies.json", null);
    }

    /**
     * 仅登录指定店铺（多店，P4）：按 store 的 cookie/profile 路径登录，反风控脚本一行不动。
     * @param cookiesPath  该店 cookie 路径（StoreService.cookiesPathOf）
     * @param storeUserDataDir 该店独立 user-data-dir（StoreService.userDataDirOf；null=脚本默认）
     */
    public String runLoginOnly(String cookiesPath, String storeUserDataDir) throws Exception {
        return runLoginOnly(cookiesPath, storeUserDataDir, null);
    }

    /**
     * 仅登录 + 尽力回填店铺名（多店）。登录成功后脚本抓到 SHOPNAME: 时，按 profile 更新 stores.json 的店铺名。
     * @param profile 该店 profile（用于回填店铺名；null 则不回填）
     */
    public String runLoginOnly(String cookiesPath, String storeUserDataDir, String profile) throws Exception {
        File scriptFile = resolvePlaywrightScript();
        if (scriptFile == null || !scriptFile.exists()) {
            throw new RuntimeException("找不到 pdd_listing.js");
        }

        GenerationTask task = taskService.createTask(2);
        File projectRoot = scriptFile.getParentFile();
        final String cp = cookiesPath;
        final String udd = storeUserDataDir;
        final String prof = profile;

        taskService.submit(task, () -> {
            Process proc = null;
            browserBusy.set(true);   // M15：登录也占用 profile，保活跳过
            try {
                ProcessBuilder pb = new ProcessBuilder(resolveNodeExe(), scriptFile.getAbsolutePath(), "--login-only")
                    .directory(projectRoot).redirectErrorStream(true);   // stderr 并入 stdout，脚本报错也能在控制台看到
                Map<String, Object> cfg = new java.util.HashMap<>();
                cfg.put("cookiesPath", cp);
                if (udd != null && !udd.isBlank()) cfg.put("userDataDir", udd);
                pb.environment().putAll(buildPlaywrightEnv(objectMapper.writeValueAsString(cfg)));
                proc = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.info("[pdd_listing] {}", line);   // 打到控制台，便于看 SHOPNAME_DIAG 等登录诊断
                        if (line.startsWith("SHOPNAME:") && prof != null && !prof.isBlank()) {
                            String shopName = line.substring("SHOPNAME:".length()).trim();
                            if (!shopName.isBlank()) {
                                try { storeService.renameStore(prof, shopName); log.info("[登录] 已回填店铺名 profile={} name={}", prof, shopName); }
                                catch (Exception ex) { log.warn("[登录] 回填店铺名失败: {}", ex.getMessage()); }
                                task.addResult(Map.of("type", "log", "message", "已识别店铺名：" + shopName));
                            }
                        } else if (!line.isBlank()) {
                            task.addResult(Map.of("type", "log", "message", line));
                        }
                    }
                }
                boolean finished = proc.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
                int exitCode = finished ? proc.exitValue() : -1;
                if (exitCode == 0) {
                    task.addResult(Map.of("type", "done", "message", "登录完成，cookies 已保存"));
                } else {
                    task.addResult(Map.of("type", "error", "message", "登录脚本异常退出（exitCode=" + exitCode + "），请重试"));
                }
            } catch (Exception e) {
                task.addResult(Map.of("type", "error", "message", "登录失败: " + e.getMessage()));
            } finally {
                if (proc != null) try { proc.destroyForcibly(); } catch (Exception ignored) {}
                browserBusy.set(false);
            }
        });
        return task.getId();
    }

    public File resolvePlaywrightScript() {
        String resourcesPath = System.getProperty("app.resources-path");
        if (resourcesPath != null && !resourcesPath.isBlank()) {
            File f = new File(resourcesPath, "tools/pdd_listing.js");
            if (f.exists()) return f;
        }
        // 多候选：无论从仓库根、模块目录还是打包态启动都能定位（修"找不到 pdd_listing.js"）。
        String userDir = System.getProperty("user.dir");
        File[] candidates = {
            new File(userDir, "tools/pdd_listing.js"),                        // 工作目录=模块目录
            new File(userDir, "gofu-client-local/tools/pdd_listing.js"),      // 工作目录=仓库根
        };
        for (File f : candidates) {
            if (f.exists()) return f;
        }
        return candidates[0];   // 都没有则返回首选（上层据 exists() 报错提示）
    }

    /**
     * 解析 node 可执行文件路径。优先用打包的便携 node，找不到回退系统 PATH 的 "node"。
     */
    public String resolveNodeExe() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win") ? "node.exe" : "node";
        java.util.List<File> candidates = new java.util.ArrayList<>();
        String resourcesPath = System.getProperty("app.resources-path");
        if (resourcesPath != null && !resourcesPath.isBlank()) {
            candidates.add(new File(resourcesPath, "tools/node/" + exe));
            candidates.add(new File(resourcesPath, "resources/tools/node/" + exe));
        }
        candidates.add(new File(System.getProperty("user.dir"), "tools/node/" + exe));
        File script = resolvePlaywrightScript();
        if (script != null) {
            File toolsDir = script.getParentFile();
            if (toolsDir != null) candidates.add(new File(toolsDir, "node/" + exe));
        }
        for (File f : candidates) {
            if (f.isFile()) {
                log.info("使用便携 node: {}", f.getAbsolutePath());
                return f.getAbsolutePath();
            }
        }
        log.warn("未找到便携 node，回退系统 node。已尝试: {}", candidates);
        return "node";
    }

    /**
     * 构建 Playwright 子进程的环境变量。PLAYWRIGHT_BROWSERS_PATH 指向 tools/browsers，
     * node_modules/.bin 加入 PATH。
     */
    private java.util.Map<String, String> buildPlaywrightEnv(String configJson) {
        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());
        env.put("PDD_CONFIG", configJson);

        String resourcesPath = System.getProperty("app.resources-path");
        File browsersDir = null;
        if (resourcesPath != null && !resourcesPath.isBlank()) {
            File f = new File(resourcesPath, "tools/browsers");
            if (f.isDirectory()) browsersDir = f;
        }
        if (browsersDir == null) {
            File f = new File(System.getProperty("user.dir"), "tools/browsers");
            if (f.isDirectory()) browsersDir = f;
        }
        if (browsersDir != null) {
            env.put("PLAYWRIGHT_BROWSERS_PATH", browsersDir.getAbsolutePath());
        }

        File nodeModulesDir = null;
        if (resourcesPath != null && !resourcesPath.isBlank()) {
            File f = new File(resourcesPath, "tools/node_modules");
            if (f.isDirectory()) nodeModulesDir = f;
        }
        if (nodeModulesDir == null) {
            File f = new File(System.getProperty("user.dir"), "tools/node_modules");
            if (f.isDirectory()) nodeModulesDir = f;
        }
        if (nodeModulesDir != null) {
            String existingPath = env.getOrDefault("PATH", "");
            env.put("PATH", nodeModulesDir.getAbsolutePath() + File.pathSeparator + existingPath);
        }

        return env;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 素材文件夹扫描 / xlsx 解析 —— 纯文件操作，原样迁入
    // ──────────────────────────────────────────────────────────────────────

    /**
     * 扫描商品大文件夹：自动识别主图/SKU/详情/白底子文件夹，按文件名匹配 SKU 图片。
     */
    public Map<String, Object> scanFolder(String folderPath) throws Exception {
        File root = new File(folderPath);
        if (!root.isDirectory()) throw new IllegalArgumentException("路径不是文件夹：" + folderPath);

        Map<String, Object> result = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        String mainImgDir = null, detailImgDir = null, whiteImgDir = null, skuImgDir = null;
        File[] dirs = root.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                String n = d.getName().toLowerCase();
                if (n.contains("主图") || n.equals("main"))          mainImgDir   = d.getAbsolutePath();
                else if (n.contains("sku") || n.contains("款式") || n.contains("颜色")) skuImgDir = d.getAbsolutePath();
                else if (n.contains("详情") || n.contains("detail")) detailImgDir = d.getAbsolutePath();
                else if (n.contains("白底") || n.contains("white"))  whiteImgDir  = d.getAbsolutePath();
            }
        }
        result.put("mainImgDir",   mainImgDir   != null ? mainImgDir   : "");
        result.put("detailImgDir", detailImgDir != null ? detailImgDir : "");
        result.put("whiteImgDir",  whiteImgDir  != null ? whiteImgDir  : "");
        result.put("skuImgDir",    skuImgDir    != null ? skuImgDir    : "");

        List<String> skuImages = new ArrayList<>();
        if (skuImgDir != null) {
            File[] imgs = new File(skuImgDir).listFiles(f ->
                f.isFile() && f.getName().toLowerCase().matches(".*\\.(jpg|jpeg|png|webp|bmp|gif)$"));
            if (imgs != null) {
                Arrays.sort(imgs, java.util.Comparator.comparing(
                    f -> f.getName(), ListingService::naturalCompare));
                for (File f : imgs) skuImages.add(f.getAbsolutePath());
            }
        } else {
            warnings.add("未找到 SKU 图片文件夹（命名需含\"sku\"/\"款式\"/\"颜色\"）");
        }

        result.put("skuImages", skuImages);
        result.put("warnings", warnings);
        return result;
    }

    /** 扫描某文件夹根目录下的图片，按文件名自然数字顺序返回绝对路径列表。 */
    public List<String> listImagesInFolder(String folderPath) {
        List<String> out = new ArrayList<>();
        File dir = new File(folderPath);
        if (!dir.isDirectory()) return out;
        File[] imgs = dir.listFiles(f ->
            f.isFile() && f.getName().toLowerCase().matches(".*\\.(jpg|jpeg|png|webp|bmp|gif)$"));
        if (imgs != null) {
            Arrays.sort(imgs, java.util.Comparator.comparing(
                f -> f.getName(), ListingService::naturalCompare));
            for (File f : imgs) out.add(f.getAbsolutePath());
        }
        return out;
    }

    /** 文件名自然排序：1 < 2 < 10。 */
    private static int naturalCompare(String a, String b) {
        String na = a.replaceAll("\\.[^.]+$", "").replaceAll("\\D", "");
        String nb = b.replaceAll("\\.[^.]+$", "").replaceAll("\\D", "");
        if (!na.isEmpty() && !nb.isEmpty()) {
            try {
                int cmp = Long.compare(Long.parseLong(na), Long.parseLong(nb));
                if (cmp != 0) return cmp;
            } catch (NumberFormatException ignore) {}
        }
        return a.compareTo(b);
    }

    /**
     * 读取「产品信息填写参考.xlsx」品类预设属性。
     */
    public Map<String, List<Map<String, Object>>> readProductInfoPresets() {
        Map<String, List<Map<String, Object>>> presets = new LinkedHashMap<>();
        File f = new File(System.getProperty("user.dir"), "产品信息填写参考.xlsx");
        if (!f.isFile()) {
            String rp = System.getProperty("app.resources-path");
            if (rp != null && !rp.isBlank()) {
                File rf = new File(rp, "产品信息填写参考.xlsx");
                if (rf.isFile()) f = rf;
            }
        }
        if (!f.isFile()) {
            // 兜底：xlsx 未随包拷到（换机/漏拷）→ 读随 jar 走的 classpath JSON，保证属性预设永不为空。
            log.warn("产品信息填写参考.xlsx 未找到({}), 回退 classpath product-info-presets.json", f.getAbsolutePath());
            return readProductInfoPresetsFromJson();
        }

        try (java.io.FileInputStream fis = new java.io.FileInputStream(f);
             org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook(fis)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            String curCat = null;
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
                if (row == null) continue;
                String line = getCellStr(row.getCell(0)).trim();
                if (line.isEmpty()) continue;

                if (line.contains(" > ") || line.contains(">")) {
                    curCat = line.replace("　", " ").trim();
                    presets.putIfAbsent(curCat, new ArrayList<>());
                    continue;
                }
                if (curCat == null) continue;

                String name, value;
                int idx = line.indexOf('：');
                if (idx < 0) idx = line.indexOf(':');
                if (idx >= 0) { name = line.substring(0, idx).trim(); value = line.substring(idx + 1).trim(); }
                else { name = line.trim(); value = ""; }

                // 第 6 列(index 5)：人工选择项的默认值(如"默认碳钢"/"通用")。非空则自动填该默认、不再留空。
                String def = getCellStr(row.getCell(5)).trim().replaceFirst("^默认", "").trim();

                Map<String, Object> attr = new LinkedHashMap<>();
                attr.put("name", name);
                List<String> options = new ArrayList<>();
                boolean manual = false;
                String fixed = "";

                if (value.contains("人工选择") || value.contains("人工")) {
                    manual = true;
                    String opt = value.replace("（人工选择）", "").replace("(人工选择)", "")
                                      .replace("人工选择", "").replace("人工", "").trim();
                    if (!opt.isEmpty()) {
                        for (String o : opt.split("/")) if (!o.trim().isEmpty()) options.add(o.trim());
                    }
                } else if (value.isEmpty()) {
                    manual = true;
                } else {
                    fixed = value;
                }
                // 有默认值则自动填(转为非人工)：新店无「一键复用」也不留空；options 保留供商家后台改。
                if (!def.isEmpty()) { fixed = def; manual = false; }
                attr.put("value", fixed);
                attr.put("options", options);
                attr.put("manual", manual);
                presets.get(curCat).add(attr);
            }
        } catch (Exception e) {
            log.warn("读取产品信息参考表失败: {}, 回退 classpath JSON", e.getMessage());
            return readProductInfoPresetsFromJson();
        }
        return presets;
    }

    /**
     * 兜底：从 classpath prompt/product-info-presets.json 读预设属性（随 jar 走，绝不会漏拷）。
     * JSON 结构：{ "_v":n, "<类目路径>": [ {name,value,options,manual}, ... ] }，与 xlsx 解析结果同形。
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<Map<String, Object>>> readProductInfoPresetsFromJson() {
        Map<String, List<Map<String, Object>>> presets = new LinkedHashMap<>();
        try {
            String json = PromptLoader.load("prompt/product-info-presets.json");
            Map<String, Object> root = objectMapper.readValue(json, Map.class);
            for (Map.Entry<String, Object> e : root.entrySet()) {
                if (e.getKey().startsWith("_")) continue; // 跳过 _v 等元字段
                if (e.getValue() instanceof List<?> list) {
                    List<Map<String, Object>> attrs = new ArrayList<>();
                    for (Object o : list) if (o instanceof Map<?, ?> m) attrs.add((Map<String, Object>) m);
                    presets.put(e.getKey(), attrs);
                }
            }
        } catch (Exception e) {
            log.warn("classpath product-info-presets.json 读取失败(属性留空继续): {}", e.getMessage());
        }
        return presets;
    }

    /** 取某品类（全路径精确匹配）的预设属性，无匹配返回空列表。 */
    public List<Map<String, Object>> productInfoFor(String category) {
        if (category == null) return new ArrayList<>();
        Map<String, List<Map<String, Object>>> all = readProductInfoPresets();
        String key = category.replace("›", ">").replace("　", " ").trim();
        String norm = key.replaceAll("\\s*>\\s*", " > ");
        for (Map.Entry<String, List<Map<String, Object>>> e : all.entrySet()) {
            String ek = e.getKey().replaceAll("\\s*>\\s*", " > ");
            if (ek.equals(norm)) return e.getValue();
        }
        return new ArrayList<>();
    }

    /**
     * (C #3) 把文件夹名里写的品类（可为叶子如"锅盖架"、或全路径）解析成预设库里的**全路径**。
     * 索引来源就是 product-info-presets 的 key 集（同一份数据既给属性也给路径，无需另建品类树）。
     * 命中(叶子精确/末段相等/包含)返回全路径；无匹配原样返回入参（脚本仍可按末段搜类目）。
     */
    public String resolveCategoryPath(String catInput) {
        if (catInput == null || catInput.isBlank()) return catInput;
        String in = catInput.replace("›", ">").replace("＞", ">").replace("　", " ").replaceAll("\\s*>\\s*", ">").trim();   // M3:补全角＞
        String leaf = in.contains(">") ? in.substring(in.lastIndexOf('>') + 1).trim() : in;
        Map<String, List<Map<String, Object>>> all = readProductInfoPresets();
        // 1) 叶子精确等于某 key 的末段
        for (String k : all.keySet()) {
            String kl = k.replaceAll("\\s*>\\s*", ">");
            String klLeaf = kl.contains(">") ? kl.substring(kl.lastIndexOf('>') + 1) : kl;
            if (klLeaf.equals(leaf)) return k;
        }
        // 2) 退化：key 全路径包含输入串
        for (String k : all.keySet()) {
            if (k.replaceAll("\\s*>\\s*", ">").contains(in)) return k;
        }
        return catInput;   // 无匹配：原样返回（末段仍可用于脚本搜类目）
    }

    private String getCellStr(org.apache.poi.ss.usermodel.Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default      -> "";
        };
    }
}

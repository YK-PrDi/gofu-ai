import java.awt.Desktop;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * GOFU-AI 便携版启动器。双击 GOFU.exe → jpackage 以内嵌 JRE 运行本类。
 *
 * <p>本应用是两个 Spring Boot 服务（云端 5020 生图 + 本地 5021 工作台），一个 exe 只有一个主入口，
 * 故由本 Launcher 用同一个内嵌 JRE 拉起两个 jar 子进程，等端口就绪后打开系统浏览器到工作台。
 *
 * <p>目录约定（jpackage app-image 布局）：
 * <pre>
 *   GOFU/
 *   ├─ GOFU.exe
 *   ├─ runtime/bin/java.exe        内嵌 JRE
 *   └─ app/
 *       ├─ launcher.jar            本类（主入口）
 *       ├─ cloud.jar  local.jar    两个业务服务
 *       └─ data/                   运行时可写目录（DB/凭证/缓存/产物落这）
 * </pre>
 * data 用 app 同级目录：app-image 装在用户可写位置（解压即用），无需管理员权限。
 */
public class GofuLauncher {

    private static final int CLOUD_PORT = 5020;
    private static final int LOCAL_PORT = 5021;
    private static final String WORKBENCH = "http://localhost:" + LOCAL_PORT + "/";

    private static final List<Process> children = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        // launcher.jar 所在目录即 app/；java 可执行文件在 ../runtime/bin/java.exe
        File appDir = new File(GofuLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).getParentFile();
        File homeDir = appDir.getParentFile();                 // GOFU/
        File javaExe = new File(homeDir, "runtime/bin/java.exe");
        File cloudJar = new File(appDir, "cloud.jar");
        File localJar = new File(appDir, "local.jar");
        File dataDir = new File(appDir, "data");
        dataDir.mkdirs();

        // 首启种子：app/ 下放了 kuaimai-config.json 种子而 data 里还没有 → 拷入，让测试开箱即有真实凭证。
        // 已存在则不覆盖（保留测试改过的 token）。
        try {
            File seed = new File(appDir, "kuaimai-config.json");
            File dest = new File(dataDir, "kuaimai-config.json");
            if (seed.isFile() && !dest.isFile()) {
                Files.copy(seed.toPath(), dest.toPath());
                log("已写入快麦凭证种子: " + dest.getName());
            }
        } catch (Exception e) {
            log("快麦凭证种子拷贝失败(不阻断): " + e.getMessage());
        }

        // 内嵌 JRE 不在则回退系统 java（源码态调试用）
        String java = javaExe.isFile() ? javaExe.getAbsolutePath() : "java";

        log("GOFU-AI 启动中…");
        log("数据目录: " + dataDir.getAbsolutePath());

        // 两个服务共用同一可写数据目录：注入 -Dapp.paths.user-data-dir（代码已预留此机制）
        String dataArg = "-Dapp.paths.user-data-dir=" + dataDir.getAbsolutePath();
        // 本地上新要找 tools/(pdd_listing.js + 便携node + chromium)：注入 app.resources-path=app/ 目录，
        // ListingService.resolvePlaywrightScript/resolveNodeExe 据此定位（打包态机制）。
        String resourcesArg = "-Dapp.resources-path=" + appDir.getAbsolutePath();

        // 云端在本机跑，本地经 GOFU_CLOUD_URL 调它（默认就是 localhost:5020，此处显式稳一手）
        children.add(start(java, cloudJar, List.of(dataArg), dataDir, "cloud", null));
        children.add(start(java, localJar, List.of(dataArg, resourcesArg), dataDir, "local",
                "GOFU_CLOUD_URL=http://localhost:" + CLOUD_PORT));

        // 关闭时清理子进程，避免残留占端口
        Runtime.getRuntime().addShutdownHook(new Thread(GofuLauncher::stopAll));

        log("等待云端服务(" + CLOUD_PORT + ")就绪…");
        if (!waitPort(CLOUD_PORT, 90)) { fail("云端服务启动超时，请查看 data/logs 下日志"); return; }
        log("等待本地工作台(" + LOCAL_PORT + ")就绪…");
        if (!waitPort(LOCAL_PORT, 90)) { fail("本地工作台启动超时，请查看 data/logs 下日志"); return; }

        log("服务就绪，打开浏览器: " + WORKBENCH);
        openBrowser(WORKBENCH);

        log("");
        log("==== GOFU-AI 已启动，可开始测试 ====");
        log("  工作台: " + WORKBENCH);
        log("  关闭此窗口将停止所有服务。");
        log("====================================");

        // 主线程驻留，保持子进程存活；子进程异常退出则本进程也退出
        for (Process p : children) p.waitFor();
    }

    /** 用内嵌 JRE 起一个 fat jar，stdout/stderr 重定向到 data/logs/<tag>.log。 */
    private static Process start(String java, File jar, List<String> jvmArgs, File dataDir,
                                 String tag, String env) throws Exception {
        if (!jar.isFile()) throw new IllegalStateException("找不到 " + jar.getName() + "，包不完整");
        File logDir = new File(dataDir, "logs");
        logDir.mkdirs();
        File logFile = new File(logDir, tag + ".log");

        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        cmd.addAll(jvmArgs);
        cmd.add("-jar");
        cmd.add(jar.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dataDir);                       // 工作目录=data，${user.dir} 相对产物也落这
        pb.redirectErrorStream(true);
        pb.redirectOutput(logFile);
        if (env != null) {
            int eq = env.indexOf('=');
            pb.environment().put(env.substring(0, eq), env.substring(eq + 1));
        }
        log("启动 " + tag + " → 日志: " + logFile.getAbsolutePath());
        return pb.start();
    }

    /** 轮询 TCP 端口，最多等 timeoutSec 秒。 */
    private static boolean waitPort(int port, int timeoutSec) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 1000);
                return true;
            } catch (Exception ignore) {
                sleep(800);
            }
        }
        return false;
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            log("Desktop.browse 失败，改用 rundll32: " + e.getMessage());
        }
        // 回退：Windows rundll32 打开默认浏览器
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
        } catch (Exception e) {
            log("自动打开浏览器失败，请手动访问: " + url);
        }
    }

    private static void stopAll() {
        for (Process p : children) {
            try { if (p.isAlive()) p.destroy(); } catch (Exception ignore) {}
        }
        // 给一点时间优雅退出，再强杀
        sleep(1500);
        for (Process p : children) {
            try { if (p.isAlive()) p.destroyForcibly(); } catch (Exception ignore) {}
        }
    }

    private static void fail(String msg) {
        log("[错误] " + msg);
        stopAll();
        System.exit(1);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void log(String s) {
        System.out.println(s);
    }
}

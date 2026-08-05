package com.gofu.cloud.service.agent;

import com.gofu.cloud.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class GptImageAgent implements ImageGeneratorAgent {

    private static final Logger log = LoggerFactory.getLogger(GptImageAgent.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppProperties appProperties;
    // 轮询计数:多张图并发时轮流从不同key起,让3个key真正负载均衡分担(原来每张都从key[0]起→全挤key[0]被单key限流卡成串行)
    private static final java.util.concurrent.atomic.AtomicInteger KEY_RR = new java.util.concurrent.atomic.AtomicInteger(0);

    public GptImageAgent(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    // 返回本次生图要试的key顺序:以轮询offset为起点旋转整个列表。
    // 效果:并发的N张图起点各不同→均摊到各key(负载均衡);列表仍含全部key→某key失败自动试下一个(failover保留)。
    private List<String> orderedKeys() {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        if (keys == null || keys.isEmpty()) return List.of();
        int n = keys.size();
        if (n == 1) return new ArrayList<>(keys);
        int start = Math.floorMod(KEY_RR.getAndIncrement(), n);
        List<String> rotated = new ArrayList<>(n);
        for (int i = 0; i < n; i++) rotated.add(keys.get((start + i) % n));
        return rotated;
    }

    private String baseUrlForKey(String apiKey) {
        Map<String, String> overrides = appProperties.getGptImage().getKeyBaseUrls();
        String configured = overrides != null ? overrides.get(apiKey) : null;
        if (configured != null && !configured.isBlank()) {
            return trimTrailingSlash(configured);
        }
        return trimTrailingSlash(appProperties.getGptImage().getBaseUrl());
    }

    private String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) return "https://api.linapi.net";
        String out = url.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private String maskKey(String apiKey) {
        if (apiKey == null || apiKey.length() <= 6) return "***";
        return apiKey.substring(0, 4) + "***";
    }

    /**
     * 判断响应是否为"中转站额度耗尽"。上游实测返回：
     * {@code HTTP 403 + {"error":{"message":"预扣费额度失败, 用户剩余额度: ¥0.042036,
     * 需要预扣费额度: ¥0.100000 ...","code":"insufficient_user_quota"}}}
     *
     * <p>只认额度信号，不把其它 403（如 key 无效、被封）误判成额度问题——
     * 那两种换 key 是有意义的，额度耗尽换 key 没意义（共用余额）。
     */
    private boolean isQuotaExhausted(int status, String respBody) {
        if (status != 403 && status != 402) return false;
        if (respBody == null) return false;
        String b = respBody.toLowerCase();
        return b.contains("insufficient_user_quota")
                || b.contains("quota")            // 兼容 insufficient_quota / quota_exceeded 等变体
                || respBody.contains("额度");
    }

    /** 从上游错误 JSON 里取 error.message（取不到就返回截断的原文，别把整个 body 灌进异常）。 */
    private String extractUpstreamMessage(String respBody) {
        try {
            Map<?, ?> root = mapper.readValue(respBody, Map.class);
            Object err = root.get("error");
            if (err instanceof Map<?, ?> em && em.get("message") != null) {
                return String.valueOf(em.get("message"));
            }
        } catch (Exception ignored) { /* 非 JSON 就走下面的截断 */ }
        String s = respBody == null ? "" : respBody.trim();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    @Override
    public String getId() {
        return "gpt-image";
    }

    @Override
    public String getDisplayName() {
        return "GPT-Image 2";
    }

    @Override
    public boolean generate(String prompt, String refImagePath, String whiteBgPath, String outputPath) {
        // 构建图片列表传给 GPT-Image edits API
        // 注意：GPT-Image 的 generateMulti 只使用 refImagePaths 参数，whiteBgPath 会被忽略
        // 因此需要把参考图和白底图都放进 refImagePaths 列表
        List<String> refs = new ArrayList<>();

        // 优先添加参考图（提供场景、背景、构图参考）
        if (refImagePath != null && !refImagePath.isBlank()) {
            refs.add(refImagePath);
        }

        // 添加白底产品图（提供产品主体特征，确保产品一致性）
        // 即使与参考图相同也添加，因为这代表用户明确要求保留产品特征
        if (whiteBgPath != null && !whiteBgPath.isBlank()) {
            // 只有当白底图与参考图路径不同时才添加，避免重复
            if (refImagePath == null || refImagePath.isBlank() || !whiteBgPath.equals(refImagePath)) {
                refs.add(whiteBgPath);
            }
        }

        return generateMulti(
                prompt,
                refs.isEmpty() ? null : refs,
                null,  // GPT-Image 的 generateMulti 不使用此参数
                outputPath,
                null
        );
    }

    @Override
    public boolean generateMulti(String prompt, List<String> refImagePaths,
                                 String whiteBgPath, String outputPath, String aspect) {
        return generateMulti(prompt, refImagePaths, whiteBgPath, outputPath, aspect, "medium");
    }

    /** quality 可传 "low"/"medium"/"high"，开品模式传 "low" 加快响应。 */
    public boolean generateMulti(String prompt, List<String> refImagePaths,
                                 String whiteBgPath, String outputPath, String aspect, String quality) {
        return generateMulti(prompt, refImagePaths, whiteBgPath, outputPath, aspect, quality, false);
    }

    /** fitContain=true 时参考图用 contain(letterbox) 而非 cover，用于详情图生成避免主图参考被裁边。 */
    public boolean generateMulti(String prompt, List<String> refImagePaths,
                                 String whiteBgPath, String outputPath, String aspect, String quality, boolean fitContain) {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        if (keys == null || keys.isEmpty()) {
            log.error("GPT-Image API Key 未配置");
            return false;
        }

        List<File> imageFiles = resolveImageFiles(refImagePaths);
        String size = pickSize(aspect);
        // 读超时不是 key 的问题而是上游拥塞：同 baseUrl 的其余 key 换了也一样慢，
        // 换一个就再等一个 readTimeout(300s) → 3 key 串成 15 分钟(08.03 开品卡死真因)。
        // 故记下已超时的 baseUrl，后续同 baseUrl 的 key 直接跳过；不同 baseUrl 的 key 仍失败转移。
        java.util.Set<String> timedOutBaseUrls = new java.util.HashSet<>();
        java.net.SocketTimeoutException lastTimeout = null;
        for (String apiKey : orderedKeys()) {
            String baseUrl = baseUrlForKey(apiKey);
            if (timedOutBaseUrls.contains(baseUrl)) {
                log.warn("GPT-Image 跳过 key [{}]：baseUrl={} 刚读超时，同上游换 key 无意义", maskKey(apiKey), baseUrl);
                continue;
            }
            log.info("GPT-Image 尝试 key [{}], baseUrl={}, fitContain={}", maskKey(apiKey), baseUrl, fitContain);
            try {
                boolean ok = !imageFiles.isEmpty()
                        ? generateWithImages(prompt, imageFiles, outputPath, apiKey, baseUrl, size, quality, fitContain)
                        : generateTextOnly(prompt, outputPath, apiKey, baseUrl, size);
                if (ok) return true;
            } catch (UpstreamQuotaExhaustedException qe) {
                // 额度耗尽：所有 key 共用同一账户余额（08.04 实测三个 key 查出的剩余额度完全相同），
                // 换 key 一定同样失败，直接上抛，别再白试后面的 key。
                log.error("GPT-Image 额度耗尽，停止尝试其余 key（共用同一账户余额）");
                throw qe;
            } catch (java.io.UncheckedIOException ue) {
                if (!(ue.getCause() instanceof java.net.SocketTimeoutException)) throw ue;
                timedOutBaseUrls.add(baseUrl);
                lastTimeout = (java.net.SocketTimeoutException) ue.getCause();
                log.warn("GPT-Image key [{}] 读超时({}), 标记 baseUrl={} 拥塞", maskKey(apiKey), lastTimeout.getMessage(), baseUrl);
                continue;
            }
            log.warn("GPT-Image key [{}] 失败，尝试下一个", maskKey(apiKey));
        }

        // 全因上游读超时而结束时抛出，让上层重试逻辑能识别"上游拥塞"并跳过无意义重试（不再吞成 false）。
        if (lastTimeout != null) {
            log.error("GPT-Image 所有可用 key 均读超时（上游拥塞）");
            throw new java.io.UncheckedIOException(lastTimeout);
        }
        log.error("GPT-Image 所有 key 均失败");
        return false;
    }

    private String buildSizeHint(String size) {
        if (size == null || size.isBlank()) return "";
        return switch (size) {
            case "1024x1024" -> " Output image must be perfectly square (1:1 aspect ratio, 1024x1024).";
            case "1024x1536" -> " Output image must be portrait (9:16 aspect ratio, 1024x1536).";
            case "1536x1024" -> " Output image must be landscape (16:9 aspect ratio, 1536x1024).";
            case "1056x1408" -> " Output image must be portrait (3:4 aspect ratio, 1056x1408).";
            case "1408x1056" -> " Output image must be landscape (4:3 aspect ratio, 1408x1056).";
            default -> "";
        };
    }

    private List<File> resolveImageFiles(List<String> paths) {
        List<File> out = new ArrayList<>();
        if (paths == null) return out;
        for (String p : paths) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p);
            if (f.exists()) out.add(f);
        }
        return out;
    }

    private String pickSize(String aspect) {
        if (aspect == null || "auto".equals(aspect)) return "1024x1024";
        // gpt-image-2 约束：边为16倍数、长:短≤3:1、总像素65.5万~829万。下列尺寸均满足。
        return switch (aspect) {
            case "9:16", "portrait" -> "1024x1536";
            case "16:9", "landscape" -> "1536x1024";
            case "1:1" -> "1024x1024";
            case "3:4" -> "1056x1408";   // 正规3:4竖版(都%16)
            case "4:3" -> "1408x1056";   // 正规4:3横版
            default -> "1024x1024";
        };
    }

    private boolean generateWithImages(String prompt, List<File> imageFiles, String outputPath,
                                       String apiKey, String baseUrl, String size) {
        return generateWithImages(prompt, imageFiles, outputPath, apiKey, baseUrl, size, "medium", false);
    }

    private boolean generateWithImages(String prompt, List<File> imageFiles, String outputPath,
                                       String apiKey, String baseUrl, String size, String quality) {
        return generateWithImages(prompt, imageFiles, outputPath, apiKey, baseUrl, size, quality, false);
    }

    private boolean generateWithImages(String prompt, List<File> imageFiles, String outputPath,
                                       String apiKey, String baseUrl, String size, String quality, boolean fitContain) {
        List<File> tempFiles = new ArrayList<>();
        HttpURLConnection conn = null;   // 提到 try 外，好让最外层 finally 能无条件 disconnect
        try {
            List<File> preparedFiles = new ArrayList<>();
            for (File f : imageFiles) {
                File prepared = prepareInputImage(f, size, fitContain);
                if (prepared != f) tempFiles.add(prepared);
                preparedFiles.add(prepared);
            }

            String boundary = "----GptImageBoundary" + Long.toHexString(System.currentTimeMillis());
            URL url = URI.create(baseUrl + "/v1/images/edits").toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            // readTimeout 定在 420s。08.04 实测(8并发 × 3图输入 × 连续三轮 = 24 次调用，24/24 全成)：
            //   第1轮 98~157s、第2轮 88~205s、第3轮 97~199s
            // 关键观察：**长尾随持续负载往上漂**（157s → 205s），而不是稳定在某个值。
            // 所以超时阈值不能贴着单轮长尾定——240s 正好落在漂移区间里，会把"本来能成、只是这轮偏慢"
            // 的请求判死；再叠上重试，一张图最坏烧 240×3=12 分钟还是全失败（用户实测 3/10）。
            // 420s = 实测最慢 205s 的两倍，留足漂移空间；配合重试仍能在可接受时间内收敛。
            conn.setReadTimeout(420_000);

            try (OutputStream os = conn.getOutputStream()) {
                String sizeHint = buildSizeHint(size);
                String finalPrompt = (prompt != null ? prompt : "product photo on clean background") + sizeHint;
                writeField(os, boundary, "model", "gpt-image-2");
                writeField(os, boundary, "prompt", finalPrompt);
                writeField(os, boundary, "size", size);
                writeField(os, boundary, "quality", quality != null ? quality : "medium");
                writeField(os, boundary, "output_format", "jpeg");
                for (File f : preparedFiles) {
                    writeFile(os, boundary, "image[]", f);
                }
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            // 分段计时：把"写完请求体"和"等到响应"分开，好定位慢在哪一段。
            // 超时那条只会打 uploadDone 不打耗时 → 据此判断是卡在等响应还是压根没发出去。
            log.info("GPT-Image 请求已发出(上传{}KB, {}图), 开始等响应…",
                    preparedFiles.stream().mapToLong(File::length).sum() / 1024, preparedFiles.size());
            long reqStart = System.currentTimeMillis();
            int status = conn.getResponseCode();
            String respBody;
            try (InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                // 健壮性修复：错误响应可能无 body（getErrorStream 返回 null），不判空会 NPE 掩盖真实 status
                respBody = (is == null) ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            log.info("GPT-Image 上游耗时 {}s (status={}, {}图输入, 上传{}KB)",
                    (System.currentTimeMillis() - reqStart) / 1000, status, preparedFiles.size(),
                    preparedFiles.stream().mapToLong(File::length).sum() / 1024);

            if (isQuotaExhausted(status, respBody)) {
                // 额度耗尽：换 key 无意义(共用余额)、重试更无意义，直接上抛让整批停下并提示充值
                String msg = extractUpstreamMessage(respBody);
                log.error("GPT-Image 中转站额度耗尽({}): {}", status, msg);
                throw new UpstreamQuotaExhaustedException(msg);
            }
            if (status < 200 || status >= 300) {
                log.error("GPT-Image edits 失败 ({}): {}", status, respBody);
                return false;
            }

            boolean saved = saveFromResponse(respBody, outputPath);
            if (saved) ensureSize(outputPath, size);
            return saved;
        } catch (java.net.SocketTimeoutException te) {
            // 上游单请求抖动。交给调用方重试，不在这里吞成 false。
            log.error("GPT-Image edits 读超时: {}", te.getMessage());
            throw new java.io.UncheckedIOException(te);
        } catch (UpstreamQuotaExhaustedException qe) {
            throw qe;   // 额度耗尽必须穿透，不能被下面的 catch(Exception) 吞成"普通失败"
        } catch (Exception e) {
            log.error("GPT-Image edits 异常: {}", e.getMessage(), e);
            return false;
        } finally {
            // disconnect 移到最外层 finally：原来它在读 body 的 try-with-resources 的 finally 里，
            // 而 getResponseCode() 抛 SocketTimeoutException 时直接跳 catch，那个块从未进入 → 不执行。
            // 注：08.04 实测过"这是否导致连接泄漏"——本地复现显示 5 次超时请求仍只占 5 条连接，
            // **没有观察到泄漏**，所以这不是"头两张成功之后全挂"的原因。此处仅作正确性清理
            // （超时后显式释放连接资源是应有之义），不要把它当成那个现象的解释。
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
            for (File t : tempFiles) { try { t.delete(); } catch (Exception ignored) {} }
        }
    }

    private File prepareInputImage(File src, String size) {
        return prepareInputImage(src, size, false);
    }

    private File prepareInputImage(File src, String size, boolean fitContain) {
        try {
            int[] target = parseSize(size);
            if (target == null) return src;
            int tw = target[0], th = target[1];
            System.setProperty("java.awt.headless", "true");
            BufferedImage img = ImageIO.read(src);
            if (img == null) return src;
            int sw = img.getWidth(), sh = img.getHeight();
            double srcRatio = (double) sw / sh;
            double tgtRatio = (double) tw / th;
            if (Math.abs(srcRatio - tgtRatio) < 0.02) return src;
            // 一律按目标 size 出画布，**不再把画布缩到源图量级**。
            // 08.03 曾加过"源图比目标小就缩画布，避免无意义上采样"，08.04 实测证明那是错的：
            // 小图会让 gpt-image-2 更慢甚至超时（8并发对照：23~31KB 小图 6/8 成功、2 张撞 420s；
            // 155~217KB 大图 8/8 成功且快 1 倍）——输出固定 1024×1024，喂小图它得上采样重建，更费算力。
            // 所以这里宁可把小图放大到目标尺寸：多花的字节不影响耗时（实测上传体积与耗时无关），
            // 却能让上游少做一次重建。
            BufferedImage canvas = new BufferedImage(tw, th, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = canvas.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            double scale;
            if (fitContain) {
                // contain: letterbox，保全内容，用浅灰填充空白——详情图参考图用此模式，避免裁边导致AI漏画内容
                g.setColor(new java.awt.Color(0xF5, 0xF5, 0xF5));
                g.fillRect(0, 0, tw, th);
                scale = Math.min((double) tw / sw, (double) th / sh);
            } else {
                // cover: 铺满+居中裁剪——主图/SKU参考图用此模式，避免白边被模型复刻进成品(07.06反馈)
                scale = Math.max((double) tw / sw, (double) th / sh);
            }
            int dw = (int) Math.round(sw * scale);
            int dh = (int) Math.round(sh * scale);
            int dx = (tw - dw) / 2;
            int dy = (th - dh) / 2;
            g.drawImage(img, dx, dy, dw, dh, null);
            g.dispose();
            File tmp = File.createTempFile("gptimg_input_", ".jpg", src.getParentFile());
            ImageIO.write(canvas, "jpeg", tmp);
            log.info("prepareInputImage: {}x{} -> {} {}x{} ({})", sw, sh, fitContain ? "contain" : "cover", tw, th, tmp.getName());
            return tmp;
        } catch (Exception e) {
            log.warn("prepareInputImage failed, using original: {}", e.getMessage());
            return src;
        }
    }

    public boolean generateWithMask(String prompt, File imageFile, File maskFile, String outputPath) {
        return generateWithMask(prompt, imageFile, maskFile, outputPath, "auto");
    }

    public boolean generateWithMask(String prompt, File imageFile, File maskFile, String outputPath, String aspect) {
        List<String> keys = appProperties.getGptImage().getApiKeys();
        if (keys == null || keys.isEmpty()) {
            log.error("GPT-Image API Key 未配置");
            return false;
        }

        String size = pickSize(aspect);
        log.info("GPT-Image inpaint 使用 size={} (aspect={})", size, aspect);
        for (String apiKey : orderedKeys()) {
            String baseUrl = baseUrlForKey(apiKey);
            log.info("GPT-Image inpaint 尝试 key [{}], baseUrl={}", maskKey(apiKey), baseUrl);
            boolean ok = doGenerateWithMask(prompt, imageFile, maskFile, outputPath, apiKey, baseUrl, size);
            if (ok) return true;
            log.warn("GPT-Image inpaint key [{}] 失败，尝试下一个", maskKey(apiKey));
        }

        log.error("GPT-Image inpaint 所有 key 均失败");
        return false;
    }

    private boolean doGenerateWithMask(String prompt, File imageFile, File maskFile,
                                       String outputPath, String apiKey, String baseUrl, String size) {
        HttpURLConnection conn = null;   // 同 generateWithImages：提到 try 外，保证超时也能 disconnect
        try {
            String boundary = "----GptImageBoundary" + Long.toHexString(System.currentTimeMillis());
            URL url = URI.create(baseUrl + "/v1/images/edits").toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(240_000);

            try (OutputStream os = conn.getOutputStream()) {
                writeField(os, boundary, "model", "gpt-image-2");
                writeField(os, boundary, "prompt", prompt != null ? prompt : "");
                writeField(os, boundary, "size", size);
                writeField(os, boundary, "quality", "medium");
                writeField(os, boundary, "output_format", "jpeg");
                writeFile(os, boundary, "image", imageFile);
                writeFile(os, boundary, "mask", maskFile);
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            String respBody;
            try (InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                // 健壮性修复：错误响应可能无 body（getErrorStream 返回 null），不判空会 NPE 掩盖真实 status
                respBody = (is == null) ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (isQuotaExhausted(status, respBody)) {
                String msg = extractUpstreamMessage(respBody);
                log.error("GPT-Image inpaint 中转站额度耗尽({}): {}", status, msg);
                throw new UpstreamQuotaExhaustedException(msg);
            }
            if (status < 200 || status >= 300) {
                log.error("GPT-Image inpaint 失败 ({}): {}", status, respBody);
                return false;
            }

            boolean saved = saveFromResponse(respBody, outputPath);
            if (saved) ensureSize(outputPath, size);
            return saved;
        } catch (UpstreamQuotaExhaustedException qe) {
            throw qe;
        } catch (Exception e) {
            log.error("GPT-Image inpaint 异常: {}", e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }
    }

    private boolean generateTextOnly(String prompt, String outputPath, String apiKey, String baseUrl, String size) {
        HttpURLConnection conn = null;   // 同上：提到 try 外，保证超时也能 disconnect
        try {
            Map<String, Object> payload = Map.of(
                    "model", "gpt-image-2",
                    "prompt", prompt != null ? prompt : "product photo",
                    "size", size,
                    "quality", "medium",
                    "output_format", "jpeg"
            );

            String jsonBody = mapper.writeValueAsString(payload);
            URL url = URI.create(baseUrl + "/v1/images/generations").toURL();
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(240_000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            String respBody;
            try (InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream()) {
                // 健壮性修复：错误响应可能无 body（getErrorStream 返回 null），不判空会 NPE 掩盖真实 status
                respBody = (is == null) ? "" : new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            if (isQuotaExhausted(status, respBody)) {
                String msg = extractUpstreamMessage(respBody);
                log.error("GPT-Image generations 中转站额度耗尽({}): {}", status, msg);
                throw new UpstreamQuotaExhaustedException(msg);
            }
            if (status < 200 || status >= 300) {
                log.error("GPT-Image generations 失败 ({}): {}", status, respBody);
                return false;
            }

            boolean saved = saveFromResponse(respBody, outputPath);
            if (saved) ensureSize(outputPath, size);
            return saved;
        } catch (UpstreamQuotaExhaustedException qe) {
            throw qe;
        } catch (Exception e) {
            log.error("GPT-Image generations 异常: {}", e.getMessage(), e);
            return false;
        } finally {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ignored) {} }
        }
    }

    @SuppressWarnings("unchecked")
    private boolean saveFromResponse(String respBody, String outputPath) throws Exception {
        Map<String, Object> resp = mapper.readValue(respBody, Map.class);
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.get("data");
        if (data == null || data.isEmpty()) {
            log.error("GPT-Image 响应中无 data 字段");
            return false;
        }

        Map<String, Object> item = data.get(0);
        File parent = new File(outputPath).getParentFile();
        if (parent != null) parent.mkdirs();

        if (item.containsKey("b64_json")) {
            byte[] imgBytes = Base64.getDecoder().decode((String) item.get("b64_json"));
            try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                fos.write(imgBytes);
            }
            log.info("GPT-Image 生成成功 (base64) -> {}", outputPath);
            return true;
        }

        if (item.containsKey("url")) {
            return downloadUrl((String) item.get("url"), outputPath);
        }

        log.error("GPT-Image 响应中既无 b64_json 也无 url");
        return false;
    }

    private void ensureSize(String outputPath, String size) {
        try {
            int[] target = parseSize(size);
            if (target == null) return;
            int tw = target[0], th = target[1];
            File file = new File(outputPath);
            if (!file.exists()) return;
            System.setProperty("java.awt.headless", "true");
            BufferedImage src = ImageIO.read(file);
            if (src == null) {
                log.warn("ensureSize: ImageIO.read returned null for {}", outputPath);
                return;
            }
            int sw = src.getWidth(), sh = src.getHeight();
            log.info("ensureSize: actual={}x{}, target={}x{}, path={}", sw, sh, tw, th, outputPath);
            if (Math.abs(sw - tw) <= tw * 0.05 && Math.abs(sh - th) <= th * 0.05) {
                log.info("ensureSize: already within tolerance, skipping");
                return;
            }
            BufferedImage dst = new BufferedImage(tw, th, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D g = dst.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            // 比例不符时用 cover(缩放到铺满+居中裁剪)，而非纯白 letterbox——
            // 白填充会在成品两侧留白边(07.06反馈)。cover 铺满目标画布、多余部分裁掉，无白边。
            double scale = Math.max((double) tw / sw, (double) th / sh);
            int dw = (int) Math.round(sw * scale);
            int dh = (int) Math.round(sh * scale);
            int dx = (tw - dw) / 2;
            int dy = (th - dh) / 2;
            g.drawImage(src, dx, dy, dw, dh, null);
            g.dispose();
            boolean written = ImageIO.write(dst, "jpeg", file);
            if (written) {
                log.info("ensureSize: resized {}x{} -> {}x{} OK", sw, sh, tw, th);
            } else {
                log.warn("ensureSize: ImageIO.write returned false for {}", outputPath);
            }
        } catch (Exception e) {
            log.warn("ensureSize failed for {}: {}", outputPath, e.getMessage(), e);
        }
    }

    private int[] parseSize(String size) {
        if (size == null || size.isBlank()) return null;
        String[] parts = size.split("x");
        if (parts.length != 2) return null;
        try {
            return new int[]{ Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void writeField(OutputStream os, String boundary, String name, String value) throws Exception {
        String part = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n";
        os.write(part.getBytes(StandardCharsets.UTF_8));
    }

    private void writeFile(OutputStream os, String boundary, String fieldName, File file) throws Exception {
        String filename = file.getName();
        String mime = filename.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        os.write(header.getBytes(StandardCharsets.UTF_8));
        os.write(Files.readAllBytes(file.toPath()));
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private boolean downloadUrl(String imgUrl, String outputPath) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(imgUrl).toURL().openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            File parent = new File(outputPath).getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outputPath)) {
                in.transferTo(fos);
            } finally {
                conn.disconnect();
            }
            log.info("GPT-Image 生成成功 (url) -> {}", outputPath);
            return true;
        } catch (Exception e) {
            log.error("GPT-Image 下载图片失败: {}", e.getMessage(), e);
            return false;
        }
    }
}

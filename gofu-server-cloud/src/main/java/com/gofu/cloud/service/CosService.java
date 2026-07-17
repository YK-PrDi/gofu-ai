package com.gofu.cloud.service;

import com.gofu.cloud.config.AppProperties;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.http.HttpMethodName;
import com.qcloud.cos.model.GeneratePresignedUrlRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 腾讯云 COS 上传服务。
 * COS 未配置时（secretId/secretKey 为空）isEnabled() 返回 false，调用方降级到本地存储。
 */
@Service
public class CosService {

    private static final Logger log = LoggerFactory.getLogger(CosService.class);

    private final AppProperties appProperties;
    private volatile COSClient client;

    public CosService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public boolean isEnabled() {
        return appProperties.getCos().isEnabled();
    }

    /**
     * 上传文件到 COS，返回**永久 COS key**（ADR-008）。
     * key 格式：generated/{yyyyMMdd}/{filename}
     *
     * <p>⚠️ ADR-008：返回的是 key 而非签名 URL。ProductContext 只存 key（永不过期），
     * 展示时调 {@link #signKey(String)} 按需换取短期签名 URL。这样上下文数据稳定不失效。
     */
    public String upload(File file, String filename) {
        String bucket = appProperties.getCos().getBucket();
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String key = "generated/" + date + "/" + filename;

        try {
            COSClient cos = getClient();
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(file.length());
            String lower = filename.toLowerCase();
            if (lower.endsWith(".png")) meta.setContentType("image/png");
            else if (lower.endsWith(".mp4")) meta.setContentType("video/mp4");
            else meta.setContentType("image/jpeg");

            cos.putObject(new PutObjectRequest(bucket, key, file).withMetadata(meta));

            log.info("COS upload: {} -> key={} (permanent key stored in context)", file.getName(), key);
            return key;
        } catch (Exception e) {
            log.error("COS upload failed: {}", e.getMessage(), e);
            throw new RuntimeException("COS 上传失败: " + e.getMessage(), e);
        }
    }

    /**
     * 按需把永久 COS key 换成短期签名 URL（ADR-008，默认 7 天有效）。
     * 供展示层（前端/本地）调用，不写入 ProductContext。
     */
    public String signKey(String key) {
        String bucket = appProperties.getCos().getBucket();
        try {
            COSClient cos = getClient();
            Date expiration = new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000);
            GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(bucket, key, HttpMethodName.GET);
            req.setExpiration(expiration);
            URL signedUrl = cos.generatePresignedUrl(req);
            return signedUrl.toString();
        } catch (Exception e) {
            log.error("COS sign failed for key={}: {}", key, e.getMessage(), e);
            throw new RuntimeException("COS 签名失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传并返回【永久公网 URL】（07.08：回传快麦用——快麦会长期存这个 picPath，不能用7天签名URL）。
     * 对象设 public-read ACL，返回标准 COS 对象 URL（永久有效，只要对象存在）。
     */
    public String uploadPublic(File file, String filename) {
        String bucket = appProperties.getCos().getPublicBucket();   // 公开读的独立桶(回传快麦用)
        String region = appProperties.getCos().getRegion();
        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String key = "kuaimai-white/" + date + "/" + filename;
        try {
            COSClient cos = getClient();
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(file.length());
            meta.setContentType(filename.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg");
            // 桶已设「公有读私有写」，对象继承桶权限即可公网读；不再设对象级 ACL
            // （某些桶开启 bucket-owner-enforced 时对象 ACL 会被拒，反而导致上传/读取异常）。
            PutObjectRequest req = new PutObjectRequest(bucket, key, file).withMetadata(meta);
            cos.putObject(req);
            String url = "https://" + bucket + ".cos." + region + ".myqcloud.com/" + key;
            log.info("COS uploadPublic: {} -> {}", file.getName(), url);
            return url;
        } catch (Exception e) {
            log.error("COS uploadPublic failed: {}", e.getMessage(), e);
            throw new RuntimeException("COS 公网上传失败: " + e.getMessage(), e);
        }
    }

    private COSClient getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    AppProperties.Cos cfg = appProperties.getCos();
                    client = new COSClient(
                        new BasicCOSCredentials(cfg.getSecretId(), cfg.getSecretKey()),
                        new ClientConfig(new Region(cfg.getRegion()))
                    );
                }
            }
        }
        return client;
    }

    /**
     * #1 预览图代理：用凭证在**服务端** getObject 取图字节。
     * 服务端拉取不带浏览器 Referer、也用 SDK 凭证访问，天然绕过「防盗链」和「对象非公有读(ACL)」——
     * 这正是浏览器直连 COS 公网/预签名 URL 会 403、图变黑的根因(桶开了防盗链)。
     * 入参可为 COS key(generated/…/x.jpg) 或完整 COS URL，统一解析成 key 再取。
     */
    public byte[] fetch(String keyOrUrl) throws java.io.IOException {
        String key = toKey(keyOrUrl);
        com.qcloud.cos.model.COSObject obj = getClient().getObject(appProperties.getCos().getBucket(), key);
        try (java.io.InputStream in = obj.getObjectContent()) {
            return in.readAllBytes();
        }
    }

    /** 把完整 COS URL 抽成对象 key(去 scheme/host/query 并 URL 解码)；本就是 key 则原样返回。 */
    private String toKey(String s) {
        if (s == null) return "";
        if (s.startsWith("http://") || s.startsWith("https://")) {
            int host = s.indexOf("://") + 3;
            int slash = s.indexOf('/', host);
            String path = slash >= 0 ? s.substring(slash + 1) : s;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            try { return java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8); }
            catch (Exception e) { return path; }
        }
        return s;
    }
}

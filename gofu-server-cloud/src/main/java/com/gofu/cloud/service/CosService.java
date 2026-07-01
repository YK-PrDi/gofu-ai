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
}

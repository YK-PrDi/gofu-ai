package com.gofu.cloud.service;

import com.gofu.cloud.entity.DisneyAssetEntity;
import com.gofu.cloud.entity.DisneyAssetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 迪士尼素材库服务：上传图片到 COS + 写元数据，按标签随机抽样。
 */
@Service
public class DisneyAssetService {

    private static final Logger log = LoggerFactory.getLogger(DisneyAssetService.class);
    private static final String TENANT = "default";
    private static final DateTimeFormatter DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final DisneyAssetRepository repo;
    private final CosService cosService;

    public DisneyAssetService(DisneyAssetRepository repo, CosService cosService) {
        this.repo = repo;
        this.cosService = cosService;
    }

    /** 导入一张素材图：上传 COS + 写入元数据表。返回落库的实体。 */
    public DisneyAssetEntity importAsset(File imageFile, String originalFilename, String tag) throws Exception {
        if (imageFile == null || !imageFile.isFile()) throw new IllegalArgumentException("图片文件无效");
        if (tag == null || tag.isBlank()) throw new IllegalArgumentException("tag 不能为空");

        String ext = originalFilename != null && originalFilename.toLowerCase().endsWith(".png") ? ".png" : ".jpg";
        String cosFilename = "disney/" + tag + "/" + UUID.randomUUID() + ext;

        String cosKey = null;
        String publicUrl = null;
        if (cosService.isEnabled()) {
            cosKey = cosService.upload(imageFile, cosFilename);
            log.info("[迪士尼素材] 上传 COS: tag={} key={}", tag, cosKey);
        } else {
            // COS 未启用：cosKey 存本地绝对路径，前端通过 /api/gen/img?ref= 代理访问
            cosKey = imageFile.getAbsolutePath();
            log.warn("[迪士尼素材] COS 未启用，cosKey 存本地路径: {}", cosKey);
        }

        DisneyAssetEntity entity = new DisneyAssetEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setTenantId(TENANT);
        entity.setTag(tag.trim());
        entity.setFilename(originalFilename != null ? originalFilename : imageFile.getName());
        entity.setCosKey(cosKey);
        entity.setPublicUrl(publicUrl);
        entity.setCreatedAt(LocalDateTime.now().format(DT));
        repo.save(entity);
        return entity;
    }

    /** 返回所有标签列表（去重排序） */
    public List<String> listTags() {
        return repo.findDistinctTagsByTenantId(TENANT);
    }

    /**
     * 按标签随机抽 N 张，返回 [{cosKey, signedUrl, tag, id}] 列表。
     * signedUrl：COS 启用时走 7 天签名 URL；否则返回 /api/gen/img?ref= 代理地址。
     */
    public List<Map<String, String>> sample(String tag, int n) {
        List<DisneyAssetEntity> entities = repo.sampleByTag(tag, TENANT, Math.max(1, n));
        return entities.stream().map(e -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("tag", e.getTag());
            m.put("cosKey", e.getCosKey());
            String signed;
            if (cosService.isEnabled() && !e.getCosKey().startsWith("/") && !e.getCosKey().startsWith("C:") && !e.getCosKey().startsWith("D:")) {
                try { signed = cosService.signKey(e.getCosKey()); }
                catch (Exception ex) { signed = "/api/gen/img?ref=" + java.net.URLEncoder.encode(e.getCosKey(), java.nio.charset.StandardCharsets.UTF_8); }
            } else {
                signed = "/api/gen/img?ref=" + java.net.URLEncoder.encode(e.getCosKey(), java.nio.charset.StandardCharsets.UTF_8);
            }
            m.put("signedUrl", signed);
            return m;
        }).toList();
    }

    /** 清空所有素材记录（重新导入前用）。 */
    public void deleteAll() {
        repo.deleteAll();
        log.info("[迪士尼素材] 已清空所有记录");
    }

    /** 统计某标签的素材总数 */
    public long count(String tag) {
        return repo.countByTagAndTenantId(tag, TENANT);
    }
}

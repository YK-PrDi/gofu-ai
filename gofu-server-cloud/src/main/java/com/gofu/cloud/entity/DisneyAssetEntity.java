package com.gofu.cloud.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 迪士尼素材库元数据表。
 * 图片实体存 COS，此表只存 key/tag 等元数据，按标签随机抽样。
 * tenant_id 预埋，MVP 写死 default（ADR-004）。
 */
@Data
@Entity
@Table(name = "disney_asset", indexes = {
        @Index(name = "idx_disney_tag", columnList = "tag"),
        @Index(name = "idx_disney_tenant", columnList = "tenant_id")
})
public class DisneyAssetEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId = "default";

    /** 分类标签，来源于素材文件夹名（如"米奇"、"冰雪奇缘"） */
    @Column(nullable = false, length = 128)
    private String tag;

    /** 原始文件名 */
    @Column(nullable = false, length = 256)
    private String filename;

    /** COS 永久 key（generated/yyyyMMdd/uuid.jpg） */
    @Column(name = "cos_key", nullable = false, length = 512)
    private String cosKey;

    /** 公网 URL（可选，快速展示用；COS 未启用时为空） */
    @Column(name = "public_url", length = 1024)
    private String publicUrl;

    @Column(name = "created_at", nullable = false, length = 32)
    private String createdAt;
}

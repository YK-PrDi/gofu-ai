package com.gofu.cloud.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品全局上下文持久化实体（权威源）。
 *
 * <p>混合存储策略（见 ADR-006）：高频查询/筛选字段走真实列并建索引；
 * 完整 {@link com.gofu.shared.context.ProductContext} 序列化进 {@code contextJson} TEXT 列。
 *
 * <p>⚠️ 这样设计正是为规避 SQLite ddl-auto:update 无法 DROP/RENAME 列的陷阱（雷区 3）：
 * 改 ProductContext 的内部子结构字段时，只动 JSON，无需迁移表结构。
 * 只有当需要新增"可查询维度"时才加真实列。
 *
 * <p>tenant_id 为预埋列（ADR-004），MVP 写死 default。
 */
@Entity
@Table(name = "product_context", indexes = {
        @Index(name = "idx_ctx_tenant", columnList = "tenant_id"),
        @Index(name = "idx_ctx_product", columnList = "product_id"),
        @Index(name = "idx_ctx_status", columnList = "status"),
        @Index(name = "idx_ctx_updated", columnList = "updated_at")
})
@Data
public class ProductContextEntity {

    @Id
    @Column(length = 64)
    private String id;

    /** 预埋租户列（ADR-004）。 */
    @Column(name = "tenant_id", length = 64, nullable = false)
    private String tenantId = "default";

    @Column(name = "product_id", length = 128)
    private String productId;

    @Column(length = 256)
    private String category;

    /** 状态机字符串（ContextStatus.name()）。 */
    @Column(length = 32)
    private String status;

    /** 完整 ProductContext 的 JSON 序列化（雷区 3：存读必须走 ObjectMapper）。 */
    @Lob
    @Column(name = "context_json")
    private String contextJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}

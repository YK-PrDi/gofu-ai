package com.gofu.cloud.service.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.cloud.entity.ProductContextEntity;
import com.gofu.cloud.entity.ProductContextRepository;
import com.gofu.shared.context.ProductContext;
import com.gofu.shared.enums.ContextStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 商品全局上下文权威服务（ADR-003）。负责 ProductContext 领域对象与实体的 JSON 往返、读写。
 *
 * <p>混合存储（ADR-006）：把领域对象整体序列化进 context_json，同时把可查询维度同步到真实列。
 */
@Service
public class ContextService {

    private final ProductContextRepository repository;
    private final ObjectMapper objectMapper;

    public ContextService(ProductContextRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 创建或更新上下文，返回持久化后的领域对象。 */
    public ProductContext save(ProductContext ctx) {
        LocalDateTime now = LocalDateTime.now();
        if (ctx.getId() == null || ctx.getId().isBlank()) {
            ctx.setId(UUID.randomUUID().toString());
            ctx.setCreatedAt(now);
        }
        ctx.setUpdatedAt(now);
        if (ctx.getTenantId() == null || ctx.getTenantId().isBlank()) {
            ctx.setTenantId("default");
        }
        // 兜底：客户端可能 POST {"visual":null}/{"structure":null}，Jackson 会覆盖字段初始化器为 null，
        // 导致后续 getVisual()/getStructure() 链式 NPE。补非 null 实例。
        if (ctx.getVisual() == null) ctx.setVisual(new com.gofu.shared.context.VisualContent());
        if (ctx.getStructure() == null) ctx.setStructure(new com.gofu.shared.context.StructureContent());

        ProductContextEntity entity = new ProductContextEntity();
        entity.setId(ctx.getId());
        entity.setTenantId(ctx.getTenantId());
        entity.setProductId(ctx.getProductId());
        entity.setCategory(ctx.getCategory());
        entity.setStatus(ctx.getStatus() == null ? ContextStatus.DRAFT.name() : ctx.getStatus().name());
        entity.setCreatedAt(ctx.getCreatedAt());
        entity.setUpdatedAt(ctx.getUpdatedAt());
        entity.setContextJson(serialize(ctx));

        repository.save(entity);
        return ctx;
    }

    /** 按 ID 读取，反序列化 context_json 为领域对象。 */
    public ProductContext findById(String id) {
        return repository.findById(id).map(this::toDomain).orElse(null);
    }

    public List<ProductContext> findByTenant(String tenantId) {
        return repository.findByTenantId(tenantId).stream().map(this::toDomain).toList();
    }

    private ProductContext toDomain(ProductContextEntity entity) {
        return deserialize(entity.getContextJson());
    }

    private String serialize(ProductContext ctx) {
        try {
            return objectMapper.writeValueAsString(ctx);
        } catch (Exception e) {
            throw new IllegalStateException("ProductContext 序列化失败: " + e.getMessage(), e);
        }
    }

    private ProductContext deserialize(String json) {
        try {
            return objectMapper.readValue(json, ProductContext.class);
        } catch (Exception e) {
            throw new IllegalStateException("ProductContext 反序列化失败: " + e.getMessage(), e);
        }
    }
}

package com.gofu.cloud.entity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * ProductContext 数据访问层。
 */
public interface ProductContextRepository extends JpaRepository<ProductContextEntity, String> {

    /** 按租户查（预埋多租户隔离的查询入口，ADR-004）。 */
    List<ProductContextEntity> findByTenantId(String tenantId);
}

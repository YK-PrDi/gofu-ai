package com.gofu.cloud.entity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 迪士尼素材库数据访问层。
 * SQLite 无 RAND()，用 RANDOM() 实现随机抽样。
 */
public interface DisneyAssetRepository extends JpaRepository<DisneyAssetEntity, String> {

    /** 返回所有不重复的标签列表 */
    @Query("SELECT DISTINCT a.tag FROM DisneyAssetEntity a WHERE a.tenantId = :tenantId ORDER BY a.tag")
    List<String> findDistinctTagsByTenantId(@Param("tenantId") String tenantId);

    /** 按标签查全部 */
    List<DisneyAssetEntity> findByTagAndTenantId(String tag, String tenantId);

    /** 按标签随机抽 N 张（SQLite RANDOM()） */
    @Query(value = "SELECT * FROM disney_asset WHERE tag = :tag AND tenant_id = :tenantId ORDER BY RANDOM() LIMIT :n", nativeQuery = true)
    List<DisneyAssetEntity> sampleByTag(@Param("tag") String tag, @Param("tenantId") String tenantId, @Param("n") int n);

    /** 统计某标签的素材数量 */
    long countByTagAndTenantId(String tag, String tenantId);
}

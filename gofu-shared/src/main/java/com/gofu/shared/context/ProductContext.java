package com.gofu.shared.context;

import com.gofu.shared.enums.ContextStatus;
import com.gofu.shared.enums.FlowStage;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 商品全局上下文（系统脊柱）。打通"生图视觉流"与"上新结构流"数据孤岛的核心聚合根。
 *
 * <p>权威源在云端数据库（{@code gofu-server-cloud}），本地按需拉取+乐观回写（ADR-003）。
 * 完整数据流：流程1 写入 visual.sellingPoints → 流程2 读卖点反哺 structure.plans →
 * 预览页渲染整个 context → 人工微调写回并追加 lockedFields → 上新从 context 取最终数据。
 *
 * <p>⚠️ 改本类字段属于大改动（影响云端存储、本地缓存、预览页渲染三处），
 * 须先按 CLAUDE.md 改动分级走 brainstorm + plan + 独立 review。
 */
@Data
@NoArgsConstructor
public class ProductContext {

    /** 上下文唯一 ID（云端生成）。 */
    private String id;

    /**
     * 租户 ID。⚠️ 预埋字段（ADR-004）：MVP 写死 "default"，不实现隔离逻辑。
     * 所有持久化数据强绑定此字段，未来多租户隔离的基础。
     */
    private String tenantId = "default";

    /** 业务商品 ID。 */
    private String productId;

    /** 品类（完整路径，如"家装主材>卫浴>花洒喷头"）。 */
    private String category;

    /** 主件名称/编码。 */
    private String mainItem;

    /** 视觉流产物（流程 1）。 */
    private VisualContent visual = new VisualContent();

    /** 结构流产物（流程 2）。 */
    private StructureContent structure = new StructureContent();

    /**
     * 劳动成果锁定：人工微调过的字段名清单（如 ["title","plans[0].items[2].skuDisplayName"]）。
     * 重新生成时跳过这些字段，防止覆盖人工心血。
     */
    private List<String> lockedFields = new ArrayList<>();

    /** 双向状态机当前状态。 */
    private ContextStatus status = ContextStatus.DRAFT;

    /**
     * 双轨交错编排进度（M8）。null 表示未走交错流程（旧数据/直接建）。
     * 与 status 并存：status 是整体生命周期，stage 是交错流程内部细粒度进度。
     */
    private FlowStage stage;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

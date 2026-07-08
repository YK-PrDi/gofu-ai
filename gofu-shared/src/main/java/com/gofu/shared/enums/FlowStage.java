package com.gofu.shared.enums;

/**
 * 双轨交错编排的流程进度（M8）。标记"羽刃出图 ↔ 乐羽出结构"交错走到哪一步，
 * 预览页据此引导下一步操作。
 *
 * <p>与 {@link ContextStatus} 并存：status 是整体生命周期（DRAFT/GENERATING/READY/LISTED），
 * stage 是交错流程内部进度（更细）。
 *
 * <p>流转：WHITE_UPLOADED →（羽刃出1主图 + 乐羽出布局）→ LAYOUT_DONE →
 * （羽刃出剩余主图/详情 + 乐羽出 SKU 图）→ SKU_DONE →（进预览页确认/上新）。
 */
public enum FlowStage {
    /** 已上传主件白底图 + 选定主件，尚未生成 */
    WHITE_UPLOADED,
    /** 首张主图 + SKU 布局已出（交错第一步完成） */
    LAYOUT_DONE,
    /** 剩余主图/详情 + SKU 图已出（交错第二步完成，可进预览） */
    SKU_DONE
}

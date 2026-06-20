package com.gofu.shared.enums;

/**
 * 商品全局上下文的双向状态机。
 *
 * <p>流转：DRAFT → GENERATING →（双轨产物齐全）→ READY →（人工确认上新）→ LISTED。
 * 任意阶段失败回到可重试态由调用方决定，本枚举只定义稳定状态。
 */
public enum ContextStatus {
    /** 草稿：刚录入品类/主件，尚未触发双轨 */
    DRAFT,
    /** 生图中：视觉流/结构流后台执行中 */
    GENERATING,
    /** 待确认：双轨产物齐全，等待人工在预览页微调确认 */
    READY,
    /** 已上新：本地一键上新完成 */
    LISTED
}

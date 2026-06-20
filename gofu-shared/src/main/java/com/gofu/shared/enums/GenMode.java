package com.gofu.shared.enums;

/**
 * 生图模式。对应 ele 原 GenerationHistory.mode 字符串，收口为枚举。
 *
 * <p>云端按此分流不同的生图编排逻辑。MVP 主要用 ECOMMERCE（电商双轨）和 INPAINT（局部重绘）。
 * 其余模式从旧系统保留以兼容历史数据迁移。
 */
public enum GenMode {
    /** 标准模式：从数据源批量生主图/SKU/详情 */
    STANDARD,
    /** 自定义模式：用户上传白底图+提示词 */
    CUSTOM,
    /** 电商模式：GOFU-AI 双轨主链路 */
    ECOMMERCE,
    /** 开品模式：素材库批量开品 */
    KAIPIN,
    /** 局部重绘：预览页对单张图重新生成 */
    INPAINT,
    /** 视频生成 */
    VIDEO
}

package com.gofu.shared.enums;

/**
 * 生图模板类型，决定生图分支（见 ARCHITECTURE.md 雷区 6）。
 *
 * <p>STICKER（贴图）：云端 AI 只生右侧主件+背景，左侧配件/滤芯由本地 Canvas 合成贴图。
 * <p>AI（纯AI模板）：云端 Gemini 图生图，基准图打底替换花洒/滤芯/背景，无本地合成。
 *
 * <p>注意：本枚举只标识"模板属于哪种分支"。合成动作在本地 Canvas，生图调用在云端，
 * 两者职责分离不可混（见 ADR-002）。
 */
public enum TemplateType {
    /** 贴图模式：AI 生主件，本地 Canvas 合成左侧配件 */
    STICKER,
    /** 纯 AI 模板：基准图复用图生图，整图由云端生成 */
    AI
}

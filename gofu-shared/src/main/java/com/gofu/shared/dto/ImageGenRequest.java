package com.gofu.shared.dto;

import com.gofu.shared.context.AccPart;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 生图请求 DTO。本地 cloudgw → 云端生图服务的传输契约（ADR-002）。
 *
 * <p>字段对齐 LY 已验证的 SKU 生图请求体（app.js:561/770）：
 * {@code {name, compDesc, itemCode, accParts, whiteImgPath}}，
 * 外加 templateId（分流贴图/纯AI，雷区 6）与 agentId（选生图 Agent，雷区 1）。
 */
@Data
@NoArgsConstructor
public class ImageGenRequest {

    /** 所属上下文 ID（云端据此把产物写回 ProductContext）。 */
    private String contextId;

    /** 租户 ID，预埋（ADR-004），MVP 默认 default。 */
    private String tenantId = "default";

    /** SKU 显示名/款式名。 */
    private String name;

    /** 构图描述（含款式名，云端 parseFilterCount 从中提取滤芯数量）。 */
    private String compDesc;

    /** 组合规格编码 `主件码+配件码*N`。云端拆分匹配配件白底图（雷区 5）。 */
    private String itemCode;

    /** 配件清单。⚠️ 必须结构化数组（雷区 4）。 */
    private List<AccPart> accParts = new ArrayList<>();

    /** 白底图路径/URL，生图参考。 */
    private String whiteImgPath;

    /** 模板 ID。决定生图分支：null/sticker 走贴图，ai 类型走纯AI图生图（雷区 6）。 */
    private String templateId;

    /** 生图 Agent ID（gemini/gpt-image/wan2.7/...）。缺省由云端 resolveAgent 回退。 */
    private String agentId;
}

package com.gofu.shared.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 生图响应 DTO。云端生图服务 → 本地 cloudgw。
 *
 * <p>imageUrl 可能是 COS URL（http 开头）或服务端临时路径（雷区 10），本地按前缀分流处理。
 * 纯 AI 模板返回整图；贴图模式返回的是 AI 生的右侧主件图，本地再 Canvas 合成左侧（ADR-002）。
 */
@Data
@NoArgsConstructor
public class ImageGenResponse {

    /** 是否成功。 */
    private boolean success;

    /** 生成图地址（COS URL 或服务端路径）。 */
    private String imageUrl;

    /** 本次是否走了贴图模式（true 时本地需 Canvas 合成左侧配件）。 */
    private boolean stickerMode;

    /** 失败时的错误信息。 */
    private String error;
}

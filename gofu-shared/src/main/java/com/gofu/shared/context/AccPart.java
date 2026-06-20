package com.gofu.shared.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 配件项：一个配件编码 + 数量。对应 LY 前端 lstSkuItems[].accParts 的 [{code,qty}] 契约。
 *
 * <p>⚠️ 雷区（见 ARCHITECTURE.md 雷区 4/5）：accParts 必须是结构化数组，不能退化成字符串，
 * 否则云端反序列化失败、生图配件错配。code 对应 itemCode 中 `+配件码*N` 拆出来的配件码。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccPart {
    /** 配件编码（如 "052底座"、"088滤芯"） */
    private String code;
    /** 数量（itemCode 里 `*N` 的 N，缺省 1） */
    private int qty = 1;
}

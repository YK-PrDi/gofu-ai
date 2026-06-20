package com.gofu.shared.dto;

import com.gofu.shared.context.ProductContext;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文同步信封。本地 sync 服务回写云端时使用（ADR-003 乐观回写）。
 *
 * <p>拉取直接返回 {@link ProductContext} 即可；回写额外携带 baseUpdatedAt 做乐观并发校验：
 * 云端比对若与库中 updatedAt 不一致则拒绝（说明期间被改过）。MVP 不做自动合并。
 */
@Data
@NoArgsConstructor
public class ContextSyncDTO {

    /** 要回写的完整上下文。 */
    private ProductContext context;

    /** 拉取时记录的 updatedAt（epoch 毫秒）。回写时云端据此判断是否过期。 */
    private long baseUpdatedAt;
}

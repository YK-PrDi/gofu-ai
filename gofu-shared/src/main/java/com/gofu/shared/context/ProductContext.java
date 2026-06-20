package com.gofu.shared.context;

/**
 * 商品全局上下文（系统脊柱）—— 占位。
 *
 * <p>这是打通"生图视觉流"与"上新结构流"数据孤岛的核心对象，权威存储在云端。
 * 完整字段（visual / structure / lockedFields / status / tenantId）在 M2 契约里程碑定义。
 *
 * <p>本类目前仅作骨架占位，使三模块可编译。请勿在此添加业务逻辑。
 */
public final class ProductContext {

    private ProductContext() {
        // 占位：M2 将替换为真实的不可变上下文模型
    }
}

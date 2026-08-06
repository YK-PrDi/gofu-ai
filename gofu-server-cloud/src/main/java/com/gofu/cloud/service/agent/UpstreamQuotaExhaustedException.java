package com.gofu.cloud.service.agent;

/**
 * 中转站账户额度耗尽（HTTP 403/402 + {@code insufficient_user_quota}）。
 *
 * <p>为什么要单列一个异常：08.04 实测发现所有 key 共用同一个账户余额
 * （三个 key 查出来的"剩余额度"数字完全一样），额度耗尽时
 * <b>换 key 毫无意义</b>，而且并发下这类请求会在上游的预扣费环节挂住、
 * 最终撞满 readTimeout 变成 {@code Read timed out} —— 于是日志报的是
 * "上游拥塞、跳过同 baseUrl 的其余 key"，把人引向查网络/查负载的错方向，
 * 真正该做的是<b>充值</b>。
 *
 * <p>所以这条要一路上抛、不被"换 key / 重试 / 归到普通失败"那套逻辑吞掉，
 * 并把上游原文（含剩余额度与所需额度）带到前端。
 */
public class UpstreamQuotaExhaustedException extends RuntimeException {

    public UpstreamQuotaExhaustedException(String message) {
        super(message);
    }
}

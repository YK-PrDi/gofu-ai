package com.gofu.local.service.listing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * M15 拼多多登录态保活。
 *
 * <p>拼多多服务端 token 有 TTL，App 闲置超过 TTL 就被服务端失效、需重新扫码登录。
 * 本服务定时（默认每 8 小时）静默调 {@code pdd_listing.js --keep-alive}：headed 打开商家后台、
 * 让拼多多续期 session/token、回写 cookies 后退出。已登录才刷新，未登录不打扰。
 *
 * <p>与上新/登录互斥（{@link ListingService#isBrowserBusy()}），争用同一 pdd_browser_profile 时跳过本次。
 * 开关：{@code app.pdd.keepalive.enabled}（默认 true）；间隔：{@code app.pdd.keepalive.interval-ms}（默认 8h）。
 */
@Service
public class PddKeepAliveService {

    private static final Logger log = LoggerFactory.getLogger(PddKeepAliveService.class);

    private final ListingService listingService;

    @Value("${app.pdd.keepalive.enabled:true}")
    private boolean enabled;

    public PddKeepAliveService(ListingService listingService) {
        this.listingService = listingService;
    }

    /**
     * 定时保活。间隔由 app.pdd.keepalive.interval-ms 控制（默认 28800000ms=8h），
     * initialDelay 30 分钟（避开启动高峰、也给用户时间先登录）。
     */
    @Scheduled(
        fixedDelayString = "${app.pdd.keepalive.interval-ms:28800000}",
        initialDelayString = "${app.pdd.keepalive.initial-delay-ms:1800000}"
    )
    public void keepAlive() {
        if (!enabled) return;
        try {
            log.info("[保活] 定时触发拼多多登录态保活…");
            boolean kept = listingService.runKeepAlive();
            log.info("[保活] 本次结果：{}", kept ? "已刷新登录态" : "跳过/未登录");
        } catch (Exception e) {
            log.warn("[保活] 定时任务异常(忽略): {}", e.getMessage());
        }
    }
}

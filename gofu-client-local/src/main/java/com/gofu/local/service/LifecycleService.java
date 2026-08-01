package com.gofu.local.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 前端心跳看门狗：工作台页面每隔几秒 ping /api/lifecycle/heartbeat；页面关闭(pagehide)时用
 * sendBeacon 发 /api/lifecycle/closing。本服务据此判断"用户是否已关掉工作台"，是则自杀退出
 * → launcher 感知 local 子进程退出后连带停 cloud/node/chrome，托盘随之消失。
 *
 * <p>为什么不靠"检测浏览器"：工作台是系统浏览器里的一个普通标签，Java 进程无从感知标签开关，
 * 只能靠页面主动报活。刷新页面会短暂断心跳，故关闭走"宽限倒计时"，宽限内又来心跳=刷新恢复→取消退出。
 *
 * <p>仅打包态启用(app.resources-path 非空，由 launcher 注入)；源码态开发不想被误杀，默认关闭。
 */
@Service
public class LifecycleService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);

    /** 打包态标志：launcher 注入 app.resources-path，源码态为空 → 看门狗不启用。 */
    @Value("${app.resources-path:}")
    private String resourcesPath;

    /** 收到 closing 后的宽限期(ms)：宽限内又有心跳(刷新恢复)则取消退出。 */
    private static final long GRACE_MS = 6_000;
    /** 兜底：完全无心跳超过此时长也退出(防浏览器整个被杀、closing 都没发出来时残留后台)。 */
    private static final long IDLE_MAX_MS = 1_800_000;

    private final AtomicLong lastBeat = new AtomicLong(0);
    /** >0 表示已收到关闭信号，值=预定退出的时间戳(ms)。0=未在关闭倒计时。 */
    private final AtomicLong closingDeadline = new AtomicLong(0);
    private volatile boolean everBeat = false;

    private boolean enabled() { return resourcesPath != null && !resourcesPath.isBlank(); }

    @PostConstruct
    void start() {
        if (!enabled()) { log.info("[lifecycle] 源码态，前端心跳看门狗不启用"); return; }
        lastBeat.set(System.currentTimeMillis());
        Thread t = new Thread(this::watchLoop, "lifecycle-watchdog");
        t.setDaemon(true);
        t.start();
        log.info("[lifecycle] 前端心跳看门狗已启用(关闭工作台→宽限{}s→退出)", GRACE_MS / 1000);
    }

    /** 前端定时心跳：报活 + 取消任何进行中的关闭倒计时(刷新场景)。 */
    public void heartbeat() {
        lastBeat.set(System.currentTimeMillis());
        everBeat = true;
        long d = closingDeadline.getAndSet(0);
        if (d > 0) log.info("[lifecycle] 关闭倒计时被新心跳取消(疑似刷新页面)");
    }

    /** 页面关闭信号(sendBeacon)：启动宽限倒计时。 */
    public void closing() {
        if (!enabled()) return;
        closingDeadline.set(System.currentTimeMillis() + GRACE_MS);
        log.info("[lifecycle] 收到工作台关闭信号，{}s 宽限后若无新心跳则退出", GRACE_MS / 1000);
    }

    private void watchLoop() {
        while (true) {
            try {
                Thread.sleep(1000);
                long now = System.currentTimeMillis();
                long deadline = closingDeadline.get();
                if (deadline > 0 && now >= deadline) {
                    log.info("[lifecycle] 宽限结束仍无心跳，判定工作台已关闭，退出进程");
                    shutdown();
                    return;
                }
                // 兜底：曾经报过活、但长时间彻底无心跳(closing 都没来得及发)→ 也退出，别残留后台。
                if (everBeat && (now - lastBeat.get()) > IDLE_MAX_MS) {
                    log.info("[lifecycle] 超过 {}s 无任何心跳，判定工作台已断开，退出进程", IDLE_MAX_MS / 1000);
                    shutdown();
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void shutdown() {
        // 正常退出即可：launcher 的 any-child-exit 会连带停 cloud/node/chrome。
        System.exit(0);
    }
}

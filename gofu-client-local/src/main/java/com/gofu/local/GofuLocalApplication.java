package com.gofu.local;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * GOFU-AI 本地客户端启动入口（结构流 / 反风控）。
 *
 * <p>职责：Canvas 图层拼装 + 上新流程调度 + Playwright 反风控。
 * <p>禁止：本地 AI 生图逻辑。所有生图/重绘通过 service/cloudgw 调用云端服务。
 * <p>@EnableScheduling：M15 拼多多登录态定时保活（PddKeepAliveService）。
 */
@SpringBootApplication
@EnableScheduling
public class GofuLocalApplication {

    private static final int PORT = 5021;

    public static void main(String[] args) {
        // 双保险(见 launcher 修1):快速重启时上个实例的 5021 可能还在 LISTENING/TIME_WAIT。
        // Spring 启动前先等端口空出来,别一撞就 "Port already in use" 崩掉导致工作台白屏。
        awaitPortFree(PORT, 30);
        SpringApplication.run(GofuLocalApplication.class, args);
    }

    /** 轮询等端口可绑定,最多等 timeoutSec 秒。能 bind 上=空闲(能穿过 TIME_WAIT,因 ServerSocket 默认 SO_REUSEADDR)。 */
    private static void awaitPortFree(int port, int timeoutSec) {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try (java.net.ServerSocket s = new java.net.ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new java.net.InetSocketAddress("0.0.0.0", port));
                return;   // 绑得上=空闲,立即释放交给 Tomcat
            } catch (java.io.IOException busy) {
                System.out.println("[启动] 端口 " + port + " 仍被占用(上个实例未完全退出),等待释放…");
                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
        System.out.println("[启动] 等待端口 " + port + " 释放超时,仍尝试启动(可能报端口占用)");
    }
}

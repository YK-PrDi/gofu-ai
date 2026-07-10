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

    public static void main(String[] args) {
        SpringApplication.run(GofuLocalApplication.class, args);
    }
}

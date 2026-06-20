package com.gofu.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * GOFU-AI 云端服务启动入口（视觉流 / 算力）。
 *
 * <p>职责：多 AI Agent 生图 + 商品上下文权威存储。
 * <p>禁止：Playwright、浏览器自动化、上新代码（那些属于 gofu-client-local）。
 */
@SpringBootApplication
public class GofuCloudApplication {

    public static void main(String[] args) {
        SpringApplication.run(GofuCloudApplication.class, args);
    }
}

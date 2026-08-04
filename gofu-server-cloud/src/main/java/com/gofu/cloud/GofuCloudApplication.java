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
        // HttpURLConnection 的 keep-alive 连接池默认每目标只留 5 条(http.maxConnections)，
        // 而生图并发是 GEN_CONC=8。超出的请求要排队等复用 → 实测 6 并发时最慢一张被拖到 226s
        // (其余 90~156s)，抬到 24 后最慢降到 149s。生图单张本就要 80~150s，排队叠上去就会撞 300s readTimeout。
        // 必须在任何 HTTP 调用发生前设置(该值只在连接池初始化时读一次)，故放在 main 首行而非 @Configuration。
        if (System.getProperty("http.maxConnections") == null) {
            System.setProperty("http.maxConnections", "24");
        }
        SpringApplication.run(GofuCloudApplication.class, args);
    }
}

package com.gofu.local.config;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 启动后在控制台打印可点击的预览页/接口地址（开发态便利）。
 *
 * <p>IDEA 终端会把 http:// 开头的文本渲染成可点击链接，省得手敲地址测试。
 */
@Component
public class StartupUrlPrinter implements ApplicationListener<WebServerInitializedEvent> {

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        int port = event.getWebServer().getPort();
        String base = "http://localhost:" + port;
        System.out.println("\n" +
            "  ==== GOFU 本地客户端已启动 ====\n" +
            "  统一工作台(点这个): " + base + "/workbench.html\n" +
            "  ===============================\n");
    }
}

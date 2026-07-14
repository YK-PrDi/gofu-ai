package com.gofu.local.controller;

import jakarta.servlet.http.HttpServletRequest;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

/**
 * 云端网关转发（M6a，cloudgw）。预览页只连本地 5021，云端能力（生图/双轨/上下文）由本层透传到 gofu-server-cloud。
 *
 * <p>符合"本地是总控中枢"（ADR-011）：前端单一入口，本地转发。转发范围限定云端独有前缀，
 * 不碰本地自有的 /api/listing、/api/task（那些本地直接处理）。
 */
@RestController
public class CloudGatewayController {

    private static final Logger log = LoggerFactory.getLogger(CloudGatewayController.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String cloudBase;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)   // 生图耗时长
            .build();

    public CloudGatewayController(@Value("${gofu.cloud.base-url:http://127.0.0.1:5020}") String cloudBase) {
        // 默认强制走 127.0.0.1(IPv4)：Windows 上 localhost 可能先解析 IPv6 ::1，
        // 云端启动时 ::1 未就绪会转发扑空(见 07.14 日志)。显式 IPv4 绕开该时序问题。
        this.cloudBase = cloudBase != null ? cloudBase.replace("localhost", "127.0.0.1") : "http://127.0.0.1:5020";
    }

    /** 转发云端独有前缀：/api/context/** 、/api/gen/** 、/api/ly-gen/** 、/api/flow/**（M8 交错编排）。 */
    @RequestMapping({"/api/context/**", "/api/gen/**", "/api/ly-gen/**", "/api/flow/**"})
    public ResponseEntity<byte[]> forward(HttpServletRequest req,
                                          @RequestBody(required = false) byte[] body) throws Exception {
        String path = req.getRequestURI();
        String qs = req.getQueryString();
        String url = cloudBase + path + (qs != null ? "?" + qs : "");

        Request.Builder rb = new Request.Builder().url(url);
        String method = req.getMethod();
        if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
            rb.method(method, okhttp3.RequestBody.create(body == null ? new byte[0] : body, JSON));
        } else {
            rb.method(method, null);
        }

        // 转发+重试：仅对"连接失败"(云端启动慢半拍/瞬时网络)退避重试最多3次；
        // 拿到任何 HTTP 响应(含4xx/5xx)即视为云端已应答，不重试(避免重复触发生图等副作用)。
        Exception lastErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Response resp = http.newCall(rb.build()).execute()) {
                byte[] respBody = resp.body() != null ? resp.body().bytes() : new byte[0];
                String ct = resp.header("Content-Type", "application/json; charset=utf-8");
                return ResponseEntity.status(resp.code())
                        .header("Content-Type", ct)
                        .body(respBody);
            } catch (java.io.IOException e) {
                lastErr = e;
                log.warn("cloudgw 连接云端失败(第 {}/3 次) {} {}: {}", attempt + 1, method, url, e.getMessage());
                if (attempt < 2) { try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; } }
            }
        }
        log.error("cloudgw 转发失败 {} {}: {}", method, url, lastErr == null ? "unknown" : lastErr.getMessage());
        String err = "{\"error\":\"云端转发失败(重试3次仍不通，请确认云端5020已启动): "
                + (lastErr == null ? "" : lastErr.getMessage().replace("\"", "'")) + "\"}";
        return ResponseEntity.status(502)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(err.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}

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

    public CloudGatewayController(@Value("${gofu.cloud.base-url:http://localhost:5020}") String cloudBase) {
        this.cloudBase = cloudBase;
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

        try (Response resp = http.newCall(rb.build()).execute()) {
            byte[] respBody = resp.body() != null ? resp.body().bytes() : new byte[0];
            String ct = resp.header("Content-Type", "application/json; charset=utf-8");
            return ResponseEntity.status(resp.code())
                    .header("Content-Type", ct)
                    .body(respBody);
        } catch (Exception e) {
            log.error("cloudgw 转发失败 {} {}: {}", method, url, e.getMessage());
            String err = "{\"error\":\"云端转发失败: " + e.getMessage().replace("\"", "'") + "\"}";
            return ResponseEntity.status(502)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .body(err.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}

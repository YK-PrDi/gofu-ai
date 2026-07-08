package com.gofu.local.service.sync;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.shared.context.ProductContext;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 商品全局上下文本地同步（M7）。从云端权威源拉取 ProductContext（ADR-003）。
 *
 * <p>上新前用它把预览页确认好的 context 拉到本地，转成 ListingConfig。
 * 复用 {@code gofu.cloud.base-url}，与 cloudgw 同一云端地址。
 */
@Service
public class ContextSyncService {

    private static final Logger log = LoggerFactory.getLogger(ContextSyncService.class);

    private final String cloudBase;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    // 云端可能返回本地 DTO 没有的字段，容错反序列化；注册 JavaTime 模块处理 LocalDateTime
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ContextSyncService(@Value("${gofu.cloud.base-url:http://localhost:5020}") String cloudBase) {
        this.cloudBase = cloudBase;
    }

    /** 按 ID 从云端拉取 ProductContext。找不到/失败抛异常。 */
    public ProductContext fetch(String contextId) throws Exception {
        if (contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("contextId 不能为空");
        }
        String url = cloudBase + "/api/context/" + contextId;
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new RuntimeException("拉取 context 失败 HTTP " + resp.code() + "：" + url);
            }
            String body = resp.body() != null ? resp.body().string() : "";
            if (body.isBlank()) throw new RuntimeException("云端返回空 context：" + contextId);
            return objectMapper.readValue(body, ProductContext.class);
        } catch (Exception e) {
            log.error("拉取 context {} 失败: {}", contextId, e.getMessage());
            throw e;
        }
    }
}

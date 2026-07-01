package com.gofu.cloud.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LY 生图线专属配置（prefix = ly-image），与 ele 生图线的 {@link AppProperties}（prefix = app）物理隔离。
 *
 * <p>背景：ele（对公众）与 LY（合作公司）是两条独立生图产品线，配置字段不同、不可混用。
 * 两个 @ConfigurationProperties 用不同 prefix 共存，各服务各的。
 *
 * <p>从 LY-Automation AppProperties 迁入生图三层真正依赖的段：GptImage / TextGen / Paths。
 * Kuaimai/Auth/Volcengine 等上新相关段不迁（那些在本地 gofu-client-local）。
 */
@Component
@ConfigurationProperties(prefix = "ly-image")
public class LyImageProperties {

    private GptImage gptImage = new GptImage();
    private TextGen text = new TextGen();
    private Paths paths = new Paths();

    public GptImage getGptImage() { return gptImage; }
    public void setGptImage(GptImage gptImage) { this.gptImage = gptImage; }
    public TextGen getText() { return text; }
    public void setText(TextGen text) { this.text = text; }
    public Paths getPaths() { return paths; }
    public void setPaths(Paths paths) { this.paths = paths; }

    public static class Paths {
        private String userDataDir = "./";
        public String getUserDataDir() { return userDataDir; }
        public void setUserDataDir(String userDataDir) { this.userDataDir = userDataDir; }
    }

    /** 生图配置：provider = gemini | openai。 */
    public static class GptImage {
        private String provider = "gemini";
        private String baseUrl = "https://generativelanguage.googleapis.com";
        private String model = "gemini-2.5-flash-image";
        private String keys = "";
        private List<String> apiKeys = new ArrayList<>();
        private String proxyHost = "";
        private int proxyPort = 0;
        private String aspectRatio = "ASPECT_RATIO_ONE_BY_ONE";
        private String imageSize = "IMAGE_SIZE_ONE_K";

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getKeys() { return keys; }
        public void setKeys(String keys) { this.keys = keys; }
        public List<String> getApiKeys() { return apiKeys; }
        public void setApiKeys(List<String> apiKeys) { this.apiKeys = apiKeys; }
        public String getProxyHost() { return proxyHost; }
        public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }
        public int getProxyPort() { return proxyPort; }
        public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }
        public String getAspectRatio() { return aspectRatio; }
        public void setAspectRatio(String aspectRatio) { this.aspectRatio = aspectRatio; }
        public String getImageSize() { return imageSize; }
        public void setImageSize(String imageSize) { this.imageSize = imageSize; }

        /** 可轮换密钥列表：apiKeys（OpenAI 路径）优先，空则回退 keys（逗号分隔，Gemini 路径）。 */
        public List<String> keyList() {
            List<String> out = new ArrayList<>();
            if (apiKeys != null) {
                for (String k : apiKeys) {
                    if (k == null) continue;
                    String t = k.trim();
                    if (!t.isEmpty()) out.add(t);
                }
            }
            if (!out.isEmpty()) return out;
            if (keys != null) {
                for (String k : keys.split(",")) {
                    String t = k.trim();
                    if (!t.isEmpty()) out.add(t);
                }
            }
            return out;
        }
    }

    /** 文本/多模态生成（标题、款式名）。默认走 DashScope OpenAI 兼容模式。 */
    public static class TextGen {
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String model = "qwen-vl-plus";
        private String apiKey = "";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    }
}

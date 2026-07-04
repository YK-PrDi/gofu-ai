package com.gofu.local.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 本地客户端配置。
 *
 * <p>⚠️ 仅保留本地上新/任务相关配置。生图 AI 配置（Volcengine/GptImage）已退役——
 * 所有 AI 算力归云端（ADR-002）。原 LY AppProperties 的 Volcengine/GptImage 未迁入。
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Paths paths = new Paths();
    private Kuaimai kuaimai = new Kuaimai();

    public Paths getPaths() { return paths; }
    public void setPaths(Paths paths) { this.paths = paths; }
    public Kuaimai getKuaimai() { return kuaimai; }
    public void setKuaimai(Kuaimai kuaimai) { this.kuaimai = kuaimai; }

    public static class Paths {
        /** 打包态由 electron 经 -Dapp.paths.user-data-dir=... 注入；源码态留空则 fallback "./"。 */
        private String userDataDir = "./";
        /** TaskService 临时归档清理用。 */
        private String tempOutputDir = "./.temp-output";

        public String getUserDataDir() { return userDataDir; }
        public void setUserDataDir(String userDataDir) { this.userDataDir = userDataDir; }
        public String getTempOutputDir() { return tempOutputDir; }
        public void setTempOutputDir(String tempOutputDir) { this.tempOutputDir = tempOutputDir; }
    }

    /**
     * 快麦（超级Boss）ERP 开放平台凭证。⚠️ 机器相关、跟随真实环境，不上云——
     * 运行时由 kuaimai-config.json（用户数据目录）覆盖这些空默认值（gofu-client-local/CLAUDE.md 本地持久化铁律）。
     * 从 LY-Automation 原样迁入（成本来源：purchasePrice，ADR-002 结构流归本地）。
     */
    public static class Kuaimai {
        private String appKey = "";
        private String appSecret = "";
        private String accessToken = "";
        private String refreshToken = "";
        private String companyId = "";
        private String appTitle = "";

        public String getAppKey() { return appKey; }
        public void setAppKey(String appKey) { this.appKey = appKey; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String appSecret) { this.appSecret = appSecret; }
        public String getAccessToken() { return accessToken; }
        public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
        public String getCompanyId() { return companyId; }
        public void setCompanyId(String companyId) { this.companyId = companyId; }
        public String getAppTitle() { return appTitle; }
        public void setAppTitle(String appTitle) { this.appTitle = appTitle; }
    }
}

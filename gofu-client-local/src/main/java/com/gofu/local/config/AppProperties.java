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

    public Paths getPaths() { return paths; }
    public void setPaths(Paths paths) { this.paths = paths; }

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
}

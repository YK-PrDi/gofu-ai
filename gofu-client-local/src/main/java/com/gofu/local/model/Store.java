package com.gofu.local.model;

/**
 * 店铺配置（借 DSR stores.json 结构）。
 *
 * <p>{@code profile} 是隔离键（形如 store_01），派生出该店独立的
 * user-data-dir 和 cookie 路径；{@code name} 只是显示标签，用于按文件夹名匹配。
 *
 * <p>机器相关数据（登录态/cookie）跟随本地，不上云（见模块 CLAUDE.md）。
 */
public class Store {
    private String name;                // 店铺显示名，匹配大文件夹一级子目录名
    private String profile;             // 隔离键 store_NN
    private String dsrUrl = "https://mms.pinduoduo.com/home/";

    public Store() {}
    public Store(String name, String profile) { this.name = name; this.profile = profile; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProfile() { return profile; }
    public void setProfile(String profile) { this.profile = profile; }
    public String getDsrUrl() { return dsrUrl; }
    public void setDsrUrl(String dsrUrl) { this.dsrUrl = dsrUrl; }
}

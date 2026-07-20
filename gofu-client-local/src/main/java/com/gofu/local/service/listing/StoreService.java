package com.gofu.local.service.listing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.local.config.AppProperties;
import com.gofu.local.model.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 多店铺管理（P4，方案A）。加载 stores.json、店铺名→profile 解析、派生每店独立
 * cookie/profile 路径。反风控脚本 pdd_listing.js 已支持 config.cookiesPath/userDataDir，
 * 故多店隔离**不改脚本一行**，只在 Java 侧按 profile 传不同路径。
 *
 * <p>stores.json 与每店 profile 目录都落在本地 userDataDir（机器相关数据不上云）。
 * 结构：{@code {"stores":[{"name","profile","dsrUrl"}]}}（借 DSR）。
 */
@Service
public class StoreService {

    private static final Logger log = LoggerFactory.getLogger(StoreService.class);
    private final AppProperties appProperties;
    private final ObjectMapper om = new ObjectMapper();

    public StoreService(AppProperties appProperties) { this.appProperties = appProperties; }

    private String baseDir() {
        String d = appProperties.getPaths().getUserDataDir();
        return (d == null || d.isBlank()) ? System.getProperty("user.dir") : d;
    }

    /** stores.json 路径（本地，跟随机器）。 */
    public File storesFile() { return new File(baseDir(), "stores.json"); }

    /** 加载所有店铺；文件不存在/损坏返回空表（不抛，前端引导新增）。 */
    @SuppressWarnings("unchecked")
    public List<Store> loadStores() {
        File f = storesFile();
        if (!f.isFile()) {
            // 诊断#2：打包态最常见失配根因=baseDir()指到的目录里根本没有 stores.json(UI存的是另一个目录)。
            log.info("[诊断·loadStores] stores.json 不存在，path={}", f.getAbsolutePath());
            return new ArrayList<>();
        }
        try {
            Map<String, Object> root = om.readValue(f, Map.class);
            List<Map<String, Object>> arr = (List<Map<String, Object>>) root.getOrDefault("stores", List.of());
            List<Store> out = new ArrayList<>();
            for (Map<String, Object> m : arr) {
                Store s = new Store();
                s.setName(String.valueOf(m.getOrDefault("name", "")));
                s.setProfile(String.valueOf(m.getOrDefault("profile", "")));
                if (m.get("dsrUrl") != null) s.setDsrUrl(String.valueOf(m.get("dsrUrl")));
                if (!s.getProfile().isBlank()) out.add(s);
            }
            // 诊断#2：打印实际路径+读到的店名(带引号露出首尾空格/全角)，与预检 warning 的目录名逐字对照即可定位失配。
            log.info("[诊断·loadStores] path={} 读到 {} 个店: {}",
                    f.getAbsolutePath(), out.size(),
                    out.stream().map(s -> "「" + s.getName() + "」").toList());
            return out;
        } catch (Exception e) {
            log.warn("加载 stores.json 失败: path={} {}", f.getAbsolutePath(), e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 保存店铺列表（新增/编辑后写回）。 */
    public void saveStores(List<Store> stores) throws Exception {
        Map<String, Object> root = Map.of(
                "_说明", "店铺由半自动上新页管理，profile 是隔离键，请勿手改已用编号",
                "stores", stores);
        om.writerWithDefaultPrettyPrinter().writeValue(storesFile(), root);
    }

    /** 按 profile 更新店铺名（登录成功后脚本抓到真实店铺名时回填）。找不到该 profile 则忽略。 */
    public synchronized void renameStore(String profile, String newName) throws Exception {
        if (profile == null || profile.isBlank() || newName == null || newName.isBlank()) return;
        List<Store> stores = new ArrayList<>(loadStores());
        boolean hit = false;
        for (Store s : stores) {
            if (profile.equals(s.getProfile())) { s.setName(newName); hit = true; break; }
        }
        if (hit) saveStores(stores);
    }

    /**
     * 店铺名 → profile 解析（供 P1 SemiAutoService 的 shopResolver 回填）。
     * 先精确匹配 name，再退子串包含（容忍文件夹名多/少后缀）。找不到返回 null。
     */
    public String resolveProfileByName(String shopName) {
        if (shopName == null || shopName.isBlank()) return null;
        String key = normName(shopName);
        List<Store> stores = loadStores();
        // 1) 归一化后精确匹配优先。归一化=trim+全角转半角+去所有空白——文件夹名与扫码存的店名常差
        //    全角空格/半角空格/前后空格(打包态实测"已登店铺却未匹配"多因此)，不归一化会漏判。
        for (Store s : stores) if (key.equals(normName(s.getName()))) return s.getProfile();
        // 2) H1修：子串仅在【唯一命中】时采用。多于一个候选=歧义,返回 null;短店名(≤2字)不走子串。
        if (key.length() <= 2) {
            log.info("[诊断·resolveProfile] 文件夹名「{}」归一化后 key「{}」精确未命中且≤2字不走子串，判未匹配", shopName, key);
            return null;
        }
        String hit = null;
        for (Store s : stores) {
            String n = normName(s.getName());
            if (n.length() <= 2) continue;
            if (key.contains(n) || n.contains(key)) {
                if (hit != null && !hit.equals(s.getProfile())) return null;   // 命中多于一个不同店 → 歧义，不猜
                hit = s.getProfile();
            }
        }
        // 诊断#2：失配时露出归一化后的文件夹名 key 与所有店名的归一化值，逐字对照即可判断差在哪(空格/全角/后缀)。
        if (hit == null)
            log.info("[诊断·resolveProfile] 文件夹名「{}」→ key「{}」未匹配任何店；候选店归一化值={}",
                    shopName, key, stores.stream().map(s -> "「" + normName(s.getName()) + "」").toList());
        return hit;
    }

    /** 店名归一化：去首尾空白 + 全角空格/连字符转半角 + 去掉所有内部空白。用于文件夹名与stores.json店名匹配。 */
    private String normName(String s) {
        if (s == null) return "";
        return s.trim().replace('　', ' ').replace('－', '-').replaceAll("\\s+", "");
    }

    /** 某店独立 user-data-dir（浏览器 profile，登录态存这）。 */
    public String userDataDirOf(String profile) {
        return new File(baseDir(), "stores/" + profile + "/pdd_browser_profile").getAbsolutePath();
    }

    /** 某店独立 cookie 路径。 */
    public String cookiesPathOf(String profile) {
        return new File(baseDir(), "stores/" + profile + "/pdd_cookies.json").getAbsolutePath();
    }
}

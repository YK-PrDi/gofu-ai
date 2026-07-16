package com.gofu.cloud.service.lyimage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.cloud.config.LyImageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * 防比价构图模板库：存 userDataDir/antiprice-templates.json，前端可增删改、运行时即时生效。
 * 首次不存在时从 classpath 默认模板（prompt/antiprice-templates.json）落地一份。
 */
@Service
public class PromptTemplateService {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateService.class);
    private final LyImageProperties appProperties;
    private final ObjectMapper om = new ObjectMapper();

    public PromptTemplateService(LyImageProperties appProperties) {
        this.appProperties = appProperties;
    }

    private File templateFile() {
        return new File(appProperties.getPaths().getUserDataDir(), "antiprice-templates.json");
    }

    /**
     * 读模板库 JSON 文本；不存在则从 classpath 默认值落地后再读。
     * 版本门：若内置默认模板的 version 比运行时副本更新，自动用默认值覆盖（新版软件的模板直接生效，无需手动恢复）。
     */
    public synchronized String loadJson() {
        File f = templateFile();
        try {
            String def = PromptLoader.load("prompt/antiprice-templates.json");
            if (!f.isFile()) {
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), def.getBytes(StandardCharsets.UTF_8));
                return def;
            }
            String cur = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (versionOf(def) > versionOf(cur)) {
                // 内置模板版本更新→覆盖运行时副本（打包带新模板时自动生效）
                Files.write(f.toPath(), def.getBytes(StandardCharsets.UTF_8));
                log.info("防比价模板已升级到内置版本 {}", versionOf(def));
                return def;
            }
            return cur;
        } catch (Exception e) {
            log.warn("读取防比价模板失败，回退默认: {}", e.getMessage());
            try { return PromptLoader.load("prompt/antiprice-templates.json"); }
            catch (Exception e2) { return "{\"templates\":[]}"; }
        }
    }

    /** 解析模板 JSON 顶层 version（缺失记为 0）。 */
    private int versionOf(String json) {
        try {
            Object v = om.readValue(json, Map.class).get("version");
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) { return 0; }
    }

    /** 保存模板库 JSON（前端编辑后写回）。 */
    public synchronized void saveJson(String json) throws Exception {
        File f = templateFile();
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回模板列表（List<Map>）。失败返回空列表。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTemplates() {
        try {
            Map<String, Object> root = om.readValue(loadJson(), Map.class);
            Object t = root.get("templates");
            if (t instanceof List) return (List<Map<String, Object>>) t;
        } catch (Exception e) { log.warn("解析模板失败: {}", e.getMessage()); }
        return new java.util.ArrayList<>();
    }

    /** 按 id 取单个模板；找不到返回 null。 */
    public Map<String, Object> findById(String id) {
        if (id == null || id.isBlank()) return null;
        for (Map<String, Object> t : listTemplates()) {
            if (id.equals(String.valueOf(t.get("id")))) return t;
        }
        return null;
    }

    /**
     * 取某模板可用的基准图，优先级：
     * 1) 模板手填的 baseImg 绝对路径
     * 2) 内置基准图（classpath assets/base/<模板名>[-有配件|-无配件].png|jpg，按是否有配件+模板名匹配）
     * 3) 运行时缓存（sku-base/<templateId>.jpg，当批生成的首张）
     * 都没有返回 null（需当批生成首张作基准）。
     */
    public File resolveBaseImg(Map<String, Object> tpl) { return resolveBaseImg(tpl, true); }

    public File resolveBaseImg(Map<String, Object> tpl, boolean hasAcc) {
        if (tpl == null) return null;
        Object bi = tpl.get("baseImg");
        if (bi instanceof String && !((String) bi).isBlank()) {
            File f = new File((String) bi);
            if (f.isFile()) return f;
        }
        // 模板可设 noBuiltinBase=true 跳过内置图：当批 AI 用加强提示词重生首张作基准，再缓存复用。
        boolean skipBuiltin = Boolean.TRUE.equals(tpl.get("noBuiltinBase"));
        if (!skipBuiltin) {
            String name = String.valueOf(tpl.get("name"));
            // 先按「有配件/无配件」变体匹配，没有再退回无后缀的单图
            File builtin = builtinBaseByName(name + (hasAcc ? "-有配件" : "-无配件"));
            if (builtin == null) builtin = builtinBaseByName(name);
            if (builtin != null) return builtin;
        }
        File cache = baseCacheFile(String.valueOf(tpl.get("id")));
        return cache.isFile() ? cache : null;
    }

    /** 内置基准图：classpath assets/base/<模板名>.(png|jpg) 落地到用户目录。找不到返回 null。 */
    public File builtinBaseByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (String ext : new String[]{".png", ".jpg"}) {
            String res = "assets/base/" + name + ext;
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(res)) {
                if (is == null) continue;
                File f = new File(appProperties.getPaths().getUserDataDir(), "sku-base/builtin/" + name + ext);
                if (!f.isFile()) { f.getParentFile().mkdirs(); Files.write(f.toPath(), is.readAllBytes()); }
                return f;
            } catch (Exception e) { log.warn("内置基准图落地失败({}): {}", res, e.getMessage()); }
        }
        return null;
    }

    /** 通用素材落地：classpath assets/<relPath> → 用户目录 sku-base/asset/<relPath>。找不到返回 null。 */
    public File assetByPath(String relPath) {
        if (relPath == null || relPath.isBlank()) return null;
        String res = "assets/" + relPath;
        try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(res)) {
            if (is == null) return null;
            File f = new File(appProperties.getPaths().getUserDataDir(), "sku-base/asset/" + relPath);
            if (!f.isFile()) { f.getParentFile().mkdirs(); Files.write(f.toPath(), is.readAllBytes()); }
            return f;
        } catch (Exception e) { log.warn("素材落地失败({}): {}", res, e.getMessage()); return null; }
    }

    /** 架类防比价选中的构图：一段 prompt + 配套参考图名 + 命中的款式组。 */
    public record ShelfPick(String prompt, String ref, String group) {}

    /**
     * 架类防比价构图选择（类目-keyed + 款式分组 + 组内配对随机）：
     * 1) 按叶子类目从 shelf-prompts.json 命中类目节点(key 末段==leaf)；
     * 2) 锅盖架等多组类目：skuName 含 吸盘/壁挂/墙/免钉 → "吸附"组，否则 "落地"；单组类目取 "默认"；
     * 3) 组内随机选一套 {prompt, ref}（配对随机，防文不对图）。
     * 参考图在 classpath assets/shelf-ref/<leaf>/<group>/<ref>。找不到返回 null。
     */
    @SuppressWarnings("unchecked")
    public ShelfPick shelfPick(String category, String skuName) {
        if (category == null || category.isBlank()) return null;
        try {
            Map<String, Object> map = om.readValue(PromptLoader.load("prompt/shelf-prompts.json"), Map.class);
            String leaf = category.replace('＞', '>').replace('›', '>');
            leaf = leaf.contains(">") ? leaf.substring(leaf.lastIndexOf('>') + 1).trim() : leaf.trim();
            // 命中类目节点：key 末段 == leaf
            Map<String, Object> catNode = null;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (e.getKey().startsWith("_") || !(e.getValue() instanceof Map)) continue;
                String k = e.getKey();
                String kl = k.contains(">") ? k.substring(k.lastIndexOf('>') + 1).trim() : k.trim();
                if (kl.equals(leaf)) { catNode = (Map<String, Object>) e.getValue(); break; }
            }
            if (catNode == null) return null;
            // 选款式组
            String s = skuName == null ? "" : skuName;
            String group;
            if (catNode.containsKey("落地") || catNode.containsKey("吸附")) {
                group = (s.contains("吸盘") || s.contains("壁挂") || s.contains("墙") || s.contains("免钉")) ? "吸附" : "落地";
                if (!catNode.containsKey(group)) group = catNode.containsKey("落地") ? "落地" : "吸附";
            } else {
                group = catNode.containsKey("默认") ? "默认" : catNode.keySet().iterator().next();
            }
            Object arr = catNode.get(group);
            if (!(arr instanceof List) || ((List<?>) arr).isEmpty()) return null;
            List<Map<String, Object>> items = (List<Map<String, Object>>) arr;
            Map<String, Object> pick = items.get((int) (Math.random() * items.size()));  // 组内随机
            String prompt = String.valueOf(pick.getOrDefault("prompt", ""));
            String ref = String.valueOf(pick.getOrDefault("ref", ""));
            if (prompt.isBlank()) return null;
            return new ShelfPick(prompt, ref, group);
        } catch (Exception e) {
            log.warn("架类构图选择失败(category={}): {}", category, e.getMessage());
            return null;
        }
    }

    /** 架类参考图落地：classpath assets/shelf-ref/<leaf>/<group>/<ref> → 用户目录。找不到返回 null。 */
    public File shelfRefFile(String leaf, String group, String ref) {
        if (ref == null || ref.isBlank()) return null;
        return assetByPath("shelf-ref/" + leaf + "/" + group + "/" + ref);
    }

    // ── M18 品类库（subjectLock/negative）：从羽刃前端 EC_CATALOG 移植为后端 ec-catalog.json ──
    private volatile Map<String, Object> ecCatalogCache;

    @SuppressWarnings("unchecked")
    private Map<String, Object> ecCatalog() {
        if (ecCatalogCache == null) {
            synchronized (this) {
                if (ecCatalogCache == null) {
                    try {
                        ecCatalogCache = om.readValue(PromptLoader.load("prompt/ec-catalog.json"), Map.class);
                    } catch (Exception e) {
                        log.warn("ec-catalog.json 读取失败，品类库禁用: {}", e.getMessage());
                        ecCatalogCache = Map.of();
                    }
                }
            }
        }
        return ecCatalogCache;
    }

    /**
     * 按 category 路径从叶子向根**冒泡**查找品类字段（subjectLock/negative），仿羽刃 ecCatalogResolveUp。
     * 如 "家装主材>厨房>厨房挂件>锅盖架" 依次试完整路径→去尾段→…→"家装主材"→ __default__ 全局默认。
     */
    private String ecResolveUp(String category, String field) {
        Map<String, Object> cat = ecCatalog();
        String c = category == null ? "" : category.replace('＞', '>').replace('›', '>').trim();
        while (!c.isBlank()) {
            Object node = cat.get(c);
            if (node instanceof Map<?, ?> m) {
                Object v = m.get(field);
                if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
            }
            int gt = c.lastIndexOf('>');
            if (gt < 0) break;
            c = c.substring(0, gt).trim();
        }
        // 回退全局默认 __default__
        Object def = cat.get("__default__");
        if (def instanceof Map<?, ?> m) {
            Object v = m.get(field);
            if (v != null) return String.valueOf(v);
        }
        return "";
    }

    /** 品类主体一致性约束（放生图 prompt 起首作最高优先级锚点）。找不到回退全局默认。 */
    public String ecSubjectLock(String category) { return ecResolveUp(category, "subjectLock"); }

    /** 品类禁止项（放生图 prompt 末尾）。找不到回退全局默认。 */
    public String ecNegative(String category) { return ecResolveUp(category, "negative"); }

    /** 拆解结构参考图（classpath assets/explode-ref.jpg），供拆解类模板锁定内部结构。 */
    public File explodeRefFile() {
        try {
            File f = new File(appProperties.getPaths().getUserDataDir(), "sku-base/explode-ref.jpg");
            if (f.isFile()) return f;
            try (java.io.InputStream is = getClass().getClassLoader().getResourceAsStream("assets/explode-ref.jpg")) {
                if (is == null) return null;
                f.getParentFile().mkdirs(); Files.write(f.toPath(), is.readAllBytes());
            }
            return f;
        } catch (Exception e) { log.warn("拆解参考图落地失败: {}", e.getMessage()); return null; }
    }

    /** 基准图缓存文件路径：userDataDir/sku-base/<templateId>.jpg */
    public File baseCacheFile(String templateId) {
        return new File(appProperties.getPaths().getUserDataDir(), "sku-base/" + templateId + ".jpg");
    }

    /** 把生成的基准图写入缓存目录，供同模板后续 SKU 复用。 */
    public void saveBaseCache(String templateId, File img) {
        try {
            File dst = baseCacheFile(templateId);
            dst.getParentFile().mkdirs();
            Files.copy(img.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { log.warn("基准图缓存失败({}): {}", templateId, e.getMessage()); }
    }
}

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

    /**
     * 主图构图库选择（0a 防同质化）：按品类从 main-compositions.json 取 N 套【不重复】构图 prompt。
     *  · 库为主 + 白底图锁主体：只提供版式/主体关系/文字区位/色彩/视效，产品本体由白底图 ref 决定。
     *  · 按叶子类目冒泡匹配 key（末段==leaf，或整名相等，兼容 category 存全路径/裸叶子）。
     *  · 锅盖架分落地/吸盘两子组：skuHint 含 吸盘/壁挂/墙/免钉 → 吸盘组，否则 落地组。
     *  · 花洒喷头：默认排除 focus=滤芯 的构图；hasFilter=true（该 SKU 带滤芯配件）才纳入滤芯构图。
     *  N 大于库容量时循环取（仍打乱顺序，尽量错开）。命中不到返回空列表（调用方回退 AI 现编）。
     * @return 每套构图的完整 prompt 列表（长度尽量=count；空=未命中，走原 AI 分析）。
     */
    @SuppressWarnings("unchecked")
    public List<String> pickMainCompositions(String category, String skuHint, int count, boolean hasFilter) {
        if (category == null || category.isBlank() || count <= 0) return List.of();
        try {
            Map<String, Object> root = om.readValue(PromptLoader.load("prompt/main-compositions.json"), Map.class);
            Map<String, Object> byCat = (Map<String, Object>) root.getOrDefault("byCategory", Map.of());
            String leaf = category.replace('＞', '>').replace('›', '>');
            leaf = leaf.contains(">") ? leaf.substring(leaf.lastIndexOf('>') + 1).trim() : leaf.trim();
            Object node = null;
            for (Map.Entry<String, Object> e : byCat.entrySet()) {
                String k = e.getKey();
                if (k.equals(leaf)) { node = e.getValue(); break; }
            }
            if (node == null) return List.of();
            // 分级版(_v=2)：品类节点两种形态——
            //   ① 单节点 {_template, rows}（支架挂钩/置物架/卫浴置物架）。
            //   ② 分组 {落地:{_template,rows}, 吸盘:{...}}（锅盖架，按 skuHint 挑组）。
            //   花洒喷头仍是旧格式 List（每条带 baked prompt + focus 标记），按老路走。
            // template 为空表示旧格式条目(自带 prompt)；非空则需 base=merge(_template,row) 现拼(排除禁止)。
            List<Map<String, Object>> entries;
            Map<String, Object> template = null;
            if (node instanceof Map) {
                Map<String, Object> m0 = (Map<String, Object>) node;
                Map<String, Object> leafNode;
                if (m0.containsKey("rows")) {
                    leafNode = m0;   // ① 单节点
                } else {
                    // ② 分组：skuHint 含 吸盘/壁挂/墙/免钉 → 吸盘，否则 落地(或首个可用组)
                    String hint = skuHint == null ? "" : skuHint;
                    String group = (hint.contains("吸盘") || hint.contains("壁挂") || hint.contains("墙") || hint.contains("免钉")) ? "吸盘" : "落地";
                    Object g = m0.get(group);
                    if (!(g instanceof Map) || !((Map<String, Object>) g).containsKey("rows")) {
                        g = m0.containsKey("落地") ? m0.get("落地") : m0.values().iterator().next();
                    }
                    if (!(g instanceof Map)) return List.of();
                    leafNode = (Map<String, Object>) g;
                }
                template = (Map<String, Object>) leafNode.getOrDefault("_template", Map.of());
                entries = (List<Map<String, Object>>) leafNode.getOrDefault("rows", List.of());
            } else if (node instanceof List) {
                // 花洒喷头等单池：不带滤芯配件时剔除 focus=滤芯 的构图，避免不带滤芯的花洒画滤芯特写
                entries = new java.util.ArrayList<>();
                for (Object o : (List<Object>) node) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) o;
                    if (!hasFilter && "滤芯".equals(String.valueOf(m.get("focus")))) continue;
                    entries.add(m);
                }
            } else return List.of();
            if (entries.isEmpty()) return List.of();
            // 打乱后取 count 套(库母题多于张数,天然不重复;不足则循环补,靠库容量而非微扰错开)。
            //   分级重构(07.28)后每套 base 由 _template+row 现拼,构图/文字逐行不同已足够差异化,
            //   删掉原微扰指令(锅盖架落地16套/吸盘15套已超常规张数,循环命中概率极低,微扰是伪需求)。
            List<Map<String, Object>> pool = new java.util.ArrayList<>(entries);
            java.util.Collections.shuffle(pool);
            List<String> out = new java.util.ArrayList<>();
            int poolSize = pool.size();
            for (int i = 0; i < count; i++) {
                Map<String, Object> e = pool.get(i % poolSize);
                String p;
                if (template == null) {
                    // 旧格式(花洒喷头):直接用 baked prompt。
                    p = String.valueOf(e.getOrDefault("prompt", "")).trim();
                } else {
                    // 分级格式:base = 固定任务开头 + merge(_template, row) 各字段拼接(禁止交 ec-catalog,不进此处)。
                    p = assembleComposition(template, e);
                }
                if (p == null || p.isBlank()) continue;
                out.add(p);
            }
            return out;
        } catch (Exception e) {
            log.warn("主图构图库选择失败(category={}): {}", category, e.getMessage());
            return List.of();
        }
    }

    // 分级格式固定任务开头(原每行 xlsx"任务"列都是同一句,收口成常量,不再逐行存)。
    private static final String COMP_TASK =
            "制作一张1:1正方形电商作图参考图，严格复刻参考版式比例、主体数量、镜头角度、人物动作、信息模块位置和视觉动势。产品仅作为可替换占位主体，不描述其具体内部结构，不分析或复刻背景内容。";

    /** 分级构图拼装:任务开头 + merge(_template, row) 按固定字段序拼句。禁止不在此(交 ec-catalog negative,消重复)。 */
    private String assembleComposition(Map<String, Object> template, Map<String, Object> row) {
        java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(template);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if ("id".equals(e.getKey())) continue;
            merged.put(e.getKey(), e.getValue());   // 行覆盖模板
        }
        // 字段拼接序(与旧 baked prompt 一致,禁止/id 排除;视觉记忆点接在风格前)
        String[] order = {"构图", "核心主体", "人物元素", "文字", "放大模块", "对比模块", "视觉记忆点", "风格与画质"};
        StringBuilder sb = new StringBuilder(COMP_TASK);
        for (String k : order) {
            Object v = merged.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isBlank()) sb.append(' ').append(s);
        }
        return sb.toString();
    }

    /**
     * 主图卖点库选择（0a 防同质化·卖点侧）：按品类从 main-sellpoints.json 取 N 个【跨分组不重复】卖点词，
     * 每张主图分配一个不同维度的卖点做画面文案主标题（安装/承重/收纳/材质…各取，防每张卖点雷同）。
     *  · 保留分组层级：先按组打散，各组轮流取一个，取满 N 个（组数<N 时组内再取，仍跨组优先）。
     *  · 架类子品类专属段（锅盖架/刀架/挂钩…）：skuHint/leaf 命中子品类时，把该子品类专属卖点并入候选池优先。
     *  · 冒泡匹配 key（末段==leaf 或整名相等），与 pickMainCompositions 同款品类解析。
     *  命中不到返回空列表（调用方回退：让 GPT 现编或按 sellingPoints）。
     * @return N 个卖点短词（长度尽量=count；空=未命中）。
     */
    @SuppressWarnings("unchecked")
    public List<String> pickSellPoints(String category, String skuHint, int count) {
        if (category == null || category.isBlank() || count <= 0) return List.of();
        try {
            Map<String, Object> node = sellPointNode(category);
            if (node == null) return List.of();
            // 候选池：各分组的词，按组分桶（保留分组层级，抽时跨组轮询）
            List<List<String>> buckets = new java.util.ArrayList<>();
            Object groups = node.get("groups");
            if (groups instanceof Map) {
                for (Object v : ((Map<String, Object>) groups).values())
                    if (v instanceof List && !((List<?>) v).isEmpty())
                        buckets.add(new java.util.ArrayList<>((List<String>) v));
            }
            // 架类子品类专属段：skuHint 命中某子品类名 → 该段作为额外一个高优先桶（插到最前，先被取）
            Object sub = node.get("sub");
            if (sub instanceof Map && skuHint != null && !skuHint.isBlank()) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) sub).entrySet()) {
                    if (skuHint.contains(e.getKey()) && e.getValue() instanceof List && !((List<?>) e.getValue()).isEmpty())
                        buckets.add(0, new java.util.ArrayList<>((List<String>) e.getValue()));
                }
            }
            if (buckets.isEmpty()) return List.of();
            for (List<String> b : buckets) java.util.Collections.shuffle(b);
            java.util.Collections.shuffle(buckets);
            // 跨组轮询取词：一轮各组取一个，直到取满 count 或池空
            List<String> out = new java.util.ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            int[] ptr = new int[buckets.size()];
            boolean progressed = true;
            while (out.size() < count && progressed) {
                progressed = false;
                for (int i = 0; i < buckets.size() && out.size() < count; i++) {
                    List<String> b = buckets.get(i);
                    if (ptr[i] < b.size()) {
                        String w = b.get(ptr[i]++);
                        progressed = true;
                        if (seen.add(w)) out.add(w);
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("主图卖点库选择失败(category={}): {}", category, e.getMessage());
            return List.of();
        }
    }

    /**
     * 首图高转化主标题（0a 卖点侧）：从品类 hiConv 整句池随机取一句（运营精选的主图主标题，如"强劲增压畅快洗"）。
     * 命中不到返回空串（调用方回退 pickSellPoints 首个或 GPT 现编）。
     */
    @SuppressWarnings("unchecked")
    public String pickHiConvHeadline(String category) {
        if (category == null || category.isBlank()) return "";
        try {
            Map<String, Object> node = sellPointNode(category);
            if (node == null) return "";
            Object hi = node.get("hiConv");
            if (hi instanceof List && !((List<?>) hi).isEmpty()) {
                List<String> pool = (List<String>) hi;
                return pool.get((int) (Math.random() * pool.size()));
            }
        } catch (Exception e) { log.warn("首图高转化句选择失败(category={}): {}", category, e.getMessage()); }
        return "";
    }

    /** 卖点库品类节点解析（冒泡匹配叶子类目 key），供 pickSellPoints/pickHiConvHeadline 共用。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> sellPointNode(String category) throws Exception {
        Map<String, Object> root = om.readValue(PromptLoader.load("prompt/main-sellpoints.json"), Map.class);
        Map<String, Object> byCat = (Map<String, Object>) root.getOrDefault("byCategory", Map.of());
        String leaf = category.replace('＞', '>').replace('›', '>');
        leaf = leaf.contains(">") ? leaf.substring(leaf.lastIndexOf('>') + 1).trim() : leaf.trim();
        // 1) 叶子精确命中
        for (Map.Entry<String, Object> e : byCat.entrySet())
            if (e.getKey().equals(leaf) && e.getValue() instanceof Map) return (Map<String, Object>) e.getValue();
        // 2) 架类兜底：叶子含"架/挂钩"等 → 归入"架类"库（覆盖锅盖架/刀架/置物架等所有末级架类）
        boolean shelfLike = leaf.contains("架") || leaf.contains("挂钩") || leaf.contains("挂件");
        if (shelfLike && byCat.get("架类") instanceof Map) return (Map<String, Object>) byCat.get("架类");
        // 3) 花洒兜底
        if ((leaf.contains("花洒") || leaf.contains("喷头") || leaf.contains("淋浴")) && byCat.get("花洒喷头") instanceof Map)
            return (Map<String, Object>) byCat.get("花洒喷头");
        return null;
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

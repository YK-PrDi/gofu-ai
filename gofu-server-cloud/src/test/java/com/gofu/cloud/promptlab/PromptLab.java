package com.gofu.cloud.promptlab;

import com.gofu.cloud.config.AppProperties;
import com.gofu.cloud.config.LyImageProperties;
import com.gofu.cloud.controller.FlowController;
import com.gofu.cloud.service.agent.GptImageAgent;
import com.gofu.cloud.service.lyimage.ImageGenService;
import com.gofu.cloud.service.lyimage.PromptTemplateService;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线 prompt 台（生图质量攻关用）。<b>不进生产 jar</b>（test 源集）。
 *
 * <h3>为什么要这个东西</h3>
 * 08.02 那轮实测坐实：prompt 里"谁排在前面"比"措辞多强硬"更决定出图结果——第一轮以为在构图文字
 * 后面追加一句"产品描述一律作废"就行，实测完全无效，A/B 才定位到真因是**位置**。结论：这类改动
 * <b>只读代码看不出来，必须实跑对比</b>。但当时那个 A/B 脚本没留下来（全仓/未跟踪/stash 都搜过，
 * 只剩 shots/ab-*.jpg 四张证据图），于是每验一次 prompt 改动都要：重启云端 5020 → 前端走完整链路
 * → 等 80~150s/张 → 烧额度。这个循环太贵，直接限制了攻关能迭代几轮。
 *
 * <h3>铁律：复用生产代码，绝不重写一份 prompt 组装</h3>
 * 开品模式前后端各写一份 buildGenPrompt，两份已经漂移（第三张图说明的位置一个在 tail 前一个在后）
 * ——那就是"重写一份"的下场。所以本类一律反射调生产方法 / 直接 new 生产类，自己不拼任何 prompt 文案。
 *
 * <h3>用法</h3>
 * <pre>
 * # 零成本：只打印最终 prompt，不调 API
 * mvn -o -pl gofu-server-cloud test-compile
 * mvn -o -pl gofu-server-cloud exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=com.gofu.cloud.promptlab.PromptLab \
 *     -Dexec.args="--cat 家装主材&gt;厨房&gt;厨房挂件&gt;锅盖架 --sku 小熊落地锅盖架 --kind main --n 3"
 *
 * # 真出图（受额度约束，单次运行硬闸 ≤5 张）
 * ... -Dexec.args="--cat ... --white D:\path\白底图.jpg --kind main --n 2 --gen --variant new"
 * </pre>
 * 若 exec 插件不可用，直接在 IDE 里跑 main()，或用 --cp 方式 java -cp 起。
 *
 * <h3>为什么改完 prompt 不用重启 5020</h3>
 * 生产读 prompt 走 {@code PromptLoader.load}（classpath，不缓存），本台直读 target/classes 下的同一份。
 * 所以"改 prompt → 看结果"从分钟级压到秒级。<b>例外</b>：antiprice-templates.json 走 loadVersioned 的
 * userDataDir 缓存 + _v 版本门，改它要把 _v +1 才生效。
 */
public final class PromptLab {

    /** 单次运行真出图的硬上限（用户授权：一次测试最多 5 张）。超了直接拒绝启动，不靠人记。 */
    private static final int MAX_GEN_PER_RUN = 5;

    private PromptLab() {}

    // ── 入参 ──────────────────────────────────────────────────────────────

    private record Args(String category, String skuHint, String kind, int n, String whitePath,
                        boolean gen, String variant, boolean hasFilter, String contextId) {}

    private static Args parse(String[] argv) {
        Map<String, String> kv = new LinkedHashMap<>();
        List<String> flags = new ArrayList<>();
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--")) continue;
            String key = a.substring(2);
            if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                kv.put(key, argv[++i]);
            } else {
                flags.add(key);
            }
        }
        return new Args(
                kv.getOrDefault("cat", ""),
                kv.getOrDefault("sku", ""),
                kv.getOrDefault("kind", "main"),
                Integer.parseInt(kv.getOrDefault("n", "3")),
                kv.get("white"),
                flags.contains("gen"),
                kv.getOrDefault("variant", "new"),
                flags.contains("hasFilter"),
                kv.get("ctx"));
    }

    private static void usage() {
        System.out.println("""
                离线 prompt 台。用法：
                  --cat  <品类>        必填。可传全路径「家装主材>厨房>厨房挂件>锅盖架」或裸叶子「锅盖架」
                  --sku  <主件名>      可选。用于构图库挑子组(如含"吸盘"→吸盘组)、卖点候选过滤
                  --kind main|detail   默认 main。detail=详情图 prompt
                  --n    <张数>        默认 3
                  --white <白底图路径>  --gen 时必填；不出图时可省(只影响 refs 打印)
                  --hasFilter          花洒专用：该 SKU 带滤芯配件(否则库会剔除 focus=滤芯 的构图)
                  --ctx  <contextId>   可选。传了会走跨次去重(同一商品重生尽量避开上次抽过的构图)
                  --gen                真调 /v1/images/edits 出图。不传=零成本只打印
                  --variant old|new|both
                                       new(默认)=当前代码 / old=还原成围栏前的裸注入 /
                                       both=同一构图同时出 old 与 new 两张，**A/B 应当用这个**——
                                       分两次跑会各自抽到不同构图条目和不同卖点，比出来的差异不是围栏造成的
                单次 --gen 出图总数最多 %d 张（both 时 = --n × 2）。""".formatted(MAX_GEN_PER_RUN));
    }

    // ── main ─────────────────────────────────────────────────────────────

    public static void main(String[] argv) throws Exception {
        if (argv.length == 0) { usage(); return; }
        Args args = parse(argv);
        if (args.category().isBlank()) { usage(); throw new IllegalArgumentException("--cat 必填"); }
        // variant=both 时一张构图要出两张图（old+new），闸门按**实际出图张数**算，不是按 --n。
        int genShots = args.gen() ? args.n() * ("both".equals(args.variant()) ? 2 : 1) : 0;
        if (genShots > MAX_GEN_PER_RUN) {
            throw new IllegalArgumentException("--gen 单次最多 " + MAX_GEN_PER_RUN + " 张（授权上限），"
                    + "本次将出 " + genShots + " 张（--n " + args.n()
                    + ("both".equals(args.variant()) ? " × old/new 两个变体" : "") + "）。拒绝启动。");
        }
        if (args.gen() && (args.whitePath() == null || !new File(args.whitePath()).isFile())) {
            throw new IllegalArgumentException("--gen 需要 --white 指向一张真实存在的白底图，当前: " + args.whitePath());
        }

        LocalConfig cfg = LocalConfig.load();
        PromptTemplateService templateService = newTemplateService(cfg);

        if (!List.of("old", "new", "both").contains(args.variant())) {
            throw new IllegalArgumentException("--variant 只支持 old / new / both，当前: " + args.variant());
        }
        List<String> prompts = switch (args.kind()) {
            case "main"   -> buildMainPrompts(args, templateService);
            case "detail" -> buildDetailPrompts(args);
            default -> throw new IllegalArgumentException("--kind 只支持 main / detail（SKU 图走生产日志，见类注释）");
        };

        if (!args.gen()) {
            System.out.println("\n[提示] 未加 --gen，本次零成本、未调用任何 API。");
            return;
        }
        runGen(args, cfg, prompts);
    }

    // ── 主图：反射调生产的 buildSeriesPrompt ────────────────────────────────

    private static List<String> buildMainPrompts(Args args, PromptTemplateService templateService) throws Exception {
        boolean structLock = gateOf("isStructuralRigidCategory", args.category());
        boolean isShower = gateOf("isShowerCategory", args.category());

        List<PromptTemplateService.CompositionPick> picks = templateService.pickMainCompositionsDetailed(
                args.category(), args.skuHint(), args.n(), args.hasFilter(), args.contextId());
        boolean fromLib = !picks.isEmpty();

        String subjectLock = templateService.ecSubjectLock(args.category());
        String negative = templateService.ecNegative(args.category());

        System.out.printf("%n[ROUTE ] main / fromLib=%s / structLock=%s / isShower=%s%n",
                fromLib, structLock, isShower);
        if (fromLib) {
            System.out.printf("[LIB   ] 构图库命中 %d 套（品类=%s, skuHint=%s, hasFilter=%s）%n",
                    picks.size(), args.category(), args.skuHint(), args.hasFilter());
        } else {
            System.out.printf("[LIB   ] 构图库未命中 → 生产会回退 GPT 现编(analyzeCustomImagePrompts)。%n"
                    + "         本台不调 LLM，改用占位段代替现编产出——**此时打印的不是生产真 prompt**，%n"
                    + "         只能验段序骨架。要验现编分支请看真实 run 的 cloud.log。%n");
        }
        System.out.printf("[SUBJ  ] subjectLock=%d字  negative=%d字%n", subjectLock.length(), negative.length());
        System.out.printf("[REFS  ] #0=白底图%s（第2~N张生产另有 #1=首图，双锚定）%n",
                args.whitePath() == null ? "(未传 --white)" : "=" + args.whitePath());

        // seriesPlan：生产来自 GPT 总分析。本台不调 LLM，用可识别的占位，避免误认为是真产出。
        String seriesPlan = fromLib ? "" : "（PromptLab 占位·非真实 GPT 总分析）";

        Method build = FlowController.class.getDeclaredMethod("buildSeriesPrompt",
                String.class, int.class, int.class, String.class, String.class, String.class,
                String.class, boolean.class, boolean.class, boolean.class, boolean.class, List.class);
        build.setAccessible(true);
        FlowController fc = newFlowController();

        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.n(); i++) {
            String base = i < picks.size() ? picks.get(i).prompt()
                    : "（PromptLab 占位·生产此处为 GPT 现编的第 " + (i + 1) + " 段构图）";
            List<String> tags = i < picks.size() ? picks.get(i).tags() : List.of();
            List<String> sellCands = fromLib
                    ? templateService.pickSellPointCandidates(args.category(), args.skuHint(), tags, 3)
                    : List.of();
            if (fromLib && i == 0) {
                String hi = templateService.pickHiConvHeadline(args.category());
                if (hi != null && !hi.isBlank()) sellCands = List.of(hi);
            }

            String prompt = (String) build.invoke(fc, base, i + 1, args.n(), seriesPlan,
                    subjectLock, negative, null, true, structLock, isShower, fromLib, sellCands);

            // variant=both 时**不在这里**分叉：old 侧由 runGen 从这同一串派生（toOldVariant 是纯文本变换）。
            // 这是 A/B 有效性的关键——pickMainCompositionsDetailed 会打乱池子、pickSellPointCandidates 也是随机的，
            // 若分两次运行各自取 old/new，两边拿到的是**不同的构图条目和不同的卖点**，比出来的差异根本不是围栏造成的。
            if ("old".equals(args.variant())) prompt = toOldVariant(prompt, base);
            prompt = enforceNoIntersection(prompt);

            System.out.printf("%n[第 %d/%d 张] tags=%s 卖点候选=%s%n", i + 1, args.n(), tags, sellCands);
            dump(prompt, base);
            out.add(prompt);
            libSegs.add(base);
        }
        return out;
    }

    // ── 详情图：反射调生产的 buildDetailPrompt ──────────────────────────────

    private static List<String> buildDetailPrompts(Args args) throws Exception {
        Method m = FlowController.class.getDeclaredMethod("buildDetailPrompt",
                com.gofu.shared.context.ProductContext.class, int.class);
        m.setAccessible(true);
        FlowController fc = newFlowController();
        System.out.printf("%n[ROUTE ] detail / 9:16 / ref=base=对应主图（ctx 参数生产里未被使用，只用 idx 排序号）%n");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.n(); i++) {
            String prompt = enforceNoIntersection((String) m.invoke(fc, null, i));
            System.out.printf("%n[第 %d/%d 张详情]%n", i + 1, args.n());
            dump(prompt, null);
            out.add(prompt);
            libSegs.add("");   // 详情图不走构图库，无可还原的裸注入段（--variant both 对 detail 无意义）
        }
        return out;
    }

    // ── old 变体：把当前代码的作废围栏还原成 08.04 之前的裸注入 ────────────────

    /**
     * A/B 的 old 侧。当前代码给构图库文字套了【版式方案·只借版式·产品信息一律作废】围栏，
     * 这里按标记把围栏摘掉、还原成旧的【第 N 张方案】裸注入，以便同一次运行内出可比的两组图。
     * 摘不掉（代码已变/标记改名）就直接报错，不静默返回一个"其实是 new"的 prompt——
     * 那会让 A/B 结论完全失真。
     */
    private static String toOldVariant(String prompt, String base) {
        int fenceAt = prompt.indexOf(FlowController.LIB_FENCE_MARK);
        if (fenceAt < 0) {
            throw new IllegalStateException("--variant old 失败：prompt 里找不到围栏标记「"
                    + FlowController.LIB_FENCE_MARK + "」。可能代码已改或本次 fromLib=false。"
                    + "不返回未还原的 prompt，避免 A/B 拿两组相同 prompt 得出假结论。");
        }
        int openAt = prompt.indexOf(FlowController.LIB_FENCE_OPEN, fenceAt);
        int closeAt = prompt.indexOf(FlowController.LIB_FENCE_CLOSE, fenceAt);
        if (openAt < 0 || closeAt < 0) {
            throw new IllegalStateException("--variant old 失败：围栏分隔线不完整，无法还原旧写法。");
        }
        String head = prompt.substring(0, fenceAt);
        String tail = prompt.substring(closeAt + FlowController.LIB_FENCE_CLOSE.length());
        return head + "【本张方案】\n" + base + "\n" + tail;
    }

    // ── 打印：段边界 + 字符偏移 + 别款商品实体词标注 ─────────────────────────

    /** 生产里每个 prompt 都会被 enforceNoIntersectionPrompt 追加这段；漏了就是在验一个生产不存在的 prompt。 */
    private static String enforceNoIntersection(String prompt) {
        String base = prompt == null ? "" : prompt.trim();
        if (base.contains("禁止穿模")) return base;
        return base + "\n\n" + NO_INTERSECTION;
    }

    /** 与 ImageGenerationService.NO_INTERSECTION_PROMPT 同文（该常量 private，此处按值对齐）。 */
    private static final String NO_INTERSECTION = """
            【最高优先级·禁止穿模】
            画面中任何产品、人体、手指、衣袖、头发、道具、墙面、桌面、玻璃、置物架、支架、线缆、背景结构之间都不得互相穿透、嵌入、融合或共享边界。
            产品必须有真实接触面、支撑点、遮挡关系和接触阴影；手指只能自然握持或触碰产品表面，不能穿过产品孔洞或外壳。
            产品不能半截插入桌面、墙面、背景、支架或其他物体；所有连接、接触、阴影、透视和前后层级必须物理合理。
            """.trim();

    private static void dump(String prompt, String libSeg) throws Exception {
        System.out.printf("[PROMPT 共 %d 字]%n", prompt.length());
        // 按【…】小节标题切，打印每节起始偏移——位置是本轮攻关的主要变量，得能一眼量出来。
        // 只认**行首**的【…】：正文里还有很多嵌在句中的标记（如 TEXT_RENDER_INSTRUCTION 里的
        // "请把本方案【画面文案】中的…"、卖点候选词本身也用【】包），全当小节头会把偏移表切碎、反而看不清位置。
        java.util.regex.Matcher mt = java.util.regex.Pattern
                .compile("(?m)^【[^】\\n]{2,40}】").matcher(prompt);
        List<int[]> marks = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (mt.find()) { marks.add(new int[]{mt.start()}); titles.add(mt.group()); }
        for (int i = 0; i < marks.size(); i++) {
            int at = marks.get(i)[0];
            int end = i + 1 < marks.size() ? marks.get(i + 1)[0] : prompt.length();
            System.out.printf("  %04d  %-34s (%d字)%n", at, titles.get(i), end - at);
        }
        if (libSeg != null && !libSeg.isBlank()) {
            int at = prompt.indexOf(libSeg);
            List<String> hits = new ArrayList<>();
            for (String w : productWords()) if (libSeg.contains(w)) hits.add(w);
            System.out.printf("  构图库文字 @偏移 %s / %d字%n", at < 0 ? "(已被改写)" : String.valueOf(at), libSeg.length());
            if (hits.isEmpty()) {
                System.out.println("  ✓ 构图库文字里未检出别款商品实体词");
            } else {
                // 判"是否真被围栏包住"要看分隔线，不能只看标题串是否出现——
                // 下游【本图卖点】/【系列一致性】两节会**指代**该标题，old 变体里那些指代还在，
                // 只看 contains(MARK) 会把 old 组误判成"已围栏"，A/B 结论直接失真。
                boolean fenced = prompt.contains(FlowController.LIB_FENCE_OPEN)
                        && prompt.contains(FlowController.LIB_FENCE_CLOSE);
                System.out.printf("  %s 检出别款商品实体词 %d 个: %s%n",
                        fenced ? "○" : "⚠", hits.size(), String.join("/", hits));
                System.out.printf("  %s%n", fenced
                        ? "○ 已被 " + FlowController.LIB_FENCE_MARK + " 围栏包住（作废声明在前）"
                        : "⚠ 无作废隔离——模型会当成'这张图要画什么'来执行");
            }
        }
        System.out.println("  ── 全文 ──");
        System.out.println(indent(prompt));
    }

    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String ln : s.split("\n", -1)) sb.append("  | ").append(ln).append('\n');
        return sb.toString();
    }

    // ── --gen：复用 GptImageAgent 真出图 ──────────────────────────────────

    /** 每张 prompt 对应的构图库原文段（toOldVariant 还原旧写法时要把它填回裸注入的位置）。 */
    private static final List<String> libSegs = new ArrayList<>();

    private static void runGen(Args args, LocalConfig cfg, List<String> prompts) {
        AppProperties ap = new AppProperties();
        ap.getGptImage().setApiKeys(cfg.appGptImageKeys());
        ap.getGptImage().setBaseUrl(cfg.appGptImageBaseUrl());
        if (ap.getGptImage().getApiKeys().isEmpty()) {
            throw new IllegalStateException("application-local.yml 里 app.gpt-image.api-keys 为空，无法出图");
        }
        GptImageAgent agent = new GptImageAgent(ap);

        File shots = new File("shots");
        shots.mkdirs();
        String catLeaf = args.category().contains(">")
                ? args.category().substring(args.category().lastIndexOf('>') + 1) : args.category();

        // 一张构图 → 一到两个出图任务。both 时 old 侧由同一串 new prompt 文本变换而来，
        // 保证两边**同构图条目、同卖点候选**，唯一差异就是围栏——否则比的不是围栏而是运气。
        record Shot(int planIdx, String variant, String prompt) {}
        List<Shot> queue = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            if ("both".equals(args.variant())) {
                queue.add(new Shot(i, "new", prompts.get(i)));
                queue.add(new Shot(i, "old", enforceNoIntersection(toOldVariant(prompts.get(i), libSegs.get(i)))));
            } else {
                queue.add(new Shot(i, args.variant(), prompts.get(i)));
            }
        }

        System.out.printf("%n[GEN   ] 真调 /v1/images/edits：%d 张, variant=%s, quality=low, refs=[白底图]%n"
                + "         上游单张实测 80~150s；读超时不重试（同 host 换 key 只会把总等待乘以 key 数）%n",
                queue.size(), args.variant());
        int ok = 0;
        for (int i = 0; i < queue.size(); i++) {
            Shot s = queue.get(i);
            File out = new File(shots, String.format("lab-%s-%s-%s%d.jpg",
                    args.kind(), catLeaf, s.variant(), s.planIdx() + 1));
            long t0 = System.currentTimeMillis();
            try {
                boolean r = agent.generateMulti(s.prompt(), List.of(args.whitePath()),
                        args.whitePath(), out.getAbsolutePath(), "1:1", "low");
                long ms = System.currentTimeMillis() - t0;
                System.out.printf("  [%d/%d] %s  %.1fs  %s%n", i + 1, queue.size(),
                        r ? "OK  " : "FAIL", ms / 1000.0, out.getPath());
                if (r) ok++;
            } catch (java.io.UncheckedIOException ue) {
                System.out.printf("  [%d/%d] 上游读超时(不重试): %s%n", i + 1, queue.size(), ue.getMessage());
            } catch (Exception e) {
                System.out.printf("  [%d/%d] 异常: %s%n", i + 1, queue.size(), e);
            }
        }
        System.out.printf("[GEN   ] 完成 %d/%d 张 → shots/lab-*%n", ok, queue.size());
    }

    // ── 生产类实例化（无 Spring 容器） ────────────────────────────────────

    /**
     * FlowController 的品类闸门判定（isShowerCategory / isStructuralRigidCategory）是 package-private
     * static，跨包不可见。走反射而不是把生产可见性放宽——不为一个测试工具改生产 API 面。
     */
    private static boolean gateOf(String method, String category) throws Exception {
        Method m = FlowController.class.getDeclaredMethod(method, String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, category);
    }

    /** 同理：ImageGenService.PRODUCT_WORDS 是 package-private static final，跨包反射读。 */
    private static String[] productWords() throws Exception {
        java.lang.reflect.Field f = ImageGenService.class.getDeclaredField("PRODUCT_WORDS");
        f.setAccessible(true);
        return (String[]) f.get(null);
    }

    /** FlowController 只用来反射调纯函数（buildSeriesPrompt / buildDetailPrompt），依赖全传 null 不会被触达。 */
    private static FlowController newFlowController() throws Exception {
        Constructor<FlowController> c = FlowController.class.getDeclaredConstructors().length == 1
                ? (Constructor<FlowController>) FlowController.class.getDeclaredConstructors()[0] : null;
        if (c == null) throw new IllegalStateException("FlowController 构造器不唯一，PromptLab 需跟进");
        c.setAccessible(true);
        Object[] nulls = new Object[c.getParameterCount()];
        return c.newInstance(nulls);
    }

    private static PromptTemplateService newTemplateService(LocalConfig cfg) {
        LyImageProperties ly = new LyImageProperties();
        ly.getPaths().setUserDataDir(".");
        return new PromptTemplateService(ly);
    }

    // ── 极简 application-local.yml 读取（只取生图必需的几项） ────────────────

    /**
     * 不起 Spring 容器，只把 application-local.yml 里生图必需的几项读出来。
     * 用 snakeyaml（已在 spring-boot-starter 传递依赖里）。<b>只读 key，不打印任何 key 值。</b>
     */
    private record LocalConfig(List<String> appGptImageKeys, String appGptImageBaseUrl) {

        @SuppressWarnings("unchecked")
        static LocalConfig load() throws Exception {
            File f = new File("gofu-server-cloud/src/main/resources/application-local.yml");
            if (!f.isFile()) f = new File("src/main/resources/application-local.yml");
            if (!f.isFile()) {
                System.out.println("[CFG   ] 未找到 application-local.yml（不出图时无妨；--gen 会失败）");
                return new LocalConfig(List.of(), "https://api.linapi.net");
            }
            String yml = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = new org.yaml.snakeyaml.Yaml().load(yml);
            Map<String, Object> app = (Map<String, Object>) root.getOrDefault("app", Map.of());
            Map<String, Object> gi = (Map<String, Object>) app.getOrDefault("gpt-image", Map.of());
            List<String> keys = (List<String>) gi.getOrDefault("api-keys", List.of());
            String baseUrl = String.valueOf(gi.getOrDefault("base-url", "https://api.linapi.net"));
            System.out.printf("[CFG   ] app.gpt-image: %d 个 key, baseUrl=%s%n", keys.size(), baseUrl);
            return new LocalConfig(keys, baseUrl);
        }
    }
}

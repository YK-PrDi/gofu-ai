package com.gofu.cloud.service.lytext;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gofu.cloud.config.LyImageProperties;
import com.gofu.cloud.service.lyimage.ImageGenService;
import com.gofu.cloud.service.lyimage.PromptLoader;
import com.gofu.shared.context.AccPart;
import com.gofu.shared.context.SkuItem;
import com.gofu.shared.context.SkuPlan;
import com.gofu.shared.enums.SkuRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LY 文本 AI 能力（标题生成 + SKU 规划）。自 LY-Automation ListingService 迁入云端（M5a，ADR-002 延伸：AI 算力归云端）。
 *
 * <p>都是 prompt 工程 + 调 {@link ImageGenService#geminiText}（文本/多模态 LLM）。
 * 剥离了 Playwright/上新相关逻辑（那些在本地 gofu-client-local）。
 *
 * <p>M5c：generateSkuPlans 新增 sellingPoints 入参，实现"卖点→SKU规划反哺"（双轨打通核心）。
 */
@Service
public class LyTextService {

    private static final Logger log = LoggerFactory.getLogger(LyTextService.class);

    /** 花洒品类命名规范（增压/软管/滤芯/喷头卖点体系）。 */
    private static final String SHOWER_NAMING_SPEC =
        "【命名规范——必须带营销卖点词，参考爆款风格】\n"
        + "- 主件 specName：颜色/款式 + 营销卖点，如\"【雅黑色】过滤增压\"\"月光银-过滤净水\"\"枪灰304不锈钢旗舰款\"，不要只写裸色名\n"
        + "- **【防虚假宣传·硬性要求】同一批全部主件的功能卖点词必须完全统一**：只有颜色/款式名可以不同，功能卖点（增压/过滤/净水/亲肤等）必须是这一系列共有的真实功能，取同一组词。"
        + "严禁给个别颜色臆造它并不具备的独有功能——例如不能只给某个颜色写\"按摩\"而其它颜色没有；若不确定某功能是否全系列都有，则该功能词一律不写。\n"
        + "- 型号 specName：用营销化的配件描述，如\"增压亲肤单喷头\"\"喷头+不锈钢支架\"\"喷头+1.5米防爆软管\"\"过滤喷头+5个滤芯\"，不要只写\"+支架\"这种干巴巴的简写\n"
        + "- **【硬性要求】型号名必须逐一列清该型号实际包含的每个配件，禁止用「全配」「全套」「套装」笼统词概括**；"
        + "如含 底座+软管+5滤芯 要写「喷头+稳固银底座+1.5米防爆软管+5支滤芯」，绝不写「全配+5支滤芯」。\n"
        + "- 批量件型号 specName 必须体现数量和价值点，如\"过滤喷头+10支滤芯【可用1年】\"\"+5支滤芯【半年装】\"\n"
        + "- 卖点词库（务必融入）：增压、亲肤、过滤、一键止水、免安装一体发货、加厚硅胶防滑、稳固不晃、防爆\n"
        + "- 每个 specName 控制在 6-15 字，既有卖点又简洁；绝不要把\"手喷袋子\"\"好评卡\"\"胶纸\"等包材写进任何 specName\n";

    /** 通用/架类命名规范（禁止臆造层数/规格档位，型号只描述真实配件组合）。 */
    private static final String GENERIC_NAMING_SPEC =
        "【命名规范——本品类不是花洒，严禁套用花洒话术(增压/亲肤/软管/滤芯/喷头/一体发货等一律不准出现)】\n"
        + "- 主件 specName：**必须完整保留主件清单里名称包含的规格属性(层数/尺寸/容量/材质)**，只在其后补颜色和通用卖点。"
        + "例：主件名是\"三层锅盖架\"→specName 必须含\"三层\"(如\"三层大容量锅盖架\")，绝对不能丢掉或改成别的层数。\n"
        + "- **【防虚假宣传·硬性要求】同一批全部主件的功能卖点词必须完全统一**：只有颜色/款式/规格可以不同，功能卖点必须是这一系列共有的真实功能，取同一组词，严禁臆造。\n"
        + "- **【最高优先级·绝对禁止】型号(models)维度 specName 里禁止出现任何层数/尺寸/规格档位词**——"
        + "包括但不限于\"单层\"\"双层\"\"三层\"\"X层架\"\"基础款\"\"旗舰款\"\"大号\"\"小号\"等。"
        + "层数/尺寸是主件的固有属性(已在主件 specName 里)，型号维度只表示\"在主件基础上加装了哪些配件\"，两者是正交的两个维度。"
        + "违反例(严禁)：主件是三层锅盖架，型号却写\"单层架+挂钩\"→ 顾客会看到\"三层锅盖架 单层架+挂钩\"自相矛盾。\n"
        + "- 型号 specName 正确写法：单品型号写\"标准款\"或\"单品\"(components为空)；带配件的写\"+挂钩\"\"+沥水盘\"\"+3个挂篮\"\"+隔板\"，"
        + "即【加号开头 + 该型号实际加装的配件及数量】，逐一列清，禁止用「全配」「全套」「套装」概括。\n"
        + "- 批量件型号 specName 体现数量，如\"+10个S型挂钩\"\"+5个沥水篮\"\n"
        + "- 卖点词库(按品类真实功能选用)：稳固承重、免打孔、加厚防锈、大容量、多层收纳、防滑、易安装\n"
        + "- **【最高优先级·配件真实性】components 只能取自下方【共享配件清单】【批量件清单】里真实列出的编码；"
        + "若清单为空或写着「无独立配件/无批量件」，则所有型号 components 一律留空(models 只出\"单品/标准款\")，"
        + "绝对禁止臆造配件、更禁止套用花洒配件(软管/滤芯/喷头等本品类没有的东西)**\n"
        + "- 每个 specName 控制在 6-15 字；绝不要把\"包装袋\"\"好评卡\"\"胶纸\"等包材写进任何 specName\n";

    /** 花洒专属配件关键词：非花洒品类喂 LLM 前从配件/批量件清单剔除，防误配进成本。 */
    private static final String[] SHOWER_ONLY_ACC = {"软管", "水管", "滤芯", "喷头", "手喷", "花洒", "增压"};

    /** 逐行剔除含花洒专属配件关键词的清单行（保留其余行原样，含末尾换行）。 */
    private static String stripShowerOnlyAcc(String lines) {
        if (lines == null || lines.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String ln : lines.split("\n")) {
            if (ln.isBlank()) continue;
            boolean showerOnly = false;
            for (String kw : SHOWER_ONLY_ACC) if (ln.contains(kw)) { showerOnly = true; break; }
            if (!showerOnly) sb.append(ln).append("\n");
        }
        return sb.toString();
    }

    private final LyImageProperties props;
    private final ImageGenService imageGenService;
    // 宽松解析：LLM 常返回带尾逗号/单引号/注释/未转义控制符的 JSON，容错以免整批失败
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true);

    public LyTextService(LyImageProperties props, ImageGenService imageGenService) {
        this.props = props;
        this.imageGenService = imageGenService;
    }

    // ── 标题生成 ─────────────────────────────────────────────────────────

    /** 纯文本生成标题+款式名（无图）。 */
    public Map<String, Object> prepareWithAI(String productType, String productName,
                                             String brand, List<String> skuColors) {
        String brandStr = (brand != null && !brand.isBlank()) ? brand : "";
        String colorsStr = skuColors != null ? String.join("、", skuColors) : "";
        String prompt = String.format(
            "你是一个拼多多电商运营专家，请根据以下信息生成商品标题和SKU款式名。\n\n" +
            "商品类型：%s\n商品简称：%s\n品牌：%s\nSKU颜色：%s\n\n" +
            "要求：\n" +
            "1. 商品标题：30个汉字以内，包含品牌+核心卖点+颜色+材质，符合拼多多搜索习惯\n" +
            "2. SKU款式名：每个颜色对应一个款式名，格式与标题风格一致，15字以内\n\n" +
            "请严格按以下JSON格式返回，不要有其他内容：\n" +
            "{\"title\":\"商品标题\",\"skuNames\":{\"颜色1\":\"款式名1\",\"颜色2\":\"款式名2\"}}",
            productType, productName, brandStr, colorsStr);
        try {
            return parseJsonObject(imageGenService.geminiText(prompt, null));
        } catch (Exception e) {
            log.error("AI 生成标题失败: {}", e.getMessage(), e);
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("title", brandStr + productName + (colorsStr.isEmpty() ? "" : " " + colorsStr));
            Map<String, String> skuMap = new LinkedHashMap<>();
            if (skuColors != null) skuColors.forEach(c -> skuMap.put(c, brandStr + productName + c));
            fallback.put("skuNames", skuMap);
            fallback.put("error", e.getMessage());
            return fallback;
        }
    }

    /** 看图生成标题+款式名（多模态）。无图回落标题库版。 */
    public Map<String, Object> prepareWithVision(String category, String material, String brand,
                                                 List<String> skuNames, List<String> mainImgPaths) {
        String brandStr = (brand != null && !brand.isBlank()) ? brand : "";
        String materialStr = (material != null && !material.isBlank()) ? material : "";
        String skuStr = skuNames != null ? String.join("、", skuNames) : "";
        String catLeaf = leafOf(category);

        List<String> imgPaths = new ArrayList<>();
        if (mainImgPaths != null) imgPaths.addAll(mainImgPaths);
        if (imgPaths.isEmpty()) return prepareFromTitleLib(category, material, brand, skuNames);

        List<String> refTitles = titleRefByCategory(category);
        String refBlock = refTitles.isEmpty() ? "（无参考标题，按品类自行生成爆款风格）" : String.join("\n", refTitles);
        String brandStr2 = brandStr.isEmpty() ? "本店" : brandStr;
        String prompt = String.format(
            "你是拼多多电商运营专家。请仔细观察这些商品主图，识别商品外观、功能和卖点，" +
            "结合下面同品类爆款标题的关键词风格，生成商品标题和SKU款式名。\n\n" +
            "【同品类爆款标题参考（学其关键词和结构，不要照抄）】\n%s\n\n" +
            "商品品类：%s\n材质：%s\n品牌：%s\nSKU列表：%s\n\n" +
            "要求：\n1. 商品标题【第一个词必须是品牌「%s」】，紧跟品类和卖点关键词；\n" +
            "2. 标题长度【严格 27-30 个汉字】（含品牌），不足则补图中观察到的卖点关键词；\n" +
            "3. 必须包含从图片观察到的核心卖点，符合拼多多搜索习惯；\n" +
            "4. 绝不要出现参考标题里别人的品牌商标名；\n" +
            "5. 同时为每个SKU生成一个15字以内款式名，风格与标题一致。\n\n" +
            "请严格按以下JSON格式返回，不要有其他内容：\n" +
            "{\"title\":\"商品标题\",\"skuNames\":{\"SKU1\":\"款式名1\",\"SKU2\":\"款式名2\"}}",
            refBlock, catLeaf, materialStr, brandStr, skuStr, brandStr2);
        try {
            return parseJsonObject(imageGenService.geminiText(prompt, imgPaths));
        } catch (Exception e) {
            log.error("看图生成标题失败: {}", e.getMessage(), e);
            Map<String, Object> fallback = prepareFromTitleLib(category, material, brand, skuNames);
            fallback.put("error", e.getMessage());
            return fallback;
        }
    }

    /** 参考标题库生成标题。 */
    public Map<String, Object> prepareFromTitleLib(String category, String material,
                                                   String brand, List<String> skuNames) {
        String brandStr = (brand != null && !brand.isBlank()) ? brand.trim() : "";
        String materialStr = (material != null && !material.isBlank()) ? material : "";
        String skuStr = skuNames != null ? String.join("、", skuNames) : "";
        String catLeaf = leafOf(category);
        List<String> refTitles = readTitleLib();
        try {
            String refBlock = refTitles.isEmpty()
                ? "（无参考标题，请根据品类自行生成同类爆款风格标题）" : String.join("\n", refTitles);
            String brandRule = brandStr.isEmpty()
                ? "本商品不指定品牌：生成的标题里【绝对不要】出现任何品牌名，只保留品类、卖点、用途等通用词。"
                : "本商品品牌为「" + brandStr + "」：标题开头放该品牌，并把参考标题里别人的品牌词全部替换掉。";
            String prompt =
                "你是拼多多电商运营专家。下面是同品类的爆款商品标题，供你参考其关键词、卖点和结构：\n" +
                refBlock + "\n\n请模仿上面标题的风格和关键词，为以下商品生成 1 条新的拼多多标题。\n" +
                "商品品类：" + catLeaf + "\n材质：" + materialStr + "\nSKU列表：" + skuStr + "\n\n" +
                "要求：\n1. 标题 30 个汉字以内，融合多条参考标题的卖点关键词，不要原样照抄任何一条。\n" +
                "2. " + brandRule + "\n3. 同时为每个 SKU 名生成一个 15 字以内的款式名，风格与标题一致。\n\n" +
                "请严格按以下JSON格式返回，不要有其他内容：\n" +
                "{\"title\":\"商品标题\",\"skuNames\":{\"SKU1\":\"款式名1\"}}";
            return parseJsonObject(imageGenService.geminiText(prompt, null));
        } catch (Exception e) {
            log.error("标题库生成标题失败: {}", e.getMessage(), e);
            Map<String, Object> fallback = prepareWithAI(catLeaf, catLeaf, brand, skuNames);
            fallback.put("error", e.getMessage());
            return fallback;
        }
    }

    // ── SKU 规划（M5c：接卖点反哺）────────────────────────────────────────

    /**
     * 生成多套 SKU 搭配方案。M5c 新增 sellingPoints——围绕卖点策划型号名（双轨打通核心）。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> generateSkuPlans(Map<String, Object> req) throws Exception {
        String category    = (String) req.getOrDefault("category", "");
        String productName = (String) req.getOrDefault("productName", "");
        String brand       = (String) req.getOrDefault("brand", "");
        String material    = (String) req.getOrDefault("material", "");
        int    planCount   = ((Number) req.getOrDefault("planCount", 3)).intValue();
        List<Map<String, Object>> skus = (List<Map<String, Object>>) req.getOrDefault("skus", List.of());
        // M5c：卖点（从 ProductContext.visual.sellingPoints 传入）
        List<String> sellingPoints = (List<String>) req.getOrDefault("sellingPoints", List.of());

        StringBuilder mainLines = new StringBuilder(), accLines = new StringBuilder(), batchLines = new StringBuilder();
        int mainCount = 0;
        for (Map<String, Object> s : skus) {
            String role = String.valueOf(s.getOrDefault("role", "main"));
            String line = s.getOrDefault("itemCode", "") + " | " + s.getOrDefault("name", "") + "\n";
            if ("accessory".equals(role)) accLines.append(line);
            else if ("batch".equals(role)) batchLines.append(line);
            else { mainLines.append(line); mainCount++; }
        }
        if (accLines.length() == 0) accLines.append("（无独立配件，可只用主件单独成 SKU）\n");
        if (batchLines.length() == 0) batchLines.append("（无批量件）\n");

        // M5c：卖点反哺段——非空则注入 prompt，让型号名/方案紧扣视觉流提取的核心卖点
        String sellingBlock = (sellingPoints == null || sellingPoints.isEmpty())
            ? ""
            : "\n【核心卖点（来自视觉流，务必围绕这些卖点策划型号名与方案）】" + String.join("、", sellingPoints) + "\n";

        // 07.08修：命名规范按品类分支。花洒用增压/软管/滤芯那套；架类/通用禁止臆造层数档位
        // （架类主件本身带层数=一层/二层/三层，型号名再编"单层款/三层款"会与主件冲突→款式对不上）。
        boolean isShower = category != null && (category.contains("花洒") || category.contains("淋浴"));
        String namingSpec = isShower ? SHOWER_NAMING_SPEC : GENERIC_NAMING_SPEC;

        // M19：非花洒品类误配花洒配件根治——从喂给 LLM 的配件/批量件清单里剔除花洒专属配件（软管/滤芯/喷头/手喷/花洒）。
        // 根因：ERP 单品池或规则可能混入花洒配件，LLM 会把它们配到锅盖架/挂钩等架类型号上，
        // 配件随 accParts 进 combo-cost 求和→非花洒品成本被误加。清单层拦掉，配不上就不进成本。
        if (!isShower) {
            int before = accLines.length() + batchLines.length();
            String cleanedAcc = stripShowerOnlyAcc(accLines.toString());
            String cleanedBatch = stripShowerOnlyAcc(batchLines.toString());
            accLines.setLength(0);   accLines.append(cleanedAcc);
            batchLines.setLength(0); batchLines.append(cleanedBatch);
            if (accLines.length() == 0) accLines.append("（无独立配件，可只用主件单独成 SKU）\n");
            if (batchLines.length() == 0) batchLines.append("（无批量件）\n");
            if (before != accLines.length() + batchLines.length())
                log.info("M19 非花洒品类[{}]已剔除花洒专属配件(软管/滤芯/喷头)，防误配进成本", category);
        }

        String prompt = String.format(
            "你是拼多多电商运营专家。用户已选定主件款式和共享配件（来自ERP）。\n"
            + "拼多多商品是【二维规格】：维度一=主件（颜色/款式，多个并排），维度二=型号（配件组合阶梯，所有主件共享同一组型号）。\n"
            + "【关键要求】请生成 %d 套不同的「搭配方案」，每套方案 = 一个完整商品，同时包含全部 %d 个主件 和一组共享型号。"
            + "主件数量不影响方案数量：要 %d 套就出 %d 套，绝不要按主件拆分相乘。\n\n"
            + "本阶段只做\"搭配 + 命名\"，不要定价。\n"
            + "%s"
            + "\n【商品品类】%s\n【商品简称】%s，品牌：%s，材质：%s\n"
            + "【主件清单（编码 | 名称）——作为维度一，全部并排】\n%s\n"
            + "【共享配件清单（编码 | 名称）——用于拼装维度二的型号】\n%s\n"
            + "【批量件清单（编码 | 名称）——可按不同数量打包成不同型号】\n%s\n"
            + "【方案结构】每套方案含两个维度：\n"
            + "- mainItems：全部主件，每个给 itemCode 和 specName（颜色/款式简名）\n"
            + "- models：一组型号（所有主件共享），每个给 specName 和 components。"
            + "components 只列【配件/批量件】编码及数量（不含主件本身）。型号按\"配件由少到多\"阶梯排列，第一个通常是\"单品\"（components 为空）。\n"
            + "【硬约束】components 里的 itemCode 只能取自【共享配件清单】和【批量件清单】，绝对禁止放入整支花洒/喷头主体/整机。\n"
            + "【多套方案差异化策略】\n"
            + "- 精简款：3-4个型号，主推单品和热门组合\n"
            + "- 全阶梯款：单品→+配件1→+配件2→+全配件，覆盖所有价格带\n"
            + "- 套餐款：突出高配组合（多配件/多滤芯打包），拉高客单价\n"
            + "- 数量档位测试款：同一主件配批量件的不同数量（1/3/5/10个装），铺多档跑需求数据\n"
            + "%s"
            + "【输出格式】严格按JSON，不要其他内容：\n"
            + "{\"plans\":[{\"planName\":\"方案名\",\"description\":\"30字内策略说明\","
            + "\"mainItems\":[{\"itemCode\":\"主件编码\",\"specName\":\"银色\"}],"
            + "\"models\":[{\"specName\":\"单品\",\"components\":[]},{\"specName\":\"+配件\",\"components\":[{\"itemCode\":\"配件编码\",\"qty\":1}]}]}]}\n"
            + "itemCode 必须用清单里的真实编码，不要编造。",
            planCount, mainCount, planCount, planCount, sellingBlock,
            category, productName, brand, material, mainLines, accLines, batchLines, namingSpec);

        return parseJsonObject(imageGenService.geminiText(prompt, null));
    }

    /**
     * 把 {@link #generateSkuPlans} 的 LLM 原始结果拍平成带结构的 {@link SkuPlan} 列表（M6 补链）。
     *
     * <p>LLM 产出是二维：每个 plan = mainItems[]（主件）× models[]（共享型号阶梯）。
     * 拼多多二维 SKU 展开 = 主件 × 型号 的笛卡尔积，每个组合是一个 {@link SkuItem}：
     * spec1=主件名、spec2=型号名、itemCode=`主件码+配件码*N`、accParts=型号的 components。
     *
     * <p>⚠️ 价格/成本此处留 0：LLM 规划阶段不定价（提示词明确"本阶段只做搭配+命名"），
     * 成本来自 ERP、售价靠预览页人工填。拍平只负责结构展开，不造价格数据。
     */
    @SuppressWarnings("unchecked")
    public List<SkuPlan> flattenPlans(Map<String, Object> llmResult) {
        List<SkuPlan> out = new ArrayList<>();
        if (llmResult == null) return out;
        Object rawPlans = llmResult.get("plans");
        if (!(rawPlans instanceof List)) return out;

        for (Object po : (List<Object>) rawPlans) {
            if (!(po instanceof Map)) continue;
            Map<String, Object> pm = (Map<String, Object>) po;

            SkuPlan plan = new SkuPlan();
            plan.setPlanName(String.valueOf(pm.getOrDefault("planName", "")));
            plan.setDescription(String.valueOf(pm.getOrDefault("description", "")));

            List<Map<String, Object>> mainItems = asMapList(pm.get("mainItems"));
            List<Map<String, Object>> models = asMapList(pm.get("models"));
            // 型号缺省时兜底一个"单品"，保证主件至少展开成一行
            if (models.isEmpty()) models = List.of(Map.of("specName", "单品", "components", List.of()));

            for (Map<String, Object> main : mainItems) {
                String mainCode = String.valueOf(main.getOrDefault("itemCode", ""));
                String mainSpec = String.valueOf(main.getOrDefault("specName", ""));
                for (Map<String, Object> model : models) {
                    plan.getItems().add(buildItem(mainCode, mainSpec, model));
                }
            }
            out.add(plan);
        }
        return out;
    }

    /** 组合一个 SKU 条目：主件 × 单个型号。itemCode 拼 `主件码+配件码*N`，accParts 结构化。 */
    @SuppressWarnings("unchecked")
    private SkuItem buildItem(String mainCode, String mainSpec, Map<String, Object> model) {
        String modelSpec = String.valueOf(model.getOrDefault("specName", ""));
        List<Map<String, Object>> comps = asMapList(model.get("components"));

        SkuItem item = new SkuItem();
        item.setName(mainSpec);
        item.setSkuDisplayName(mainSpec + (modelSpec.isBlank() ? "" : "-" + modelSpec));
        item.setSpec1(mainSpec);
        item.setSpec2(modelSpec);
        item.setRole(SkuRole.MAIN);

        StringBuilder code = new StringBuilder(mainCode);
        for (Map<String, Object> c : comps) {
            String ccode = String.valueOf(c.getOrDefault("itemCode", ""));
            if (ccode.isBlank()) continue;
            int qty = c.get("qty") instanceof Number n ? n.intValue() : 1;
            code.append("+").append(ccode).append("*").append(qty);
            item.getAccParts().add(new AccPart(ccode, qty));
        }
        item.setItemCode(code.toString());
        // 价格/成本留默认 0（见方法注释）
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asMapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object e : (List<Object>) o) if (e instanceof Map) out.add((Map<String, Object>) e);
        }
        return out;
    }

    /**
     * 从标题/产品/主图提取结构化核心营销卖点（写入 ProductContext.visual.sellingPoints）。
     * 这是"视觉流→结构流"数据链的起点：卖点由此产出，供 SKU 规划反哺（M5c）。
     *
     * @return 卖点字符串列表（如 ["过滤","增压","抑菌"]）
     */
    @SuppressWarnings("unchecked")
    public List<String> extractSellingPoints(String title, String productType, List<String> imagePaths) {
        String prompt =
            "你是拼多多电商运营专家。请从以下商品信息中提取 3-6 个【核心营销卖点】关键词。\n" +
            "商品类型：" + (productType == null ? "" : productType) + "\n" +
            "商品标题：" + (title == null ? "" : title) + "\n" +
            (imagePaths != null && !imagePaths.isEmpty() ? "并结合所给商品图观察到的功能特征。\n" : "") +
            "要求：每个卖点是 2-4 字的精炼关键词（如\"过滤\"\"增压\"\"抑菌\"\"一键止水\"），不要整句。\n" +
            "严格按 JSON 数组返回，不要其他内容：[\"卖点1\",\"卖点2\",\"卖点3\"]";
        try {
            String content = imageGenService.geminiText(prompt, imagePaths);
            if (content == null) return new ArrayList<>();
            int start = content.indexOf('['), end = content.lastIndexOf(']');
            if (start >= 0 && end > start) content = content.substring(start, end + 1);
            List<Object> raw = objectMapper.readValue(content, List.class);
            List<String> out = new ArrayList<>();
            for (Object o : raw) if (o != null && !o.toString().trim().isEmpty()) out.add(o.toString().trim());
            return out;
        } catch (Exception e) {
            log.warn("卖点提取失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ── 标题库辅助 ───────────────────────────────────────────────────────

    private File titleLibFile() {
        return new File(props.getPaths().getUserDataDir(), "title-lib.json");
    }

    public synchronized String loadTitleLibJson() {
        return PromptLoader.loadVersioned("prompt/title-lib.json", titleLibFile(), objectMapper);
    }

    @SuppressWarnings("unchecked")
    private List<String> readTitleLib() {
        try {
            Map<String, Object> root = objectMapper.readValue(loadTitleLibJson(), Map.class);
            Object t = root.get("titles");
            List<String> titles = new ArrayList<>();
            if (t instanceof List) {
                for (Object o : (List<Object>) t) {
                    if (o != null && !o.toString().trim().isEmpty()) titles.add(o.toString().trim());
                }
            }
            return titles;
        } catch (Exception e) {
            log.warn("解析标题库 JSON 失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<String> titleRefByCategory(String category) {
        List<String> all = readTitleLib();
        if (all.isEmpty()) return all;
        boolean isShower = category != null && (category.contains("花洒") || category.contains("淋浴"));
        List<String> shower = new ArrayList<>(), shelf = new ArrayList<>();
        List<String> cur = null;
        for (String t : all) {
            if (t.contains("花洒商品标题")) { cur = shower; continue; }
            if (t.contains("架类商品标题") || t.contains("架商品标题")) { cur = shelf; continue; }
            if (t.endsWith("商品标题") && t.length() <= 8) continue;
            if (cur != null) cur.add(t);
        }
        List<String> picked = isShower ? shower : shelf;
        return picked.isEmpty() ? all : picked;
    }

    private static String leafOf(String category) {
        if (category == null || category.isBlank()) return "";
        String[] segs = category.split("[>›]");
        return segs[segs.length - 1].trim();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String content) throws Exception {
        if (content == null) throw new RuntimeException("LLM 返回空");
        String cleaned = stripJsonFence(content);
        int start = cleaned.indexOf('{'), end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
        try {
            return objectMapper.readValue(cleaned, Map.class);
        } catch (Exception e) {
            log.warn("LLM 返回非法 JSON，原始内容：{}", content);
            throw new RuntimeException("LLM 返回非法 JSON：" + e.getMessage(), e);
        }
    }

    /** 剥掉 markdown 代码块围栏（```json ... ``` 或 ``` ... ```），LLM 常包这层。 */
    private static String stripJsonFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) t = t.substring(nl + 1);           // 去掉首行 ```json
            int fence = t.lastIndexOf("```");
            if (fence >= 0) t = t.substring(0, fence);      // 去掉尾部 ```
        }
        return t.trim();
    }
}

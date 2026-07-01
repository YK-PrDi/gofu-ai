# M5 方案：双轨打通（卖点→SKU规划反哺 + 生图产物入 ProductContext）

> 动系统脊柱 ProductContext + 迁 AI 文本能力，属大任务。先审阅方案，认可后写代码。

## 目标（GOFU-AI 方案的核心价值）

打通"数据孤岛"：流程1（视觉流）产出的**卖点**，反哺流程2（结构流）的 **SKU 规划**；两轨产物都挂到 ProductContext。这条"卖点→规划"数据链**在现有 ele/LY 里从未存在**，是 gofu-ai 新建的核心。

## 现状（已核实）

- SKU 规划 `generateSkuPlans`（LY ListingService:593）：纯 prompt 工程 + 调 `geminiText`（文本LLM）。**入参无卖点**。
- 标题生成 `prepareWithAI/Vision/FromTitleLib`：卖点只是揉进标题 prompt，无独立结构化卖点产出。
- 这些 AI 文本方法 **M4b 迁移时剥离了，当前不在 gofu-ai**。
- 云端 lyimage.ImageGenService 已有 `geminiText(prompt, imagePaths)` 可用（文本LLM入口）。
- 决策：AI 文本能力**都归云端**（AI算力集中，ADR-002 延伸）。

## 一、迁移：AI 文本能力入云端

在 `gofu-server-cloud` 新建 `service/lytext/` （或复用 lyimage），迁入：
- `generateSkuPlans`（SKU 规划）—— 从 LY ListingService 提出，改为独立服务方法
- 卖点提取 —— **新建**（见二）
- 标题生成 `prepareWithAI` 等 —— 迁入（可选，M5 聚焦规划反哺，标题按需）

这些方法内部调 lyimage.ImageGenService.geminiText，包名 com.lyauto→com.gofu.cloud。

## 二、新建：独立"卖点提取"步骤

- 新增云端方法 `extractSellingPoints(title, productType, images)`：调 geminiText，prompt 让 LLM 从标题/产品输出**结构化卖点数组**（如 ["过滤","增压","抑菌"]）。
- 产出写入 `ProductContext.visual.sellingPoints`（M2 已预留该字段）。

## 三、接线：卖点反哺 SKU 规划

- `generateSkuPlans` 入参**新增 sellingPoints**（从 ProductContext.visual 读）。
- prompt 里注入卖点段："围绕这些核心卖点策划型号：{sellingPoints}"——让 AI 的方案名/型号紧扣卖点。
- 规划产物写入 `ProductContext.structure.plans`（M2 已预留）。

## 四、接线：生图产物入 ProductContext + COS 永久 key（ADR-008 尾巴）

- LyGenController.sku-images 生图后：若配了 COS，upload 返回**永久 key**（ADR-008），写入 `ProductContext.visual.mainImages`；否则存本地路径。
- 补齐 M3 遗留的"生图产物写回 context"（ele 版 GenController 已做，LY 版补上）。

## 五、新建收敛编排入口（可选，MVP 简化）

云端加一个 `POST /api/context/{id}/dual-track`：给定 contextId，串起"提卖点→写visual→SKU规划读卖点→写structure"。或 MVP 阶段先分别暴露 `/api/gen/selling-points`、`/api/gen/sku-plans`（读写 context），前端/本地按序调，不做大编排。

## 六、验证（端到端，无密钥可验的部分）

1. mvn compile 三模块通过。
2. 启动云端，建 context → 调卖点提取（无密钥报错为预期，验链路）→ 调 SKU 规划（验 prompt 含卖点注入）。
3. 有密钥时：录入品类 → 提卖点写 visual → 规划读卖点产出方案写 structure → GET context 看两轨数据齐全。
4. 验证 ProductContext 往返不被破坏（M2 回归）。

## 决策点（需你定）

- **A. 卖点提取的触发**：是生图后自动提，还是独立步骤手动调？（影响编排）
- **B. M5 范围**：是否含标题生成迁移，还是只做"卖点+规划反哺+生图入context"三件核心？
- **C. 编排粒度**：一个大编排端点，还是几个小端点前端串（简化）？

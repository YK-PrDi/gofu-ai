# UPSTREAM · ele-business-java 生图能力上游追踪

> **用途**：gofu-ai 的生图能力移植自羽刃（`ele-business-java`，仓库 `YK-PrDi/ECIG`）。
> 两者是**独立代码库**（包名 `com.gofu.*` vs `com.elebusiness.*`，架构不同），
> **不能 `git merge`**。同步只能在「移植生图能力」层面手工做。本文件记录已移植的
> ele 提交、对应 gofu 落点、以及尚未吸收的差距。
>
> **同步流程**：用户说"同步 ele" → 在 ele 仓 `git log` 找本表 `已同步游标` 之后的新提交
> → 只筛生图相关 diff → 翻译成 gofu 包名/架构手工移植 → 更新本表游标。

## 仓库对照

| 项 | ele（上游） | gofu（本项目） |
|----|-------------|----------------|
| 路径 | `D:\code\ele-business-java` | `D:\code\gofu-ai` |
| 远端 | `YK-PrDi/ECIG` (main) | 见 gofu 自己的 remote |
| 生图入口 | `GenerateController`（单体，L757~870 自定义模式两步） | `FlowController.genAllMains`（云端，M11 接线） |
| 分析方法 | `ImageGenerationService.analyzeCustomImagePrompts` | 同名，已迁入 `gofu-server-cloud` |
| 底层 Agent | `GptImageAgent` | 同一套，已迁入 gofu-cloud |

## 已同步游标

- **ele main 已核对到**：`4199863`（2026-07-08，"全面增加生图逻辑+展示"）
- **gofu 侧承载里程碑**：M12（M11 接线基础上修复白底图参考丢失 bug）
- 上次核对日期：2026-07-09

## M12 关键修复（07.09 反馈：生图质量差根因）
M11 接线后仍差，根因**不是** prompt/预处理（few-shot 范例、cover 预处理、ensureSize、sizeHint gofu 都已有，`custom-analysis-system.txt` 与 ele 逐字一致），而是一个 bug：
`GptImageAgent.generateMulti` 声明 `whiteBgPath` 参数却**整个函数体没用它**，只用 `refImagePaths`。导致 `genAllMains` 里真实白底产品图从未作为像素参考进 GPT-Image，首图纯文生图（模型没见过产品），系列锚定在文字+首图幻觉 → 产品一致性崩坏。
**修复**（M12，改调用侧不动 agent 签名）：`genAllMains` 首图 `refs=[white]`、2~N `refs=[white, firstRef]`（对齐 ele 白底图+首图双锚定）；`regenMain` 主图重生补 `buildSeriesPrompt` + 白底图进 refs。真实出图质量需带密钥实跑验证。

## 生图能力吸收对照（截至 M11）

| ele 能力 | ele 出处 | gofu 状态 | gofu 落点 |
|----------|----------|-----------|-----------|
| 自定义模式：看白底图分析出产品专属多段 prompt | `analyzeCustomImagePrompts` + `custom-analysis-system.txt` | ✅ 已吸收 | `ImageGenerationService.analyzeCustomImagePrompts`；`FlowController.genAllMains` L166 调用 |
| 两步：首图串行定基调 → 2~N 参考首图并行 | `GenerateController` L757~870 | ✅ 已吸收 | `FlowController.genAllMains`（首图基调 + ref 首图） |
| 角度差异化（每张不同角度，禁重复） | `72e5d93`：`buildAngleConstraint`/`selectAngleSequence` | ✅ 已吸收 | `FlowController` L574 `buildAngleConstraint` / L600 `selectAngleSequence` |
| 系列一致性约束 | `buildSeriesPrompt` | ✅ 已吸收 | `FlowController` L557 `buildSeriesPrompt` |
| auto 比例按参考图推断 | `resolveAutoAspect` | ✅ 已吸收 | gofu `normalizeAspect` + 比例白名单 |
| 局部重绘（mask inpaint） | ele inpaint | ✅ 已对齐 | `/api/gen/inpaint` |
| 单张重生走视觉分析路径 | ele 自定义模式单张重生 | ✅ 已吸收 | `/api/flow/regen-main`（M11 新增） |
| COS 签名 URL（7天，私有桶） | `4199863` CosService | ✅ 已有（更完善） | gofu `CosService.signKey`（key 永久存 + 按需签名，ADR-008） |
| 提示词搜索（卖点/prompt 关键词） | `4199863` `PromptService.search` | ⬜ 未吸收（低优先，gofu 卖点提取走 LyTextService） | — |
| **Civitai LoRA 质感增强** | `41891cd`：`CivitaiLoraService`/`CivitaiConfig`（studio/lifestyle/minimal 预设，SDXL+产品摄影 LoRA，异步轮询） | ❌ **未吸收** | 见下方「差距」 |

## 当前差距（gofu 未吸收，按价值排序）

### 1. Civitai LoRA 质感增强（ele `41891cd`，+518行）
- **是什么**：自定义模式的**可选**增强。勾选后不走原 Agent，改调 Civitai API（Stable Diffusion XL + 专业产品摄影 LoRA），3 个预设：工作室/生活场景/极简。异步轮询（每3秒，最多300秒）。
- **ele 文件**：`config/CivitaiConfig.java`(95行)、`service/CivitaiLoraService.java`(259行)、`GenerateController` +80行（`useLora`/`loraPreset` 参数）、`frontend/index.html` +87行（复选框+风格下拉）、`application.yml`（Civitai key + 3预设）。
- **移植到 gofu 的落点**：新增 `gofu-server-cloud/.../service/CivitaiLoraService.java` + `config/CivitaiConfig.java`；`FlowController.genAllMains` 加 `useLora`/`loraPreset` 分支（启用则走 Civitai，否则现有 GptImageAgent）；workbench.html 一键生成区加"LoRA 增强"选项；`application.yml` 加 Civitai 配置占位。
- **风险/成本**：需 Civitai API key（新外部依赖+费用）；异步轮询要接进 gofu 现有 `GenerationTask` 轮询体系；效果需实测。**未验证是否值得**——建议先本地对比 ele 开/关 LoRA 的出图差异再决定移植。

### 2. 提示词搜索（低优先）
- gofu 卖点/prompt 由 `LyTextService` 自动生成，无手动搜索场景，暂不需要。

## ele 生图相关提交历史（供追溯，新→旧）

```
4199863  全面增加生图逻辑+展示（PromptService.search + CosService 签名URL）← 已核对到此
72e5d93  自定义模式产品展示角度差异化（buildAngleConstraint）← 已吸收
41891cd/5bf68e4  集成 Civitai LoRA 到自定义模式 ← ❌ 未吸收
836f665/4baa848  优化产品生成（保存点，并发12/线程池20）
51ccb6e  GPT-Image 多参考图 + quality high + 一致性 prompt
9da742e  后端支持重绘与并发生图
```

## 下次同步检查清单

1. `cd /d/code/ele-business-java && git fetch main && git log --oneline 4199863..main/main -- src/main/java/.../service/ src/main/java/.../controller/GenerateController.java`
2. 只看生图相关（`ImageGenerationService`/`GenerateController`/`GptImageAgent`/`CivitaiLoraService`/prompt 文件）的 diff
3. 逐条判断：gofu 是否需要 → 翻译包名/架构移植 → `mvn -pl gofu-server-cloud compile`
4. 更新本表「已同步游标」到新的 ele commit

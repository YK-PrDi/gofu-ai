# M3 同步方案：ele 生图能力迁入 gofu-server-cloud

> 本文档是"跑通一次上游同步"的详细方案。以这次 ele 多角度更新为样板，把云端生图能力建起来。
> **先审阅本方案，认可后再写代码。**

## 背景与定位

- gofu-ai 当前状态：M1 地基 + M2 ProductContext 契约/存储已完成，`gofu-server-cloud` **尚无任何生图代码**。
- 本次"同步"= 执行 M3 = 把 ele 的生图能力（含同事最新的多角度系列图、任务可中断更新）迁入云端。
- 迁入的是**同事更新后的最新版**，正是当初"M3 暂停等生图稳定"想等的版本。
- 范围决策：**先出方案，认可后写代码**（本文件）。Agent 迁入范围本方案默认"全迁 5 个"，如需缩减在审阅时说明。

## 一、迁入清单（源 → 目标）

| ele 源文件 | gofu-server-cloud 目标 | 处理 |
|---|---|---|
| `service/agent/ImageGeneratorAgent.java` | `service/agent/` | 接口，改包名。**是否把 GenerationTask 提进接口见决策②** |
| `service/agent/{Gemini,GptImage,Qwen,Wan,Hunyuan}ImageAgent.java` | `service/agent/` | 5 个 Agent，改包名，保留 GenerationTask 透传+分段中断 |
| `service/ImageGenerationService.java` | `service/` | 生图调度 + 多角度 buildSeriesPrompt 那套 |
| `service/CosService.java` | `service/` | 含 7 天签名 URL（见决策①） |
| `model/GenerationTask.java` | 复用 or 迁入 | ele 版含 volatile 进度/取消；与本地已迁版对齐 |
| `config/AppProperties.java` 生图段 | `config/` | gemini/dashscope/gpt-image/civitai/cos/paths 等配置 |
| `resources/prompt/image-*.txt`、`custom-*.txt` 等 | `resources/prompt/` | 生图 prompt 模板，原样迁 |
| `controller/GenerateController.java` 生图端点 | `controller/` | 见"二、接口收敛" |
| `service/PromptTemplateLoader/PromptService` 等 | 按需 | 生图链路依赖的 prompt 加载器 |

包名统一：`com.elebusiness.*` → `com.gofu.cloud.*`。

## 二、接口收敛（关键：不照搬 8 个端点）

ele 有 8 个生图端点（generate/custom_generate/kaipin/inpaint/gpt-image 等），但 gofu-ai 云端 MVP 只需**面向 ProductContext 的收敛接口**，不照搬散乱端点：

- `POST /api/gen/images` — 生图，入参 `ImageGenRequest`（已在 shared 定义），产物写回 ProductContext.visual
- `POST /api/gen/regenerate` — 局部重绘（对应 inpaint），针对单张
- 内部保留 ImageGenerationService 的多角度/系列图能力供上述接口调用

原 ele 端点逻辑作为内部方法保留，但对外只暴露收敛后的 REST。这样本地 cloudgw 只需对接 2 个稳定接口。

## 三、必须定的三个决策（ADR）

### 决策①：COS 签名 URL 7 天过期 → ProductContext 存什么

同事把 COS 改成 7 天签名 URL。`ProductContext.visual.mainImages` 若直接存签名 URL，7 天后失效、上下文永久损坏。

**方案（推荐）**：ProductContext 存**永久 COS key**（如 `generated/20260628/xxx.jpg`），不存签名 URL。前端/本地要展示时，调云端 `GET /api/gen/sign?key=...` 按需换取短期签名 URL。契约稳定、不过期。

→ 写入 ADR-008。

### 决策②：GenerationTask 要不要提进 ImageGeneratorAgent 接口

同事用"加重载 + 类型转换"的方式加了 GenerationTask（接口没动，实现类各加一个带 task 的重载）。迁入时更干净的做法是**把 task 提进接口**，省掉 ImageGenerationService 里的 instanceof 类型转换。

**方案（推荐）**：接口统一为带 `GenerationTask` 的签名，5 个 Agent 都实现。这是迁移重构的合理时机（旧代码是渐进式打补丁，迁移时可一步到位）。

→ 写入 ADR-009。

### 决策③：GenerationTask 归属（shared 还是 cloud）

本地 M4b 已迁过一个 `com.gofu.local.model.GenerationTask`（纯任务管理）。云端也要一个。是否提到 shared 共用？

**方案（推荐）**：**不提 shared**。两端的 GenerationTask 是各自进程内的运行时对象，不跨网络传输（跨网络传的是 ProductContext/DTO）。各留各的，避免 shared 被运行时细节污染（守 shared 契约层纯净原则）。

→ 写入 ADR-010。

## 四、LoRA/Civitai 说明

配置里有 `CIVITAI_API_KEY`，LoRA 集成是 ele 已提交的既有功能（非本次更新）。迁移时**连带迁入**（GptImageAgent/相关配置），保持 ele 生图能力完整，不单独裁剪。

## 五、验证方式（端到端）

1. `mvn compile` 云端模块通过。
2. 启动 gofu-server-cloud（5020），`POST /api/gen/images` 传一个 ImageGenRequest。
3. **无生图密钥环境下**：验证到"调用 Agent 前"的链路通（参数解析、prompt 拼装、Agent 分发、写 context 骨架），Agent 实际出图因无密钥会失败——这是预期，如实报告。
4. 有密钥时（你本地/云端）：验证真出图 + COS 上传 + 永久 key 写回 ProductContext.visual + 多角度系列图生效。
5. 验证生图产物正确挂到 ProductContext，M2 的上下文往返不被破坏。

## 六、风险与协调

- **不碰 LY**：本次只在 gofu-server-cloud 内操作，不动 ele/LY 任何文件。ele 是同事的工作区，只读参考。
- **密钥安全**：API key 全走环境变量，不硬编码、不提交（沿用 M1 gitignore）。
- **迁移基线**：以当前 ele 工作区（含同事未提交的多角度更新）为迁移源。记录基线 commit + "工作区未提交状态"，便于日后再同步时对比。

## 七、里程碑更新

M3 完成后：
- ARCHITECTURE.md 里程碑表 M3 → ✅
- 追加 ADR-008/009/010
- gofu-server-cloud 具备完整生图能力，本地 cloudgw（M4c 之后）可对接

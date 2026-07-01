# GOFU-AI 架构决策记录（ADR）与雷区清单

> 本文件是长期维护的架构权威。**大改动前必读，改动后追加 ADR。**
> 顶层导航见 `CLAUDE.md`，各模块规则见各模块 `CLAUDE.md`。

---

## 第一部分：架构决策记录（ADR）

### ADR-001 Monorepo + 云/本地职责分离（2026-06-20）

**决策**：新建 `gofu-ai` Monorepo，三模块：`gofu-shared`（契约）/ `gofu-server-cloud`（云端生图，端口5020）/ `gofu-client-local`（本地上新+反风控，端口5021）。

**理由**：生图吃 GPU 算力、需多租户计费 → 必须云端；上新靠真实 Cookie/UA/IP 反风控 → 必须本地。物理分离即护栏，跨模块只能走 shared 契约，import 不到对方业务类。

**来源**：原 `ele-business-java`（生图）+ `LY-Automation`（上新），两者均 Spring Boot 3.3.5 / Java 17 / Electron 33。旧仓冻结只读作参考。

### ADR-002 生图能力唯一收口云端（2026-06-20）

**决策**：所有 AI 生图/重绘只在云端。本地 `service/cloudgw` HTTP 调云端。LY 的 `ImageGenService` 中纯生图调用（`callGptImage2` / `callGemini`）**退役**，**仅保留 Canvas 合成部分**（`compositeShowerLeft` 等 Graphics2D）。

**理由**：消除两边重复调 AI 的冗余；算力集中计费。

### ADR-003 ProductContext 为系统脊柱，云端为权威源（2026-06-20）

**决策**：定义统一的"商品全局上下文"对象，住云端数据库。生图产物（卖点）、上新产物（SKU/定价）、人工锁定字段都挂其上。本地按需拉取+乐观回写，不做冲突合并（MVP）。

**理由**：打通"先做图→存本地→手动传链接"的割裂。卖点二次提取的耦合由此对象解开。

### ADR-004 核心表预埋 tenant_id，MVP 不实现隔离（2026-06-20）

**决策**：云端所有 JPA 实体第一天带 `tenantId` 字段，MVP 写死 `default`。

**理由**：见下方雷区"ddl-auto 陷阱"——SQLite 下事后给核心表加列代价高，预埋最省。

### ADR-005 前端：预览页用 Vue3，旧页面原生 JS 共存（2026-06-20）

**已修正的事实**：经核实，`LY-Automation/frontend/app.js`（行48起）与 `ele-business-java/frontend/index.html` **均为纯原生 JS + 手写 DOM 字符串拼接**，无 Vue/React（grep `createApp|reactive|Vue.|v-model` = 0 命中）。原"以 LY 的 Vue 为基座"前提不成立。

**决策**：旧生图页/上新页**保持原生 JS 不动**；只把交互最重的「结果合流预览页」用 **Vue3 新写**（改价联动、字段锁定、局部重绘这类双向状态，手写 DOM 极痛苦）。新旧页面共存。

**理由**：改动最小、收益最大。预览页是 MVP 唯一值得上框架的地方。

**实现约束（M6 落地时遵守）**：
- Vue3 优先用 CDN/单文件挂载方式接入，**不引入 Vite 构建链**，以免改变现有"免构建静态文件"的 Electron 加载与打包流程（见雷区 8）。仅当预览页复杂度确实压不住时再评估 Vite。
- 预览页只负责渲染 + 微调 ProductContext，所有数据走既有 REST，不新造前端状态后端。

### ADR-006 ProductContext 混合存储（2026-06-20）

**决策**：云端 `ProductContextEntity` 用混合存储——高频查询维度（id/tenantId/productId/category/status/时间戳）走真实列并建索引；完整 ProductContext 序列化进 `context_json` clob 列。

**理由**：直接规避 SQLite `ddl-auto:update` 无法 DROP/RENAME 列的陷阱（雷区 3）。改 ProductContext 子结构字段时只动 JSON、不迁移表；只有新增"可查询维度"才加真实列。

**验证（M2）**：POST/GET 往返三层嵌套（visual.sellingPoints → plans[].items[].accParts）完全一致；SQLite 实测建表含 `tenant_id varchar(64) not null` + 4 索引，context_json 存完整 803B JSON。

### ADR-007 M4 范围调整：生图相关暂缓，先迁纯上新（2026-06-20）

**背景**：LY 那边正在改"防比价生图质量"，且 M3（云端生图）暂停等其稳定。

**决策**：M4 拆分，本期只迁**不依赖 AI/生图**的部分：
- **M4a 完成**：Playwright 反风控工具链（pdd_listing.js + node/browsers/node_modules）原样迁入，运行时二进制 junction 链接、gitignore。
- **M4b 完成**：上新调度迁入（ListingService/ListingConfig/TaskService/GenerationTask/ListingController/TaskController），包名 com.lyauto→com.gofu.local。**剥离**：ImageGenService 依赖、AI 方法（标题生成 prepareWithAI/Vision/TitleLib、SKU 规划 generateSkuPlans）、生图端点（/prepare /gen-sku-images /analyze-bg）。AppProperties 仅留 Paths。
- **M4c 暂缓**：Canvas 合成（compositeShowerLeft）与生图调用交织，且可能是对方正在改的区域。等 M3 + 生图稳定。
- **剥离的 AI 能力**待云端 AI 服务就绪后，由本地 cloudgw 调用，不在本地重建。

**验证（M4a/b/d）**：本地服务启动 5021；product-info/list-images 端点真实响应；通过 /api/listing/run 触发 dry-run，完整链路 Java→Playwright子进程→真实Chromium 跑通，浏览器导航到拼多多商家后台登录页并触发人工介入暂停（headless=false 实证，截图存档 .verify-evidence/）。

### ADR-008 ProductContext 存永久 COS key，展示层按需换签（2026-07-01）

**背景**：ele 同事把 COS 上传改成返回 7 天签名 URL。若 ProductContext 直接存签名 URL，7 天后图失效、上下文永久损坏。

**决策**：`CosService.upload` 返回**永久 COS key**（如 `generated/20260628/xxx.jpg`），ProductContext 只存 key。新增 `CosService.signKey(key)` 按需换 7 天签名 URL，经 `GET /api/gen/sign?key=` 暴露给展示层。上下文数据永不过期。

**验证（M3）**：/api/gen/sign 端点返回正常；upload 逻辑返回 key（无 COS 环境降级本地路径）。

### ADR-009 GenerationTask 提进 ImageGeneratorAgent 接口（2026-07-01）

**背景**：ele 同事用"加重载 + 调度端 instanceof 类型转换"的方式给 Gemini/Wan 加了任务可中断能力，接口没动。

**决策**：迁移时把带 `GenerationTask` 的 `generateMulti` 提为接口的 default 方法（默认忽略 task 转调旧版，Gemini/Wan 覆写做分段中断）。`ImageGenerationService` 里删掉 `if(gemini)/else if(wan2.7)` 的 instanceof 转换，统一直调 `agent.generateMulti(...task)`。迁移是一步到位重构的合理时机。

**验证（M3）**：5 Agent 全注册成功，收敛端点调 Agent 无类型转换、链路通。

### ADR-010 GenerationTask 不提 shared，两端各留（2026-07-01）

**决策**：本地（M4b）和云端各有一份 `GenerationTask`（内容同源）。**不提到 gofu-shared**——它是进程内运行时对象，不跨网络传输（跨网络传的是 ProductContext/DTO）。守 shared 契约层纯净原则（见 gofu-shared/CLAUDE.md）。

### M3 同步小结（首次上游同步样板）

以 ele 多角度更新为样板跑通首次同步。迁入 gofu-server-cloud：`ImageGeneratorAgent` 接口 + 5 Agent（Gemini/GptImage/Qwen/Wan/Hunyuan，含 GenerationTask 分段中断）+ `ImageGenerationService`（多角度 buildSeriesPrompt 等）+ `CosService`（永久 key）+ `PromptTemplateLoader` + 生图 prompt 模板 + `AppProperties` + 2 config。包名 `com.elebusiness`→`com.gofu.cloud`。对外只暴露收敛的 `/api/gen/{images,regenerate,sign}`，不照搬 ele 8 个散乱端点。**LoRA/Civitai 作为 ele 既有功能连带迁入。** 迁移基线：ele 工作区含同事未提交的多角度更新。

### ADR-011 生图整流程在云端（含 Canvas 合成），字体内置（2026-07-01，修正版）

**背景**：LY 生图重构为三层——`AiImageClient`（AI 调用）/ `ShowerCompositor`（Canvas 合成，零 AI 依赖）/ `ImageGenService`（6 模式编排）。

**初版决策（已推翻）**：曾定"编排在本地、AI 步骤走云端中粒度接口、合成在本地"。**精读 500 行 `generateSkuImage` 后推翻**：编排里 AI 调用有 5 处（analyzeRefImage/analyzeBackgroundStyle×2/callGemini/callGptImage2）且与 prompt 组装、img2img 参考图编排、基准图缓存深度交织，无法切出"中粒度接口"——任何切法都会导致 prompt 组装逻辑在云/本地重复或强行拆碎。

**最终决策**：**整个生图流程（prompt 组装 + AI 调用 + Canvas 合成）都在云端** `gofu-server-cloud`。
- LY 三层原样迁云端：`AiImageClient` + `ShowerCompositor` + `ImageGenService`，与上游结构一致，未来同步最省。
- 云端直接调 `ShowerCompositor` 合成。**字体问题一次性解决**：ShowerCompositor 的 `Microsoft YaHei` 改为项目内置中文 ttf（`Font.createFont` 注册），云端 Linux 也能正确渲染中文。
- 本地不再迁 ShowerCompositor（之前 5f17dda 迁入本地的要挪到云端）。本地通过收敛接口 `/api/gen/images` 拿最终成品图。

**对本地的影响**：本地 `service/canvas` 目录取消；本地只经 cloudgw 调云端生图，收完整成品图。契约层不变。

**进度（M4c 完成）**：LY 生图三层原样迁入云端 `service/lyimage`（AiImageClient/ShowerCompositor/ImageGenService/PromptTemplateService/PromptLoader）+ LY prompt 模板/json。配置隔离：新建 `LyImageProperties`（prefix `ly-image`）与 ele 的 `AppProperties`（prefix `app`）共存不冲突。字体：ShowerCompositor 静态探测可用中文字体（YaHei→Noto/文泉驿→SansSerif 兜底），云端 Linux 可渲染中文。启动实测两套生图线 + 双 Properties 无 Bean 冲突。**待办**：给 LY 生图接收敛 REST 入口（当前 /api/gen/images 走 ele 版；LY 花洒防比价那套需单独入口，下一步）。

---

## 第二部分：雷区清单（不读源码发现不了的隐藏约束）

> 行号基于旧仓库（迁移参考）。迁入后更新为新路径。

### 雷区 1：生图 Agent 统一契约（云端）

- 接口 `ImageGeneratorAgent`：`getId()` / `getDisplayName()` / `generate(prompt, refImagePath, whiteBgPath, outputPath)`；可选 `generateMulti(...)`（万相/通义已覆写支持多参考图+aspect）。
- 注册靠 Spring：`ImageGenerationService` 构造器收 `List<ImageGeneratorAgent>`，按 `getId()` 存入 `agentMap`。**新增 Agent = 标 `@Service` + 实现接口 + 唯一 id**，无需手动注册。
- 默认 Agent 常量 `DEFAULT_AGENT_ID = "gpt-image"`。改它必须指向注册表里真实存在的 id，否则 `resolveAgent` 回退失败。
- **联动**：改 `generate()` 签名 → 6 个实现类全部同步改 + 调用方 + 测试。

### 雷区 2：生图模式分流（云端）

- `mode` 取值字符串精确匹配：`standard` / `custom` / `ecommerce` / `kaipin` / `video` / `inpaint`。存进 `GenerationHistory.mode`，前端 `@RequestParam mode` 消费。
- **联动**：新增 mode 要同步：实体字段注释、Controller 新入口、`historyService.recordGeneration(...)` 调用、（如需）prompt 压缩逻辑。
- `custom`/`inpaint` 走 **FormData**（含文件上传 + `sessionId`），`standard` 走 `@RequestBody`。融合时序列化策略不统一是坑。

### 雷区 3：JPA + SQLite ddl-auto:update 陷阱（云端）

- `ddl-auto:update` 在 SQLite 下**只能加列，不能 DROP COLUMN / RENAME / 改约束**。改字段只能新增或软删除。← 这就是 tenant_id 必须预埋的原因。
- **JSON 字符串字段是隐藏契约**：`GenerationHistory.configJson`（模式配置快照）、`refPathsJson`（参考图路径数组 `[".history-refs/<id>/0.jpg"]`）。存前必须 `objectMapper.writeValueAsString`，读时必须解析，格式错前端恢复就崩。

### 雷区 4：lstSkuItems 数据契约（本地上新，最重要）

- 前端全局数组 `lstSkuItems`（`app.js:48`），手写 DOM。一个 SKU 条目字段一路透传 ERP→定价→生图→上架，**漏一个就断**：

| 字段 | 漏传后果 |
|------|----------|
| `itemCode` | 上架规格编码列空，PDD 可能拒绝；生图配件匹配失败 |
| `accParts` `[{code,qty}]` | 必须 JSON 数组（非字符串），否则后端反序列化崩、生图配件错配 |
| `imgDir` | 上架前校验"还有N个SKU没有图片"被卡住（`app.js:1572`） |
| `skuDisplayName` | 营销标题丢失，回退用 `name` |
| `groupPrice`/`singlePrice` | 上架时 ×100 转分；单买价全表统一 |
| `spec1`/`spec2` | 拼多多二维 SKU（颜色×配件组合）匹配 |

- 生图请求体字段（已验证 `app.js:561/770`）：`{ idx, name, compDesc, itemCode, accParts, whiteImgPath }` → 后端 `generateSkuImage` 须字段对应。

### 雷区 5：itemCode 规格编码格式（本地）

- 格式 `主件码+配件码*N`，`+` 分隔配件、`*` 跟数量。例 `GF-099-灰+052底座*1+088滤芯*5`。
- 解析（已验证 `ImageGenService.java:306`）：`split("\\+")` 取第2段起，每段再 `split("\\*")[0]` 取配件码 → 用于匹配配件白底图。
- **格式写错（缺分隔符/末尾多 `+`）→ 拆分遗漏 → 配件白底图匹配失败 → 生错图**。

### 雷区 6：生图双分支（本地 Canvas vs 云端纯AI）

- 决策点（`ImageGenService.java:229`）：`templateId` 对应模板 `type=="ai"` → 纯AI图生图（基准图打底，无 Java 合成）；否则 → **sticker 贴图模式**（AI 生右侧主件，Java `compositeShowerLeft` 合成左侧配件卡/滤芯卡）。
- 滤芯数量约束（`:236`）：从款式名 `parseFilterCount` 提取，prompt 里数字+英文单词双重强调"exactly N filters"防生错。
- **融合归属**：`compositeShowerLeft` 等 Canvas 合成 → 本地保留；`callGptImage2`/`callGemini` 纯生图 → 退役，走 cloudgw。
- **联动**：`templateId` 前端两处都传（`siGenerate`/`siRegenOne`），漏传回退默认 sticker。

### 雷区 7：拼多多选择器易碎点（本地 pdd_listing.js，平台改版高频）

- **全局搜索框干扰**：选类目时排除 `className` 含 `mms-header`、placeholder 含"搜索功能/订单/课程"的 input。
- **虚拟滚动**：SKU 图/价格表是虚拟滚动（一次渲染~11行）。定位+`scrollIntoView`+取 handle 必须在同一次 `evaluate` 内完成，否则行被重排失效。价格表超12行用 `offsetTop`（行高~88px）回退定位，最多重试6次。
- **子串误配**：按最长 SKU 名匹配，防"芯"误配"滤芯"。排除含"批量设置"的行。
- **图片区域顺序**：`input[type=file][accept*=image]` 按序 0主图/1详情/2白底，平台加新区域会错位。
- **铁律**：改这里必须 `headless=false` 本地跑通 + 留截图，否则不算完成。

### 雷区 8：Electron → JVM 启动契约

- `res()` 函数：打包态 `process.resourcesPath`，开发态 `../dist`。两端一致。
- 启动 JVM 用 `-D` 覆盖 `app.paths.*`（ele 8个参数，LY 3个）。**`electron/package.json` 的 `extraResources` 新增资源，必须同步加 main.js 的 `-D` 参数名**，否则 Spring 找不到。
- 数据目录：ele 用 `prepareDataDir`（自动复制内置资源，少弹窗）优于 LY 的独立 `pickDataDir` 弹窗。融合取 ele 范式（呼应"消除弹窗"目标）。
- **appId 冲突**：`com.lyauto.automation` vs `com.elebusiness.aistudio` → 融合后统一新 appId（建议 `com.gofu.ai`）。

### 雷区 9：密钥与环境变量

- 绝不硬编码、绝不提交。LY 用 `electron/secret.js`（gitignore），ele 用环境变量 + `application-prod.yml`（gitignore）。融合统一到环境变量注入 + 本地配置文件。
- ele 关键环境变量：`GEMINI_API_KEY`/`GPT_IMAGE_KEY_1..4`（生图必需）、`DASHSCOPE_API_KEY`/`SILICONFLOW_API_KEY`（可选 Agent）、`COS_SECRET_ID/KEY`（无则降级本地）、`APP_PASSWORD`（默认123456，生产必改）。

### 雷区 10：COS 上传 URL 契约（云端）

- URL 格式固定：`https://{bucket}.cos.{region}.myqcloud.com/generated/{yyyyMMdd}/{filename}`。
- 上传的是 `.temp-output`（临时）非 `savedPath`（永久）。结果里 `outputRef` 可能是本地路径或 COS URL，前端按是否 `http` 开头分流。这个 URL 是否写进 ProductContext 需在 M3 定。

### 雷区 11：任务异步契约（两端通用）

- 状态机：`pending → running → done/stopped/error`。`GenerationTask` 用 `volatile` 字段。
- 取消靠 worker 主动轮询 `task.isCancelled()`。
- **TTL**：完成任务内存保留 60 分钟、`.temp-output` 文件 2 小时后清理。前端须在 TTL 内拉完 results，否则 404。

### 雷区 12：本地单模块运行前必须先 install（构建顺序）

- `gofu-shared` 是 cloud/local 的依赖。**单独 `mvn spring-boot:run` 某个模块前**，必须先把父 POM + shared 装进本地仓库，否则报 `Could not find artifact com.gofu:gofu-shared / gofu-ai:pom`。
- 正确姿势：仓库根执行 `mvn -N install`（装父POM）+ `mvn -pl gofu-shared install`（装契约），或直接 `mvn install -DskipTests` 全量。改了 shared 后也要重新 install 才对下游生效。
- 全量 `mvn compile`（reactor 模式）不受影响——reactor 会自动按序编译，问题只出在"单模块孤立运行"。

### 雷区 13：中文请求体必须 UTF-8 传输

- 实测 Windows shell 下 `curl -d '中文'` 会触发 `Invalid UTF-8 start byte` 400 错误（shell 编码污染，非代码问题）。
- 测试带中文的接口用 `--data-binary @file.json` + `charset=utf-8`，文件存 UTF-8。前端 fetch 走 JSON 不受影响。

---

## 第三部分：实施里程碑

| 里程碑 | 内容 | 验证 | 状态 |
|---|---|---|---|
| M1 地基 | 骨架+父POM+三模块+分层CLAUDE.md+本文件 | `mvn compile` 通过 | ✅ 完成 |
| M2 契约 | ProductContext+DTO+云端上下文表（预埋tenant_id） | 建表成功 | ✅ 完成 |
| M3 云端 | ele 生图Agent迁入，封装生图/重绘REST，接上下文 | 生图并写入context | ✅ 完成（首次上游同步样板） |
| M4 本地 | LY上新/Canvas/反风控迁入，生图改调cloudgw | 拉context+Playwright headless=false留截图 | ✅ M4a/b/c/d 完成（生图整流程在云端 lyimage） |
| M5 双轨 | 流程1卖点→流程2 SKU规划反哺打通 | 录入1品类，context两轨数据齐全 | 待办 |
| M6 预览页 | Vue3 预览页（合流预览+微调回写+字段锁定）+ 旧页原生JS共存 | 点击≤3次，改价联动/局部重绘/锁定生效 | 待办 |
| M7 上新 | 预览确认→本地一键上新，滑块人工介入 | 真实店铺跑通一单，留截图 | 待办 |

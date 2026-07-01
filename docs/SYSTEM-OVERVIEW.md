# GOFU-AI 系统总览

> 给团队/同事看的系统架构与用法总览。技术决策细节见 `ARCHITECTURE.md`（ADR + 雷区），
> 各模块规则见各模块 `CLAUDE.md`。本文档回答：这系统是什么、怎么跑、接口怎么用。

## 一、这是什么

电商全业务流程自动化统一中枢，融合两条原本割裂的业务线：
- **生图**（视觉流）：AI 批量生成主图/详情图/SKU图、提卖点、生标题 → 跑**云端**
- **上新**（结构流）：Playwright 自动化上架拼多多 + 反风控 → 跑**本地**

两者通过 **商品全局上下文 ProductContext** 打通——生图产物（卖点、图、标题）和上新产物（SKU方案、定价）挂在同一个对象上，云端权威存储，本地按需拉取。

技术栈：Java 17 + Spring Boot 3.3.5 + Electron + Playwright + Vue3(预览页)。

## 二、三模块架构（Monorepo）

```
gofu-ai/
├── gofu-shared         契约层：ProductContext/DTO/枚举（两端唯一共同依赖）
├── gofu-server-cloud   云端(5020)：AI 生图 + AI 文本 + 上下文权威存储 → 部署腾讯云
└── gofu-client-local   本地(5021)：上新 + 反风控 + Electron 桌面壳
```

**职责铁律**（物理隔离即护栏）：
- 云端只做 AI 算力（生图/文本）+ 上下文存储，**绝不碰** Playwright/上新
- 本地只做上新/反风控，**所有 AI 走 HTTP 调云端**，本地无 AI 逻辑
- 跨模块只能依赖 shared 契约，import 不到对方业务类

## 三、云端能力地图（gofu-server-cloud）

云端有**两条生图线**（不同产品线，配置/代码隔离）+ 一条文本线 + 上下文存储：

| 能力 | 服务类 | 对外端点 | 说明 |
|---|---|---|---|
| ele 生图（对公众） | `ImageGenerationService` + 5 Agent | `POST /api/gen/images` `/regenerate` `/sign` | 多角度系列图、任务可中断、配置 prefix `app` |
| LY 生图（合作公司） | `service/lyimage/*`（AiImageClient/ShowerCompositor/ImageGenService） | `POST /api/ly-gen/sku-images` `/analyze-bg` `/antiprice-templates` | 花洒防比价6模式、Canvas贴图合成、配置 prefix `ly-image` |
| AI 文本 | `service/lytext/LyTextService` | `POST /api/gen/selling-points` `/sku-plans` `/title` | 卖点提取、SKU规划、标题生成 |
| 上下文存储 | `service/context/ContextService` | `POST /api/context` `GET /api/context/{id}` | ProductContext 权威源，SQLite 混合存储 |

生图 Agent：Gemini / GPT-Image / 通义Qwen / 万相Wan / 混元Hunyuan（标 @Service 自动注册）。

## 四、本地能力地图（gofu-client-local）

| 能力 | 服务类 | 对外端点 | 说明 |
|---|---|---|---|
| 上新调度 | `service/listing/ListingService` | `POST /api/listing/run` | 驱动 Playwright 子进程上架 |
| 任务查询 | `service/listing/TaskService` | `GET /api/task/{id}` `POST /api/task/{id}/stop` | 异步任务进度/取消 |
| 素材扫描 | ListingService | `POST /api/listing/{scan-folder,list-images}` | 扫描商品文件夹、图片排序 |
| 产品预设 | ListingService | `GET /api/listing/product-info` | 品类属性预设（xlsx） |
| 反风控自动化 | `tools/pdd_listing.js` | （子进程） | 真实Cookie/UA/IP + 模拟人类输入 + 滑块人工介入 |

本地生图能力已剥离——需要生图时通过 cloudgw（M6+ 接入）调云端。

## 五、核心数据流：双轨工作流

```
【录入品类+主件】
      │
      ├─ 视觉流(云端) ─ POST /api/gen/title      → context.visual.title
      │                POST /api/gen/selling-points → context.visual.sellingPoints ⭐
      │                POST /api/ly-gen/sku-images  → context.visual.mainImages(COS永久key)
      │
      └─ 结构流(云端) ─ POST /api/gen/sku-plans   → 读 sellingPoints 反哺 ⭐
                                                  → context.structure.plans
      │
【结果合流预览页(M6, Vue3)】读整个 context 渲染 → 人工微调(改价/局部重绘/字段锁定) → 回写 context
      │
【一键上新(M7)】本地取 context → Playwright 上架
```

⭐ = 打通数据孤岛的关键：卖点由视觉流产出、被结构流读取反哺。

## 六、ProductContext（系统脊柱）

```
ProductContext
├── tenantId          预埋多租户(MVP写死default)
├── productId/category/mainItem
├── visual            视觉流：mainImages[]/detailImages[]/title/sellingPoints[]
├── structure         结构流：plans[](SkuPlan: planName/description/items[])
├── lockedFields[]    人工锁定字段(防重新生成覆盖)
└── status            DRAFT→GENERATING→READY→LISTED
```

云端 SQLite 混合存储：查询维度走真实列+索引，完整对象序列化进 `context_json`（规避 SQLite 改列限制）。
图片存**永久 COS key**，展示时 `GET /api/gen/sign?key=` 换 7 天签名 URL（ADR-008）。

## 七、怎么跑起来

**前置**（雷区12）：单模块运行前先装依赖到本地 Maven 仓库
```bash
cd gofu-ai
mvn -N install                    # 装父 POM
mvn -pl gofu-shared install -DskipTests   # 装契约层
```

**编译全部**：`mvn compile`

**启动云端**（需 API 密钥环境变量）：
```bash
cd gofu-server-cloud && mvn spring-boot:run   # 5020
# 密钥：GEMINI_API_KEY / GPT_IMAGE_KEY_1..4 / DASHSCOPE_API_KEY /
#      COS_SECRET_ID / COS_SECRET_KEY，LY文本线 ly-image.text.api-key
```

**启动本地**（需 tools/node + browsers 运行时，见 gofu-client-local/tools/README.md）：
```bash
cd gofu-client-local && mvn spring-boot:run   # 5021
```

## 八、当前进度

| 里程碑 | 状态 |
|---|---|
| M1 地基 / M2 ProductContext | ✅ |
| M3 ele 生图迁云端 | ✅ |
| M4 本地上新反风控 + LY生图迁云端 | ✅ |
| M5 双轨打通（卖点↔规划反哺） | ✅ 数据链通，真实LLM待密钥验 |
| M6 预览页（Vue3） | 待办 |
| M7 一键上新 | 待办 |

**已验证**：编译、建表、上下文往返、Playwright真实跑通拼多多登录页、各端点链路通到调AI。
**待密钥验**：真实生图质量、卖点提取准确度、SKU规划是否真围绕卖点。

## 九、两条上游线怎么同步到本仓

ele（对公众）和 LY（合作公司）仍在各自演进。同步范式（M3/M4/M5 已跑通）：
1. `git diff` 精读上游改动（不靠复制覆盖猜）
2. 判断影响面（碰不碰 shared 契约/数据模型）
3. 迁移改包名 `com.elebusiness`/`com.lyauto`→`com.gofu.cloud`，剥离不要的，收敛接口
4. 设计分叉记 ADR
5. 编译+启动+链路验证 → 提交推送

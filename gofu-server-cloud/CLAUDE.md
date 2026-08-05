# gofu-server-cloud — 云端服务（视觉流 / 算力）

> 进入本模块前先读仓库根的 `CLAUDE.md`。（`ARCHITECTURE.md` 只在碰架构决策/踩怪问题时查其「雷区」清单，不必每次全读。）

## 这个模块能做什么

- `service/agent/` — 多 AI 生图 Agent（Gemini / GPT-Image / 通义 Qwen / 万相 Wan / 混元 Hunyuan）。统一接口，从旧 `ele-business-java` 迁入。**当前默认走 `GptImageAgent`**（gpt-image-2 经中转站）。
- `service/lyimage/` — **生图编排主战场**（实际改生图逻辑最常来这里）：`ImageGenService` 按品类分支组装 prompt+参考图（花洒贴图/架类整图AI/通用SKU三条路）、`AiImageClient` 发 multipart 请求、`PromptTemplateService` 读构图库、`ShowerCompositor` 做 Graphics2D 合成（配件卡/多件装主件框/底部通栏）。
- `service/context/` — 商品全局上下文服务：ProductContext 的**权威**读写（这里是唯一真相源）。
- `resources/prompt/` — prompt 模板与素材库：`image-shelf-main.txt` 等模板、`shelf-prompts.json`(架类构图库)、`main-compositions.json`(主图构图库)、`main-sellpoints.json`(卖点库)。改生图效果优先看这里，不要急着改 Java。
- `entity/` — JPA 实体，SQLite 持久化。**所有实体必须带 `tenantId` 字段**。
- `controller/` — 生图、局部重绘、上下文 CRUD、(M5+ 预埋) 租户/计费接口。

端口 5020。持久化：SQLite (`gofu-cloud.db`) + JPA `ddl-auto:update`。

⚠️ **库路径跟启动目录绑定**：`jdbc:sqlite:${user.dir}/gofu-cloud.db`。从仓库根启动 → `D:\code\gofu-ai\gofu-cloud.db`（有数据）；从模块目录启动 → 另一个空库；打包版 Launcher 把工作目录设成 `GOFU/app/data/` → 又一个库。三者互不相通，`ddl-auto:update` 会在空库里静默建表、不报错。遇到"数据明明导过却查不到"（如开品模式报"未找到标签"），先 `find . -name gofu-cloud.db` 数行数定位，别先怀疑导入失败。

## 绝对禁止

- ❌ Playwright、浏览器自动化、任何上新代码（那是 `gofu-client-local` 的事）
- ❌ 直接 import `com.gofu.local.*`（物理上也没有依赖）
- ❌ 新增实体表不带 `tenantId` 列

## 关键设计

- 生图能力在本系统**唯一收口于此**。本地的所有生图/重绘都来调这里，不存在第二套生图逻辑。
- API key 通过环境变量注入（`GEMINI_API_KEY` / `DASHSCOPE_API_KEY` 等），禁止硬编码。
- 部署：systemd + 腾讯云，参考旧项目 `ele-business-java/DEPLOY.md`。

## 迁移来源

`ele-business-java/src/main/java/com/elebusiness/service/agent/*`、`model/entity/*`、`repository/*`、`application.yml`。

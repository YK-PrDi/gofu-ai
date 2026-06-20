# gofu-server-cloud — 云端服务（视觉流 / 算力）

> 进入本模块前先读仓库根的 `CLAUDE.md` 和 `ARCHITECTURE.md`。

## 这个模块能做什么

- `service/agent/` — 多 AI 生图 Agent（Gemini / GPT-Image / 通义 Qwen / 万相 Wan / 混元 Hunyuan）。统一接口，从旧 `ele-business-java` 迁入。
- `service/context/` — 商品全局上下文服务：ProductContext 的**权威**读写（这里是唯一真相源）。
- `entity/` — JPA 实体，SQLite 持久化。**所有实体必须带 `tenantId` 字段**。
- `controller/` — 生图、局部重绘、上下文 CRUD、(M5+ 预埋) 租户/计费接口。

端口 5020。持久化：SQLite (`gofu-cloud.db`) + JPA `ddl-auto:update`。

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

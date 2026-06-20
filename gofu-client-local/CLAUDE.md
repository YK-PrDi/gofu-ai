# gofu-client-local — 本地客户端（结构流 / 反风控）

> 进入本模块前先读仓库根的 `CLAUDE.md` 和 `ARCHITECTURE.md`。

## 这个模块能做什么

- `service/canvas/` — Canvas 图层拼装（Graphics2D）。花洒左侧配件/滤芯白底图合成贴图。从旧 `LY-Automation` 的 `ImageGenService` **仅迁入合成部分**（`compositeShowerLeft` 等），生图部分不要。
- `service/listing/` — 上新流程调度：扫描素材文件夹、生成标题/SKU 方案、驱动 Playwright。
- `service/cloudgw/` — **云端生图网关**：HTTP 调 `gofu-server-cloud` 做生图/重绘。本地需要图就走这里。
- `service/sync/` — 商品上下文本地缓存：进入商品时拉取、人工微调时回写云端。
- `electron/` — Electron 总控中枢（启动 JVM + webview 嵌真实商家后台）。
- `frontend/` — 统一 Vue 前端。`tools/` — pdd_listing.js + Playwright + 便携 Node。

端口 5021。

## 绝对禁止

- ❌ **本地写 AI 生图逻辑**。要生图/重绘就调 `service/cloudgw`。这是和云端职责分离的核心，违反就回到了"数据孤岛"。
- ❌ 直接 import `com.gofu.cloud.*`（物理上也没有依赖）
- ❌ "优化"反风控逻辑（模拟人类输入、随机延迟、滑块人工介入）——原样迁入，别动

## 反风控铁律

- webview/BrowserWindow 嵌真实商家后台，用真实本地 Cookie/UA/IP。
- 模拟人类键盘逐字输入 + 字间随机延迟；操作间随机等待 800~1500ms。
- 触发滑块/验证码 → 脚本暂停 + 桌面强提醒 → 人工划过 → 点继续无缝续跑。
- Playwright 验证必须 `headless=false` 本地跑通并留截图，否则不算完成。

## 本地持久化

机器相关数据（`pdd_cookies.json`、快麦 token）保持本地文件，**不上云**——它们必须跟随真实浏览器环境。商品相关数据走云端 ProductContext。

## 迁移来源

`LY-Automation/tools/pdd_listing.js`、`service/ListingService.java`、`service/ImageGenService.java`(仅Canvas部分)、`model/ListingConfig.java`、`electron/main.js`、`frontend/`(Vue基座)。

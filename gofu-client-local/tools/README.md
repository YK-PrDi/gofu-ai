# tools — 本地上新反风控工具链

## 源码（版本控制）

- `pdd_listing.js` — 拼多多商品自动发布脚本（Playwright 驱动）。**反风控核心，原样自 LY-Automation 迁入，禁止重写**（见 ARCHITECTURE.md 雷区 7、ADR-002）。
  - 真实 Cookie/UA/IP + 模拟人类输入 + 随机延迟 + 滑块人工介入。
  - 用法：`node pdd_listing.js`（stdin/PDD_CONFIG 传 JSON）/ `--login-only`（仅登录存cookie）/ `--dry-run`（每步截图不提交）。
  - 进度协议（stdout）：`PROGRESS:10:描述` / `DONE:success` / `ERROR:信息`。
- `package.json` / `package-lock.json` — 依赖 playwright ^1.60.0。

## 运行时依赖（不入 git，需本地 provision）

以下体积大（合计约 790M），由构建/初始化流程获取，**不提交**：

| 目录/文件 | 来源 | 获取方式 |
|---|---|---|
| `node/` (~88M) | 便携 Node 运行时 | 从 LY-Automation/tools/node 复制，或装系统 Node |
| `browsers/` (~685M) | Playwright Chromium | `npx playwright install chromium` 或从 LY 复制 |
| `node_modules/` (~17M) | npm 依赖 | `npm install`（在本目录） |

## 运行时产物（不入 git）

`*.png`（步骤截图）、`pdd_cookies.json`（登录态）、`sku_input_diag.json`（dry-run 诊断）—— 跟随真实浏览器环境，属本地机器数据（ADR-003：本地机器相关数据不上云）。

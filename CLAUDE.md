# GOFU-AI — 顶层总纲

> 本文件是整个仓库的架构铁律与导航。**任何改动前先读本文件和 `ARCHITECTURE.md`。**
> 子模块各有自己的 `CLAUDE.md`，进入某模块工作时同时遵守该模块规则。

## 这是什么项目

电商全业务流程自动化统一中枢。把原先割裂的两个项目融合为一个 Monorepo：
- **生图**（视觉流）跑在云端 → `gofu-server-cloud`
- **上新**（结构流 + 反风控）跑在本地 → `gofu-client-local`
- 两者通过 **商品全局上下文 ProductContext** 打通数据孤岛，契约定义在 `gofu-shared`

技术栈：Java 17 + Spring Boot 3.3.5 + Electron 33 + Playwright + Vue。

## 模块地图

| 模块 | 职责 | 端口 | 绝不允许 |
|------|------|------|----------|
| `gofu-shared` | 共享契约：ProductContext、DTO、枚举 | — | 任何业务逻辑、框架依赖、IO |
| `gofu-server-cloud` | AI 生图 + 上下文权威存储，部署腾讯云 | 5020 | Playwright / 浏览器 / 上新代码 |
| `gofu-client-local` | Canvas 拼装 + 上新 + 反风控，Electron 桌面端 | 5021 | 本地 AI 生图逻辑（生图走 cloudgw 调云端） |

## 架构铁律（违反 = 改爆项目）

1. **云/本地职责不可混**：生图只在云端，上新只在本地。本地需要生图就 HTTP 调云端（`service/cloudgw`），不准把生图逻辑搬回本地。
2. **跨模块只走 shared 契约**：`cloud` 和 `local` 不准互相直接 import 对方的类。物理上也做不到（无依赖），别尝试加依赖绕过。
3. **ProductContext 是脊柱**：生图产物（卖点等）、上新产物（SKU/定价）、人工锁定字段都挂在它上面。改它的字段 = 大任务，必须先 brainstorm + plan + 独立 review。
4. **核心表预埋 tenant_id**：云端所有 JPA 实体第一天就带 `tenantId` 字段（MVP 写死 default，不实现隔离逻辑）。加新表必须带这个列。
5. **反风控逻辑神圣不可重写**：真实 Cookie/UA/IP + 模拟人类输入 + 随机延迟 + 滑块人工介入。从旧项目原样迁入，别"优化"。

## 改动分级（决定走多重的流程）

- **大任务**（碰 `gofu-shared/context`、云端核心实体表、新增公共 API）：brainstorm → plan → 实现 → 独立 code review。
- **中任务**（单模块内新增/改 service、controller）：短 plan → 实现 → 定向验证。
- **轻任务**（改 prompt、前端文案、配置值）：直接做 + 定向验证。

## 验证要求

- 改 Java：`mvn -pl <module> compile`（或全量 `mvn compile`）必须通过。
- 改上新/反风控：Playwright 必须以 `headless=false` 本地跑通并留截图证据。
- 没有验证证据不得声称完成。禁止虚构命令输出或样例数据。

## 当前进度

见 `ARCHITECTURE.md` 末尾的里程碑表。当前：**M1 地基已完成**（骨架 + 父POM + 三模块 + 分层文档，`mvn compile` 通过）。

# gofu-shared — 共享契约层

> 进入本模块前先读仓库根的 `CLAUDE.md` 和 `ARCHITECTURE.md`。

## 这个模块能做什么

只放**契约**：云端与本地之间传递的数据结构。
- `context/` — 商品全局上下文 ProductContext（系统脊柱）及其子结构
- `dto/` — 云↔本地 REST 传输对象（生图请求/响应、上下文同步包等）
- `enums/` — SKU 类型、任务状态、生图模式、上新状态机等枚举

## 绝对禁止

- ❌ 任何业务逻辑（生图、拼图、上新、HTTP 调用）
- ❌ 引入 Spring、JPA、OkHttp、Playwright 等运行时框架依赖（只允许 jackson-annotations + lombok）
- ❌ 文件 IO、网络、数据库访问
- ❌ 依赖 `gofu-server-cloud` 或 `gofu-client-local`（契约层在最底层，谁都不依赖）

## 为什么这么严

这是两端唯一的共同依赖。一旦这里塞了业务逻辑或重依赖，两端就被污染、被强耦合，"改爆"就从这里开始。保持它薄、纯、稳定。

## 改动须知

改 `context/ProductContext` 的字段属于**大任务**——它影响云端存储、本地缓存、预览页渲染三处。先 brainstorm + plan + 独立 review，再动。

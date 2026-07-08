# M6 方案：结果合流预览页（Vue3）

> 大任务：前端从零 + 接 M3-M5 全部端点 + 动到本地/云端连接方式。先审阅方案，认可后写代码。

## 目标（GOFU-AI 方案第三步）

一个 Vue3 单页"商品工作台"：读 ProductContext 渲染双轨结果（主图/详情图/标题/卖点/SKU方案/定价），人工微调（改价联动、局部重绘、字段锁定），回写 context。落地"少弹窗少操作"（理想态点击 ≤3 次）。

## 现状

- `gofu-client-local/frontend`、`electron` 目录**空**（M6 从零建）。
- `service/cloudgw`、`service/sync` 目录空（本地连云端那层未实现）。
- 本地 application.yml 已配 `gofu.cloud.base-url=http://localhost:5020`。
- 云端端点齐全：context CRUD、selling-points、sku-plans、title、ly-gen/sku-images、gen/sign 等。
- ADR-005 定：预览页用 Vue3（CDN 挂载，不引 Vite），旧页原生 JS 共存。

## 一、架构决策（需你定）：预览页怎么连云端

预览页要调的接口**大部分在云端 5020**（context/生图/双轨），少部分在本地 5021（上新）。三种接法：

- **A. 预览页直连云端 5020 + 本地 5021（前端两个 base）**：最简单，前端按接口分别调两个地址。缺点：前端要管两个地址、跨域(CORS)要配。
- **B. 本地 cloudgw 转发（前端只连本地 5021）**：本地建 cloudgw 把云端接口代理一层，前端只连 5021。符合"本地是总控中枢"，但要写一层转发。
- **C. MVP 先直连（A），cloudgw 留到打包时再做**：先让预览页跑起来，直连两个后端；等 Electron 打包/上线再引 cloudgw 统一入口。

推荐 **C**：MVP 阶段直连最快看到效果，cloudgw 是打包优化不阻塞验证。

## 二、页面结构（Vue3 单页，CDN 挂载）

`gofu-client-local/frontend/preview.html`（单文件，Vue3 CDN + 内联组件）：

```
商品工作台
├── 顶部：品类/主件录入 + 「一键双轨」按钮 + 状态徽章(DRAFT/GENERATING/READY)
├── 左栏 视觉流：主图网格(6) + 详情图(6) + 标题(可编辑) + 卖点标签(可编辑)
│         每张图 hover 出「局部重绘」按钮
├── 右栏 结构流：SKU方案切换 + 方案内型号表(specName/配件/定价)
│         改价输入框 → 前端公式实时算利润率
└── 底部：「确认上新」按钮(→ M7)
```

**三个人工微调机制**（ADR 要求）：
1. **改价联动**：改某 SKU 售价 → 前端按 `(价-成本)/价` 实时刷新利润率，无需请求后端。
2. **局部重绘**：某图不满意 → 调 `/api/gen/regenerate` 重生单张，替换。
3. **字段锁定**：人工改过的字段名加入 `context.lockedFields`；重新生成时后端跳过锁定字段（防覆盖人工心血）。

## 三、数据流

```
录入品类主件 → 一键双轨:
  ├ POST /api/gen/title          → 标题
  ├ POST /api/gen/selling-points → 卖点
  ├ POST /api/ly-gen/sku-images  → 生图(异步,轮询/api/task)
  └ POST /api/gen/sku-plans      → SKU方案
     ↓ 各自写入 context
预览页 GET /api/context/{id} 渲染全部
  ↓ 人工微调
POST /api/context (整体回写, 带 lockedFields)
图片展示：context 存 COS key → 前端用 /api/gen/sign?key= 换签名URL 显示
```

## 四、MVP 简化（避免过度设计）

- 不引 Vite/npm 构建，Vue3 用 CDN `<script>`，保持免构建（ADR-005）。
- 局部重绘、字段锁定先做核心交互，动画/美化后置。
- 生图异步轮询复用已有 `/api/task/{id}`（本地）或云端返回同步结果。
- 不做前端路由，单页 tab 切换即可。

## 五、验证方式

1. 浏览器直开 `preview.html`（或本地服务 serve），连 5020。
2. 录入品类 → 一键双轨 → 页面渲染出卖点/标题/方案/图。
3. 改价 → 利润率实时变；锁定标题 → 重新生成不覆盖；局部重绘单图替换。
4. 全程点击 ≤3 次到出结果。

## 决策点

- **A/B/C 选哪个**（预览页连云端方式）？
- **M6 范围**：完整三微调(改价/重绘/锁定)一次做，还是先渲染+改价，重绘/锁定二期？
- **图从哪显示**：生图产物是 COS key，预览页走 /api/gen/sign 换 URL 显示（需 COS 通）；还是先显示本地路径？

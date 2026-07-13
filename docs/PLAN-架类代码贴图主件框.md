# PLAN：给架类（挂钩等）建代码贴图通路 · 多件组合主件框

> 状态：**待开工（大任务）**。本文件是下个 session 的起点，避免丢上下文。
> 按项目铁律走：brainstorm → plan → 实现 → 独立 code review。

## Context（为什么做）

用户反馈：多件组合 SKU（如"GF-奶白-红吸盘红色移动挂钩-60CM-三件组合"）的 SKU 图
只显示一个产品，看不出是几件装。用户诉求：**像配件框一样，用代码画一个"主件框"，
里面画 N 个主件**（参考乐羽 `D:\code\LY-Automation` 的 Canvas 合成思路）。

成本侧的"多件不翻倍"bug 已在本轮修复（见提交 `0f26f25`）。本 plan 只解决**贴图**。

## 关键架构事实（子代理已调查确认）

1. **SKU 图按品类走三条独立路**（`gofu-server-cloud/.../lyimage/ImageGenService.java:167-455`）：
   - 花洒：AI 生"主件+背景"，左侧 **Java 代码贴配件卡**（`ShowerCompositor.drawAccCard`）✅ 有贴图
   - **架类/挂钩：整图 AI 生成，完全不走 Java 贴图**（注释"架类品防比价…不走 Java 贴图"，`:414` 起）❌ 无贴图通路
   - 非花洒非架类：`:464` 分支
2. `ShowerCompositor.drawAccCard`（`ShowerCompositor.java:294`）本质是通用"画一组产品框"：
   去重分组→横向等分格→组内 `sub=ceil(√n)` 网格铺 N 份→底条写 label→左侧红「+」圆。
   **画 N 个主件可直接复用**，绘制层几乎白嫖（`drawImageFit`/`whiteToTransparent`/`drawFitText` 都可复用）。
3. 前端不做任何 canvas 合成，只组装 `accParts`（`{code,qty,kw}`）传后端。
4. **主件白底图目前被前端排除**（`app.js` 注释"排除已用作主件白底图的颜色图"）——
   要新开一条数据通路把"主件图 + 数量 N"喂进 compositor。这是真正的工作量所在。

## 实现要点（待 brainstorm 细化）

### 后端（gofu-server-cloud）
- **绘制层**：给 `drawAccCard` 加 `boolean showPlusBadge`（主件框传 false，去掉配件的红「+」）。
  新增薄封装 `compositeMainCardAt(base, region, List<File> mainImgs, List<String> labels, ...)`，
  内部构造后直接调 `drawAccCard`。**不碰 shared/context。**
- **架类分流改造（核心难点）**：架类现在 `:414` 整图 AI、不走贴图。要决策：
  - 是"架类多件组合"专门走一条 AI 生底图 + Java 贴 N 个主件框？
  - 还是保持整图 AI，只在 prompt 里要求画 N 个？（用户已否决：AI 一次生多件一致性差）
  - → **倾向前者**：架类多件 SKU = AI 生单主件底图 + Java 主件框贴 N 份，绕开一致性问题。
- **数据通路**：`generateSkuImage` 增主件白底图入参；编排层把被排除的主件色图放开传入。
- 件数 N：后端从 SKU 名/spec2 解析（前端已有 `mainQtyFromSpec` 正则，可对齐一份到后端）。

### 前端（gofu-client-local）
- 放开"主件白底图被排除"的逻辑，把主件图 + 件数传给云端生图入参。
- 确认 `mainQtyFromSpec`（已在 app.js/preview.html/workbench.html 定义）解析口径与后端一致。

## 版式设计（brainstorm 待定）
- N 个主件横排 / 品字？主件放大、配件退次要位？
- 主件框和现有配件框、底部通栏的位置关系（架类底图没有花洒的固定构图）。
- 标签：件数怎么标（"三件组合"/"×3"角标）。
- 投影一致性（贴上去别浮空——和主图浮空 bug 同源，见 [[image2image-no-large-rotation]] 记忆）。

## 验证要求
- `mvn -pl gofu-server-cloud -am test` 通过。
- **必须实机生图**：挂钩三件组合 SKU 图真的出现 3 个主件、结构一致、不浮空、件数可见。
  Playwright/实机留图，否则不算完成（项目铁律）。

## 相关文件
- `gofu-server-cloud/src/main/java/com/gofu/cloud/service/lyimage/ShowerCompositor.java`
  （`drawAccCard`@294、`compositeAccCardAt`@152、`compositeShowerLeft`@59、`drawImageFit`@457、`whiteToTransparent`@466、`drawFitText`@378）
- `gofu-server-cloud/src/main/java/com/gofu/cloud/service/lyimage/ImageGenService.java`
  （`generateSkuImage`@155、accParts解析@238-346、compositor调用@598/@611、架类分支@414）
- 参考：`D:\code\LY-Automation\src\main\java\com\lyauto\service\ShowerCompositor.java`（逻辑同源）
- 前端件数解析：`gofu-client-local/.../static/leyu/app.js`、`preview.html`、`workbench.html` 的 `mainQtyFromSpec`

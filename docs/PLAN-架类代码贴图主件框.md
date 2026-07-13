# PLAN：给架类（挂钩等）建代码贴图通路 · 多件组合主件框

> 状态：**代码已完成，待实机验证**（07.13 完成 brainstorm→plan→实现3段→独立review→修复）。
> 剩最后一关：实机生图确认贴图效果（见文末验证要求）。
> 相关提交：mainQty 生成端/成本端/贴图端/数据通路/review修复 共 5 个 commit。

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

## 🔑 根节点发现（07.12，必读，决定实现顺序）

亲验 `LyTextService.java:48-65` 提示词：**"主件卖几个"(多件装)这个维度根本不在 AI 生成设计里**。
型号(models)维度只表示"加装了哪些配件"，且明确禁止层数/规格档位词进型号名（正交两维原则）。
截图里的"双件/三件组合"是 AI **偶发自由发挥、格式不可控**。

→ **用正则解析文本(已提交的 mainQtyFromSpec)是流沙方案**：AI 今天"三件组合"明天"3件套"。
**正解：让 AI 输出结构化字段 `mainQty`(整数)，下游读字段不猜文本。**

### 正确实现顺序（自上而下，三段）
1. **生成端（前置·大任务）**：`LyTextService` 提示词正式加"主件数量档位"维度 +
   要求 JSON 输出每个型号带 `mainQty` 整数字段(默认1)；specName 按固定格式体现(如"N件装")。
   DTO/拍平 `buildItem` 携带 mainQty 到 item。**碰生成结构，属大任务。**
2. **解析/成本端**：前端改成读 `it.mainQty` 结构化字段(替代 mainQtyFromSpec 正则)；
   若 mainQty 缺失(老数据)才回退正则兜底。成本主件 qty 用 mainQty。
3. **贴图端**：架类 AI 单主件底图 + Java 主件框 ×N 角标(下详)。

> 注意：已提交的 `mainQtyFromSpec` 正则(app.js/preview/workbench)先留作兜底，
> 等 mainQty 结构化字段落地后，正则降级为"字段缺失时的 fallback"。

## 已定设计决策（07.12 brainstorm，用户拍板）

1. **底图**：AI 整图生**单主件**场景底图（走现有架类防比价分支 `ImageGenService.java:414`），
   再用 Java 在底图上贴主件框。不是纯白底合成。
2. **范围**：仅**多件 SKU**贴框；单件架类 SKU 保持现状（整图 AI）不变。
3. **版式**：**主件放大居中 + ×N 角标**（不平铺 N 份）。→ 不能直接套 drawAccCard 的 √n 平铺，
   需新写"放大单图 + 右上角 ×N 徽章"绘制（角标可仿 drawAccCard 左侧红圆的画法）。
4. **⚠️ 件数解析隐患**：用户明确 SKU 名**不一定**是"双件组合/N件组合"，AI 可能写成
   **"几件装"**（如"三件装""3件装"）。当前已提交的前端 `mainQtyFromSpec` 正则只认
   "件组合/件套"，**会漏判"几件装"**——本任务开工第一件事：查 `LyTextService` 提示词
   确认 AI 被要求的所有多件命名写法，放宽正则（前后端一份），否则成本翻倍和贴框都会漏。

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

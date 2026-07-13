# PLAN：生图质量四条（手部违和 / 锅盖浮空 / 一致性+卖点文案 / 优化羽刃分析）

> 状态：**#1#2 已改待实机验证；#3卖点=非prompt问题(查日志)；#3一致性/#4 暂缓**（07.13）。
> - #1 手部违和：强化 ImageGenerationService:518 手部范例(逻辑自洽/宁可不加手)。已改。
> - #2 锅盖浮空：真因是架类SKU走 image-shelf-main.txt、缺收纳物防浮空约束(structLock只在主图流)。已补【收纳物·物理支撑·严禁浮空】。
> - #3 卖点文案缺失：排查 withText 开关正常(硬编码true)、字段完整→非prompt bug，属降级本地兜底(有warn日志)或模型渲染，需实机看日志。
> - #3 一致性 / #4 羽刃分析：约束已饱和(8维物理约束/卖点→画面已一一对应)，堆字会稀释，等 #1#2 实机效果再决定。
> 全在 gofu-server-cloud 生图侧。

## Context（为什么做）
用户对比羽刃(yuren=全生图参考)后指出我们主图/SKU 图仍有质量问题。羽刃 6 条卖点样例：
涡轮增压/120mm大面板全覆盖/四档出水一键切换/液态硅胶自洁防堵/加厚防爆结实耐用/喷枪模式清洁全屋
——它是"卖点→对应功能视觉画面"一一映射，可参考优化我们的 AI 分析。

## 四条问题（用户原话）
1. **#1 手部违和**：某图左下角违背现实逻辑（像手已拿下来却还…）。方向：可做"人手已拿下来"
   的合理化画面，或去掉违和的手部元素。
2. **#2 锅盖浮空**：锅盖架的锅盖浮空、违背现实逻辑。与既有浮空根因同源
   （见记忆 [[image2image-no-large-rotation]] + [[structlock-bidirectional-fidelity]]）：
   被收纳物(锅盖)与产品的支撑受力必须符合物理，不穿模不悬空。structLock 文案已有此约束
   （FlowController.java:846 附近），需查为何锅盖仍浮空——可能约束不够强或未命中架类判定。
3. **#3 产品一致性 + 卖点文案缺失**：多张间产品不一致；卖点文案没渲染出来。
   一致性见 buildSeriesPrompt 的系列连贯性约束(FlowController.java:855-865)；
   卖点文案缺失查 buildMainPrompt 的【画面文案】段(706-732)+seriesPlan 分配是否生效。
4. **#3附 优化羽刃 AI 分析**：让分析阶段产出"每条卖点配一个具体可视化画面"的规划，
   而非泛泛生成。涉及 analyzeCustomImagePrompts / seriesPlan 生成逻辑。

## 相关文件
- `gofu-server-cloud/src/main/java/com/gofu/cloud/controller/FlowController.java`
  （buildMainPrompt@706、buildSeriesPrompt@830、buildAngleConstraint@881、structLock文案@840-846、背景分析@462）
- `gofu-server-cloud/src/main/java/com/gofu/cloud/service/ImageGenerationService.java`
  （analyzeCustomImagePrompts / seriesPlan 相关）
- 参考审美：羽刃 `D:\code\gofu-ai\gofu-client-local\src\main\resources\static\yuren\`

## 验证要求
必须实机生图留图：手部不违和、锅盖有支撑不浮空、多张产品一致、卖点文案渲染出来。
Playwright/实机 headless=false 留截图，否则不算完成（项目铁律）。

## 顺序建议
建议在 A（多件主件框）之后或穿插做。#2 锅盖浮空可优先（与已有 structLock 同源，改动集中）。

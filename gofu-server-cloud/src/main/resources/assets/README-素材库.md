# GOFU-AI 素材库索引

> 白底图、被收纳物、防比价参考图的统一说明。新增素材按此归位。

## 目录结构

| 目录 | 装什么 | 命名约定 | 谁在用 |
|------|--------|----------|--------|
| `assets/base/` | **架体白底基准图**（架类品主体） | `shelf-<品种>.jpg/png`，变体加后缀如 `-米奇款` | `PromptTemplateService.builtinBaseByName("shelf-"+kind)` |
| `assets/collectibles/` | **被收纳物白底图**（锅盖/砧板/毛巾等，往架子上贴） | `<物品>-<特征>.jpg`，如 `锅盖-玻璃不锈钢钮.jpg` | 架类 Java 合成（往架子凹槽贴收纳物） |
| `../../docs/assets/showcase/` | 成品展示图（对外介绍用） | 描述性中文名 | 文档展示 |
| `./大参考`（配置 `app.reference-dir`，运行时目录，不在仓库） | 防比价参考图（换风格/基调的参考库） | — | 生图 refImage |

## 现有架体白底（assets/base/）
- shelf-刀架.jpg
- shelf-吸盘锅盖架.jpg
- shelf-挂钩.jpg
- shelf-沥水收纳架.jpg
- shelf-浴室转角置物架.jpg
- shelf-落地锅盖架.jpg
- **shelf-落地锅盖架-米奇款.png**（2026-07-15 新增，米奇造型+红蝴蝶结+3斜插槽+红接水盘）

## 现有被收纳物（assets/collectibles/，2026-07-15 新建）
- **砧板-竹木圆孔.jpg**（竹木、带圆孔手柄、3/4角度）
- **锅盖-玻璃不锈钢钮.jpg**（玻璃+不锈钢圈+圆钮、3/4角度）

## 备注
- 微信/临时目录的素材必须复制进本库永久保存（temp 会被清）。
- 架类 Java 合成方案见 `docs/架类Java合成-方案核对-0714.md`。

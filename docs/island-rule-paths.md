<!-- SPDX-License-Identifier: GPL-3.0-only -->
<!-- Copyright (C) ImKani. Project: FocusRestore. Source: https://github.com/ImKani/FocusRestore -->

# 正文规则规范路径分级 / Canonical text-rule path classification

阶段 C1 路径登记，未接入通知或运行引擎。来源基线为 `2b1d30d0ca507b14fd143a6ecaf3da14de1ce1a8`（FocusRestore 0.48.2/code345）。结构字段的来源行号精确指向实际叶值读取；自由文本来源可指对应读取方法或调用起点。全部来自本项目既有解析器，不复制外部 ROM 实现。

运行登记见 [IslandRulePaths.java](../app/src/main/java/com/hyperos3/focusrestore/IslandRulePaths.java)。106 条规范路径、14 组；不开放 `paramIsland` 别名，`W_ALIAS_PATH` 仅预留、不触发。`miui.focus.paramtextInfo` 是完整键名，不按点拆分。`legacy` 从原始根读取，其余组遵循已选容器；本登记不增加容器搜索行为。

分级按字段语义：`structural` / `STRUCTURAL` 指 progress 进度数值、step 步骤编号等非自由文本语义；`freeText` / `FREE_TEXT` 指标题、正文、标签等自由文本及无法判定项；`unsafe` / `UNSAFE` 指禁止能力、对象或超范围读取，拒绝运行读取。枚举仅记录字段语义：最近记录编辑示例优先使用 freeText，缺少时回退 structural；运行期 structural 与 freeText 均不脱敏，unsafe 拒绝。0.55.0 已移除捕获导出，分级不再承担导出占位。

统计：**structural=11，freeText=95，unsafe=0**。允许名单只接受本登记实例且级别不是 unsafe；未登记项（包括别名、对象父节点、图标、计时器、动作、文件、URL 路径）一律不获读取权限。

这是按已裁决字段语义的分类，**不是运行时输入类型证明，也不代表 structural 输入已经过数值校验**。现有 progress 由 `percentageText` 在 [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) 读取任意 `Object`，在 [IslandPayloadParser.java:408](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L408) 使用 `String.valueOf`，仅补 `%`，没有数值范围或类型校验。step 在 [IslandPayloadParser.java:148](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L148) 作为步骤字段传入 `joinTexts`，经 [IslandPayloadParser.java:392](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L392) 调用 `firstText`，实际叶读取在 [IslandPayloadParser.java:460](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L460)，随后同样字符串化标量而无步骤编号校验。因此不能由这些源码断言应用无法在 structural 字段填入文字。

## 分组统计 / Group counts

规则可用 `meta.templateType: "mixed"` 按用户指定的顺序组合多个组的登记路径。例如 `/ticker`、`/title`、`/baseInfo/title` 分别从选中容器、原始根、选中容器读值。每条路径仍按表中的所属组定位，显示顺序由 `display.order` 指定。`mixed` 只是选择器，登记组总数仍是 14。

| 模板组 / Group | 路径数 / Count |
|---|---:|
| `baseInfo` | 6 |
| `highlightInfo` | 3 |
| `highlightInfoV3` | 4 |
| `chatInfo` | 2 |
| `iconTextInfo` | 3 |
| `animTextInfo` | 2 |
| `coverInfo` | 3 |
| `hintInfo` | 4 |
| `stepInfo` | 4 |
| `progressInfo` | 8 |
| `multiProgressInfo` | 8 |
| `param_island` | 54 |
| `fallback` | 2 |
| `legacy` | 3 |

## Structural 全量证据 / All structural entries

以下 11 条按非自由文本字段语义分类；每条都记录实际叶读取行。progress 的百分比格式处理在 [IslandPayloadParser.java:408–409](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L408-L409)，其 title/content/label 兄弟项仍为 freeText。嵌套 progressInfo 经 [IslandPayloadParser.java:400–401](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400-L401) 转给同一读取器；三个岛数字节点经 [IslandPayloadParser.java:428–429](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428-L429) 调用 `progressText`。

| structural 路径 | 精确叶读取源码 | 非自由文本语义及调用证据 |
|---|---|---|
| `/stepInfo/step` | [IslandPayloadParser.java:460](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L460) | 步骤编号；[148行](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L148) 明确传入 step 键，经 joinTexts/firstText 读取，未校验编号类型。 |
| `/progressInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 单进度数值/百分比；[143行](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L143) 进入 progressText，再经398行读取进度叶。 |
| `/progressInfo/progressInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 单进度的嵌套进度数值；143行入口、400–401行嵌套对象回退，仍由 percentageText 读取。 |
| `/multiProgressInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 多进度入口的进度数值/百分比；[139行](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L139) 进入 progressText，经398行读取。 |
| `/multiProgressInfo/progressInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 多进度的嵌套进度数值；139行入口、400–401行嵌套对象回退，仍由 percentageText 读取。 |
| `/param_island/bigIslandArea/progressTextInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 岛进度文本节点中的进度数值；428–429行选择 progressTextInfo 并调用 progressText，经398行读取。 |
| `/param_island/bigIslandArea/progressTextInfo/progressInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 岛进度文本节点的嵌套进度数值；428–429行入口、400–401行嵌套回退。 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 固定宽数字节点中的进度数值；428–429行选择 fixedWidthDigitInfo 并调用 progressText，经398行读取。 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 固定宽数字节点的嵌套进度数值；428–429行入口、400–401行嵌套回退。 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 等宽数字节点中的进度数值；428–429行选择 sameWidthDigitInfo 并调用 progressText，经398行读取。 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/progress` | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) | 等宽数字节点的嵌套进度数值；428–429行入口、400–401行嵌套回退。 |

## 完整分级清单 / Complete classification

下表逐项列出全部 106 条路径，包含全部 95 条 freeText（无法明确判断者也归入此类）；顺序与注册表、阶段 B 规范路径枚举一致。来源文件为 [IslandPayloadParser.java](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java)。

| 规范路径 / Canonical path | 模板组 / Group | 级别 / Level | 来源与行号 / Source and line |
|---|---|---|---|
| `/baseInfo/title` | `baseInfo` | freeText | [IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/subTitle` | `baseInfo` | freeText | [IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/specialTitle` | `baseInfo` | freeText | [IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/extraTitle` | `baseInfo` | freeText | [IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/content` | `baseInfo` | freeText | [IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/subContent` | `baseInfo` | freeText | [IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/highlightInfo/title` | `highlightInfo` | freeText | [IslandPayloadParser.java:104](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L104) |
| `/highlightInfo/content` | `highlightInfo` | freeText | [IslandPayloadParser.java:104](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L104) |
| `/highlightInfo/subContent` | `highlightInfo` | freeText | [IslandPayloadParser.java:104](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L104) |
| `/highlightInfoV3/primaryText` | `highlightInfoV3` | freeText | [IslandPayloadParser.java:109](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L109) |
| `/highlightInfoV3/secondaryText` | `highlightInfoV3` | freeText | [IslandPayloadParser.java:109](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L109) |
| `/highlightInfoV3/highLightText` | `highlightInfoV3` | freeText | [IslandPayloadParser.java:109](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L109) |
| `/highlightInfoV3/label` | `highlightInfoV3` | freeText | [IslandPayloadParser.java:109](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L109) |
| `/chatInfo/title` | `chatInfo` | freeText | [IslandPayloadParser.java:114](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L114) |
| `/chatInfo/content` | `chatInfo` | freeText | [IslandPayloadParser.java:114](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L114) |
| `/iconTextInfo/title` | `iconTextInfo` | freeText | [IslandPayloadParser.java:118](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L118) |
| `/iconTextInfo/content` | `iconTextInfo` | freeText | [IslandPayloadParser.java:118](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L118) |
| `/iconTextInfo/subContent` | `iconTextInfo` | freeText | [IslandPayloadParser.java:118](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L118) |
| `/animTextInfo/title` | `animTextInfo` | freeText | [IslandPayloadParser.java:124](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L124) |
| `/animTextInfo/content` | `animTextInfo` | freeText | [IslandPayloadParser.java:124](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L124) |
| `/coverInfo/title` | `coverInfo` | freeText | [IslandPayloadParser.java:128](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L128) |
| `/coverInfo/content` | `coverInfo` | freeText | [IslandPayloadParser.java:128](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L128) |
| `/coverInfo/subContent` | `coverInfo` | freeText | [IslandPayloadParser.java:128](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L128) |
| `/hintInfo/title` | `hintInfo` | freeText | [IslandPayloadParser.java:134](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L134) |
| `/hintInfo/subTitle` | `hintInfo` | freeText | [IslandPayloadParser.java:134](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L134) |
| `/hintInfo/content` | `hintInfo` | freeText | [IslandPayloadParser.java:134](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L134) |
| `/hintInfo/subContent` | `hintInfo` | freeText | [IslandPayloadParser.java:134](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L134) |
| `/stepInfo/title` | `stepInfo` | freeText | [IslandPayloadParser.java:147](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L147) |
| `/stepInfo/content` | `stepInfo` | freeText | [IslandPayloadParser.java:147](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L147) |
| `/stepInfo/subContent` | `stepInfo` | freeText | [IslandPayloadParser.java:147](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L147) |
| `/stepInfo/step` | `stepInfo` | structural | [IslandPayloadParser.java:460](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L460) |
| `/progressInfo/title` | `progressInfo` | freeText | [IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/progressInfo/content` | `progressInfo` | freeText | [IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/progressInfo/label` | `progressInfo` | freeText | [IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/progressInfo/progress` | `progressInfo` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/progressInfo/progressInfo/title` | `progressInfo` | freeText | [IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/progressInfo/progressInfo/content` | `progressInfo` | freeText | [IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/progressInfo/progressInfo/label` | `progressInfo` | freeText | [IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/progressInfo/progressInfo/progress` | `progressInfo` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/multiProgressInfo/title` | `multiProgressInfo` | freeText | [IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/multiProgressInfo/content` | `multiProgressInfo` | freeText | [IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/multiProgressInfo/label` | `multiProgressInfo` | freeText | [IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/multiProgressInfo/progress` | `multiProgressInfo` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/multiProgressInfo/progressInfo/title` | `multiProgressInfo` | freeText | [IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/multiProgressInfo/progressInfo/content` | `multiProgressInfo` | freeText | [IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/multiProgressInfo/progressInfo/label` | `multiProgressInfo` | freeText | [IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/multiProgressInfo/progressInfo/progress` | `multiProgressInfo` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/param_island/title` | `param_island` | freeText | [IslandPayloadParser.java:412](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L412) |
| `/param_island/content` | `param_island` | freeText | [IslandPayloadParser.java:412](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L412) |
| `/param_island/frontTitle` | `param_island` | freeText | [IslandPayloadParser.java:412](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L412) |
| `/param_island/bigIslandArea/imageTextInfoLeft/textInfo/frontTitle` | `param_island` | freeText | [IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/textInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/textInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/textInfo/subContent` | `param_island` | freeText | [IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/frontTitle` | `param_island` | freeText | [IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/subContent` | `param_island` | freeText | [IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoRight/textInfo/frontTitle` | `param_island` | freeText | [IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/textInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/textInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/textInfo/subContent` | `param_island` | freeText | [IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/frontTitle` | `param_island` | freeText | [IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/subContent` | `param_island` | freeText | [IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/textInfo/frontTitle` | `param_island` | freeText | [IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/textInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/textInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/textInfo/subContent` | `param_island` | freeText | [IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/miui.focus.paramtextInfo/frontTitle` | `param_island` | freeText | [IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/miui.focus.paramtextInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/miui.focus.paramtextInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/miui.focus.paramtextInfo/subContent` | `param_island` | freeText | [IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/progressTextInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/progressTextInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/progressTextInfo/label` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/progressTextInfo/progress` | `param_island` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/param_island/bigIslandArea/progressTextInfo/progressInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/progressTextInfo/progressInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/progressTextInfo/progressInfo/label` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/progressTextInfo/progressInfo/progress` | `param_island` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/label` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progress` | `param_island` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/label` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/progress` | `param_island` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/param_island/bigIslandArea/sameWidthDigitInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/sameWidthDigitInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/sameWidthDigitInfo/label` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progress` | `param_island` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/title` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/content` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/label` | `param_island` | freeText | [IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428) |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/progress` | `param_island` | structural | [IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/param_island/smallIslandArea/title` | `param_island` | freeText | [IslandPayloadParser.java:430](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L430) |
| `/param_island/smallIslandArea/content` | `param_island` | freeText | [IslandPayloadParser.java:430](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L430) |
| `/param_island/smallIslandArea/subContent` | `param_island` | freeText | [IslandPayloadParser.java:430](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L430) |
| `/aodTitle` | `fallback` | freeText | [IslandPayloadParser.java:167](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L167) |
| `/ticker` | `fallback` | freeText | [IslandPayloadParser.java:176](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L176) |
| `/title` | `legacy` | freeText | [IslandPayloadParser.java:96](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L96) |
| `/desc1` | `legacy` | freeText | [IslandPayloadParser.java:96](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L96) |
| `/desc2` | `legacy` | freeText | [IslandPayloadParser.java:96](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L96) |

## Unsafe 全量清单 / All unsafe entries

无（0 条）。这是对已有 106 条候选文本叶的分类结果，不意味着未登记路径安全。`UNSAFE` 枚举与拒绝分支保留，未来登记变动不得据此扩大既有读取权限。

English: the 106 canonical leaves comprise 11 structural fields (ten progress values and one step number), 95 free-text fields and zero unsafe entries. This is a semantic classification, not proof of numeric input validation: the existing parser stringifies these values. For capture/export policy, structural values are retained by default and free-text values require placeholders. Both allowed categories remain unredacted during runtime display; unsafe and unknown paths are denied. Stage C1 records policy metadata only; it implements no capture, export substitution or runtime processing.

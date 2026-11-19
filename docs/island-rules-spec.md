# FocusRestore 自定义超级岛正文规则规范 / Custom island text rule specification

> **阶段 C1 合同 / Stage C1 contract — 仅不可变模型、严格解码与路径登记；无规则执行或设备接入 / Models, strict decoding and path registry only; no rule execution or device integration.**
> ImKani · FocusRestore · [项目 / Project](https://github.com/ImKani/FocusRestore) · SPDX-License-Identifier: GPL-3.0-only.
> 源码核对基线 / Source baseline: `2b1d30d0ca507b14fd143a6ecaf3da14de1ce1a8` (0.48.2/code345)。行号均对应此提交；此文档不声明任何运行期或实机验收。
> All source lines refer to this commit. Schema/example checks are not Android or ROM validation.

## 1. 范围与不可变约束 / Scope and invariants

后续接入时，规则仅作用于 `extractIslandContent()` 中的全文正文选择。自定义规则按 §2 顺序逐条尝试，首个有效非空且通过 R 的结果胜出；单条未命中、失败、超时或后处理为空继续下一条，关闭或全部无效才执行原有内置路径：按原设置尝试 `parseCompact`，必要时 `parse`。全文模式本来不调用 compact，仍不新增 compact 调用。简略模式不尝试规则。不得把“全文失败回退”改为重写/删改内置解析器。C1 不接入此入口。
At a later integration stage, rules affect full-text selection only. Evaluate in §2 order until the first valid nonempty R-approved result; individual misses, failures, timeouts and empty outputs continue. Disabled rules or total exhaustion use the unchanged mode-dependent built-in path. Compact mode does not run rules. C1 does not integrate this entry.

来源 / Evidence: [HyperOS3FocusRestoreHook.java:5950–5975](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L5950); [IslandPayloadParser.java:85–185](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L85).

外围 `shouldConvert`、原生焦点优先、隐私、通知存活/代次、图标、计时器、RemoteViews 与展开卡片完全保留。规则包白名单与强制接管应用名单不同；规则不能绕过任一外围门控。
Keep conversion eligibility, native-focus priority, privacy, notification liveness/generation, icons, timers, RemoteViews and expanded cards unchanged. Rule package filtering never grants notification conversion permission.

来源 / Evidence: [HyperOS3FocusRestoreHook.java:5541–5563](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L5541); [IslandClockTimer.java:31–73](../app/src/main/java/com/hyperos3/focusrestore/IslandClockTimer.java#L31); [FocusBannerController.java:507](../app/src/main/java/com/hyperos3/focusrestore/FocusBannerController.java#L507).

本规范、Schema 与样例共同约束阶段 C1 手写 Java 8 解码器；无新增依赖，不在运行期下载 Schema。不启用已废弃的 `island_custom_rules`。后文 L2/L3/R、运行选择、脱敏、缓存及捕获合同属于后续阶段要求，C1 不实现，也不接入 `extractIslandContent()`、SystemUI、设置或 UI。
This specification, Schema and samples define the handwritten Java 8 C1 decoder contract. No new dependency, runtime Schema fetching or revival of `island_custom_rules` is allowed. L2/L3/R, rule evaluation, redaction, caching and capture remain later-stage requirements; C1 does not integrate with SystemUI, settings, UI or the shared extraction entry.

## 2. 文件与顶层合同 / File and top-level contract

一个 UTF-8 JSON 文件是一条规则；禁止 BOM、重复键、尾随内容、非法 UTF-8、未配对代理项、非有限数及非标准 JSON。文件含所有空白的字节数 ≤ **65,536 bytes (64 KiB)**。独立仓库可持有多条规则，但本版文件不是数组或内嵌包格式。
One UTF-8 JSON object per rule file. Reject BOM, duplicate keys, trailing data, invalid UTF-8, lone surrogates, non-finite numbers and nonstandard JSON. The 65,536-byte limit includes whitespace. A future repository may hold multiple files; this format is not a multi-rule envelope.

规则文件 ≤65,536 UTF-8 字节是 L1 有界读取合同，包含所有空白和 `x-*` 注解；`x-maxUtf8Bytes` 仅是 Schema 注解。读入器在构造超额对象前拒绝：**JSON 对象/数组容器嵌套深度 ≤8**，根容器计 1，标量不增加容器深度；**每对象 ≤64 键，每数组 ≤32 项，键名 ≤256 UTF-16 单元，字符串值和 JSON 数字 token 各 ≤4096 UTF-16 单元**。已知业务字段还须满足 Schema 更严格的逐字段/逐数组限制。此 JSON 深度与 §5 的提取路径最多 8 段是不同的边界。
The bounded reader caps each rule at 65,536 UTF-8 bytes, including whitespace and annotations. JSON object/array container depth is at most 8 (root container = 1; scalars add no container depth), with at most 64 keys per object, 32 items per array, 256 UTF-16 units per key, and 4096 UTF-16 units per string or number token. Known fields additionally obey their stricter Schema bounds. JSON depth is separate from the eight-segment extraction-path limit.

Schema: [rule.schema.json](rule.schema.json), draft 2020-12。所有已知结构对象拒绝普通未知属性，仅以 `patternProperties` 放行 `x-*` 注解。注解值允许任意标准 JSON，仍完整检查严格编码、重复键、字节/容器深度/键数/数组项数/字符串及数字 token 限额；注解内部键不作业务未知字段或禁止能力检查。整个注解不进入运行模型，也不赋予任何能力。Schema 文件本身保持 ≤64 KiB。
Known rule-structure objects reject ordinary unknown properties and allow only `x-*` annotations through `patternProperties`. Annotation values may contain arbitrary standard JSON under every reader limit; their internal keys are data, not business fields or capabilities. Annotations are omitted from the runtime model. The Schema document itself remains below 64 KiB.

`default` 是 Schema 文档注解；解码器按本合同建立不可变模型的缺省值，不改写输入。Schema `maxLength` 按 Unicode 码点计数；C1 另检查同值的 UTF-16 单元限额。所有字段严格类型，禁止字符串/布尔/数值互转；integer 按数学整数判断，接受数值 `1.0`、`1e0`，拒绝字符串 `"1"`。字节大小、重复键、日历日期、UTF-16 限额、L2/L3/R 不能仅靠 JSON Schema 完成。
Schema defaults document model defaults without mutating input. Schema string lengths count code points; C1 also enforces UTF-16-unit limits. No loose coercion is allowed. Integer fields accept mathematically integral JSON numbers such as `1.0` and `1e0`, never the string `"1"`. Strict byte, duplicate-key, calendar and UTF-16 checks require handwritten code; L2/L3/R remain later stages.

| 字段 / Field | 类型 / Type | 必填 / Required | 取值 / Values | 缺省 / Default | 说明 / Meaning |
|---|---|---|---|---|---|
| `ruleVersion` | integer | 是 / yes | `1` | 无 / none | 文件协议版本，不是 app 版本 / format version, not app version |
| `meta` | object | 是 / yes | §3 | 无 / none | 来源、适配声明 / provenance and compatibility declarations |
| `match` | object | 是 / yes | `all`: 1–16 conditions | 无 / none | 全部成立，不执行代码 / AND-only matching |
| `extract` | array<object> | 是 / yes | 1–32 bindings | 无 / none | 有序提取定义，id 唯一 / ordered bindings with unique ids |
| `display` | object | 是 / yes | §6 | 无 / none | 仅正文文本投影 / text-only projection |

`IslandRuleSet` 的活跃规则 ≤50，**全部条目**原始规则文件累计 ≤2 MiB（2,097,152 字节），每条仍 ≤65,536 UTF-8 字节。`priority` 仅来自外部 `IslandRuleSet.Entry`，默认 0，不增加规则 JSON 字段。**声明顺序是 Entry 在 IslandRuleSet 中的导入/添加顺序**；单文件仅一条规则，不存在文件内规则顺序，也不以文件名或存储枚举顺序替代 Entry 顺序。后续执行按 **priority 降序 → 声明顺序**稳定遍历；单条失败、异常或超时继续下一条，首个匹配、非空且通过 R 的结果胜出，全部无效才返回 fallback 信号。C1 只提供不可变模型与容量边界，不实现排序执行或调用内置解析器。
`IslandRuleSet` permits at most 50 active rules and 2 MiB (2,097,152 bytes) of original rule files across all entries; each remains capped at 65,536 bytes. Priority belongs only to the external set entry and defaults to 0. Declaration order means the entries' import/addition order in the set. Each file has only one rule, so there is no within-file rule order; filenames and storage enumeration do not replace entry order. Later evaluation is stable by descending priority then declaration order; failures continue to the next rule, and only exhaustion produces fallback. C1 models these inputs without executing rules or invoking the built-in parser.

后续运行配额冻结为单规则字段读取 ≤100、字符串操作 ≤10、去重 ≤1、提取读取路径 ≤8 段，执行预算 50 ms；超时 `R_TIMEOUT`、异常 `R_EXCEPTION`，均继续下一条。force 不增大配额，首版禁用 regex。耗时检查须配合有界声明式操作，不能将 Java 线程中断或普通时钟检查宣传为强抢占沙箱；这些 R 检查不属于 C1 实现。
Later runtime quotas are at most 100 field reads, 10 string operations, one deduplication, eight path segments and 50 ms per rule. Timeout (`R_TIMEOUT`) and exception (`R_EXCEPTION`) continue with the next rule. Force cannot enlarge limits. Regex remains disabled. These bounded-runtime requirements are not implemented in C1 and do not claim a preemptive Java sandbox.

**C3-1 本轮裁决补充计数单位，不改变既有上限 / Current C3-1 decision clarifies counting; existing limits are unchanged.**

- 字段读取：每次实际解析 match 的一个 Context 字段、探测一个容器候选成员、查找一个提取路径段，各计 1；查找结果缺失或为 `null` 也计入。同一次逻辑查找区分缺失和 `null` 只计 1。模板组入口的首段查找在本规则内复用，见 §5；其余路径读取不跨绑定合并计数。display 引用已提取绑定不重复读取通知字段。 / Field reads: count one per actual match Context field resolution, container-candidate member probe or extraction-path segment lookup, including missing or null results. Distinguishing missing from null within one logical lookup does not count twice. Reuse the template-entry first segment within the rule as in §5; do not merge other path reads across bindings. Display references reuse extracted bindings without rereading notification fields.
- 字符串操作：每执行一个 `{from,to}` 的字面替换扫描计 1，即使没有命中；整次 display 拼接计 1。单条规则的各执行阶段共用计数，不重置。match 比较不计此项，仍受条件数、字段读取数、长度和时间边界约束；标量转文本、代理项检查及固定过滤、归一化、截断也不计此项，仍须遵守各自边界。 / String operations: count one per executed literal `{from,to}` replacement scan, even if it finds no matches, and one for the display join as a whole. Share this counter across the phases of a rule without resetting it. Match comparisons do not count here but remain subject to condition, field-read, length and time limits. Scalar conversion, surrogate validation and fixed filtering, normalization and truncation also do not count here and remain bounded.
- 去重：启用时对有序且规范化后的 display 片段执行一次处理，独立计 1；未启用计 0，内部比较不重复计数。 / Deduplication has its own counter: one pass over the ordered, normalized display fragments if enabled, zero otherwise, with no separate count for internal comparisons.

`templateType` 指定提取范围。原有组名只接受本组路径，显式 `mixed` 允许每个绑定按其登记组读取。模板组检查属于提取阶段，C2 选规则层仍仅检查 §4 的四个 match 字段。普通单组的入口不适配使整条规则失败，`mixed` 中的缺失或类型错误按每个绑定的 required/optional 声明处理（§5）。失败后继续下一条规则。
`templateType` selects the extraction scope. A group name restricts bindings to that group. The `mixed` selector resolves each binding within its registered group. C2 selection evaluates only §4 match predicates. An invalid ordinary single-group entry fails its rule. In `mixed`, each binding's required/optional flag governs missing or wrong-type members (§5). A failed rule yields to the next candidate.

## 3. meta 字段表 / Metadata fields

未提供的可选字段按 unknown 处理，不当作空串匹配；显式 `null` 也表示 unknown。未知设备、版本、tag 不得从文件名或其他通知推测。适配说明不授予权限。
Absent optional fields and null mean unknown. Never invent device/version/tag values. Metadata does not grant execution or access rights.

| 字段 / Field | 类型；必填；上限 / Type; required; limit | 默认 / Default | 用途、来源与可用状态 / Meaning, evidence and availability |
|---|---|---|---|
| `packageName` | string; yes; 255 | none | 精确包名；R 必须对照真实通知包名及用户白名单 / exact package, mandatory R gate. [Hook:6724](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6724) |
| `focusNotificationName` | string/null; no; 128 | unknown | 作者说明性名称，不是已核实的系统唯一字段；**待确认，不进入运行匹配白名单** / descriptive only; runtime source pending |
| `notificationTag` | string/null; no; 255 | unknown | 目标通知 tag 声明；当前通用提取点未核实对应读取，**待确认，不进入 v1 match** / pending source, not a v1 predicate |
| `systemUiVersion` | `{name:string≤128,code:integer≥0}`/null; no | unknown | L2 环境声明；name/code 精确相等，不做字符串区间比较 / exact L2 environment. [SettingsActivity:1182–1192](../app/src/main/java/com/hyperos3/focusrestore/SettingsActivity.java#L1182) |
| `hyperOsVersion` | string/null; no; 128 | unknown | **待确认**完整固件版本来源；首次 OS3/4 初始化不是完整 ROM 版本 / full firmware source pending. [InitialHookMode:17–49](../app/src/main/java/com/hyperos3/focusrestore/InitialHookMode.java#L17) |
| `deviceModel` | string/null; no; 128 | unknown | 仅记录，不参与运行时 match；后续 L2 可给出可跳过的警告 / record only; optional skippable L2 warning, never a runtime predicate. [SettingsActivity:1170](../app/src/main/java/com/hyperos3/focusrestore/SettingsActivity.java#L1170) |
| `deviceCodename` | string/null; no; 128 | unknown | **待确认**独立代号读取；不能以 MODEL 顶替 / codename source pending, never substitute MODEL |
| `protocolVersion` | integer/null; no; 0..2147483647 | unknown | 原始 root.protocol，L2 声明；可用匹配别名见 §4 / raw root protocol. [Parser:63](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L63) |
| `business` | string/null; no; 128 | unknown | L2 声明，业务值为应用自定义 / app-defined business. [真实 fixture:25–27](../app/src/test/java/com/hyperos3/focusrestore/IslandPayloadParserTest.java#L25) |
| `templateType` | enum; yes | none | 本规则提取组选择器，不是假称 payload 必有此字段 / rule path-group selector. §5 每组有源码证据 |
| `notificationChannelId` | string/null; no; 255 | unknown | **待确认**当前通用捕获读取，首版仅声明，不用于执行匹配 / pending source; descriptive only |
| `notificationCategory` | string/null; no; 64 | unknown | **待确认**通用捕获读取，首版仅声明，不用于执行匹配 / pending source; descriptive only |
| `ruleAuthor` | string; yes; 1..64 | none | 规则文件作者自述，不是签名或可信认证 / self-declared author, not authentication |
| `ruleDescription` | string; yes; ≤2048 | none | 纯说明文本 / description only |
| `createdAt` | RFC3339 string; yes; ≤64 | none | 规则创建时间，不是通知捕获时间；C 必须显式校验 offset/date / creation timestamp, not capture time |

`createdAt` 的 v1 子集为带秒的 RFC3339 形态：年份 0001..9999、实际合法日历日期、小时 00..23、分/秒 00..59，`T/t` 分隔；小数秒可选，存在时至少一位且总字符串 ≤64；offset 为 `Z/z` 或正负 00:00..23:59。解码器自行检查 offset 范围，不借用 Java `OffsetDateTime` 的 ±18 小时限制；未确认的闰秒 60 拒绝。Schema 的 `date-time` 仅作格式说明，pattern 检查结构，日历合法性由 C1 检查；不声称完整覆盖 RFC3339 的全部特殊情况。
The v1 timestamp subset requires calendar-valid years 0001–9999, seconds, 00–23 hours, 00–59 minutes/seconds, `T/t`, optional fractional digits and `Z/z` or an offset up to ±23:59; total length remains 64. Leap seconds are rejected. Schema patterns check shape; handwritten calendar/offset validation completes C1 checks without claiming exhaustive RFC3339 conformance.

待确认的**适配声明**若非 null，后续 L2 必须报告“无法验证”，不能静默通过；`focusNotificationName`、`ruleAuthor`、`ruleDescription` 等纯说明字段不成为适配谓词。`deviceModel` 仅记录，L2 可作警告。force 只跳 L2，不能将未核实 tag/channel/category/deviceCodename/完整固件版本或 deviceModel 升级为运行匹配来源。C1 仅严格解码这些元数据，不执行 L2。
Non-null unsupported compatibility declarations require a later L2 unverifiable finding. Device model remains record-only and may receive a warning. Force skips only L2; neither it nor metadata creates runtime predicates for unverified sources. C1 only decodes metadata and does not perform compatibility validation.

## 4. match 白名单 / Match allowlist

`match.all` 每项业务字段仅 `{field,op,value}`（另允许不进入模型的 `x-*` 注解），无 OR、表达式和隐含类型强转。比较大小写敏感、不 trim；缺字段为未匹配，不把 null 当文字。字符串按受限 UTF-16 序列比较，无区域语言转换。
Each condition has only the business fields `{field,op,value}`, plus optional ignored `x-*` annotations. AND only, case-sensitive, no trimming or coercion of null. Missing values do not match.

| 可用 field / Allowed field | 读取 / Resolution | 来源 / Source |
|---|---|---|
| `packageName` | 当前通知包名；还必须满足独立用户白名单 / actual SBN package plus independent user allowlist | [HyperOS3FocusRestoreHook.java:6724–6754](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6724) |
| `protocolVersion` | raw root.protocol，严格非负整数后转十进制；只允许 equals / strict integer to decimal, equals only | [IslandPayloadParser.java:63](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L63), [Hook:5571–5573](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L5571) |
| `business` | raw root.business，仅字符串 / string only | [IslandPayloadParserTest.java:25–27](../app/src/test/java/com/hyperos3/focusrestore/IslandPayloadParserTest.java#L25) |
| `scene` | raw root.scene，仅字符串；**仅作 match 谓词，不在 meta 声明**；与 meta.business / meta.protocolVersion 的 L2 适配声明用途不同 / string-only match predicate, not metadata; distinct from L2 business/protocol declarations | [IslandPayloadParser.java:64,97](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L64) |

操作 / Operators: `equals` 精确相等 / exact; `contains` 字面子串 / literal substring; `startsWith` 字面前缀 / literal prefix. `value` 为 1–256 字符，不能是代码。`protocolVersion` 非 equals 为 `L3_OPERATOR_TYPE`。

`protocolVersion` 条件的正确示例是 `{"field":"protocolVersion","op":"equals","value": "1"}`；错误示例是 `{"field":"protocolVersion","op":"equals","value": 1}`，解码返回 `L1_TYPE`，不进行宽松转换。这里的 match.value 必须是字符串；与 §3 中允许整数的 meta.protocolVersion 声明不同。
A protocol predicate requires a string value: `"value": "1"` is valid; numeric `"value": 1` fails with `L1_TYPE`. This differs from the integer metadata declaration and performs no loose conversion.

**regex：预留、首版禁用 / RESERVED, DISABLED IN V1.** 不在 Schema 枚举，不能经 force 开启。未来若提案启用，需新协议版本、独立安全设计、执行上限证明及专门测试，并经作者另行确认；Java 线程中断不是恶意正则的可靠超时机制。

## 5. extract 规范路径登记 / Canonical extraction-path registry

以下为首版完整的 **106 条、14 组规范路径登记**，不是任意 JSON Pointer。每条均列源码行号；完整分级、structural 逐条语义理由及 unsafe 全量清单见 [island-rule-paths.md](island-rule-paths.md)。按字段语义重新分为 `structural=11/freeText=95/unsafe=0`：progress 数值/step 编号为 structural；title/content/subTitle/label 等自由文本与不确定项为 freeText。**捕获导出默认保留 structural、强制占位 freeText；运行期两类均不额外脱敏**。unsafe 不得进入运行白名单，未登记项一律拒绝；C1 只记录标签，不实现捕获占位、导入报告高亮或运行后处理，也不保证 structural 输入已通过数值验证。`path` 使用 `/` 分隔，最多 8 段；禁止 `..`、通配符、数组索引、递归、转义扩展和计算。含点的 `miui.focus.paramtextInfo` 是一个完整键，不按点切分。
The registry contains exactly 106 canonical paths in 14 groups: 11 structural, 95 freeText, zero unsafe. Capture/export preserves structural values by default and placeholders freeText; runtime redacts neither. Unsafe and unregistered paths are denied. C1 records policy metadata only. Structural classification describes semantics rather than proof of numeric input validation. See the complete source-backed [classification table](island-rule-paths.md). Paths have at most eight fixed member segments and do not support arbitrary JSON Pointer evaluation.

单组规则中，非 `legacy` 组在 raw root 中依次探测 `param_v2`、`param_v3`、`param_voip_v2`，选第一个对象型成员，均非对象（含缺失或 `null`）则选 raw root。`legacy` 直接从 raw root 读取；`fallback` 直接从所选容器读取 `/aodTitle`、`/ticker`。这两组不要求同名对象。普通组要求所选容器中以 `templateGroup` 命名的组入口（路径首段）为对象；入口缺失或为 `null` 报 `R_MISSING_FIELD`，类型错误报 `R_VALUE_TYPE`，均整条规则失败，即使所有绑定均为 optional。组入口查找结果复用为第一绑定的路径首段；后续绑定仍独立计费读取完整路径。不合并容器、不跨容器补找、不新增路径。源码容器顺序：[IslandPayloadParser.java:22–43](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L22)。
For a single-group rule outside `legacy`, probe `param_v2`, `param_v3` and `param_voip_v2` in that order on the raw root. Select the first object-valued member; use the raw root if none is an object, including when members are missing or null. `legacy` reads the raw root directly; `fallback` reads `/aodTitle` and `/ticker` directly from the selected container. Neither requires a same-named object. Ordinary groups require an object-valued entry named by `templateGroup` (the first path segment) in the selected container. A missing or null entry is `R_MISSING_FIELD`; an entry of the wrong type is `R_VALUE_TYPE`. Either fails the rule even if every binding is optional. Reuse the entry lookup as the first segment of the first binding; later bindings are charged separately for their complete paths. Do not merge containers, look in another container or invent paths.

`mixed` 按 `extract` 顺序逐条读取精确登记路径。登记为 `legacy` 的路径从 raw root 读取，其余路径在首次需要时选择一次容器。每个绑定独立计费读取自己的全部路径段，与 match、其他绑定和 display 共用单规则预算。`mixed` 中的组入口按普通路径段处理。例如 `/ticker`、`/title`、`/baseInfo/title` 依次读取所选容器、原始根、所选容器中的值。如果 `baseInfo` 缺失且第三项可选，前两项仍可按 `display.order` 显示。仅认识单组选择器的旧读取方会拒绝 `mixed`。
With `mixed`, resolve each registered path in `extract` order. Read `legacy` paths from the raw root and select one container on the first non-`legacy` binding. Charge each binding's full path separately against the rule's shared budget. Group entries follow ordinary segment handling. In the example above, an optional missing `baseInfo` leaves the first two bindings available for `display.order`. Older readers that recognize only single-group selectors reject `mixed`.

每绑定 / Binding: `id` 唯一标识，1–32 ASCII 字符；`path` 必填；`required` 默认 false；`replace` 默认空，至多 8 个 `{from,to}`。`from` 不空，from/to 各 ≤128 字符。
Binding ids are unique (1–32 ASCII characters). `path` is mandatory; `required` defaults to false. `replace` defaults to empty and allows up to eight `{from,to}` pairs, with a nonempty `from` and at most 128 characters in each string.

- 组入口条件满足后，绑定路径成员缺失或为 `null` 属于 `R_MISSING_FIELD`；叶值为对象/数组，或路径中间成员非 `null` 且不是对象，属于 `R_VALUE_TYPE`。required 绑定遇到任一情况使规则失败；optional 绑定只在这两类缺失或类型问题下省略，诊断保留固定原因。不因 optional 忽略输入、配额、超时或非法代理项错误。 / After the group-entry requirement is met, missing or null path members are `R_MISSING_FIELD`; an object/array leaf or a non-null, non-object intermediate member is `R_VALUE_TYPE`. Either fails a required binding and its rule; an optional binding is omitted for these missing or wrong-type cases, with a fixed diagnostic reason. Optionality does not suppress input, budget, timeout or illegal surrogate errors.
- 只将字符串、严格 JSON 数字、布尔叶值转为文本，不序列化对象/数组。数字保留原始合法 token（如 `1.0`、`1e0`、`-0`），不经 `double`、不展开指数；标准 JSON 数字按有限十进制表示处理，不另加 IEEE-754 范围限制，token 仍受绑定容量约束；布尔为 `true`/`false`。这不是 §4 的协议规范化。required 要求取得合法标量，不要求文本非空；空字符串是合法标量。若后续投影和清洗后无可显示文本，按 §7 处理为 `R_EMPTY_RESULT`。 / Only string, strict JSON number and boolean leaves become text; never serialize objects or arrays. Preserve the valid numeric token verbatim (e.g. `1.0`, `1e0`, `-0`), without conversion through `double` or exponent expansion. Standard JSON numbers are finite decimal representations with no extra IEEE-754 range limit; token text remains subject to the binding capacity. Booleans are `true` or `false`. The §4 protocol normalization does not apply here. A required binding needs a valid scalar, not nonempty text; an empty string is valid. If later projection and cleanup leave no displayable text, handle it as `R_EMPTY_RESULT` under §7.
- 替换前拒绝未配对代理项。按列表顺序对每对参数执行一次从左到右的字面、非重叠扫描，不递归扫描本步插入的文字。每一步替换的输出及最终绑定文本均不得超过 4096 UTF-16 单元：追加前检查容量、扫描中检查超时，超出长度报 `R_BUDGET`，不先构建超额结果、不截断以继续。原始叶字符串受 payload 上界约束，可由第一步缩至 4096 内；无替换时超长即失败。不得用替换去修复孤立代理项。C3-1 不做过滤或空白归一化。 / Reject unpaired surrogates before replacement. Apply the pairs in order, one left-to-right, literal, non-overlapping scan per pair; do not rescan text inserted during that step. Each step's output and the final binding must be at most 4096 UTF-16 units. Check capacity before appending and check the deadline during scans. A length overflow is `R_BUDGET`; do not build an oversized result or truncate it to continue. The original string leaf remains subject to the payload bound and may be shortened by the first replacement; without replacements, an oversized leaf fails. Replacement cannot repair a lone surrogate. C3-1 does not filter or normalize whitespace.

`paramIsland`：图标和部分兼容模型支持此别名，但活动正文路径读取 `param_island`。因此首版 extract **不开放 `/paramIsland/...`**，也不自动重命名。106 条均为规范路径；`W_ALIAS_PATH` 仅预留、不触发。来源：[Parser:239–240](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L239)、[正文:152](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L152)。
Some existing icon paths support the alias, but text rules do not. All 106 entries are canonical. `W_ALIAS_PATH` is reserved and never emitted in v1.

### 5.1 `baseInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/baseInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/subTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/specialTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/extraTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |
| `/baseInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:99](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99) |

### 5.2 `highlightInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/highlightInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:104](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L104) |
| `/highlightInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:104](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L104) |
| `/highlightInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:104](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L104) |

### 5.3 `highlightInfoV3`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/highlightInfoV3/primaryText` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:109](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L109) |
| `/highlightInfoV3/secondaryText` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:109](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L109) |
| `/highlightInfoV3/highLightText` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:109](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L109) |
| `/highlightInfoV3/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:109](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L109) |

### 5.4 `chatInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/chatInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:114](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L114) |
| `/chatInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:114](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L114) |

### 5.5 `iconTextInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/iconTextInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:118](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L118) |
| `/iconTextInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:118](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L118) |
| `/iconTextInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:118](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L118) |

### 5.6 `animTextInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/animTextInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:124](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L124) |
| `/animTextInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:124](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L124) |

### 5.7 `coverInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/coverInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:128](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L128) |
| `/coverInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:128](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L128) |
| `/coverInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:128](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L128) |

### 5.8 `hintInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/hintInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:134](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L134) |
| `/hintInfo/subTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:134](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L134) |
| `/hintInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:134](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L134) |
| `/hintInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:134](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L134) |

### 5.9 `stepInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/stepInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:147](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L147) |
| `/stepInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:147](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L147) |
| `/stepInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:147](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L147) |
| `/stepInfo/step` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:460](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L460) |

### 5.10 `progressInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/progressInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/progressInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/progressInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/progressInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/progressInfo/progressInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/progressInfo/progressInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/progressInfo/progressInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/progressInfo/progressInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |

### 5.11 `multiProgressInfo`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/multiProgressInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/multiProgressInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/multiProgressInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:396](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `/multiProgressInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |
| `/multiProgressInfo/progressInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/multiProgressInfo/progressInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/multiProgressInfo/progressInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:400](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L400) |
| `/multiProgressInfo/progressInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406) |

### 5.12 `param_island`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/param_island/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:412](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L412) |
| `/param_island/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:412](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L412) |
| `/param_island/frontTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:412](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L412) |
| `/param_island/bigIslandArea/imageTextInfoLeft/textInfo/frontTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/textInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/textInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/textInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/frontTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoLeft/miui.focus.paramtextInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:416](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L416) |
| `/param_island/bigIslandArea/imageTextInfoRight/textInfo/frontTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/textInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/textInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/textInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/frontTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/imageTextInfoRight/miui.focus.paramtextInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:419](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L419) |
| `/param_island/bigIslandArea/textInfo/frontTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/textInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/textInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/textInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/miui.focus.paramtextInfo/frontTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/miui.focus.paramtextInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/miui.focus.paramtextInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/miui.focus.paramtextInfo/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:425](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L425) |
| `/param_island/bigIslandArea/progressTextInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/progressTextInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/progressTextInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/progressTextInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/progressTextInfo/progressInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/progressTextInfo/progressInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/progressTextInfo/progressInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/progressTextInfo/progressInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/fixedWidthDigitInfo/progressInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/label` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:428](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L428); progress leaf helper 396–409 |
| `/param_island/bigIslandArea/sameWidthDigitInfo/progressInfo/progress` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:406](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L406); progress leaf helper 396–409 |
| `/param_island/smallIslandArea/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:430](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L430) |
| `/param_island/smallIslandArea/content` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:430](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L430) |
| `/param_island/smallIslandArea/subContent` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:430](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L430) |

### 5.13 `fallback`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/aodTitle` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:167](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L167) |
| `/ticker` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:176](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L176) |

### 5.14 `legacy`

| 精确路径 / Exact path | 叶类型 / Leaf type | 来源文件+行 / Source + line |
|---|---|---|
| `/title` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:96](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L96) |
| `/desc1` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:96](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L96) |
| `/desc2` | string / finite number / boolean → bounded text | [app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java:96](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L96) |


## 6. display 槽位、拼接与渲染限制 / Display slots and rendering limits

display 是新规则结果的**文本投影合同**，并不是 ROM 布局控制接口。`order` 1–16 项，每项 `{slot,ref}`；ref 必须指向本规则唯一 extract id。按 order 顺序、跳过缺失可选值，用 separator 连接；默认 separator=`·`、dedupe=false。**规则 display.separator 仅作用于该规则结果，不读取或写入现有「内容解析与拼接」连接符设置**。开启 dedupe 时只删除规范化后完全相等的后续片段，保留首个；不做内置子串去重，也不自动忽略 Copy/稍后提醒。
Display slots are semantic text labels, not ROM widgets. References must resolve to unique binding ids. Join nonempty fragments in explicit order. A rule's separator applies only to its own result and neither reads nor writes the existing content-parsing/joining separator setting. Dedupe is opt-in exact normalized-fragment equality, first occurrence retained; it is not the builtin substring policy.

| 首版槽位 / V1 slot | 类型 / Type | 来源与限制 / Evidence and limits |
|---|---|---|
| `title` | string projection | 正文标题片段，无新标题 View / title fragment, no new View. [Parser:99–101](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L99); 最终仅text/source [375–381](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L375) |
| `subtitle` | string projection | 次要正文片段，无字体/行数控制 / secondary fragment. [Parser:100–101](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L100) |
| `progress` | string projection | 仅已有进度叶值的文本，不建立进度条 / textual progress only. [Parser:396–409](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L396) |
| `extra` | string projection | 补充正文，不允许对象/布局 / supplementary text, never objects/layout. [Parser:134–150](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L134) |

`icon`、`timer` 有独立既有链路但本规则范围禁止修改；不在可用 enum。任何动作、图片加载、颜色/样式/HTML注入槽位亦不可用。未知槽位不能因 force 进入运行期。现有富文本开关的消费行为仍保持，规则不得另开 HTML/脚本执行路径。
Icon/timer slots and action/layout/style controls are disabled, not null placeholders. Existing rich-text consumption remains governed by its original setting; rules cannot add a new rendering mechanism.

## 7. 规则结果后处理合同 / Result post-processing

这是规则结果必须满足的合同，不能顺手加到默认解析路径：
This applies to rule results only; do not alter builtin behavior.

1. 对每片段及替换结果检查类型和配额；非法代理项拒绝，`unsafe` 与未登记路径拒绝。**敏感脱敏仅作用于捕获导出，不作用于运行期展示**：`structural` 路径在捕获导出默认保留，`freeText` 路径在捕获导出强制占位；导入报告高亮 freeText 路径。运行期由规则作者的 display 决定文本，除声明的替换/拼接/可选去重外，后处理只做以下 4096 截断、控制字符过滤和空白归一化，不额外脱敏，也不自动识别或遮盖车次等正文。C1 只保存标签，捕获占位和运行后处理均未实现。
2. 精确过滤 U+200B（零宽空格）、U+2060（word joiner）、U+FEFF（BOM）、C0 U+0000..U+001F（保留 TAB U+0009、LF U+000A 供空白归一化）及 C1 U+0080..U+009F。保留 U+200C/U+200D/U+FE0E/U+FE0F/U+20E3；不再附加旧草案的 bidi 清单。输出中的 BOM 过滤与规则文件 BOM 拒绝是不同阶段。
3. 先按第 2 项精确过滤，再将 Unicode White_Space 的剩余固定字符 TAB（U+0009）、LF（U+000A）、U+0020、U+00A0、U+1680、U+2000..U+200A、U+2028、U+2029、U+202F、U+205F、U+3000 归一为普通空格；其余 White_Space 字符已在第 2 项滤除。不依赖平台空白判断。连续空白压成一个空格、trim；空片段不生成分隔符。若 display.dedupe=true，仅按规范化片段完全相等去重；按显式 order、separator 拼接，再规范化全串。
4. 最终结果限 **4096 UTF-16 单元、保护高代理项边界**，截断后再 trim；为空则尝试下一条，所有规则无有效结果才 fallback。输出截断不代表允许先分配无限中间字符串。

English: redaction applies only to capture/export: preserve structural values by default and replace freeText values with placeholders. Runtime display performs no extra redaction; the author's declared transformations/projection remain effective. Later R denies unsafe/unknown paths, applies exactly the listed U+200B/U+2060/U+FEFF/C0/C1 filter, and retains ZWNJ/ZWJ/variation selectors/keycap combining mark. After filtering, map the remaining fixed Unicode White_Space set (TAB U+0009, LF U+000A, U+0020, U+00A0, U+1680, U+2000..U+200A, U+2028, U+2029, U+202F, U+205F, U+3000) to ordinary spaces without platform-dependent whitespace tests; all other White_Space characters were filtered. Collapse runs and trim; empty fragments add no separators. If enabled, dedupe by exact normalized-fragment equality, keeping the first. Join in explicit order with the declared separator, normalize the full string, and truncate safely to 4096 UTF-16 units without splitting surrogate pairs, then trim again. Empty output continues to the next rule. C1 records labels only and implements neither capture placeholders nor runtime sanitation.

源码差异说明：当前内置 `clean()` 有 trim、已知文案忽略及截断，**没有通用控制字符过滤/内部空白压缩器**。上述清洗是本次新规则结果合同；不是宣称现有代码已实现。默认路径仍原样。原有去重/已知操作文案忽略不强制继承。
The current built-in clean() trims, ignores known action wording and truncates; it has no generic control-character filter or internal whitespace normalizer. The new sanitation contract applies only to rule results. Existing deduplication and known-action suppression are not mandatory inheritance. Evidence: [IslandPayloadParser.java:481–495](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L481); [InputLimits.java:18,50–55,77–81](../app/src/main/java/com/hyperos3/focusrestore/InputLimits.java#L50).

## 8. 完整正例与真实来源 / Complete positive example and provenance

实际原始参数（不是完整 SBN 转储）来自已有真实 fixture：[IslandPayloadParserTest.java:19–34](../app/src/test/java/com/hyperos3/focusrestore/IslandPayloadParserTest.java#L19)。该注释确认原字段为 `miui.focus.param.custom`。复制到 [notification-travel.real.json](samples/notification-travel.real.json) 的 rawParam 按 UTF-8 计算摘要，decodedParam 是其解析结果，未知 tag、ROM、捕获时间没有补造。此文件是证据封装，不是声称已由未来 capture exporter 生成。
The raw param is verbatim from the existing real captured fixture; the surrounding evidence envelope is new and explicitly not a full notification dump or an implemented capture export.

### 8.1 原始 JSON / Raw param

```json
{
  "timeout": 470,
  "enableFloat": true,
  "updatable": true,
  "reopen": "reopen",
  "business": "train",
  "protocol": 1,
  "scene": "personal-assistant-travel",
  "aodTitle": "检票口 检票口",
  "aodPic": "miui.focus.travel_aod_pic",
  "param_island": {
    "islandProperty": 1,
    "highlightColor": "#3482FF",
    "islandTimeout": 28200,
    "bigIslandArea": {
      "imageTextInfoLeft": {
        "type": 1,
        "picInfo": {
          "type": 1,
          "pic": "island_pic"
        },
        "textInfo": {
          "title": "D8396",
          "showHighlightColor": true
        }
      },
      "textInfo": {
        "title": "检票口",
        "showHighlightColor": true
      }
    },
    "smallIslandArea": {
      "picInfo": {
        "type": 1,
        "pic": "island_pic_small"
      }
    }
  }
}
```

### 8.2 可用规则 / Valid rule

完整文件：[rule-travel.valid.json](samples/rule-travel.valid.json)。规则作者/创建时间是示例文档元数据，不是原通知作者或发生时间；未知适配信息为 null。
Complete rule file below; null compatibility facts remain unknown.

```json
{
  "ruleVersion": 1,
  "meta": {
    "packageName": "com.miui.personalassistant",
    "focusNotificationName": "出行 / Travel (human label)",
    "notificationTag": null,
    "systemUiVersion": null,
    "hyperOsVersion": null,
    "deviceModel": null,
    "deviceCodename": null,
    "protocolVersion": 1,
    "business": "train",
    "templateType": "param_island",
    "notificationChannelId": null,
    "notificationCategory": null,
    "ruleAuthor": "ImKani",
    "ruleDescription": "Stage B example: train number then area label; icons and timers unchanged.",
    "createdAt": "2026-11-18T00:00:00+08:00"
  },
  "match": {
    "all": [
      {
        "field": "business",
        "op": "equals",
        "value": "train"
      },
      {
        "field": "protocolVersion",
        "op": "equals",
        "value": "1"
      }
    ]
  },
  "extract": [
    {
      "id": "trainNo",
      "path": "/param_island/bigIslandArea/imageTextInfoLeft/textInfo/title",
      "required": true
    },
    {
      "id": "gate",
      "path": "/param_island/bigIslandArea/textInfo/title",
      "required": true
    }
  ],
  "display": {
    "order": [
      {
        "slot": "title",
        "ref": "trainNo"
      },
      {
        "slot": "subtitle",
        "ref": "gate"
      }
    ],
    "separator": "·",
    "dedupe": true
  }
}
```

### 8.3 预期结果 / Expected result

`title = D8396`，`subtitle = 检票口`，按规则声明的运行期拼接期望仍为 **`D8396·检票口`**；此结果同时是既有内置测试的历史期望 [test:142–151](../app/src/test/java/com/hyperos3/focusrestore/IslandPayloadParserTest.java#L142)。两条样例路径都是 `freeText`（title 自由正文，不因车次外观变为 structural）；该标签只要求捕获导出强制占位，运行期不额外脱敏，因此不改变此期望。C1 仅将样例解码为不可变模型，不执行 extract/display；此声明式复核不代替新引擎执行或设备验收。原始 fixture、rawParam 和期望不改写，图标与 timer 仍不在规则范围内。
`D8396·检票口` remains the declared runtime expectation and the existing built-in test expectation. Both sample paths are freeText: capture/export uses placeholders, while runtime applies no extra redaction. C1 only decodes the rule; this review is not an engine execution or device test.

## 9. 反例与确定性拒绝 / Negative examples and rejection

所有反例都是真正的 JSON 文档，不是错误描述包装。L1 首个失败即拒绝；若已被 L1 拒绝，不声称运行了 L2/L3。L3 对同类危险能力仍须独立复核。force 不改变任何下列结果。
Each negative file is the invalid rule itself. L1 failure stops import; L3 remains defense in depth, not a claim that a rejected file reached execution.

| 文件 / File | 错误 / Failure | 首要错误码 / Primary code | 具体原因 / Reason |
|---|---|---|---|
| [invalid-01-unknown-field.json](samples/invalid-01-unknown-field.json) | root.unexpected | `L1_UNKNOWN_FIELD` | 关闭对象未知字段 / closed object |
| [invalid-02-path.json](samples/invalid-02-path.json) | `../../secret` | `L1_PATH_ENUM` | 不在有限路径枚举；运行期亦禁止 / outside finite allowlist |
| [invalid-03-slot.json](samples/invalid-03-slot.json) | slot=icon | `L1_SLOT_ENUM` | 越界至独立图标能力 / icon is outside text scope |
| [invalid-04-regex.json](samples/invalid-04-regex.json) | op=regex | `L1_OPERATOR_ENUM` | 首版禁用，force 无效 / disabled in v1 |
| [invalid-05-capacity.json](samples/invalid-05-capacity.json) | >65,536 UTF-8 bytes | `L1_FILE_SIZE` | 字节预检先失败，描述字段亦过长 / byte preflight before field validation |
| [invalid-06-forbidden.json](samples/invalid-06-forbidden.json) | display.intent/action/url | `L1_FORBIDDEN_FIELD` | 结构上拒绝；同能力亦属 L3 禁止 / prohibited keys and capability |
| [invalid-07-template-path.json](samples/invalid-07-template-path.json) | baseInfo + island path | `L1_TEMPLATE_PATH` | 全局合法路径不能越过所选组 / legal global path, illegal group |

## 10. L1 / L2 / L3 + R 与错误码 / Validation and error codes

F1-A App 导入流程为有界字节读取 → L1 → L3 → L2 → 用户确认 → 原子保存。L3 复用操作类型和提取模型防御，限额及禁止能力继续由严格解码和运行期各自把关。L2 对本机可查询的 SystemUI 版本名和长版本码作精确比较。用户可明确跳过有证据的 L2 不匹配，批准记录保存具体代码、字段和原因。导入阶段没有通知样本，`L2_SAMPLE_MISMATCH` 留待有适用样本的验证。提交点以前的失败保留当前修订。错误报告使用固定代码和字段，省略规则输入与通知正文。
F1-A App import performs bounded reading, then L1, L3, L2, user confirmation and atomic save. L3 shares the operation and extraction-model defenses. L2 compares the reported local SystemUI version name and long version code exactly. Explicit force records each skipped blocking L2 finding by code, field and reason. Sample checks require evidence unavailable to this import flow. Failures before the commit point preserve the current revision.

| 层 / Layer | 错误码 / Codes | 含义与处置 / Meaning and action |
|---|---|---|
| L1 格式层 / Format | `L1_FILE_SIZE`, `L1_ENCODING`, `L1_JSON`, `L1_DUPLICATE_KEY`, `L1_VERSION` | 超限/编码/严格语法/重复键/未知版本，拒绝 / reject |
| L1 | `L1_IO`, `L1_INTERNAL` | 读取失败/解码内部异常，结构化失败；固定摘要不回显输入或任意异常消息 / structured read/internal failure, fixed content-free summaries |
| L1 | `L1_UNKNOWN_FIELD`, `L1_FORBIDDEN_FIELD`, `L1_TYPE`, `L1_REQUIRED`, `L1_CAPACITY` | 字段/类型/必填/reader或字段数组字符串容量错误 / reject |
| L1 | `L1_PATH_ENUM`, `L1_SLOT_ENUM`, `L1_OPERATOR_ENUM`, `L1_TEMPLATE_PATH` | 有限枚举或组关系错误 / reject |
| L2 适配层 / Compatibility | `L2_ENVIRONMENT`, `L2_UNVERIFIABLE`, `L2_SAMPLE_MISMATCH` | 环境不符、无法核实或样本预期不符。只有阻断性的 L2 项可由用户明确跳过 / explicit force can skip blocking L2 findings |
| L3 安全层 / Safety | `L3_FORBIDDEN_CAPABILITY`, `L3_PATH_SCOPE`, `L3_OPERATOR_TYPE`, `L3_REFERENCE`, `L3_QUOTA` | 禁止能力、路径、操作、引用或配额违规均拒绝 / reject |
| R 运行期 / Runtime | `R_PACKAGE_DENIED`, `R_RULE_DISABLED`, `R_UNMATCHED` | 后续运行层：包名白名单/状态/条件不符，继续下一条 / later-stage only; continue |
| R | `R_INPUT_LIMIT`, `R_MISSING_FIELD`, `R_VALUE_TYPE`, `R_BUDGET`, `R_TIMEOUT`, `R_EXCEPTION`, `R_EMPTY_RESULT` | 输入/所需字段/类型/预算/50 ms超时/异常/空结果；单条失败继续下一条，全部无效才 fallback / later-stage only; continue after rule failure |
| R | `R_STALE_GENERATION`, `R_CONFIGURATION_CHANGED` | 过期通知或规则代次，丢弃结果，不复活通知 / later-stage discard, never resurrect |

L2 适配层补充处置（文档要求，不表示已实现）：
Additional L2 handling requirements (documentation only, not an implementation claim):

- `L2_ENVIRONMENT`：`systemUiVersion` 发生切换（升级、降级或 ROM 变更）后，已导入规则应重新触发 L2 适配检查并报告 `L2_ENVIRONMENT`；不得沿用旧版本的通过结果。 / After a `systemUiVersion` change through an upgrade, downgrade or ROM change, imported rules must undergo L2 compatibility checks again and report `L2_ENVIRONMENT`; a passing result from the old version must not be reused.
- `L2_UNVERIFIABLE`：规则声明或依赖的字段、行为在当前样本或当前 ROM 上无法核实时，报告 `L2_UNVERIFIABLE`；不得据此直接拒绝导入，也不得在未核实状态下标记为已核实。 / Report `L2_UNVERIFIABLE` when a field or behavior declared or relied upon by a rule cannot be verified against the current sample or ROM. That finding alone must not directly reject import, and an unverified item must not be marked verified.
- `scene` 仅作 §4 的 match 谓词；缺失、不匹配或上下文未提供时，不报 L2 错误，按 C2-2 选条语义处理为不命中。 / `scene` is only a §4 match predicate. A missing or mismatched value, including absent context, is not an L2 error; it is a non-match under C2-2 selection semantics.

C1 L1 错误优先：**文件字节/编码 → JSON语法、重复键及reader限额 → ruleVersion → 已知结构的禁止键 → 普通未知键 → 按固定结构顺序返回字段语义首错**。字段语义检查保持固定次序，不承诺把任意嵌套处的类型/容量错误全局排到所有枚举错误之前。结构化错误带稳定 primary code、字段路径与固定摘要，不抛给调用方，也不回显输入、通知正文或任意异常 message。七个反例的首要错误码保持 §9 所列不变。
C1 deterministically prioritizes byte/encoding, syntax/duplicates/reader limits, version, prohibited structural keys, ordinary unknown keys, then the first semantic failure in fixed structure order. It does not promise a global cross-field type-before-enum ranking. Errors contain stable codes, paths and fixed summaries, never input content or arbitrary exception text, and do not escape to the caller. The seven negative examples keep their listed primary codes.

R 不能靠 try/catch 代替安全模型：运行前核对真实包名、用户允许集合、immutable规则快照；不在 layout/pre-draw 做文件 IO、正则编译或大 IPC。按每通知代次/规则revision复用结果，规则变更必须失效缓存；单条失败只记录一次有限诊断并继续下一条，全部无效才返回 fallback，不让错误中断其他通知。C1 不连接缓存或通知链路。
Later R checks real package, user allowlist and immutable revision; avoids IO/regex compilation/large IPC in layout paths; uses generation-scoped results and bounded diagnostics, invalidating caches on rule changes. A failed rule continues to the next. C1 implements neither evaluation nor caching and makes no hard-sandbox claim.

## 11. 禁止字段与版本失效 / Forbidden fields and version migration

Schema 已知结构对象采用 `additionalProperties:false` 加 `patternProperties` 的 `^x-` 例外；注解内部仅作严格 JSON 与有界读取检查，不进入运行模型。`x-forbiddenKeys` 是手写解码器和文档的禁止能力注解：`code/script/expression/eval/exec/class/method/reflection/url/network/file/filePath/intent/Intent/action/Action/pendingIntent/PendingIntent/layout/style/view/color/font/html/regex`。这些键出现在不允许的规则结构位置时为 `L1_FORBIDDEN_FIELD`；**明确允许的 `meta.systemUiVersion.code` 是版本整数，不受同名 `code` 误伤**。不允许禁止控制键或等价能力；字符串值里的同名普通文字不是代码，不会把通知词语当指令。不得遍历原始通知对象任意反射/执行，也不得自动打开 URL 或 Intent。
Known structures reject ordinary unknown properties but permit `^x-` annotations under reader limits only. Forbidden structural keys document disabled capabilities; explicitly allowed `meta.systemUiVersion.code` remains a valid version integer. Plain strings and annotation-internal keys are data and never become executable commands or capabilities.

未知 ruleVersion 拒绝，不自动猜版本。未来迁移在导入边界执行，保留旧原文件、规则ID/revision及校验结果；失败保留旧激活版本。路径增删、操作或槽位语义变化需新版本审查，不静默扩大旧规则权限。App降级不执行不认识的规则；只禁用规则层并使用内置路径。仅改meta环境声明不能绕过R。
Unknown versions are rejected; migration is explicit at import, rollback retains the old active revision, downgrades disable unsupported rules and use builtin behavior. Environment changes do not grant new capabilities.

## 12. 独立存储合同 / Independent storage contract

### 12.1 F1-A App 存储与导入事务 / App storage and import transaction

- App 是唯一批准者。规则原始 UTF-8 字节位于默认凭据保护的 App 私有 `no_backup/island-rules`。锁定时拒绝打开。普通设置、设置备份和旧 `island_custom_rules` 不承载规则。App owns approvals and stores original UTF-8 bytes under credential-protected `no_backup/island-rules`.
- 导入在读取前固定基线 manifest 修订，将单次有界读取的原始字节暂存为不可变文件。确认绑定原始 SHA-256（含全部规则元数据）、App 生成的 ID、规则修订、启停、优先级和声明顺序。内容替换及启停或优先级变更必须走新的校验与确认。替换保持声明顺序。基线修订或可查 SystemUI 环境变化后拒绝旧确认。Confirmations bind the original digest, app-generated ID, per-rule revision, enabled state, priority and declaration order against the captured baseline. A changed baseline or SystemUI environment invalidates the pending confirmation.
- 既有限额为每条 65,536 UTF-8 字节、活跃规则 50 条和规则文件合计 2 MiB。统一账本同时计入当前、暂存和读者固定的旧规则文件，禁用规则也计入。同一物理不可变文件只计一次，同内容的独立文件分别计费。替换暂存与现行文件同时计费。The 2 MiB ledger includes staging, disabled entries and reader-pinned old files, charging each physical file once.
- F1-A 新增上限为 manifest 256 KiB、每个 manifest 最多 1,024 条元数据、App ID 最多 128 UTF-8 字节（当前生成 36 字节的 UUID）。清单格式版本独立于规则 `ruleVersion`，清单和临时清单不计入规则字节账本。New F1-A bounds are 256 KiB per manifest, 1,024 entries per manifest and 128 UTF-8 bytes per app ID (currently a 36-byte UUID).
- 提交点是同目录的原子 manifest 替换。先同步不可变文件与候选清单，再同步目录、切换清单并再次同步目录。切换后同步失败会报告落盘未确认，此时保留可能被两代清单引用的文件。重开时以有效的当前清单为准，核对格式、限额和引用摘要后清除未提交候选及无引用文件。损坏清单拒绝打开。旧文件只为存活读者保留，回滚需重新验证并提交新修订。Recovery uses the valid on-disk manifest and reclaims orphaned files. Old revisions are retained only for live readers. Rollback requires a new validated revision.
- App 保存状态以事务落盘结果为准。SystemUI 的应用状态独立显示，见 §12.2。 / The App reports the save result independently of SystemUI application status.

### 12.2 F1-B 只读批次与快照发布 / Read-only batches and snapshot publication

- `com.hyperos3.focusrestore.islandrules` 是独立的凭据保护只读 Provider。以 Binder 实际 UID、框架调用包、SystemUI UID 及平台签名、同一已解锁前台 Android 用户判定访问。查询不明时拒绝，旧设置合同保持原语义。 / The independent read-only provider authenticates binder identity, SystemUI's package and platform signature, and the same unlocked foreground Android user.
- 批次格式 1 的 Binder 头仅携带协议、App 进程会话及修订。管道固定一个正修订并传输最多 256 KiB 的清单与最多 2 MiB 的唯一规则文件，另加 28 字节固定协议开销。接收端检验起止修订、长度、清单与批准记录、每个对象的 SHA-256 和流结束，按本机环境重验 L1 → L3 → L2。已批准 force 仅适用于记录中匹配的 L2 阻断项。 / Version 1 streams one pinned positive revision, with a bounded manifest and unique rule files. The receiver verifies revision, bounds, approval, integrity, EOF and current validation.
- 设置可导入明确的 JSON `[]`，经用户确认后原子提交新修订。通过批次验证的正修订空清单和全禁用清单都是有效快照。修订 0、读取失败及损坏来源不能转成有效空集。 / Importing `[]` requires explicit confirmation and commits a new revision atomically. Verified empty and all-disabled manifests can publish as valid snapshots.
- OS4 初始化持有唯一发布者。变更通知触发失效和核对，正常调度每 15 秒对账，单次尝试限 5 秒，发布租期 30 秒。候选在私有状态加载。只有 `loaded`、`failures.isEmpty()`、同会话、同目标修订、同 generation 和时限同时成立才原子发布。`loaded` 本身仅表示加载尝试结束。 / OS4 owns one publisher. Notifications trigger invalidation and reconciliation. A fully verified, current and timely candidate is published atomically.
- “SystemUI 已应用”表示最近确认发布了与 App 保存修订相同的快照。状态受 App 会话、SystemUI 存活及租期约束。F1-B 的输出止于快照，正文与无障碍清理属于后续消费端阶段。 / Application status describes recent publication of the saved revision. Notification rendering and accessibility cleanup belong to a later consumer stage.


### 12.3 F3-C 规则包与设置入口 / Rule packs in Settings

- 「关于 → 自定义规则」提供 FRPK 批量导入和导出。导入通过系统文档选择器读取，最大 2,122,796 字节。后台校验并暂存一份候选，确认窗口显示基线修订、总数、启用数、按声明顺序排列的优先级及 `L2_UNVERIFIABLE` 提示。确认后重新核对基线和环境，再原子替换。空包须单独确认「清空全部规则」。 / The App stages one verified candidate and atomically replaces the repository after confirmation. An empty pack requires explicit confirmation to clear all rules.
- 导出固定一个仓库修订，生成完整可移植包后打开系统保存选择器。遇到不支持的规则或字段时拒绝整包。成功状态显示导出修订和字节数，保存状态显示实际仓库修订和导入条目数。 / Export builds a complete portable pack from one pinned revision before opening the save picker. Unsupported rules or fields reject the entire export.
- 生成、读取、提交和文档写入各设 60 秒期限，写入失败后的清理共用该次写入的期限。只有输出关闭且在期限内确认，才可报告导出成功。写入或清理到期仍未确认时，界面直接显示「导出未确认，目标文件可能不完整，清理待确认」，不等待文档提供方响应。停止界面等待不等于结束后台 I/O：进程内只有一个批量规则包工作位，关闭文档后才尝试删除或截断，直到后台退出才允许重试。迟到结果不替换超时提示。 / An unconfirmed write or cleanup at the deadline stops the UI wait, but retains the process-wide worker slot until the provider call and any cleanup return. Late results cannot replace the timeout notice.
- 导入在原子切换前取消保留旧修订；跨过切换边界后即使超过期限也按实际仓库结果报告。 / Cancellation before the atomic switch preserves the old revision. After the switch begins, the UI reports the actual repository result.
- 可编辑预览列表最多 30 条，底层 Session 原文最多保留 64 条。 / The editable preview list holds up to 30 entries. The underlying session retains up to 64 original records.

文件来源、哈希和L2校验并不能证明内容可靠。风险见 [island-rules-risk.md](island-rules-risk.md)，捕获/导出合同见 [capture-export-spec.md](capture-export-spec.md)。规则/通知/AI生成文本始终是数据，不是执行或扩大授权的指令。

## 13. C1 交付边界与后续阶段 / C1 scope and later stages

本次 C1 范围为不可变 `IslandRuleSet` / `IslandRule` / `MatchClause` / `ExtractSpec` / `DisplaySpec` 模型、`IslandRuleCodec` 严格有界解码、结构化 L1 错误，以及 106 条规范路径登记和分级。JSON 五段结构不变，priority 仅存在于外部 Set 条目；普通未知字段拒绝，`x-*` 不进入模型。C1 测试限于纯 JVM 解码、模型不可变性、路径元数据与相关边界，不代表运行期或设备验收。
C1 covers immutable models, strict bounded decoding, structured L1 errors and the 106-entry canonical path registry. The five-part rule JSON remains unchanged. JVM model/decoder checks establish neither rule execution nor ROM acceptance.

以下尚未实现，须后续分别确认与交付：

- C2：统一 `validateRule(json)`、L2 样本/适配检查、独立 L3 能力/引用/预算检查、freeText 路径导入提示与捕获导出占位合同（仅澄清既有脱敏语义，运行输出不脱敏）。
- C3：有界执行、稳定排序、超时/异常继续、首个有效结果选择、结果和 fallback 信号、缓存接口及规则变更失效。
- C4：运行预算、后处理、超时/异常、跨锁状态接口等后续行为测试；当前捕获规范中仍开放的状态/历史策略需另行对齐。
- SystemUI / `extractIslandContent()` 接入、UI/捕获 UI、设置项、SettingsActivity/SettingsProvider、存储/IPC/导出和任何设备操作均不在 C1。

已裁决的数量、顺序、别名、注解及 Unicode 边界按前文章节执行，不再列为 B 阶段开放项。剩余捕获存储、授权、跨锁续捕获与历史保留问题见 [capture-export-spec.md](capture-export-spec.md) 的开放项；不得从 C1 的路径模型推导为已批准或已实现功能。

## 14. 当前实现状态（D1，2026-11-18） / Current implementation status

§13 保留 C1 交付时的阶段记录。当时纯 Java 代码已完成 C2-1～C2-5 及 D1，过程记录保存在作者的 D1 阶段报告中。历史测试数量与实跑结果不回写。该阶段的统一 `validateRule(json)`、独立 L2、完整 L3、规则执行与真实通知接入尚未完成。
Section 13 preserves the C1 delivery snapshot. C2-1 through C2-5 and D1 now have pure Java implementations; historical results remain unchanged. Unified validation, independent L2, full L3, execution and real notification integration remain outside this delivery.

D1 的原始适配输入为调用方通知包名及受限 JSON 字符串：包名仅取调用参数；`protocolVersion` 仅取 root `protocol` 的精确数学非负整数，范围 `0..2147483647`，规范化为十进制字符串。作者本次裁决允许 `1.0` / `1e0` / `-0`，分别成为 `1` / `1` / `0`；字符串数字、非整数、负数、越界或缺失/null 不代填。`business` / `scene` 仅接受 root 真实字符串，不 trim，不从 meta/嵌套参数读取。复用既有 payload 上界（262144 UTF-16 单元、524288 UTF-8 字节）和严格迭代 JSON 语法层，不套用规则文件的字段白名单或深度8限额。
D1 reads only caller package identity and the three root payload fields. Protocol normalization uses exact mathematical integers in the stated range, with no loose conversion or fallback. The existing payload size limits bound the reused iterative JSON reader; rule-file field restrictions and depth limits do not apply to notification payloads.

独立 `IslandRuleMatchValidator.validate(rule)` 只补 `protocolVersion` 非 `equals` 的 `L3_OPERATOR_TYPE`；C1 数字 `match.value` 仍为 `L1_TYPE`。选条先做该语义检查，再执行四字段 AND；非法规则跳过并继续，首个 match 成立即停。详细诊断每次最多128条、条目ID最多128 UTF-16单元（超长省略，索引保留），不含条件值、正文或异常原文，无历史集合或去重；这不代表完整 L3、R、最终有效结果或内置 fallback 已接通。
The independent protocol operator check runs before condition short-circuiting. Selection diagnostics are bounded per call and carry only identity/index, fixed phase/code and declaration paths. Selection still means a matching entry, not executed content or a valid final result.

## 15. C3-1 范围 / C3-1 scope

本轮按作者裁决澄清 §2、§5、§7 的执行合同，仅开展纯 Java 8 有界提取：容器与路径组、绑定读取、标量与字面替换、类型及配额检查。C3-1 只实现提取所需的计数，match 读取、display 拼接与去重计数由后续执行阶段接入，保留 D1 接口行为。绑定文本不是通过完整 R 的最终正文；C3-1 不做控制字符过滤、空白归一化、展示去重或拼接、最终截断。C3-2 后处理及后续逐条执行、首个有效结果与 fallback 信号、正文结果缓存仍待分别交付。本节仅定范围，不声明实现验收或测试已通过。

This round clarifies the execution contract in §§2, 5 and 7 under the author's decisions and limits C3-1 to bounded extraction in pure Java 8: container and path-group handling, binding reads, scalar conversion, literal replacements, and type and quota checks. C3-1 accounts only for extraction work; later execution will wire in match reads, display joining and deduplication without changing D1 interface behavior. Binding text is not a final R-approved text result. C3-1 does not perform control-character filtering, whitespace normalization, display deduplication or joining, or final truncation. C3-2 post-processing and the later per-rule executor, first valid result and fallback signaling, and text-result cache remain separate deliverables. This section defines scope only; it makes no implementation-acceptance or test-pass claim.

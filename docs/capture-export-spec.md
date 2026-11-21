<!-- SPDX-License-Identifier: GPL-3.0-only -->
<!-- Copyright (C) ImKani. Project: FocusRestore. Source: https://github.com/ImKani/FocusRestore -->

# 原始焦点内容捕获与导出规范 / Focus capture and export specification

**项目 / Project:** FocusRestore · ImKani · <https://github.com/ImKani/FocusRestore> · GNU General Public License v3.0 only (`GPL-3.0-only`).

**当前实现 / Current session**

用户在前台主用户已解锁时主动开启本地捕获。此后，各应用真实新增或更新且已进入焦点链路的通知共用最近记录。OS3 对已核验的 `NotifCollection` 活动事件与 `showOnStatusBar` 判定按同一 SBN 实例及会话代次配对。决策中的包名、key 和 `ExpandedNotification.getNotification()` 返回的对象必须同时匹配当前活动条目。OS4 在活动 `NotifPipeline` 的新增、更新回调生成有内容的候选项后取样。两版均在读取前后检查 Entry 与 SBN。锁屏、熄屏、资料变化、前台用户变化或桥接失效会清除会话。

ROM 接口依据为作者提供的只读 APK 样本。OS3 `com.android.systemui` 16.03.251211.r／202501210（样本 `6d9nlm26`）经 `NotifCollection.dispatchEvents()` 派发 `onEntryAdded(NotificationEntry)` 和 `onEntryUpdated(NotificationEntry, UpdateSource)`，`getEntry(String)` 返回当前活动条目。OS4 17.03.260226.r／202602260（样本 `d63pb6r9`，APK SHA-256 `c003074232b1088f92dc4e8e4b159ff9bd9cdc57ae41fb69b195668ddfce5e54`）通过 `NotifPipeline.addCollectionListener` 注册，`getAllNotifs()` 返回主线程实时活动集合。接口属小米 SystemUI 及 Android 上游，ROM 私有实现的具体权利归属与许可未确认。FocusRestore 独立实现监听、身份及会话检查。不同 ROM 的回调可用性和 30 条会话行为以实机验收为准。

最近记录按新到旧展示，最多 30 条，总负载上限 2 MiB。按条数或字节预算淘汰最老记录时，同一记录的预览、保存和脱敏导出票据一并失效。普通配置备份不包含记录。列表返回包名、时间、字段数和固定原因，可见行另取最多 96 个 UTF-16 单元的文本。缺岛参数、参数格式错误、无法读取、超限或没有可编辑登记字段时可查看对应原因。只对有字段的记录签发编辑预览，对仍在列表中且保留合规脱敏导出来源的记录签发导出票据。

The unlocked foreground main user explicitly starts an in-memory session. New or updated Focus notifications across apps share the latest 30 records within a 2 MiB budget. Visible rows can request bounded snippets. Rows without eligible fields show a reason. Removing a row revokes all its tickets. Redacted export reads eligible records from that same history.

## 历史设计合同 / Historical design contract

以下章节记录早期 Stage B 合同、离线格式与首版实现。有关单应用、64 条已完成缓冲及独立预览队列的描述适用于对应阶段，当前设备捕获以上述会话为准。

## 1. 冻结范围 / Frozen scope

- 捕获默认关闭；用户显式开启，并指定应用白名单；只做有界本地保存。 / Capture is off by default, explicitly enabled by the user, restricted to an application allowlist, and stored locally with bounds.
- 锁屏暂停正文捕获，只允许元数据占位；锁屏期间不能选择完整内容导出。队列与历史的具体处置见 §7 的 draft/open 项。 / Body capture is suspended while locked; only metadata placeholders are allowed. Full-content export cannot be selected while locked. Queue/history handling remains draft/open in §7.
- 默认导出脱敏内容；完整内容导出需单独二次确认。 / Export is redacted by default. Full-content export requires a separate second confirmation.
- 捕获读写与导出需要独立、严格 UID/用户授权，不沿用普通配置查询的身份不明放行策略。 / Capture write/read and export require independent, strict UID/user authorization and must not inherit inconclusive-identity allowances from ordinary settings queries.
- 规则包单独导入导出，普通设置备份不存规则或捕获内容，不复活历史 `island_custom_rules`。 / Rule packages are imported/exported separately. Ordinary settings backup contains neither rules nor capture content and does not reactivate historical `island_custom_rules`.
- 自定义规则只在共享全文正文路径形成可选外层，命中后返回；不命中、失败或空输出回到原有按当前模式运行的 `parseCompact()`→`parse()` 路径。不改内置实现、外围 `shouldConvert`、原生焦点、隐私、存活、图标、计时器或展开卡片。 / Custom rules are an optional outer layer for shared full-text body extraction. A match returns its result; an unmatched, failed, or empty result returns to the existing mode-dependent `parseCompact()`→`parse()` fallback. The built-in implementation and surrounding conversion, native-priority, privacy, liveness, icon, timer, and expanded-card behavior remain intact.
- 校验命名固定：**L1 格式层 / Format、L2 适配层 / Compatibility、L3 安全层 / Safety + R 运行期 / Runtime**。force 只跳 L2，L1/L3/R 永不跳。首版 match 只允许 `equals`、`contains`、`startsWith`；regex 预留但禁用。 / Validation uses the fixed names **L1 Format, L2 Compatibility, L3 Safety + R Runtime**. Force skips L2 only. L1/L3/R are never skipped. Initial match operators are limited to `equals`, `contains`, and `startsWith`; regex is reserved and disabled.

风险说明见 [island-rules-risk.md](island-rules-risk.md)。本捕获规范不会扩大规则的操作能力；捕获到某个键，不代表规则获准读取或执行该键代表的能力。 / See [island-rules-risk.md](island-rules-risk.md). Capture does not expand rule capabilities: observing a key does not authorize a rule to access or execute the capability it may represent.

## 2. 当前源码证据与非目标 / Source evidence and exclusions

当前 [HyperOS3FocusRestoreHook.java:6724](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6724) 的 `inspectExpanded()` 识别通知及选定参数；[同文件:5950](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L5950) 是共享正文入口；[同文件:5983](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L5983) 的 Debug payload 只单行截断到 2000 字符。它不是本规范的捕获器，不能据此宣称已有完整导出。 / `inspectExpanded()` identifies notification data and the selected parameter; `extractIslandContent()` is the shared body entry. The existing debug payload line flattens and truncates at 2000 characters. It is not this capture subsystem and establishes no existing full-export capability.

`FocusData` 是内部包装，不是可安全序列化的原始 SBN 模型，见 [HyperOS3FocusRestoreHook.java:7047](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L7047)。 / `FocusData` is an internal wrapper, not a safely serializable original SBN model.

**一律禁止 / Always forbidden:** blob、Bitmap/Drawable 像素、Icon 对象、RemoteViews 对象/布局动作、PendingIntent、Intent、Binder/Parcelable 对象、完整 Bundle、任意通知对象序列化、附件与文件内容。只为已确认键生成安全标量/存在性描述，不调用对象任意 `toString()`。 / No blobs, bitmap/drawable pixels, Icon objects, RemoteViews objects/layout actions, PendingIntent, Intent, Binder/Parcelable objects, whole Bundles, arbitrary notification-object serialization, attachments, or file content. Only safe scalars/presence descriptors for confirmed keys are allowed; arbitrary object `toString()` must not be used.

规则提取只输出 `title/subtitle/progress/extra` 文本投影；捕获的“解析比较”也只是文字与状态。icon/timer 为不可用的预留能力，不因捕获而启用。 / Rule extraction produces `title/subtitle/progress/extra` text projections only. Capture comparisons contain text/status only. Icon/timer capabilities are reserved and unavailable and are not enabled by capture.

## 3. 容器结构 / Container structure

顶层采用 `meta` + `samples[]`。下列布局名称为**设计元数据**，不是声称源通知已有这些字段。完整 JSON 结构、枚举和格式版本标识在后续实现前还需与规则规范共同定版。 / The top level is `meta` plus `samples[]`. Layout names below are **design metadata**, not fields claimed to exist in a source notification. Exact schema, enums, and format identifier require final alignment with the rule specification before implementation.

```json
{
  "meta": {
    "formatVersion": 1,
    "documentStatus": "draft",
    "exportMode": "redacted",
    "sessionId": "session-example",
    "complete": true,
    "replayableOriginal": false
  },
  "samples": [
    {
      "id": "sample-example",
      "capturedAt": "2026-11-18T00:00:00+08:00",
      "state": "unlocked-redacted",
      "sbn": {},
      "extras": {},
      "rawParamJSON": {
        "sourceKey": "miui.focus.param",
        "representation": "redacted-json-text",
        "value": "{\"param_v2\":{\"baseInfo\":{\"title\":\"<redacted:string>\"}}}",
        "originalUtf16Length": null,
        "exportedUtf16Length": null,
        "replayableOriginal": false
      },
      "comparison": {
        "before": null,
        "after": null,
        "changed": null,
        "status": "not-observed"
      },
      "redactions": []
    }
  ]
}
```

此例仅说明容器，不是采到的通知或回放样本；日期、ID 和字段值为示意。`null` 表示未观测/未知，不代表原始字段为 JSON null；不能编造缺失的长度或比较结果。`formatVersion: 1` 是 draft 格式提议，不是已经发布的兼容承诺。 / This illustrates the container only, not a captured notification or replay fixture. Dates, IDs, and values are illustrative. `null` means unobserved/unknown, not an assertion that the original value was JSON null. Missing lengths or comparison results must not be invented. `formatVersion: 1` is a draft format proposal, not a published compatibility promise.

### 3.1 设计元数据 / Design metadata

| 路径 / Path | 含义 / Meaning | 来源类别 / Provenance |
|---|---|---|
| `meta.formatVersion` | 捕获格式版本，独立于 App 版本 / Capture format version, independent of app version | 设计元数据；draft/open 版本号 / Design metadata; draft/open version |
| `meta.documentStatus` | 草案/格式状态 / Document/format status | 设计元数据 / Design metadata |
| `meta.exportMode` | 脱敏或经确认的完整导出 / Redacted or confirmed full export | 用户本次导出选择，设计元数据 / Per-export user choice; design metadata |
| `meta.sessionId` | 本地会话关联标识；不复用原通知 key / Local session correlation, not a source notification key | 生成的设计元数据 / Generated design metadata |
| `meta.complete` | 是否完整结束本次导出；中断不能标 true / Whether this export completed; interrupted exports cannot be true | 导出生命周期结果，设计元数据 / Export lifecycle result; design metadata |
| `meta.replayableOriginal` | 是否具备逐值原始保真；默认 false / Whether original value fidelity is retained; false by default | 保真度声明，设计元数据；不是可在ROM重放的承诺 / Fidelity claim; design metadata, not a ROM-replay guarantee |
| `samples[].id` | 本文件内唯一样本 ID / Unique sample ID within the file | 生成的设计元数据 / Generated design metadata |
| `samples[].capturedAt` | 捕获墙钟时间、带偏移；不代替 SBN postTime / Capture wall time with offset; does not replace SBN postTime | 生成的设计元数据 / Generated design metadata |
| `samples[].state` | 锁屏、脱敏、完整内容或被丢弃原因 / Lock/redaction/full-content state or discard reason | 生命周期设计元数据；未知隐私状态按锁屏受限处理 / Lifecycle design metadata; unknown privacy state is restricted |
| `samples[].comparison.before` | 既有内置解析结果及其 source/status / Existing built-in output and source/status | 派生结果；源码来源见 §5，不是原始字段 / Derived result; see §5, not a raw field |
| `samples[].comparison.after` | 实际自定义全文结果或实际回退结果 / Actual custom full-text output or fallback output | 派生结果；未执行时 null，不为捕获多跑规则 / Derived result; null when unexecuted; do not rerun rules merely for capture |
| `samples[].comparison.changed/status` | 比较和错误阶段代码 / Comparison and failure-stage code | 派生设计元数据；不能用含正文的任意异常文本 / Derived design metadata; no arbitrary exception message containing body text |
| `samples[].redactions` | 逐路径变换清单 / Per-path transformation records | 脱敏设计元数据，见 §6 / Redaction design metadata, see §6 |

模块/ROM版本或设备资料若未读取到，标未知或省略；不从捕获文件名、Probe版本或点击日志猜填完整加载版本。额外 meta 字段需先登记来源，当前表不自行加入 device codename、Android ID、账户或硬件标识。 / Omit or mark unknown unavailable module/ROM/device metadata. Do not infer a fully loaded version from capture filenames, Probe versions, or click logs. Additional meta fields require provenance registration; this table does not add device codenames, Android IDs, accounts, or hardware identifiers.

### 3.2 C2-3 内部记录合同 / C2-3 internal record contract

**C2-3 实现合同（作者批准 2026-11-18）**。本节仅定义待实现的进程内捕获记录，不是 §3 示例的导出容器或已实现能力；不冻结文件格式、导出格式或导出确认。§3/§3.1 中导出专用字段不自动成为 C2-3 内存字段。 / This approved contract defines only the proposed in-process capture record, not the export container or implemented behavior. Export formats and confirmation remain open; export-only fields do not automatically enter C2-3 records.

| 内部字段 / Field | 必填 / Required | 语义与 null 规则 / Meaning and null rules |
|---|---|---|
| `id` | 是 / Yes | 生成的 sample ID / Generated sample ID |
| `capturedAt` | 是 / Yes | 捕获时刻 epoch millis / Capture time in epoch milliseconds |
| `sequence` | 是 / Yes | 入队序号，用于计数/去重 / Enqueue sequence for counting/deduplication |
| `state` | 是 / Yes | `CAPTURED` / `LOCKED_PLACEHOLDER` / `REJECTED` / `READ_ERROR` |
| `reason` | 否 / No | `CAPTURED` 必须为 null；其余 state 必须使用下表对应的固定枚举 / Null for CAPTURED; otherwise the fixed reason for that state |
| `packageName` | 否 / No | 仅 CAPTURED、明确未锁屏且身份授权已确认时非空；锁屏/拒绝/错误为 null / Non-null only for authorized, unlocked CAPTURED records; otherwise null |
| `userId` | 否 / No | 同上；锁屏/拒绝/错误为 null / Same boundary; null when locked, rejected, or errored |
| `notificationKey` | 否 / No | 同上；锁屏/拒绝/错误为 null / Same boundary; null when locked, rejected, or errored |
| `bodyText` | 否 / No | 仅 CAPTURED、明确未锁屏且授权确认时非空；锁屏/拒绝/错误为 null / Non-null only for authorized, unlocked CAPTURED records; otherwise null |
| `rawParamJSON` | 否 / No | 同 bodyText；不是允许任意对象序列化 / Same boundary as bodyText; no arbitrary object serialization |
| `extrasJson` | 否 / No | 同 bodyText；仍只允许 §4 白名单描述，不是完整 Bundle / Same boundary as bodyText; restricted to §4 descriptors, never a whole Bundle |
| `comparison` | — | C2-3 不生成、不保存；留给 C2-4 导出层生成 / Not generated or stored by C2-3; reserved for C2-4 export |

**注（待作者确认 2026-11-18）：**C2-4 导出层不主动生成 `comparison`；`comparison` 数据应由后续运行期在解析执行时记录并随捕获记录提供，C2-4 仅按记录输出，缺失时标记 `not-observed`。

**Note (pending author confirmation, 2026-11-18):** The C2-4 export layer does not actively generate `comparison`. Comparison data should be recorded by a future runtime during parser execution and supplied with capture records. C2-4 only emits what is already recorded; missing data is marked `not-observed`.

| state | reason 固定枚举 / Fixed reasons |
|---|---|
| `CAPTURED` | null |
| `LOCKED_PLACEHOLDER` | `LOCKED`、`LOCK_STATE_UNKNOWN` |
| `REJECTED` | `DISABLED`、`NOT_ALLOWLISTED`、`AUTH_UNVERIFIED`、`QUEUE_FULL`、`OVERSIZE`、`SESSION_STALE`、`COMPLETED_BUFFER_FULL` |
| `READ_ERROR` | `READ_FAILED`、`TYPE_MISMATCH`、`MISSING_FIELD` |

缺失字段、错误类型、读取失败统一进入 `READ_ERROR`，分别使用 `MISSING_FIELD`、`TYPE_MISMATCH`、`READ_FAILED`；不得用空串、0、推测值伪造成功记录。锁屏、拒绝与错误记录的身份/正文槽位为 null，不携带实际值；锁屏占位实际保留的字段集合严格限于 §7.2。不得把异常原文或通知正文写日志。 / Missing fields, wrong types, and read failures produce READ_ERROR with their respective fixed reasons, never fabricated successful values. Identity/body slots are null for locked, rejected, and errored records; actual locked placeholders retain only §7.2 fields. No raw exception messages or notification bodies are logged.

## 4. 原始字段白名单与证据 / Raw-field allowlist and evidence

下表只批准**有实际源码读取依据的候选导出字段**，不表示已经实现捕获。路径均相对仓库根目录；行号对应阶段 B 勘察基线。读取失败时记缺失原因，不把反射包装中的 `0`、空串或推测值冒充原始字段。 / The table approves candidate export fields supported by existing source reads; it does not claim capture is implemented. Paths are repository-relative and line numbers refer to the Stage B inspection baseline. Failed reads record absence/reason rather than inventing original fields from wrapper defaults, zero, empty strings, or assumptions.

### 4.1 SBN 标量 / SBN scalars

| 导出路径 / Export path | 原始来源和允许形态 / Source and permitted form | 实际源码证据 / Source evidence |
|---|---|---|
| `samples[].sbn.packageName` | `StatusBarNotification.getPackageName()`，字符串；未脱敏也须在应用白名单内 / String; application allowlist still applies | [app/src/main/java/com/hyperos3/focusrestore/NativeFocusTemplateRenderer.java:1045](../app/src/main/java/com/hyperos3/focusrestore/NativeFocusTemplateRenderer.java#L1045) |
| `samples[].sbn.key` | `getKey()`，字符串；默认占位；内部代次判断不得用导出占位替代真实 key / String; redacted by default; exported placeholder never replaces the internal generation key | [app/src/main/java/com/hyperos3/focusrestore/NativeFocusTemplateRenderer.java:1042](../app/src/main/java/com/hyperos3/focusrestore/NativeFocusTemplateRenderer.java#L1042) |
| `samples[].sbn.postTime` | `getPostTime()`，毫秒整数；默认按脱敏策略占位并记录原类型 / Millisecond integer; placeholder by default with original type recorded | [app/src/main/java/com/hyperos3/focusrestore/NativeFocusTemplateRenderer.java:1043](../app/src/main/java/com/hyperos3/focusrestore/NativeFocusTemplateRenderer.java#L1043) |
| `samples[].sbn.uid` | `getUid()`，整数；不由包名推导，默认占位 / Integer; never inferred from package name; redacted by default | [app/src/main/java/com/hyperos3/focusrestore/NativeFocusTemplateRenderer.java:1045](../app/src/main/java/com/hyperos3/focusrestore/NativeFocusTemplateRenderer.java#L1045) |
| `samples[].sbn.userId` | `getUserId()`，整数；先用于 profile 授权再决定是否记录，默认占位 / Integer; profile authorization precedes recording; redacted by default | [app/src/main/java/com/hyperos3/focusrestore/RomFocusVisibility.java:162](../app/src/main/java/com/hyperos3/focusrestore/RomFocusVisibility.java#L162) |

当前 `inspectExpanded()` 可能面对非 SBN 的 ROM 包装；真实 SBN 不可用时不能凭 `FocusData` 补造上述快照。真实身份已有的完整性检查可参考 [NativeFocusTemplateRenderer.java:1056](../app/src/main/java/com/hyperos3/focusrestore/NativeFocusTemplateRenderer.java#L1056)。通知 `id/tag` 等尚未核实通用捕获入口，列入 §12 待确认，不在正式白名单。 / `inspectExpanded()` may receive a ROM wrapper rather than a real SBN. When a real SBN is unavailable, do not fabricate this snapshot from `FocusData`. Existing real-identity checks provide a reference. General capture access to notification `id/tag` is unconfirmed and remains in §12, outside this allowlist.

### 4.2 extras 已确认键 / Confirmed extras keys

`extras` 采用按键的受限描述：`present`、安全 `valueType`，以及下表允许的标量值。描述字段是本规范设计元数据；不可存类实例、完整类布局或调用对象 `toString()`。Absent、空字符串、错误类型、读取异常须区分，不能统一成 false。 / `extras` uses per-key restricted descriptors: `present`, safe `valueType`, and only the scalar values permitted below. Descriptor fields are design metadata; no class instances, object layouts, or object `toString()` are allowed. Absence, blank text, wrong type, and failed reads must remain distinguishable instead of all becoming false.

| 原键 / Source key | 允许内容 / Allowed content | 实际源码证据 / Source evidence |
|---|---|---|
| `miui.focus.isFocus` | 布尔值/缺失描述 / Boolean or absence descriptor | [app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java:6774](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6774) |
| `miui.focus.param` | 此处只留存在性/类型/长度描述；字符串内容放 `rawParamJSON` / Presence/type/length here; selected string content belongs in `rawParamJSON` | [app/src/main/java/com/hyperos3/focusrestore/FocusParamKeys.java:24](../app/src/main/java/com/hyperos3/focusrestore/FocusParamKeys.java#L24)；[实际选择:43](../app/src/main/java/com/hyperos3/focusrestore/FocusParamKeys.java#L43) |
| `miui.focus.param.custom` | 同上；不自动合并两个参数键 / Same descriptor; never automatically merge both parameter keys | [app/src/main/java/com/hyperos3/focusrestore/FocusParamKeys.java:25](../app/src/main/java/com/hyperos3/focusrestore/FocusParamKeys.java#L25)；[实际选择:43](../app/src/main/java/com/hyperos3/focusrestore/FocusParamKeys.java#L43) |
| `miui.focus.ticker` | 有界字符串，默认占位 / Bounded string, redacted by default | [app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java:6787](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6787) |
| `miui.focus.rv` | 仅存在性和安全类型枚举，禁止对象内容 / Presence and safe type enum only, never object content | [app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java:6788](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6788) |
| `miui.focus.rvNight` | 仅存在性和安全类型枚举 / Presence and safe type enum only | [app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java:6789](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6789) |
| `miui.focus.rvBar` | 仅存在性和安全类型枚举 / Presence and safe type enum only | [app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java:6790](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6790) |
| `miui.focus.rvBarNight` | 仅存在性和安全类型枚举 / Presence and safe type enum only | [app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java:6791](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6791) |

不得把“遍历所有 `miui.focus.*` 键”当作白名单。未列出的键即使当前 Bundle 内存在，也不自动进入导出。JSON payload 内出现的字段是允许捕获的那一个字符串的内容，不因此升级为规则运行期字段白名单。 / A wildcard over all `miui.focus.*` keys is not an allowlist. Unlisted Bundle keys do not enter export automatically. Fields appearing inside the allowed JSON string do not thereby become approved runtime rule paths.

### 4.3 选定参数字符串 / Selected parameter string

| 导出路径 / Export path | 含义 / Meaning | 来源 / Evidence |
|---|---|---|
| `samples[].rawParamJSON.sourceKey` | 选中的实际键名，只能是 PRIMARY 或 CUSTOM / Actual selected key, PRIMARY or CUSTOM only | [app/src/main/java/com/hyperos3/focusrestore/FocusParamKeys.java:55](../app/src/main/java/com/hyperos3/focusrestore/FocusParamKeys.java#L55) |
| `samples[].rawParamJSON.value` | 选定 extras 的原字符串，或其明确标注的脱敏表示；不把 trimmed 解析输入称作原字节 / Selected extras string or an explicitly marked redacted representation; trimmed parser input is not original bytes | [app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java:6779](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L6779)；[取值/trim语义:43](../app/src/main/java/com/hyperos3/focusrestore/FocusParamKeys.java#L43) |

`representation`、`originalUtf16Length`、`exportedUtf16Length`、`replayableOriginal` 为设计元数据。完整导出若保留 extras 中未 trim 的字符串，需同时说明内置解析实际使用 trim 后输入；字符串再编码为 UTF-8 不等于原 Android 进程内存字节。脱敏模式保留可解析的对象/数组结构，不能把敏感原文藏到另一个“原始”字段。若原字符串无效 JSON，默认模式仅输出固定占位及 `invalid-json` 原因，不输出无法安全脱敏的原字符串；完整模式仍受确认、锁屏、授权和容量约束。 / These fidelity/length fields are design metadata. A full export retaining the untrimmed extras string must state that the built-in parser consumes trimmed input. Encoding a Java string as UTF-8 is not preservation of original process-memory bytes. Redacted mode retains parseable object/array structure and must not hide sensitive originals elsewhere. Invalid JSON is represented by a fixed placeholder and `invalid-json` reason in default mode; unsafe raw text is not emitted. Full mode remains subject to confirmation, lock state, authorization, and quotas.

## 5. 解析前后比较 / Before/after comparison

比较只记录**实际发生**的内置与规则结果；未执行的侧标 `not-observed`，不得为导出在布局线程重新运行解析。当前内置结果的 `text/source` 来源于 [IslandPayloadParser.java:375](../app/src/main/java/com/hyperos3/focusrestore/IslandPayloadParser.java#L375)，被共享入口转换为 `IslandText` 的位置见 [HyperOS3FocusRestoreHook.java:5969](../app/src/main/java/com/hyperos3/focusrestore/HyperOS3FocusRestoreHook.java#L5969)。自定义规则结果尚未实现，`after` 是设计字段，不能引用不存在的 Java 结果类作为证据。 / Only actually observed built-in/rule outcomes are compared. An unexecuted side is `not-observed`; export must not rerun parsing in layout callbacks. Existing `text/source` originates in `ParsedText` and is adapted at the shared hook entry. Custom outputs are not implemented: `after` is a design field and must not cite a nonexistent result class.

默认模式将 before/after 文本同样脱敏。`changed` 可在授权的瞬时数据上计算后仅存布尔，但布尔不等于视觉不同、原生卡片已变更或通知已成功展示。内部 source、ruleId、失败阶段是诊断元数据；不得输出携带输入正文的堆栈参数。 / Default mode redacts before/after text as well. A `changed` boolean can be computed from transient authorized data, but it does not prove visual changes, native-card changes, or successful display. Source labels, rule IDs, and failure stages are diagnostic metadata; stack arguments containing input text must not be exported.

## 6. 脱敏保真度与二次确认 / Redaction fidelity and confirmation

默认以固定占位替换敏感标量，保留可识别的字段路径、对象层级及数组项位置。每次变换在 `redactions[]` 记录设计字段 `path`、`action`、`originalType`、`exportedType`、`originalUtf16Length`、`exportedUtf16Length`、`reason`；路径编码建议 JSON Pointer，**draft/open**，尚不声称已有编码器。长度未知用 null；长度指该标量字符串表示的 UTF-16 单元，非字节，非容器总长度。数字/布尔变字符串占位时须记录类型改变，不能仍宣称数值比较可回放。null、缺失、读取失败也必须区分。 / Default replacement uses fixed placeholders while retaining recognizable field paths, object nesting, and array positions. Each transformation records design fields `path`, `action`, `originalType`, `exportedType`, `originalUtf16Length`, `exportedUtf16Length`, and `reason`. JSON Pointer is a **draft/open** path-encoding recommendation, not an existing encoder. Unknown lengths are null. Lengths count UTF-16 units of a scalar's string representation, not bytes or container length. Replacing a number/boolean with placeholder text must record the type change and cannot claim replayable numeric conditions. Null, absence, and failed reads stay distinct.

占位会改变 `equals/contains/startsWith`、字面替换、长度条件和拼接结果；即使原类型被保留，值仍改变。`replayableOriginal=false` 必须贯穿默认文件，AI 只能据路径提出规则，不能声称通过原通知回放。完整确认模式可保留允许字段的值，但仍缺禁止对象、其他 Bundle 字段及设备运行上下文，故不能承诺重建 Notification 或在 ROM 重放。 / Placeholders change equality/containment/prefix matching, literal replacement, lengths, and joined output. Preserving a type does not preserve its value. Default files remain `replayableOriginal=false`; an AI may propose paths but must not claim replay validation against the original. Confirmed full mode can preserve allowlisted values, but omitted objects, other Bundle fields, and device context still prevent a claim of Notification reconstruction or ROM replay.

**Draft/open：**动态 JSON 键名本身含人名、账户等内容时，需制定键名处理策略；无法保持安全路径时应移除该子树并记录 `structureChanged=true` 及原因，不得同时声称结构完全保留。未确认的敏感字段识别覆盖率不得宣传为“完全脱敏”。 / **Draft/open:** dynamic JSON keys may themselves contain names or accounts. A key-redaction policy is required. If safe paths cannot be preserved, remove the subtree and record `structureChanged=true` plus a reason rather than claiming complete structural fidelity. Unverified sensitive-field coverage must not be marketed as complete anonymization.

完整导出的二次确认针对本次选择的样本/范围，不可由“曾经开启过捕获”替代。确认时再次检查当前锁屏、用户/profile、白名单和会话代次。导出不能包含确认后才到达的新内容而不重新确认范围。 / Full-content confirmation applies to the current selected samples/scope and cannot be replaced by prior capture activation. Recheck lock, user/profile, allowlist, and session generation at confirmation. New arrivals after confirmation must not silently expand the approved export scope.

## 7. 会话、锁屏和队列 / Session, lock state, and queues

### 7.1 已冻结状态边界 / Frozen state boundaries

- 关闭：不读正文、不排正文任务；不能因为通知更新自动开启。 / Off: no body read or body work queued; notification updates cannot enable capture.
- 显式开启且应用允许：仍需检查当前锁屏、真实 SBN 身份及用户/profile 边界。 / Explicitly on and application-allowed: lock state, real SBN identity, and user/profile boundaries still require checks.
- 锁屏或隐私状态无法确认：不读取正文，只允许没有正文的元数据占位；完整内容不可选。不得回填锁屏期间未获授权的正文。锁屏前排队但未落盘内容建议丢弃，具体竞态处置是 §7.2 的 draft/open 设计。 / Locked or unverifiable privacy state: no body read, metadata-only placeholders, and no full-content option. Unauthorized locked-period content must not be backfilled. Discarding pre-lock queued but unpersisted bodies is a draft/open race-handling recommendation in §7.2.

### 7.2 Draft/open 状态机与 C2-3 已批准占位合同 / Draft/open state machine and approved C2-3 placeholders

建议每次开启仅对本次进程会话有效，进程结束/重启不恢复开启。推荐 `OFF → UNLOCKED_REDACTED → LOCKED_METADATA`；完整导出是单次授权操作，不是持久模式。解锁后可见性重新核对；建议不自动恢复完整内容授权。会话代次随关闭、重锁、profile 变化或撤权增加，使旧回调不能复活任务。 / Activation is recommended to last only for the current process session and not survive process end/restart. Suggested states are `OFF → UNLOCKED_REDACTED → LOCKED_METADATA`; full export is a one-operation authorization, not a persistent mode. Recheck visibility after unlock; do not automatically restore full-content consent. Increment generations on disable, relock, profile change, or revocation so stale callbacks cannot revive work.

建议锁定时清空本会话内此前捕获的**正文内容**、缓存、比较文本和未导出完整内容，只留无正文事件计数/原因；但“是否清空已经落盘历史内容、解锁是否续捕获、保留期限”尚未拍板。若后续决定保留历史，须另定义锁屏不可读、导出禁止和安全删除边界，不能默认为锁屏可导出。 / It is recommended to clear previously captured session bodies, caches, comparison text, and unexported full content on lock, retaining only content-free counters/reasons. Deletion of already persisted history, capture resumption after unlock, and retention duration remain undecided. Retaining history would require explicit rules for locked access, export prohibition, and deletion; it must never imply export is allowed while locked.

队列应只持有有界、脱离 View/Entry 的必要数据副本，逐阶段核对会话代次、锁屏和 profile：入队前、工作执行前、落盘前、导出提交前。已开始的后台任务也受失效约束，不得只取消尚未启动的 Future。关闭/锁定与入队竞态应使旧任务弃置；正文不写入普通 Logcat、错误消息或临时公开文件。 / Queues should hold bounded necessary copies detached from View/Entry references and recheck generation, lock state, and profile before enqueue, execution, persistence, and export commit. Already running work must be invalidated as well as not-yet-started futures. Disable/lock races should discard stale work. Bodies must not leak through ordinary Logcat, errors, or public temporary files.

**C2-3 实现合同（作者批准 2026-11-18）——锁屏占位字段集合：**只保留生成的 `id`、`capturedAt`、`sequence`、`state = LOCKED_PLACEHOLDER`、`reason = LOCKED` 或 `LOCK_STATE_UNKNOWN`。不保留包名、通知 key、uid、userId、正文描述、正文、extras 或 rawParamJSON；不读取源标题、ticker、JSON、图标或解析比较。锁屏状态未知按锁定处理，fail-closed，不读正文。会话统计可保留 `lockedPlaceholders` 计数，占位记录中不得保留身份元数据。 / Approved C2-3 locked placeholders retain only generated id, capturedAt, sequence, LOCKED_PLACEHOLDER state, and LOCKED or LOCK_STATE_UNKNOWN reason. No package, notification key, uid/userId, body descriptors, body, extras, rawParamJSON, source title/ticker, icons, or comparisons are retained or read. Unknown lock state is restricted and body-free. A session-level lockedPlaceholders counter is allowed; placeholder records contain no identity metadata.

该批准只确定占位字段与不读正文的边界；历史内容删除、解锁恢复、中途重锁策略继续为 draft/open。正文处理前重查锁屏与会话代次的最小合同见 §12.1，不据此冻结 §7.2 其余建议。 / This approves only placeholder fields and the body-read boundary. History deletion, unlock resumption, and broader relock policy remain draft/open. The minimal pre-body lock/generation recheck is defined in §12.1 and does not freeze other recommendations here.

### 7.3 用户/profile 边界 / User and profile boundaries

应用白名单需与已授权用户/profile 一起匹配。不得用 SystemUI 的广泛通知访问能力收集其他用户或工作资料；`USER_ALL` 不能推测成当前用户后当成普通样本。真实 userId 不可得、profile 不可用/被锁或授权无法确认时，拒绝正文，只记录不含该通知身份的占位/计数。跨用户切换建议结束会话并清队列，不能把两种 profile 的内容合到同一导出文件。 / Application allowlisting must be combined with authorized user/profile scope. SystemUI's broad notification access must not authorize collection from other users or work profiles. `USER_ALL` must not be guessed into an ordinary current-user sample. Missing real user identity, unavailable/locked profile, or unverifiable authorization denies body capture and permits only non-identifying placeholders/counters. A user switch is recommended to end the session and clear queues rather than mix profiles in one export.

现有可见性读取证据为 [RomFocusVisibility.java:147](../app/src/main/java/com/hyperos3/focusrestore/RomFocusVisibility.java#L147)，但显示允许不自动等于允许持久捕获。捕获仍需独立授权；工作资料是否允许用户显式选入、`USER_ALL` 如何处理须另行确认，不在本文扩权。 / Existing visibility reads provide source evidence but display permission is not persistence permission. Capture needs independent authorization. Explicit work-profile opt-in and `USER_ALL` handling still require decisions; this document grants no such access.

## 8. 本地保存、去重与配额 / Local storage, deduplication, and quotas

**Draft/open 推荐：**模块 App 的 CE 私有存储承接有界内容，SystemUI 只做有界临时队列和必要 IPC；不存 DP 共享内容，不借用普通配置 Cursor 装大段原始 JSON，不直接从注入进程写模块 App 私有目录。进程会话关闭时如何删除、是否支持跨进程持久历史及保存期限待确认。 / **Draft/open recommendation:** use module-app CE-private storage for bounded content, with only bounded transient queues and necessary IPC in SystemUI. Do not persist content in DP settings, place large JSON payloads in the ordinary config Cursor, or assume an injected process can write the module app's private directory. Deletion on session end, cross-process history, and retention duration remain open.

去重应以会话+真实用户/profile+真实通知 key/postTime+白名单内容修订判定；同 key 的正文更新不得错误合并为首条，通知重发不得继承前一代样本。精确算法与是否采用进程内摘要为 draft/open；不能把稳定哈希当作脱敏或在导出中泄露全局关联标识。去重表本身也受容量上限，驱逐/丢弃须输出不含内容的计数及原因。 / Deduplication should consider session, actual profile, real key/postTime, and allowlisted-content revision. Updates under one key must not disappear into the first sample, and reposted notifications must not inherit the previous generation. The algorithm and use of process-local digests are draft/open. Stable hashes are not anonymization and must not introduce global correlation identifiers into exports. Dedup state is bounded too, with content-free eviction/drop counts and reasons.

既有输入上限仅可作为上界参考：payload 256 Ki UTF-16字符、512 Ki UTF-8 字节，输出4096字符、separator32、包名255，见 [InputLimits.java:16](../app/src/main/java/com/hyperos3/focusrestore/InputLimits.java#L16)；有界 UTF-8 检查见 [同文件:26](../app/src/main/java/com/hyperos3/focusrestore/InputLimits.java#L26)，截断不切高代理项见 [同文件:77](../app/src/main/java/com/hyperos3/focusrestore/InputLimits.java#L77)。这些不是已批准的捕获文件容量，也不能依赖静默截断来伪装完整样本。C2-3 捕获内存限额与整条拒绝策略按 §8.1 执行；文件/导出等其他范围的超额策略仍待定。 / Existing limits are upper-bound references only, not approved capture-file quotas. UTF-8 checks and surrogate-safe truncation are reusable facts, not permission to silently truncate an allegedly complete sample. C2-3 in-memory capture follows §8.1 quotas and whole-record rejection; overflow policy for files, export, and other scopes remains open.

### 8.1 C2-3 内存配额 / C2-3 memory quotas

**C2-3 实现合同（作者批准 2026-11-18）**。下列字节均为 `bodyText`、`rawParamJSON`、`extrasJson` 三类正文字段的 UTF-8 字节，累计限额按这三类字段合计，不是对象堆大小、文件容量或导出格式上限；2 MiB = 2,097,152 字节，192 KiB = 196,608 字节。 / These approved quotas count UTF-8 bytes only across bodyText, rawParamJSON and extrasJson, not heap-object sizes, file quotas, or export-format limits.

| 范围 / Scope | 上限 / Limit |
|---|---|
| 待处理队列 / Pending queue | 最大 64 项；累计字符串字节 ≤ 2 MiB / At most 64 items and 2 MiB of string bytes |
| 单条 `bodyText` / Per-record bodyText | ≤ 65,536 UTF-8 字节 / bytes |
| 单条 `rawParamJSON` / Per-record rawParamJSON | ≤ 65,536 UTF-8 字节 / bytes |
| 单条 `extrasJson` / Per-record extrasJson | ≤ 65,536 UTF-8 字节 / bytes |
| 单条记录所有字符串 / All strings per record | 合计 ≤ 196,608 UTF-8 字节（192 KiB） / Combined at most 192 KiB |
| 已完成记录缓冲 / Completed-record buffer | 最大 64 条；累计字符串字节 ≤ 2 MiB / At most 64 records and 2 MiB of string bytes |

**字节统计口径：**“单条记录所有字符串合计 ≤196,608”仅统计 `bodyText`、`rawParamJSON`、`extrasJson` 三个正文字段的 UTF-8 字节合计；`id`、`capturedAt`、`sequence`、`state`、`reason`、`packageName`、`userId`、`notificationKey` 等标识、枚举和时间字段不计入该 196,608 上限。“待处理队列累计 ≤2 MiB”与“已完成记录缓冲累计 ≤2 MiB”同样只统计这三类正文字段的 UTF-8 字节合计，元数据字段不计入。项数上限（64 项 / 64 条）仍按记录条数计算，包含锁屏占位、拒绝记录和错误记录；元数据不占字节额度，但仍受 §7.2 锁屏身份字段禁令和“不写正文日志”约束。

**Byte accounting:** The per-record 196,608-byte cap counts only the UTF-8 bytes of `bodyText`, `rawParamJSON`, and `extrasJson`. Identifier, enum, and time fields such as `id`, `capturedAt`, `sequence`, `state`, `reason`, `packageName`, `userId`, and `notificationKey` are excluded from this cap. The 2 MiB pending-queue and completed-buffer caps likewise count only those three body fields; metadata is excluded from the byte caps. Item-count caps (64 items / 64 records) still count every record, including locked placeholders, rejected records, and error records. Excluding metadata from byte caps does not relax the §7.2 locked-placeholder identity prohibition or the no-body-logging rule.

- 队列满：拒绝新项并计数；不阻塞、不淘汰旧项。 / Queue full: reject and count the new item without blocking or evicting old items.
- 单条超额：整条拒绝并计数；不截断、不部分保留。 / Oversized record: reject and count the whole record; no truncation or partial retention.
- 完成缓冲满：拒绝新完成记录并计数，不淘汰已捕获记录；已捕获记录保留至显式清除或会话失效。本条不定义历史内容删除、解锁恢复或中途重锁如何触发会话失效。 / Completed buffer full: reject and count the new completed record without evicting captured records. Captured records remain until explicit clearing or session invalidation. This does not decide history deletion, unlock resumption, or broader relock-triggered invalidation.
- 至少记录以下无正文计数：`droppedQueueFull`、`droppedOversize`、`droppedCompletedBufferFull`、`droppedSessionStale`、`rejectedDisabled`、`rejectedNotAllowlisted`、`rejectedAuthUnverified`、`lockedPlaceholders`。 / Maintain at least these content-free counters.
- 捕获入口不得做重 IO、序列化写盘、网络或等待导出。 / The capture entry must not perform heavy IO, serialize to disk, use the network, or wait for export.

### 8.2 其余配额仍为 draft/open / Remaining quotas stay draft/open

除 §8.1 已批准的 C2-3 内存限额与超额策略外，文件总量、深度/节点数、去重表项数、批量 IPC 大小、写入/导出 timeout、历史保留时长及其驱逐策略仍为 **draft/open，无默认已拍板值**。零值、并发超额、低磁盘和取消边界须由对应实现覆盖，不能使用无限队列；本次批准不冻结文件容量、历史内容删除、解锁恢复、中途重锁策略、导出确认或导出格式。 / Apart from §8.1 approved C2-3 memory quotas and overflow behavior, file totals, depth/node counts, dedup-table capacity, IPC batches, write/export timeouts, history retention and eviction remain draft/open with no approved defaults. Corresponding implementations must cover zero, concurrent overflow, low disk and cancellation. This approval freezes no file quota, history deletion, unlock resumption, broader relock policy, export confirmation, or export format.

## 9. 严格通道与导出中重锁 / Strict IPC and relock during export

配置读取当前在身份查询空/异常时会放行，见 [SettingsProvider.java:98](../app/src/main/java/com/hyperos3/focusrestore/SettingsProvider.java#L98)；加载状态上报不沿用此策略，见 [同文件:75](../app/src/main/java/com/hyperos3/focusrestore/SettingsProvider.java#L75)。这里只引用现状，不修改现有配置校验。捕获通道必须独立 fail-closed：校验调用 UID、可确认的包/进程角色和用户/profile；身份查询失败拒绝，不依赖请求自报 package、Intent extra 或 UID 相等的猜测。写入与读取/导出分别授权，不能因为允许 SystemUI 写入而允许任意同权限组件读取正文。具体 IPC 方法、权限表和跨用户验证方式为实现前 draft/open。 / Ordinary config reads currently allow inconclusive identity lookups, while loaded-state reporting does not. These are source facts, not changes to existing config authorization. Capture needs independent fail-closed checks of caller UID, verifiable package/process role, and profile. Failed identity lookup is rejected, not replaced by self-reported package/Intent data or guessed UID equivalence. Write and read/export authorization are separate; permission to accept SystemUI writes does not grant arbitrary components access to bodies. Exact IPC methods, access matrix, and cross-user checks remain draft/open before implementation.

导出前推荐冻结所选样本与会话代次，检查解锁状态；只通过用户选择的文档 URI 输出，不自动网络传输。**Draft/open fail-closed 建议：**导出中重锁/撤权/profile变化立即失效本次授权、停止后续读取与分块写、关闭流并标为取消；不因随后解锁而自动续写。二次确认不应成为永久授权。 / Freeze the selected sample set and session generation and recheck unlock state before export. Write only to a user-selected document URI and never upload automatically. **Draft/open fail-closed recommendation:** relock, revoked consent, or profile change invalidates consent, stops further reads/chunks, closes the stream, and marks cancellation. Unlocking must not resume it automatically. Second confirmation must not become permanent authorization.

外部文档提供方可能已接收部分字节，关闭流不等于删除或回滚。无法可靠移除时必须提示“可能存在部分文件”，建议用户手动删除；删除结果不可确认时不得声称已清除。两阶段临时文件/原子替换也受提供方能力限制，其可行性和清理策略待确认。 / An external document provider may already have received bytes; closing a stream is not deletion or rollback. If removal is unconfirmed, report that a partial file may exist and advise manual deletion. Do not claim successful erasure without evidence. Temporary-file/two-phase or atomic replacement depends on provider capabilities and needs a separate decision.

## 10. Markdown 摘要格式 / Markdown summary format

JSON 是带来源/保真度记录的主文件；Markdown 是便于人工与 AI 阅读的伴随摘要，不能替代 JSON 或声称当前 App 已生成此类文件。两者应使用同一冻结样本集合、同一导出模式和相同脱敏策略；不能在摘要中偷偷恢复 JSON 已脱敏的正文。 / JSON is the primary provenance/fidelity record. Markdown is a companion for human/AI review, not a replacement or an existing app capability. Both use the same frozen sample set, export mode, and redaction policy. A summary must not restore values redacted in JSON.

建议版式 / Proposed layout:

```text
# FocusRestore capture summary
Format: draft v1 | Mode: redacted | Complete: true
Original-value replay: false
Samples: 1 | dropped: 0 | placeholders: 0

## Sample sample-example
State: unlocked-redacted
Package: <approved-or-redacted>
Captured at: <time-or-redacted>
Source key: miui.focus.param.custom
Paths observed: /param_island/bigIslandArea/textInfo/title
Builtin before: <redacted> | source: <known-or-not-observed>
Custom/fallback after: <redacted> | status: <known-or-not-observed>
Changed: <true/false/unknown>
Redaction: values replaced; originalTypes/changedPaths in JSON
Limits/errors: <content-free codes only>
```

所有来自通知/规则的字符串按纯文字转义；不得产生可执行 HTML、自动链接、图片加载或会被误读为命令的活动内容。摘要必须注明缺失、截断、脱敏变换、locked 占位以及未实际执行的一侧。原始正文只可在已确认的完整模式里作为带明示标签的文本块出现，且仍受锁屏、范围冻结和配额限制。 / Escape notification/rule strings as plain text. Do not create active HTML, automatic links, image fetches, or executable commands. State absence, truncation, redaction, locked placeholders, and unobserved comparison sides explicitly. Original text blocks require confirmed full mode and the same lock/scope/quota checks.

## 11. 清空能力 / Clear capability

必须提供独立的“清空捕获 / Clear captures”，与停用规则、普通设置恢复、取消一次导出区分。清空范围至少包括本地样本正文及元数据、待写队列、比较结果、去重状态与模块所有临时导出副本；先使会话代次失效并停止后续任务，再清理已拥有的文件，避免旧回调重新写回。不得删除规则包、普通设置或其他应用数据。 / Provide a separate Clear captures action, distinct from disabling rules, restoring settings, or cancelling one export. It covers local samples/metadata, pending writes, comparisons, dedup state, and module-owned temporary export copies. Invalidate the generation and stop work before deleting owned data so stale callbacks cannot repopulate it. Do not delete rule packages, settings, or other apps' data.

清空后显示实际删除/失败数量，失败保留不含正文的原因并可重试；不假称闪存安全覆写或已撤回分享副本。已经交给 SAF/外部提供方或用户分享出去的文件不受模块本地清空控制，须明确提示另行处理。确认文案、锁屏是否可执行清空、历史保留与粒度清除 UI 是待拍板细节，具体授权未确认时不得越权访问其他 profile。 / Report actual success/failure counts with content-free retryable errors. Do not promise secure flash erasure or revocation of shared copies. SAF/provider and previously shared files remain outside local clearing and require separate handling. Confirmation wording, locked clearing, retention, and per-item UI remain open; never cross profiles without authorization.

## 12. 待确认项与后续验收 / Open decisions and later acceptance

除 §12.1 明确标注的本次最小实现合同，以及 §3.2、§7.2、§8.1 对应批准项外，本章其余项目仍为 **draft/open**，不得先写成实现默认值。 / Except for the explicitly approved minimal contract in §12.1 and its corresponding items in §3.2, §7.2 and §8.1, the remaining decisions here stay draft/open and must not become implementation defaults.

1. 会话是否严格仅本次进程、进程由 SystemUI 还是模块 App 定义、关闭/崩溃后的残留清除。 / Session process owner and cleanup on disable/crash.
2. CE 私有保存方案、持久历史、保存期限与锁屏前已落盘内容是否清除；解锁是否续捕获。 / CE storage, history/retention, deletion of pre-lock persisted content, and unlock resumption.
3. §8.2 的其余容量、数量、超时和驱逐参数；§8.1 内存配额与超额策略已批准。 / Remaining §8.2 quotas, timeouts and eviction parameters; §8.1 memory quotas and overflow behavior are approved.
4. 锁屏占位字段已按 §7.2 批准且不含身份；工作资料显式授权和 `USER_ALL` 处理仍待定。 / Locked placeholders are approved in §7.2 without identity fields; work-profile opt-in and USER_ALL handling remain open.
5. IPC 权限表、caller 角色验证及导出中重锁的可实现取消/部分文件清理合同。 / IPC access matrix, caller-role verification, relock cancellation, and partial-file cleanup.
6. `notification id/tag`、设备代号等未核实通用调用点的字段；在核实前不得加入原始白名单。 / Fields without verified general source access, including notification id/tag and device codename, remain outside the raw allowlist.
7. 动态键名脱敏、路径编码、长度元数据隐私及导出格式版本/枚举的最终名称仍待定；C2-3 内部 state/reason 已按 §3.2 批准，不据此冻结导出枚举。 / Dynamic-key redaction, path encoding, length metadata privacy, and final export format/enumeration names remain open. C2-3 internal state/reason enums are approved in §3.2 without freezing export enums.
8. C2-3 显式会话内有界暂存白名单原始字符串已按 §12.1 批准，脱敏仅在 C2-4 导出时执行；本轮不要求入队前脱敏。完整导出二次确认的具体实现、持久保存方案、导出格式仍待定，不由本次内存暂存授权替代。 / Bounded allowlisted original strings may be retained in an explicit C2-3 session under §12.1; redaction occurs only during C2-4 export, not before enqueue in this scope. Full-export confirmation implementation, persistence, and export format remain open and are not authorized by in-memory retention consent.

### 12.1 C2-3 原文暂存授权与身份边界 / C2-3 original-string retention and identity boundary

**C2-3 实现合同（作者批准 2026-11-18）**。允许显式启用后在内存中有界暂存白名单原始字符串；脱敏仅在 C2-4 导出时执行。必须同时满足： / Explicit activation permits bounded in-memory retention of allowlisted original strings, with redaction only during C2-4 export. All of the following are required:

- 捕获已显式启用；初始默认关闭。 / Capture is explicitly enabled and initially off.
- 包名在显式白名单中；空白名单表示全部拒绝。 / The package is explicitly allowlisted; an empty list rejects all.
- 锁屏状态为明确 `UNLOCKED`；`UNKNOWN` 按 `LOCKED` 处理。 / Lock state is explicitly UNLOCKED; UNKNOWN is restricted as LOCKED.
- 调用方传入 `IdentityAuthorization.CONFIRMED`；`UNVERIFIED` / `DENIED` 拒绝正文。 / The caller supplies CONFIRMED identity authorization; UNVERIFIED and DENIED reject body access.
- 会话代次有效。 / The session generation is valid.
- 未超过 §8.1 容量及单条上限。 / All §8.1 aggregate and per-record quotas are satisfied.

纯 Java 接口语义（仅合同声明，本轮不新增 Java 代码） / Pure-Java interface semantics (contract only; no Java implementation in this update):

```java
enum LockState { LOCKED, UNLOCKED, UNKNOWN }
enum IdentityAuthorization { CONFIRMED, UNVERIFIED, DENIED }
```

捕获层只接受调用方给出的授权结论，不自行推断真实身份或用户/profile 授权。身份授权未知/未确认（`UNVERIFIED`）或被拒绝（`DENIED`）时拒绝正文，记录 `REJECTED / AUTH_UNVERIFIED`，不保留身份字段；这不是对现有 Provider/IPC 或跨用户访问的授权。 / The capture layer consumes the caller's authorization conclusion without inferring real identity or user/profile authorization. UNVERIFIED or DENIED rejects body access with REJECTED / AUTH_UNVERIFIED and no identity fields. This grants no Provider/IPC or cross-user access.

异步任务在处理正文前必须再次检查锁屏状态和会话代次：若已锁定或锁屏状态未知，转为 §7.2 锁屏占位且不读正文；若代次失效，丢弃并计入 `droppedSessionStale`。本条只冻结处理正文前的读取/发布边界，不冻结历史内容删除、解锁恢复或中途重锁的其他策略。 / Before body processing, asynchronous tasks recheck lock state and session generation. Locked or unknown state yields a §7.2 body-free placeholder; stale generations are discarded and counted in droppedSessionStale. This freezes only the pre-body access/publication boundary, not history deletion, unlock resumption, or other relock policy.

捕获内容不自动落盘、不上传、不写正文日志。C2-3 不生成或保存 `comparison`，不实现导出；本轮仅同步合同，C2-3/C2-4 均未因此进入实现。 / Captures are not automatically persisted, uploaded, or written to body logs. C2-3 does not generate/store comparison or implement export. This update synchronizes contracts only and starts neither C2-3 nor C2-4 implementation.

后续实现验收至少需覆盖：默认关零正文 IO、未授权应用/profile、锁屏和重锁竞态、已在执行的任务失效、历史内容清理决策、完整确认范围冻结、无效JSON与超额输入、通知更新/重发去重、低磁盘与外部提供方失败、退出后的队列/流释放。仅文档评审不等于通过这些运行验证。 / Later acceptance must cover default-off body-free behavior, unauthorized applications/profiles, lock/relock races, invalidation of running tasks, approved history cleanup, frozen full-export scope, invalid/oversized JSON, update/repost deduplication, low disk/provider failure, and queue/stream release. Document review alone does not establish runtime acceptance.

## 13. C2-4 导出合同拟裁决草案 / Proposed C2-4 export contract

**C2-4 导出合同拟裁决草案（待作者确认，2026-11-18）**。本节尚未批准、尚未实现，不构成开始 C2-4 实现或后续阶段的授权。 / **Proposed C2-4 export contract (pending author confirmation, 2026-11-18).** This section is neither approved nor implemented and does not authorize C2-4 implementation or later stages.

本节 `comparison` 安排显式引用 **§3.2 表后的“注（待作者确认 2026-11-18）”**：C2-4 不主动生成比较，只输出输入记录中已经存在的比较数据，缺失标 `not-observed`。这是对 §3.2“留给 C2-4 导出层生成”既有预留安排的**拟变更，待作者确认**，不是推翻已批准裁决；§3.2 原句保留不变。本草案不授权修改 C2-3 模型以补造比较数据。 / The comparison proposal explicitly refers to the **note pending author confirmation dated 2026-11-18 after the §3.2 field table**. C2-4 does not actively generate comparisons: it only emits comparison data already present in input records and marks missing data `not-observed`. This is a **proposed change, pending author confirmation**, to the existing §3.2 reservation for C2-4 export, not a reversal of an approved decision. The original §3.2 sentence is preserved. This draft does not authorize changing the C2-3 model to fabricate comparisons.

### 13.1 范围与输入输出 / Scope, inputs and outputs

- 仅拟实现纯 Java 导出文档生成器；不接 UI、设置、Hook、SystemUI、SAF 或文件系统写入。 / Propose only a pure-Java export-document generator, without UI, settings, Hook, SystemUI, SAF or filesystem writes.
- 输入：C2-3 的不可变捕获记录快照、导出模式 `REDACTED / FULL`、锁屏状态、会话代次、完整导出授权结论、样本选择范围；独立导出授权仍受 §13.4 约束。 / Inputs are an immutable C2-3 capture-record snapshot, REDACTED/FULL mode, lock state, session generation, full-export authorization conclusion and selected sample scope. Independent export authorization remains subject to §13.4.
- 输出：导出文档对象，至少含 JSON 字符串、Markdown 字符串、导出元数据、`redactions` 清单、内容无关的错误/拒绝原因。 / Output is an export-document object containing at least JSON and Markdown strings, export metadata, a redactions list and content-free error/rejection reasons.
- 不新增捕获、不重新读取通知、不为导出重跑解析或规则、不生成 `comparison`。若导出出现 comparison，只能来自输入记录中已经存在的字段；缺失标 `not-observed`，不得推测 before/after/changed 值。 / Do not capture new data, reread notifications, rerun parsers/rules for export or generate comparison. Any exported comparison must already exist in input records. Missing comparison is not-observed; before/after/changed values must not be inferred.
- JSON 为主文档，Markdown 为伴随摘要；两者使用同一冻结样本集合、同一导出模式和同一脱敏策略，不在摘要恢复 JSON 已脱敏的内容。 / JSON is the primary document and Markdown its companion summary. Both use the same frozen sample set, mode and redaction policy; the summary must not restore content redacted in JSON.

### 13.2 字段映射与脱敏 / Field mapping and redaction

**默认 REDACTED 模式 / Default REDACTED mode：**

- 保留可安全保留的字段名、对象层级、数组位置、存在性，以及原类型和长度元数据；占位造成的类型改变必须据实记录，不能声称值或类型未改变。 / Preserve safe field names, object nesting, array positions, presence, and original-type/length metadata. Any placeholder-induced type change must be recorded rather than claiming unchanged values or types.
- `freeText` 值用固定占位替换，例如 `<redacted:string>`；`structural` 字段保留原值或结构描述，不把敏感原文藏到其他字段。 / Replace freeText values with fixed placeholders such as `<redacted:string>`. Retain structural values or structural descriptions without hiding sensitive originals in other fields.
- `packageName` 仅在白名单内保留原值；`userId`、`notificationKey`、`postTime`、`uid` 默认占位。字段未捕获时不补造；本项不扩张 §4 允许的原始字段范围。 / Retain an original packageName only when allowlisted; userId, notificationKey, postTime and uid are placeholders by default. Do not fabricate uncaptured fields or expand the §4 raw-field allowlist.
- `bodyText`、`rawParamJSON`、`extrasJson` 中的文本值按 freeText 占位；JSON 结构保留。无法安全保留键名时移除该子树并记录 `structureChanged=true`，不同时声称结构完全保留；不得把不安全原文转存到其他字段。无效 JSON 继续遵守 §4.3 的固定占位及 `invalid-json` 原因边界。 / Replace text values in bodyText, rawParamJSON and extrasJson as freeText while retaining JSON structure. If key names cannot be retained safely, remove the subtree and record structureChanged=true rather than claiming full structural fidelity. Do not move unsafe originals elsewhere. Invalid JSON follows the §4.3 fixed-placeholder and invalid-json boundary.
- 每次变换写入 `redactions[]`，至少含 `path`、`action`、`originalType`、`exportedType`、`originalUtf16Length`、`exportedUtf16Length`、`reason`；未知长度为 null。沿用 §6 的 UTF-16 长度定义；本草案不将尚待定的路径编码等其他事项写成已冻结。 / Record every transformation in redactions[] with at least path, action, originalType, exportedType, originalUtf16Length, exportedUtf16Length and reason. Unknown lengths are null. Use the §6 UTF-16 length definition; this draft does not freeze unresolved path encoding or other decisions.
- 默认文档 `replayableOriginal=false`，不宣称脱敏文档可按原始值回放。 / Default documents have replayableOriginal=false and do not claim original-value replay.

**FULL 模式 / FULL mode：**

- 仅保留 C2-3 已捕获且属于允许字段的原值，不补造未捕获字段。 / Retain only already captured C2-3 values belonging to allowed fields; never fabricate missing fields.
- 仍需锁屏、身份授权、会话代次和容量检查，并按 §13.4 检查独立的完整导出确认。 / Lock state, identity authorization, session generation, capacity and the separate full-export confirmation in §13.4 remain required.
- FULL 不意味着具备禁止对象、完整 Bundle 或设备运行上下文，不得描述成可重建 Notification 或可在 ROM 回放。 / FULL does not supply prohibited objects, whole Bundles or device runtime context and must not be described as Notification reconstruction or ROM replay.

### 13.3 容量、超额与空选择 / Capacity, overflow and empty selection

| 拟约束范围 / Proposed scope | 拟上限或行为 / Proposed limit or behavior |
|---|---|
| 单次选择的样本 / Selected samples | 最多 64 条 / At most 64 |
| JSON 文档 / JSON document | UTF-8 字节 ≤ 2 MiB（2,097,152） / At most 2 MiB of UTF-8 bytes |
| Markdown 文档 / Markdown document | UTF-8 字节 ≤ 512 KiB（524,288） / At most 512 KiB of UTF-8 bytes |
| 超出容量 / Overflow | 整次拒绝、固定且内容无关的原因；不截断、不部分导出、不静默丢弃样本 / Reject the entire export with a fixed content-free reason; no truncation, partial export or silent sample removal |
| 空选择 / Empty selection | 返回合法空文档，`meta.complete=true`、`samples=[]`，不视为错误 / Return a valid empty document with meta.complete=true and samples=[]; not an error |

**拟统计口径：**JSON 和 Markdown 按各自最终文档字符串的 UTF-8 字节分别计量，不相互抵扣，不将两者的镜像内容相加为共享的 2 MiB 额度。JSON 计量包含其结构、转义、导出元数据和 redactions；Markdown 按完整摘要计量。空选择仍须通过适用的模式、锁屏和授权检查，不能据此绕过门禁。 / **Proposed accounting:** measure each final JSON/Markdown document string independently in UTF-8 bytes. Neither borrows from the other, and mirrored content is not combined into a shared 2 MiB allowance. JSON accounting includes structure, escaping, export metadata and redactions; Markdown accounting covers the complete summary. An empty selection still requires applicable mode, lock and authorization checks.

上述容量仅为 C2-4 导出文档的拟裁决，不改变 §8.1 的 C2-3 捕获配额，也不冻结 §8.2 的文件总量、深度/节点、timeout 或其他 draft/open 项。 / These proposed limits apply only to C2-4 export documents. They do not change C2-3 capture quotas in §8.1 or freeze file totals, depth/node counts, timeouts or other draft/open items in §8.2.

### 13.4 二次确认、锁屏与代次 / Confirmation, lock state and generation

- FULL 必须由调用方提供独立的完整导出授权结论 `CONFIRMED`；REDACTED 不需要该完整导出二次确认，但仍需独立导出授权。 / FULL requires the caller's separate full-export authorization conclusion CONFIRMED. REDACTED does not require that full-content second confirmation but still requires independent export authorization.
- “曾经开启捕获”不能替代本次确认；确认范围须冻结为本次选择的样本集合和会话代次，不得静默加入确认后到达的新内容。 / Prior capture activation cannot replace confirmation for this export. Freeze confirmation to the selected samples and session generation; do not silently include later arrivals.
- `LOCKED / UNKNOWN` 时不得 FULL 导出。**拟采用：FULL 直接拒绝；REDACTED 可导出，但只含锁屏占位与已脱敏内容**，仍须导出授权；这不是已批准的锁屏历史读取或删除策略。 / FULL is forbidden for LOCKED/UNKNOWN. **Proposed behavior: reject FULL directly; allow authorized REDACTED output containing only locked placeholders and redacted content.** This does not approve a locked-history access or deletion policy.
- 确认后发生重锁、撤权、profile 变化或代次失效时，停止生成后续内容并标记导出未完成；已经生成的部分字符串不得声称已删除外部副本，也不能标为完整导出。 / Relock, revoked authorization, profile change or stale generation after confirmation stops further generation and marks the export incomplete. Do not claim deletion of external copies of already generated partial strings or mark such output complete.
- 不实现历史内容删除、解锁恢复或中途重锁历史策略；这些继续为 draft/open。不得自动落盘、上传、写正文日志或调用网络。 / Do not implement history deletion, unlock resumption or broader relock-history policy; those remain draft/open. No automatic persistence, upload, body logging or network calls.

本节四部分及 §3.2 的澄清注记均待作者确认；本轮仅同步文档，不写 Java、不运行测试或构建、不开始 C2-4 实现。 / All four parts of this section and the §3.2 clarification note remain pending author confirmation. This update changes documentation only, with no Java changes, tests, builds or C2-4 implementation.

## 14. 当前交付状态（D1，2026-11-18） / Current delivery status

§13 末段保留当次文档草案更新时的状态。此后 C2-3 纯内存捕获、C2-4 分步纯导出及 C2-5 整合测试分别交付，过程保存在作者的阶段报告中。Android 接入的现行边界见本文开头。
Section 13 retains its historical drafting status. The C2-3 memory capture, C2-4 stepped export and C2-5 integration tests were delivered in later stages. See the opening section for the current Android integration.

D1 补选条前原始数据适配、协议操作语义检查及无正文诊断，捕获记录的 11 个字段和导出格式沿用原定义。D2 捕获适配当时仍在设计中，`comparison` 当时统一为 `not-observed`，阶段记录为 216 项纯 JVM 测试。
D1 leaves the eleven capture fields and export format unchanged. D2 capture adaptation is only a proposal in the D1 report. Comparison remains not-observed, and the JVM checks do not establish Android or ROM acceptance.

## 15. 类型签名与预览分页 / Type signatures and preview pages

从捕获创建规则前，编辑器在原有活动会话中读取预览分页。每页携带同一份完整登记路径签名、稳定协议标识和登记表摘要，仍受 8 KiB 页上限约束。签名在截减预览值之前计算。发现跨页签名不一致、旧页缺少签名或登记表不匹配时终止编辑。保存许可继续绑定当前会话和规则摘要，SystemUI 以当前捕获签名复算摘要。规则格式见 [全文规则合同](island-rules-spec.md#41-从捕获创建的通知类型规则--notification-types-in-capture-created-rules)。

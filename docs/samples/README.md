# 规则样例 / Rule examples

B 阶段样例沿用至 C1，**不代表当前 App 已支持导入**。ImKani · FocusRestore · GPL-3.0-only · https://github.com/ImKani/FocusRestore
C1 covers immutable models and strict JVM decoding only; import UI and runtime behavior are not implemented.

106 条规范路径重新分为 structural=11、freeText=95、unsafe=0，见[逐项分级与结构字段理由](../island-rule-paths.md)。捕获导出默认保留 structural、强制占位 freeText；运行期两类均不额外脱敏。正例的两条 title 路径均为 freeText；运行期声明期望仍为 `D8396·检票口`，与已有内置解析期望一致。C1 只解码模型，不执行新规则引擎；原始 fixture、rawParam、期望和摘要不改写。
Capture/export preserves structural values by default and placeholders freeText; runtime redacts neither. Both sample title paths are freeText and their declared runtime expectation remains `D8396·检票口`. C1 does not execute the rule engine.

## 正例与证据 / Positive example and evidence

- [rule-travel.valid.json](rule-travel.valid.json): 唯一合法规则 / valid v1 draft rule.
- [notification-travel.real.json](notification-travel.real.json): 已有真实 fixture 的原始 `miui.focus.param.custom`、解码副本与期望；不是完整 SBN，也不是未来导出格式的实机产物 / verbatim real raw param with decoded copy and expectations, not a complete SBN or generated exporter output.
- 真实来源 / source: [IslandPayloadParserTest.java:19–34](../../app/src/test/java/com/hyperos3/focusrestore/IslandPayloadParserTest.java#L19); 原内置期望 / builtin expectation: [142–151](../../app/src/test/java/com/hyperos3/focusrestore/IslandPayloadParserTest.java#L142).
- 包名据 fixture 注释；未获得的时间、tag、ROM、通道等不补造 / missing facts are not invented. The source payload contains train/gate labels, not a full personal itinerary dump.

## 反例 / Negative rules

| 文件 / File | primary code | 原因 / Reason |
|---|---|---|
| [invalid-01-unknown-field.json](invalid-01-unknown-field.json) | L1_UNKNOWN_FIELD | 未知字段 / unknown member |
| [invalid-02-path.json](invalid-02-path.json) | L1_PATH_ENUM | 路径越界 / path outside enum |
| [invalid-03-slot.json](invalid-03-slot.json) | L1_SLOT_ENUM | icon 非正文槽位 / icon outside text scope |
| [invalid-04-regex.json](invalid-04-regex.json) | L1_OPERATOR_ENUM | regex 首版禁用 / regex disabled |
| [invalid-05-capacity.json](invalid-05-capacity.json) | L1_FILE_SIZE | 超过65,536 UTF-8字节；故意保留大样例 / deliberately oversized |
| [invalid-06-forbidden.json](invalid-06-forbidden.json) | L1_FORBIDDEN_FIELD | Intent/Action/URL注入，结构和能力皆禁用 / forbidden controls |
| [invalid-07-template-path.json](invalid-07-template-path.json) | L1_TEMPLATE_PATH | 模板组与路径不符 / group/path mismatch |

05 实际大小为 **67,324 UTF-8 字节**，超过单文件 65,536 字节；解码器在读取阶段返回 `L1_FILE_SIZE`，不继续字段检查。**05 测的是文件级上限，字段级容量由独立用例覆盖**：`ruleDescription` 2048 UTF-16 单元通过、2049 单元返回 `L1_CAPACITY`，且越界输入整体仍小于 65,536 字节；对象键、数组项、字符串及深度也有独立 `L1_CAPACITY` 断言，见 [IslandRuleCodecTest.java](../../app/src/test/java/com/hyperos3/focusrestore/IslandRuleCodecTest.java)。
Example 05 is 67,324 UTF-8 bytes and tests the file-size limit (`L1_FILE_SIZE`). Independent field/container tests exercise `L1_CAPACITY`, including an under-file-limit description with 2049 UTF-16 units.

均以合法规则为基线仅改变一个主要违规点。强制导入不得使任何反例合法。
Each alters one primary aspect of the valid rule. Force import cannot make any negative example valid.

## 检查范围 / Validation scope

需要分别检查严格JSON、65,536字节预检、Schema约束和手写L3/R规则；Schema本身不能表示文件字节数、重复JSON键、动态ref解析或运行预算。
Check strict JSON, bytes, schema constraints and hand validation separately. `x-*` schema annotations are not enforcement.

完整合同见[规范](../island-rules-spec.md)、[Schema](../rule.schema.json)、[风险](../island-rules-risk.md)、[捕获规范](../capture-export-spec.md)。

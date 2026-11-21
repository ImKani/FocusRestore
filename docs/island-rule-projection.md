# 平台共享 UID 最小规则投影

FocusRestore · ImKani · GPL-3.0-only · [项目来源](https://github.com/ImKani/FocusRestore)

SystemUI 有独立 UID 的设备通过严格身份校验直接读取规则。共享平台 UID 的设备需要用户确认兼容读取。在「高级 → 自定义规则」中，存在可投影的启用规则且当前清单待授权时，会显示读取授权入口。操作后需要重新授权时，App 会就地显示与规则编辑一致的确认页。滚动查看目标应用、通知类型和所选字段路径，点击底部「允许读取」批准当前清单。关闭页面可保留已保存的规则，授权生效前继续使用内置正文。授权后可在「查看和编辑已保存规则 → 查看或撤销读取授权」中核对清单，点击「撤销授权」停止后续读取。

捕获、导入及此前已保存的合格规则均可列入清单。授权存放于 App 私有凭据加密存储的 `no_backup`，按仓库修订、纳入条目的原始字节摘要和实际投影字节绑定。

在「查看和编辑已保存规则」停用或删除所选规则时，App 在同一临界区核对原授权、页面修订和所有选中条目，然后一次提交。剩余投影必须恰好等于原清单移除这些条目后的结果。App 核对其余条目的标识、原始摘要、应用、字段和顺序完全一致后，才将授权绑定到新修订。如果最后一条投影被移除，读取方收到空响应。若授权续写失败，规则更改已经保存，剩余规则需重新授权。新增、更新、重新启用和批量导入均需重新核对清单并确认。

Android UID 1000 内的进程共享同一 UID 安全边界。即使入口要求 `com.android.systemui` 归属，也无法保证数据只可被某一进程取得。读取方可以保留已取得的数据。包名和登记路径可能暴露用户使用的应用和配置。投影不含通知正文、样本值、原始规则、规则标识、作者、时间、原始优先级数值或仓库元数据。完整规则快照与捕获 SAVE 使用各自的独立身份门禁。

## 读取接口

独立 Provider 的 authority 为 `com.hyperos3.focusrestore.islandruleprojection`，支持 `ContentResolver.call(uri, "read_projection", null, null)`。运行时验证 Binder 调用 UID 为 `Process.SYSTEM_UID`，系统包 `android` 和 `com.android.systemui` 均实际属于该 UID，SystemUI 与系统包签名匹配，调用归属为 `com.android.systemui`，当前是同一前台、已解锁的非资料用户。授权未生效、失效、正在处理另一读取或读取故障时返回无 `projection` 字段。申请、撤销和仓库变化会发出投影 URI 通知，端点仍独立检查每次读取。

投影仅使用 `FRPJ` v3。协议头写入 `FRPJ`、版本字节 `3`、行数字节和登记表有序路径摘要（`writeUTF`）。各行先用 Java `DataOutputStream.writeUTF` 写包名，再写一个路径个数字节，随后用 `writeUTF` 按显示顺序写入登记表的完整路径。行按优先级降序、声明顺序升序排列。每个响应上限 128 KiB，最多 50 行，每行选 1–16 个互异且允许运行的登记路径。

每行末尾有类型标记，`0` 表示未分型，`1` 表示紧随类型内容。类型内容依次写入协议、业务和场景标识（`writeUTF`，空串表示缺省），再写入完整类型路径数及各登记索引（均为无符号 16 位）。读取方核对登记表摘要、包名、登记路径、计数和 EOF，并验证所选路径属于该行类型。单组和跨组皆按登记路径重建为 `ruleVersion: 2`。旧实验投影 v1/v2 读取会被拒绝，基于其字节的旧授权需要重新确认。

投影只纳入通过校验、已启用、未经强制批准且完整结构与编辑器规范输出等价的规则。该子集包含分型规则和只有应用匹配的合格未分型规则。额外匹配条件由严格身份路径读取完整规则。规则包迁移保留可移植规则的提取及显示顺序。显式写出的等价默认值仍可进入最小投影，新仓库修订需要重新授权。

Android 接口来源为 AOSP Android 平台公开 API，编译目标 API 35，最低 API 31。FocusRestore 自行实现授权和编码。参考 Android Open Source Project 的 [ContentProvider](https://developer.android.com/reference/android/content/ContentProvider)、[PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager)、[Binder](https://developer.android.com/reference/android/os/Binder) 和 [UserManager](https://developer.android.com/reference/android/os/UserManager) 文档。

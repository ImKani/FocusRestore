# 平台共享 UID 最小规则投影

FocusRestore · ImKani · GPL-3.0-only · [项目来源](https://github.com/ImKani/FocusRestore)

在「关于 → 自定义规则」中，配置了合格规则且需要授权的设备会显示「允许 SystemUI 读取应用范围和所选字段路径」。授权默认关闭。从最近记录保存、在管理页更新或启用规则后，若当前修订需要重新授权，App 在本次操作后直接展示确认页。确认页按顺序列出每条规则对应的应用及所选字段路径，点击「允许读取」批准当前清单。授权后可在「查看和编辑已保存规则 → 查看或撤销读取授权」中核对清单，点击「撤销授权」停止后续读取。

捕获、导入及此前已保存的合格规则均可列入清单。授权存放于 App 私有凭据加密存储的 `no_backup`，按仓库修订、纳入条目的原始字节摘要和实际投影字节绑定。

从「查看和编辑已保存规则」单条删除或停用时，App 在同一临界区核对原授权、页面修订和目标规则。提交后若剩余投影恰好是原清单移除该条后的结果，且其他条目的标识、原始摘要、应用、字段及顺序一致，App 会将授权绑定到新修订。移除未列入投影的规则时也核对相同条件。最后一条投影被移除后，读取方收到空响应。若授权续写失败，规则更改已经保存，剩余规则需重新授权。新增、更新、重新启用和批量导入均需重新核对清单并确认。

Android UID 1000 内的进程共享同一 UID 安全边界。即使入口要求 `com.android.systemui` 归属，也无法保证数据只可被某一进程取得。读取方可以保留已取得的数据。包名和登记路径可能暴露用户使用的应用和配置。投影不含通知正文、样本值、原始规则、规则标识、作者、时间、原始优先级数值或仓库元数据。完整规则快照与捕获 SAVE 使用各自的独立身份门禁。

## 读取接口

独立 Provider 的 authority 为 `com.hyperos3.focusrestore.islandruleprojection`，支持 `ContentResolver.call(uri, "read_projection", null, null)`。运行时验证 Binder 调用 UID 为 `Process.SYSTEM_UID`，系统包 `android` 和 `com.android.systemui` 均实际属于该 UID，SystemUI 与系统包签名匹配，调用归属为 `com.android.systemui`，当前是同一前台、已解锁的非资料用户。授权未生效、失效、正在处理另一读取或读取故障时返回无 `projection` 字段。申请、撤销和仓库变化会发出投影 URI 通知，端点仍独立检查每次读取。

协议头为 `FRPJ`、版本字节和行数。各行先用 Java `DataOutputStream.writeUTF` 写包名，再写一个路径个数字节，随后用 `writeUTF` 按显示顺序写入登记表的完整路径。行按优先级降序、声明顺序升序排列。每个响应上限 128 KiB，最多 50 行，每行选 1–16 个互异且允许运行的登记路径。

只有单组行的快照（包括空快照）沿用 v1 字节。只要一行含多个组，整个快照写 v2，新读取方按路径登记组重建 `mixed` 规则。v1 读取方将 v2 视为未知版本，v1 解码仍拒绝跨组行。两个版本共用包名校验、路径允许名单、计数和 EOF 检查。

投影只纳入通过校验、已启用、未经强制批准且完整结构符合捕获编辑器生成形式的规则。完整快照保留原规则字节，规则包迁移保留可移植规则的提取及显示顺序。迁移后显式写出的等价默认值仍可进入最小投影，新仓库修订需要重新授权。

Android 接口来源为 AOSP Android 平台公开 API，编译目标 API 35，最低 API 31。FocusRestore 自行实现授权和编码。参考 Android Open Source Project 的 [ContentProvider](https://developer.android.com/reference/android/content/ContentProvider)、[PackageManager](https://developer.android.com/reference/android/content/pm/PackageManager)、[Binder](https://developer.android.com/reference/android/os/Binder) 和 [UserManager](https://developer.android.com/reference/android/os/UserManager) 文档。

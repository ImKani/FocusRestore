# FocusRestore 通知测试工具

本工具独立安装，包名 `com.hyperos3.focustestnotifier`，当前版本 1.1.1 / code 7，最低 Android 12（API 31），与主模块一致。测试能力不放在主模块设置页，不随 FocusRestore 模块 APK 发布。

通知载荷、预期说明和RemoteViews使用一致的虚构样本：外卖显示“测试餐厅·示例分店”，出行显示“测试车次”“测试起点站/测试终点站”；配送时间、距离及进度也改为合成值。不携带真实商家、行程或订单标识。保留字段层级、颜色标记和图标引用，原始日志证据留在工作区，不复制进发布包。

“布局与图标样本”提供普通通知、原生焦点、岛短文字、长文字、左右分区、彩色文字及三种图标来源共九类；下方保留原有九类出行/外卖/协议回归样本。默认发送前清除本工具旧测试通知，等待 400ms 再发；关闭该选项可保留多类通知测试切换。同类重复发送会更新；清理仅按已知 tag/id 移除本工具两组样本，不使用 cancelAll。

Android 13 及以上首次发送按需申请通知权限，允许后再次点击发送；提供通知权限设置入口。需要检查模块转换时，开启超级岛内容转换，并把本工具加入主模块“强制转换超级岛应用”。ROM 可能为模板补上原生 Focus 标记，默认原生优先仍然有效。普通通知用于通知栏对照，不预期主动变成焦点。

七类岛样本同时提供正文 `baseInfo` 和 `param_island`。正文与岛摘要是不同结构，不能只验证模块能解析摘要而省略 ROM 的正文条件。来源为用户提供的小米超级岛指南副本（标注 2026-01-29），及小米 OS4 插件 `miui.systemui.plugin` 18.3.2.22.0 / code 183022200 的 `TemplateFactoryV3.chooseModule/checkAreaA` 只读分析：正文 A 区为空时会隐藏通知。原接口版权归小米/原权利人，许可未确认；这里只独立构造字段，不复制反编译实现。完整本地证据位于仓库外 Knowledge/sources/decompiled/notification-tests-20261101。

圆形 smallIcon、三角焦点图标与菱形岛图标用于辨认单个图标的来源；图标排序是候选回退顺序，不会同时显示三个图标。主模块恢复 0.42.0 的完整解析行为后，正文与岛摘要连接符不同可能重复显示同组左右文字；这是既有解析边界，迁移不引入 0.43.1 的共享解析改动。媒体和设备通知请用实际播放/连接场景测试。

Debug 沿用 `HyperOS3FocusRestore` INFO 标签，输出发送版本/code、合成载荷和 escaped 副本。日志来自本工具进程；仅抓 SystemUI 的 capture 不自动包含工具发送日志。notify 返回成功不代表 ROM 已显示，需要结合 SystemUI 实际加载版本、模板处理和设备画面判断。

纯 Java 样本合同和脱敏回归测试位于 src/test，使用工作区已有的 JUnit4/JSON jar，通过 javac 和 JUnitCore 运行；未给工具添加依赖。APK 构建使用 `:testnotifier:clean :testnotifier:assembleDebug :testnotifier:assembleRelease -x lintVitalAnalyzeRelease`，签名与主模块共用现有作者证书。构建、合同测试与 ROM 实机显示分别验收。

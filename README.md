# FocusRestore

用于 HyperOS 3/4 的实验性 LSPosed 模块，尝试恢复 HyperOS 2 的 Focus（焦点通知）状态栏显示路径。

仓库地址：`https://github.com/ImKani/FocusRestore`

## 作者与声明

制作者：ImKani

酷安主页：<https://www.coolapk.com/u/1205658>

GitHub：<https://github.com/ImKani/FocusRestore>

本模块由 AI 辅助反编译分析与编写，代码通过 LSPosed Hook 介入系统界面，存在 ROM 版本差异、系统崩溃、状态栏显示异常、功能失效、数据丢失或其他不可控风险。使用前请自行备份，并自行承担使用风险。模块不保证适用于所有设备、系统版本或第三方通知。

## 许可证

本项目使用 `GNU General Public License v3.0 only`（`GPL-3.0-only`）发布。完整许可证声明见仓库根目录的 `LICENSE` 文件，许可证正文请参阅 GNU 官方文本：<https://www.gnu.org/licenses/gpl-3.0.txt>。

## 实现细节说明

本项目的具体 Hook 方法、字段处理逻辑和内部判断流程不在 README 中公开，以防止他人轻易绕过 GPL 重新实现。如需了解实现细节，请直接查看仓库源码（遵循 GPL-3.0-only）。

## 当前版本

版本：`0.29.4`（versionCode 249，Debug 测试版）

- 修复超级岛图标反色后在浅色背景变成黑团的问题，保留图标透明孔洞；关闭反色时不再附加黑色描边。
- 图标等比居中并保留透明安全边距，减少边缘贴边与非方形图标失真；实际设备效果待测试。

0.29.3：

- 修复部分出行通知已经提供超级岛图标、却仍显示应用图标的问题；实际设备效果待测试。
- 保留原有图标反色和应用图标回退行为，不改变设备通知适配。

0.29.1：

- 适配 HyperOS 4 设备通知（充电 / 静音 / 勿扰）转焦点提示，保留 HyperOS 3 兼容路径；当前已完成静态接口核对，K80u 真机效果待验证。
- 保留系统下发的显示时长和点击动作，充电更新复用同一通知，拔电或到期后收起；动画图标仅支持静态降级。
- Debug 版补充设备通知入口、内容与图标解析、投递、状态栏显示和取消的全链路诊断。

以下为历史版本说明（0.29.0 及更早）。

0.29.0：

- 超级岛图标：契约要求的图片取不到时，改为按名字去应用自己的包里查同名图标（沿用设备通知那条已验证的做法）；同时记录查找结果，便于判断是否命中。
- （沿用 0.27.0）修复竖屏下设备通知焦点提示（充电 / 静音 / 勿扰）只显示分隔竖线、看不到文字的问题。真机复核：三类通知的开关共 15 次全部正常显示文字。
- 自带界面内容的系统焦点提示仍由系统渲染，模块不再重复写入。

以下为历史版本说明（0.28.0 及更早）。

0.28.0：

- 诊断：超级岛图标取不到时，记录载荷要求的图片引用名与通知实际提供的图片键，用于定位"焦点位显示应用图标而不是岛图标"。

0.27.0：

- 修复竖屏下设备通知焦点提示（充电 / 静音 / 勿扰）只显示分隔竖线、看不到文字的问题。

0.26.2：

- 修复设备通知焦点提示内容写入失败的问题（此前把类型对象当作数据对象传入）。

0.26.1：

- 修复设备通知焦点提示内容写入失败的问题（改用反射调用，避开参数类型匹配错误）。

0.26.0：

- 修复设备通知焦点提示取不到文字内容的问题（改用系统询问时已解析好的内容，不再自己重新读一遍）。

0.25.9：

- 诊断：记录设备通知焦点提示的内容交接结果，以及提示文字被清空时的调用栈，用于定位竖屏只剩分隔竖线。

0.25.8：

- 修复设备通知焦点提示在系统没有把提示内容交给界面时显示空白的问题（模块自己补上这次内容交接）。

0.25.7：

- 修复设备通知焦点提示在系统没有把提示内容交给界面时显示空白的问题（模块自己补上这次内容交接）。

0.25.6：

- 诊断：焦点提示显示后 0.3s / 1.3s 各记录一次提示文字视图的现场（文字、对齐、颜色、坐标、可见区），用于定位竖屏只剩分隔竖线。

0.25.5：

- 新增诊断：焦点文字装得下却看不见时，记录文本视图的滚动位置、透明度、文字颜色与坐标。

0.25.4：

- 修复竖屏下设备通知焦点提示只显示分隔竖线、看不到文字的问题（文字装得下时不再启动跑马灯）。

0.25.3：

- 修复设备通知只有个别事件能显示焦点提示的问题：现在每次切换静音 / 勿扰 / 充电都会显示。
- 彻底移除模块自绘横幅兜底：设备通知只走系统焦点通知通路，不再出现横幅。

0.25.2：

- 修复重启系统界面后只显示模块横幅、以及横幅与状态栏焦点提示同时出现的问题。
- 修复连续触发时部分事件不重新显示焦点提示的问题。
- 充电事件现在也会带上焦点提示图标。

0.25.1：

- 把系统焦点通路的判定结果写进日志（`isFocusNotification` / `showOnStatusBar` / `ticker` / `tickerIcon`），行为不变。

0.25.0：

- 首次接入系统焦点通知通路：设备通知改由系统自己显示在状态栏焦点位，不再由模块自绘横幅（通路不可用时才回退横幅）。

0.24.2：

- 设备通知横幅横向改为紧跟状态栏时间右侧显示（0.24.1 为屏幕居中，会压在状态栏中间）。

0.24.1：

- 设备通知横幅回到状态栏那一行显示，不再落到状态栏下方的横幅区。
- 静音 / 勿扰等系统没有下发停留时长的事件改回 5 秒自动收起（0.24.0 为 10 秒）。

0.24.0：

- 设备通知（充电 / 静音 / 勿扰）改由独立的透明焦点横幅呈现，状态栏不再出现横跨顶部的黑色遮罩。
- 横幅内容与系统强提示同源，使用系统下发的文案、文字颜色与图标；点击横幅执行系统下发的点击动作。
- 设备通知横幅与焦点横幅共用同一套显示宿主：锁屏、旋转、点击外侧和到时收起的表现一致。

0.13.47：根据 0.13.46 真机日志，本版移除独立横幅宿主的统一最小高度，让短模板随原生内容收缩，保留原生边距与正常日历布局；正文／非操作控件区域轻点接入系统原通知点击，原生按钮优先处理。焦点显示期间，只有起点命中可见焦点区域的状态栏单击／双击不再触发浏览内容回顶，其他位置仍沿用系统行为。新增有限次数的高度变化和点击派发日志，用于真机复核。

“点击焦点展开原生横幅（实验性）”改为独立创建系统插件中用于通知栏展开的 V3 原生模板 View。浅色模式选择浅色内容，深色模式选择深色内容；底色和圆角使用带深浅色配置的系统焦点通知资源。保留默认关闭及已有开关值。横幅不再添加模块自绘标题栏和关闭按钮；点击外侧、原生收起广播或超时关闭。模板或插件依赖不可用时记录具体原因，不回退到 0.13.45 自绘界面。真实设备上的排版、主题与按钮兼容性仍需测试。

原生内容通过新的 Builder / Adapter / Content 实例生成，不搬移系统正在使用的 View；独立宿主承载与通知栏展开链相同的 Modal 内容，并处理通知更新、移除、锁屏和配置变化。浅色／深色内容按当前系统配置选择，焦点背景和圆角读取系统通知资源。模板元数据只来自当前插件正常处理链，构造时校验真实通知 key、用户、时间与协议，避免把旧模板绑定到新通知。通知栏的完整玻璃合成、过渡动画和所有复杂模板不在本版的已验证范围。

本功能独立于两个旧点击开关，优先消费焦点点击；展开失败会记录原因，不会因此自动打开应用。保持原有超级岛屏蔽，未接回历史模拟触摸或临时开启原生岛的重放实现。本轮仅构建 Debug，保留原签名，不发布 Release。

本版本在 0.13.22 启动热修基础上完善白名单、图标、点击实验功能与设置布局：

- 由用户手动选择 HyperOS 3 或 HyperOS 4，默认 HyperOS 3；变更自动保存，重启 SystemUI 或设备后生效
- 设置在后台按代次同步到 CE 与 Direct Boot 可读的设备加密存储；启动设置页时会用较新代次修复另一份副本
- SystemUI 在 `Application.attach()` 阶段直接使用可用的 base Context 查询设置；首次使用须先进入设置页完成设备加密存储初始化，再手动重启 SystemUI 或设备
- 若启动时 Provider 或配置存储不可用，本次 SystemUI 生命周期不会安装模式 Hook，也不会自动改用默认模式或稍后切换模式
- 不自动检测系统版本，不在 Hook 缺失时自动切换或回退
- HyperOS 3 继续使用原有 Focus Prompt 路径
- HyperOS 4 监听通知管线，并使用状态栏 Primary Chip 位置显示原生 Focus 或转换后的超级岛文本
- HyperOS 4 无候选时保持 ROM 已停用的 Legacy Primary Chip 为隐藏状态，避免默认通话图标、`00:00:00` 计时器和底色泄漏
- HyperOS 4 接入状态栏 `DarkIconDispatcher`，焦点文字使用与状态栏时间相同的实时明暗 tint，而不是仅按深色模式切换
- HyperOS 4 默认恢复时间与焦点内容之间的分隔竖线，可在设置中关闭；竖线与时间使用相同的实时 tint，正文滚动时保持固定
- 默认在焦点通知显示期间隐藏左侧通知图标容器，焦点消失后恢复 ROM 原 visibility；右侧信号、电池等系统图标不受影响
- 焦点显示期间精确拦截 ROM 对左侧通知图标容器的 visibility 写入，持续保持隐藏并记录 SystemUI 最新期望值；焦点结束后恢复最新 visibility，不覆盖 ROM 的 alpha 动画
- 带 `miui.focus.param` 且没有原生 Bar RemoteViews 的 PARAMS 通知进入超级岛 JSON 文本解析，不再被系统生成的类别 ticker（例如 `Weather`）误判为原生 Focus
- 原生 Focus 优先于手动白名单、短信验证码和普通超级岛转换
- 超级岛内容转焦点通知（可选开关），强制转换白名单上限由 256 提升至 4096，并按包名确定性保存
- 焦点通知宽度限制与滚动方向控制
- 设置页重新按系统版本、转换、显示、图标、点击、滚动与兼容功能分组
- Release 保留旧版通知点击实验开关；0.13.26 移除失败率较高的超级岛展开触摸实验，新增可选的通知列表 Row 点击兜底，二者可组合使用
- 0.13.26 移除无法稳定工作的超级岛触摸展开实现；历史代码与坐标测试归档到 `legacy/experimental-island-expand`
- 0.13.27 修复通知列表点击兜底因 Entry 全局缓存安装过晚而找不到通知的问题；OS4 直接把当前 NotificationEntry 传递到 Row 点击路径
- 超级岛图标显示默认关闭，图标反色默认开启且可独立配置；岛图标添加仅沿非透明边缘的黑色轮廓
- 默认使用应用图标兜底且保持原色；可实验性改用通知 smallIcon 兜底，smallIcon 不描边但可跟随状态栏反色；0.13.26 修复样式器把不可变 Bitmap 交给 Canvas 导致 OS4 图标被移除的问题
- OS3/OS4 文本焦点图标统一缩至 13dp，与 14sp 文字协调
- 修复 0.13.21 在 LSPosed 入口对象构造阶段依赖主 Looper，导致 OS3/OS4 全部 Hook 未安装的问题；主线程 Handler 只在 SystemUI `Application.attach()` 后创建
- 两条超级岛屏蔽路径在两种模式下均保持启用
- 转换超级岛时可选复用 `miui.focus.pics` 中由小岛/大岛 JSON 引用的图标；无岛图标时默认使用应用图标，实验开关开启后优先使用通知 smallIcon；OS3 写回并在 Bean 复用时恢复 ROM 原图标
- OS3 关闭宽度限制时恢复各 View 的最新 ROM 原值；未挂载文本使用有界 attach 等待启动跑马灯
- OS3 已知 RemoteViews 绑定异常按通知降级为文本或丢弃坏候选，未知异常保持原样并完整记录
- OS4 合并过期渲染任务，以 Pipeline、状态栏 Host、DarkReceiver 和候选代次隔离旧回调
- 通知 payload、解析输出、分隔符和白名单集合具有统一上限，避免异常输入拖垮 SystemUI
- 测试工具已归档

模块标识：

```text
应用名：FocusRestore
Application ID：com.hyperos3.focusrestore
Hook 入口：com.hyperos3.focusrestore.HyperOS3FocusRestoreHook
日志 Tag：HyperOS3FocusRestore
作用域：com.android.systemui
```

## 功能概述

- 通过 LSPosed 模块恢复 HyperOS 2 的焦点通知显示路径，使部分通知可以显示在状态栏焦点区域。
- HyperOS 3 模式保留原有 `FocusedNotifPromptView` Hook；HyperOS 4 模式通过通知集合事件维护显示状态，并复用系统 `ongoing_activity_chip_primary` 位置。两种模式只安装用户选择的对应 Hook。
- 提供“转换超级岛内容为焦点通知”开关，开启后会尝试从带有超级岛协议的通知中提取文本。超级岛图标显示默认关闭；开启实验开关后只复用 JSON 明确引用的 `miui.focus.pic_` 图标，不使用封面或背景图。对于没有超级岛参数的普通通知不会生成额外内容，HyperOS 4 转换不会强制修改 `mIsFocusNotification`。
- 提供焦点通知宽度限制开关（默认开启，上限 160dp）。HyperOS 4 会先测量完整内容，再将显示 Host 截断到该上限并在超宽时滚动。
- HyperOS 4 焦点内容跟随状态栏时钟实时反色，适配浅色/深色应用界面和状态栏外观变化。
- 提供两个 HyperOS 4 专用开关：“焦点通知隐藏其他图标”和“显示焦点通知分隔竖线”，均默认开启；选择 HyperOS 3 时保留其设置值但在界面中浅色禁用。
- 隐藏图标只影响左侧通知图标容器；锁屏/解锁时若 ROM 请求恢复可见，模块会记录该最新请求并继续隐藏，焦点消失后恢复 SystemUI 最新期望的 visibility。分隔竖线固定在内容左侧并跟随状态栏时间反色。
- 提供滚动方向开关：开启“往返滚动”时内容左右往返移动，关闭时单向滚动循环。可配合“兼容重试模式”使用，解决某些 ROM 滚动停止的问题。
- 默认关闭焦点通知点击。高级页可独立开启“点击焦点通知展开横幅（实验性）”，优先显示模块横幅（普通通知用“通知横幅”，媒体焦点用“媒体横幅”）；不需要开启“旧版打开通知内容”。关闭横幅时，原有“旧版打开通知内容”和通知 Row 点击兜底继续保持原行为。三种点击路径互斥，开启横幅会关闭两个旧版直接打开选项。
- 媒体 Focus 以 SystemUI 通知栏媒体 Entry 的新增、更新和移除时机为准：媒体通知真正进入通知栏集合后才成为候选，暂停但仍留在通知栏时不会提前隐藏；媒体通知被从通知栏划掉时，媒体 Focus 跟随系统媒体头部移除状态一并隐藏，恢复播放后重新显示。点击媒体焦点时始终有响应——默认展开媒体横幅，优先复用原媒体通知的系统控件，通知未携带 RemoteViews（例如小米音乐）时改由系统媒体会话 MediaData 构建横幅，并按通知栏媒体通知布局展示封面、歌曲、歌手与播放控制，控件运行系统自身的媒体操作；不会移除、重排或改写原媒体通知。媒体 Focus 优先级低于其他 Focus 通知，普通 Focus 显示期间媒体 Focus 自动让位。
- 模块始终尝试关闭 HyperOS 超级岛显示路径，避免其占用状态栏区域。
- 模块按掉设备通知的系统强提示窗口（灵动舞台）本身，并将其状态栏文案与图标改由透明横幅呈现，避免顶部的黑色遮罩。
- 模块仅作用于 `com.android.systemui`，不要求 KernelSU 模块。

## 设置项说明

模块设置页从 LSPosed 模块详情进入，不显示桌面图标。设置变更会立即保存并显示 Toast 提示，之后需手动重启 SystemUI 或设备才能完整生效。

主要设置项：

- **系统界面版本**：手动选择 HyperOS 3 或 HyperOS 4，默认 HyperOS 3。模块不会自动检测或回退；选错版本时只会记录缺失能力或 Hook 失败日志。
- **超级岛内容转焦点通知**：默认关闭。开启后尝试从超级岛协议中提取文本内容并显示为焦点通知；关闭时不做转换。
- **焦点通知宽度限制**：默认开启，上限 160dp；关闭后使用 ROM 原生宽度。HyperOS 4 日志会记录 `contentWidth`、`hostWidth` 和 `maxWidthPx`。
- **焦点通知隐藏其他图标（HyperOS 4）**：默认开启。只在焦点内容可见期间隐藏 `notificationIcons`；锁屏/解锁时会拦截并记录 ROM 最新的 visibility 请求，焦点消失后恢复该最新值，不修改 ROM 的 alpha。选择 HyperOS 3 时该项浅色禁用，但保存值不变。
- **显示焦点通知分隔竖线（HyperOS 4）**：默认开启。在时间与焦点内容之间显示固定竖线，颜色跟随状态栏时间实时反色。选择 HyperOS 3 时该项浅色禁用，但保存值不变。
- **往返滚动**：默认开启。开启后内容左右往返滚动，关闭则单向循环。
- **兼容重试模式**：默认关闭。开启后滚动任务最多启动两次，适用于某些 ROM 布局刷新后重置跑马灯的情况。
- **点击焦点通知展开横幅（实验性）**：默认关闭。统一开关，开启后点击焦点通知展开“通知横幅”：普通通知使用通知横幅，媒体焦点使用媒体横幅（封面、歌名、控制、进度）。其下三个子项（通知横幅背景、点击媒体焦点通知、无缝流转入口）与开关左对齐，未开启时统一置灰不可用。
- **通知横幅背景**（子项）：默认「统一使用纯色背景」，可选「使用普通通知背景」。横幅开关未开启时本项置灰不可用。
- **点击媒体焦点通知**（子项）：二选一，默认「展开媒体横幅」。需同时开启「点击焦点通知展开横幅」和「启用媒体焦点通知」才可用。媒体焦点点击始终有响应——默认展开媒体横幅；选「直接展开流转界面」则直接打开流转界面，不再回退到普通通知原生模板（媒体通知无原生 V3 焦点参数，此前会表现为点击无反应）。
- **旧版：打开通知内容（实验性）**：默认关闭；关闭横幅时保留旧版跳转及可选通知列表点击兜底。
- **点击媒体焦点通知**：二选一，默认「展开媒体横幅」。「展开媒体横幅」为原有媒体焦点横幅行为；「直接展开流转界面」改为点击媒体焦点直接打开流转界面，不再弹出横幅。两者互斥。媒体焦点的点击**始终生效**，不再受“点击焦点通知展开横幅”总开关或旧点击开关影响（其他类型的焦点通知仍遵循原有点击设置）。
- **媒体横幅无缝流转入口**（子项）：二选一，默认「小米妙播」。需同时开启「点击焦点通知展开横幅」和「启用媒体焦点通知」才可用。**安卓原生**：流转界面使用系统的媒体输出选择器（`MediaOutputDialogManager`）。**小米妙播**（实验性）：流转界面使用系统插件自带的妙播设备面板，模块只提供承载窗口、遮罩、背景层与关闭方式，插件或面板不可用时自动回退安卓原生。媒体横幅右上角的无缝流转图标始终保留并始终可点击，行为由本项决定。以上选择随下一次重启 SystemUI 或设备完整生效。

## 系统灵动舞台

设备通知（充电 / 静音 / 勿扰）在 HyperOS 3 上由系统的强提示窗口（灵动舞台，`MIUIStrongToast`）呈现，窗口内有一块横跨状态栏的黑色遮罩。模块直接按掉该窗口的显示，并把同一次事件下发的状态栏文案、文字颜色与图标改由透明的设备通知横幅呈现，因此状态栏不会再有黑色遮罩，图标与文字仍然可见。横幅与状态栏同一行显示，横向紧跟在状态栏时间右侧（和 ROM 原来的位置一致），点击横幅执行系统下发的点击动作。

强提示的拦截与超级岛屏蔽属于两条不同的系统路径：前者只作用于 `MIUIStrongToast`，后者始终保留 `feature.island.debug=false` 与 `DynamicFeatureConfig.FEATURE_DYNAMIC_ISLAND=false`。设备通知横幅与焦点横幅共用同一个显示宿主，锁屏、旋转、点击外侧和到时收起的行为一致。

## 测试工具

测试发送器已经归档到 `legacy/testsender`，不参与主模块构建和 LSPosed 作用域。它独立安装后提供焦点通知、超级岛模板和清理测试通知，便于点击后立即返回桌面观察 SystemUI 显示。

设置修改后会立即保存，并由用户手动重启 SystemUI 或设备。

## 设置页风险提示

设置页会明确提示以下内容：

- “调试：允许焦点通知点击”仅用于调试且功能不可靠；HyperOS 3 上基本所有焦点通知都不支持点击，HyperOS 4 模式关闭点击时会消费状态栏 Focus 区域的触摸事件。
- 开启调试点击后，HyperOS 4 优先使用原生 RemoteViews 点击事件，转换文本使用通知 `contentIntent`；这些事件可能无效，并可能导致焦点通知消失、不可见、误触发或系统处理异常。
- 点击后的系统通知逻辑可能无法正常处理。
- 模块通过 LSPosed Hook 介入 SystemUI，存在 ROM 版本差异、系统崩溃、显示异常、功能失效和数据丢失风险。
- 超级岛转换只处理通知实际提供的协议内容；灵动舞台由模块按掉窗口后改用透明设备通知横幅呈现，不是通过超级岛通路隐藏。
- 修改设置后会立即保存；收到 Toast 提示后，重启 SystemUI 或设备才能完整生效。

## 日志判读

测试时先确认日志中的 `configuredMode=OS3/OS4` 和 `installedMode=OS3/OS4` 与手动选择一致。HyperOS 3 模式会记录 `showOnStatusBar`、`before setData`、`after setData`、`updateRemoteViews begin` 等日志；HyperOS 4 模式会记录 `notifPipelineListener`、`statusBarPrimarySlot`、`OS4 candidate` 和 `OS4 focus shown`。所有 Hook 独立安装和记录错误，某一项缺失不会触发自动模式回退。

如果只有动态岛日志而没有模块日志，可能模块未生效或通知未满足焦点条件。如果模块日志中出现 `updateRemoteViews` 报错，说明 RemoteViews 与当前 SystemUI 不兼容。

模块不再提供整体功能旁路开关；需要停用模块时，应在 LSPosed 中关闭作用域或禁用模块。

## KernelSU 关系

KernelSU 不是本模块的必需依赖。若使用 KernelSU 修改动态岛属性，应确保它不会重新开启原生超级岛；不一致时以更早生效的系统属性和 SystemUI 初始化结果为准。

## 构建

建议环境：

```text
JDK 17
Android SDK Platform 35
Android Gradle Plugin 8.7.3
```

构建 debug 或 release 变体，APK 输出路径：

```text
app/build/outputs/apk/debug/FocusRestore-0.29.0-debug.apk
app/build/outputs/apk/release/FocusRestore-0.29.0-release.apk
```

模块不声明网络、存储或后台服务权限。为显示白名单应用列表，Manifest 声明包可见性相关的 `QUERY_ALL_PACKAGES` 和小米系统权限 `com.android.permission.GET_INSTALLED_APPS`；关于项目按钮通过系统浏览器打开外部链接，网络访问由浏览器处理。配置 XML 保持私有，但导出的只读 Provider 必须允许不同签名的 SystemUI 查询，因此其他应用也可能读取模式、白名单等配置；Provider 不提供写接口。

## 安装和作用域

1. 安装 `FocusRestore-0.29.0-release.apk` 或 `FocusRestore-0.29.0-debug.apk`。
2. 在 LSPosed 中启用本模块。
3. 作用域应只有：

```text
系统界面
com.android.systemui
```

4. 从 LSPosed 模块详情进入设置页，选择 HyperOS 3 或 HyperOS 4；变更会自动保存，默认 HyperOS 3。
5. 第一轮测试关闭 KernelSU 的动态岛属性模块。
6. 重启设备，确保 SystemUI 的静态功能字段和手动选择的 Hook 在启动阶段初始化。**每次覆盖安装新版本后都必须重启系统界面或设备**：模块代码在 SystemUI 进程启动时加载，只装 APK 不重启，正在运行的系统界面仍然执行旧代码。
7. 触发以前会显示超级岛或焦点通知的通知。

这是现有 Application ID 的显示品牌更新，旧版可通过相同包名、签名和更高版本号覆盖升级。Debug 与 Release 共用当前固定测试证书，以支持覆盖安装并保留 CE/DP 配置；Release 仅表示构建变体，不代表应用商店生产签名。测试时请禁用旧模块，避免两个模块同时 Hook SystemUI。

## 抓取日志

测试时不要让模块主动重启 SystemUI。先清空日志，再由用户手动重启 SystemUI，等待状态栏恢复后触发测试歌词。

### MT 管理器 Root 终端

在 MT 管理器终端中先执行：

```sh
/system/bin/logcat -c
```

然后执行下面这一行开始抓取。只读取默认 buffer，不使用 `-b all`，避免日志快速增长到几十 MB：

```sh
/system/bin/logcat -v threadtime HyperOS3FocusRestore:I FocusedNotifPromptView:I PromptViewAnimState:D AndroidRuntime:E '*:S' > /sdcard/hyperos3-focus-restore.log
```

如果已经进入 Root shell（提示符为 `#`），不要再次输入 `su -c`。开始抓取后不会返回命令提示符，这是正常现象。测试完成后按 `Ctrl+C` 停止。

日志文件位置：

```text
/sdcard/hyperos3-focus-restore.log
```

如果 MT 管理器对标签过滤命令处理异常，可以抓取默认 buffer 的完整日志：

```sh
/system/bin/logcat -v threadtime > /sdcard/hyperos3-focus-restore.log
```

### ADB 电脑抓取

```sh
adb logcat -c
```

用户手动重启 SystemUI 后，执行：

```sh
adb logcat -v threadtime HyperOS3FocusRestore:I FocusedNotifPromptView:I PromptViewAnimState:D AndroidRuntime:E '*:S' > hyperos3-focus-restore.log
```

不要使用 `adb shell pkill -f com.android.systemui`，除非用户明确要求由电脑重启 SystemUI。

### SystemUI 崩溃日志

如需单独检查崩溃，在测试完成后执行：

```sh
/system/bin/logcat -b crash -d > /sdcard/hyperos3-focus-restore-crash.log
```

## 重点日志

```text
HyperOS3FocusRestore: Dynamic Island property override: feature.island.debug=false
HyperOS3FocusRestore: capabilities configuredMode=OS3 installedMode=OS3 ...
HyperOS3FocusRestore: showOnStatusBar=...
HyperOS3FocusRestore: before setData ...
HyperOS3FocusRestore: scheduled native focus marquee delayMs=...
HyperOS3FocusRestore: capabilities configuredMode=OS4 installedMode=OS4 ...
HyperOS3FocusRestore: OS4 notifPipelineListener=registered
HyperOS3FocusRestore: OS4 statusBarPrimarySlot=attached ...
HyperOS3FocusRestore: OS4 focus shown ...
HyperOS3FocusRestore: status bar anchor captured: ...
HyperOS3FocusRestore: strong toast suppressed: showCustomStrongToast category=... duration=...
HyperOS3FocusRestore: device banner request text=... color=... icon=... duration=... target=...
```

设备通知横幅另有宿主的通用日志：`show accepted: ... source=device-notification ...` 表示窗口已装好，`show rejected: ...` / `dismiss reason=...` 会给出被拒绝或收起的原因；`device banner skipped: ...` 表示本次没有横幅（缺少锚点、上下文未就绪或模型没有文案）。

如果只有 `showOnStatusBar` 而没有 `setData`，说明判断已放行但通知没有进入焦点通知 View。如果只有 `DynamicIslandService`，说明它只进入了动态岛路径。若 `updateRemoteViews` 报错，说明 RemoteViews 与当前 SystemUI 的布局、资源或类不兼容。

# FocusRestore

用于 HyperOS 3/4 的实验性 LSPosed 模块，尝试恢复 HyperOS 2 的 Focus（焦点通知）状态栏显示路径。

作者及维护者：**ImKani**；AI 协助分析与开发。

[GitHub 项目](https://github.com/ImKani/FocusRestore) · [酷安主页](https://www.coolapk.com/u/1205658)

模块通过 Hook 介入系统界面，可能因 ROM 差异出现功能失效、状态栏异常或系统界面崩溃。使用前请备份；不保证适用于所有设备、系统版本或第三方通知。

## 功能概述

- 显示和管理焦点通知，支持将符合条件的超级岛内容转换为 Focus，并提供应用白名单；原生 Focus 默认优先，通话、录屏、投屏等重要提示保留系统处理。
- 支持普通、设备及实验性媒体焦点，提供彩色文字、计时内容、滚动显示和最大显示时长；到期只收起焦点，不取消系统通知。
- 自定义横竖屏宽度、图标来源与排序、反色、尺寸、间距、分隔线及按类型隐藏时间；可独立调整触控范围。
- 提供点击展开横幅、长按打开通知、左右滑动切换与收起；媒体支持简短显示、字段排序及妙播／安卓输出入口。
- 设置页支持搜索定位、配置导入导出，并展示系统信息及实际加载状态。模块设置从 LSPosed 详情进入，不提供桌面图标。

媒体、手势和高级材质等实验功能的效果依赖具体 ROM。通知小图标依赖应用适配，反色出现白块时可改为应用图标或焦点专用图标优先。

## 近期变化

当前版本：**0.45.0**（versionCode **329**）。

- 新增“高级材质接入（实验性）”，默认关闭；旧配置也保持关闭。开启点击展开并选择普通通知背景后可启用，媒体封面不受影响。
- 修补 OS4 超级岛屏蔽入口安装、启动配置读取与焦点横幅展开，保留原生重要提示共存。
- 调整媒体横屏横幅宽度，重整首页概览与高级设置入口，保留搜索和配置兼容。

## 安装与使用

需要 Android 12（API 31）及以上、HyperOS 3/4 和可用的 LSPosed 环境；不要求 KernelSU。

1. 安装 [Releases](https://github.com/ImKani/FocusRestore/releases) 中的 APK，在 LSPosed 中启用模块。
2. 仅勾选系统界面 `com.android.systemui` 作用域。
3. 从 LSPosed 模块详情进入设置，确认 HyperOS 3 或 HyperOS 4 模式，按需调整高级选项。
4. 手动重启系统界面或设备，使新的 Hook 和配置完整生效。

覆盖安装不会让正在运行的 SystemUI 自动加载新 Hook；请结合设置页加载状态及日志确认。模式切换和需要重载的选项仍须手动重启。

设置自动保存；“关于”页可通过系统文件选择器导入或导出配置。导入会覆盖当前设置，缺少的字段使用兼容默认值，请先备份。测试通知工具见 [testnotifier](testnotifier/README.md)，不随主模块发布。

Debug 和 Release 使用相同作者签名，便于覆盖升级。**从 0.34.2 之前的旧测试签名版本升级时**，需先导出配置，再卸载旧包、安装新版并导入；卸载会清除未导出的设置。

出现系统界面异常时优先在 LSPosed 中禁用模块并重启系统界面。卸载或回退前也建议先禁用模块并重启。

## 日志抓取

Debug 和 Release 均保留模块加载版本、横幅点击与失败原因等运行日志；`DIAG` 详细诊断仅 Debug 输出。覆盖安装后需重启系统界面或整机才能加载新的 Hook。

安装或更新模块后，先在 LSPosed 中启用模块并确认作用域为 `com.android.systemui`。以下两种方案任选其一，二者都要求**先开始抓取，再手动重启系统界面**，这样可以同时记录 SystemUI 重启后的模块加载过程。不要在命令中自动执行 `killall` 或 `force-stop`，请使用设备上已有的“重启系统界面”方式手动重启。

### 手机端：MT 终端

在 MT 终端执行：

```sh
su
/system/bin/logcat -c
/system/bin/logcat -v threadtime HyperOS3FocusRestore:I FocusedNotifPromptView:I PromptViewAnimState:D AndroidRuntime:E '*:S' > /sdcard/focus-restore-0.43.0.log
```

保持命令运行，然后通过设备上的方式手动重启系统界面。完成复现后回到 MT 终端，按 `Ctrl+C` 停止抓取。日志保存在：

```text
/sdcard/focus-restore-0.43.0.log
```

### 电脑端：ADB + PowerShell

在电脑上执行：

```powershell
adb devices
adb logcat -c
adb logcat -v threadtime -b main -b system -b crash | Tee-Object -FilePath ".\focus-restore.log"
```

保持命令运行，然后在手机上手动重启系统界面。完成复现后回到 PowerShell，按 `Ctrl+C` 停止抓取。日志保存在执行命令时的电脑当前目录：

```text
.\focus-restore.log
```

### 新旧命令的区别

旧命令：

```sh
/system/bin/logcat -v threadtime HyperOS3FocusRestore:I FocusedNotifPromptView:I PromptViewAnimState:D AndroidRuntime:E '*:S' > /sdcard/focus-restore-0.29.1.log
```

新 README 同时保留了这类手机端过滤方案，并增加电脑端完整日志方案。两者区别如下：

- **手机端旧命令**：在设备本机运行，使用 Tag 白名单，只保存 `HyperOS3FocusRestore`、`FocusedNotifPromptView`、`PromptViewAnimState` 和 `AndroidRuntime`，文件位于 `/sdcard`，体积较小，适合直接发送分析。
- **电脑端新命令**：通过 ADB 读取 `main`、`system`、`crash` 三个缓冲区，不按 Tag 过滤，同时在 PowerShell 显示并保存到电脑的 `focus-restore.log`，信息更完整，但文件可能更大、包含更多隐私内容。
- **`-b main -b system -b crash`**：表示读取指定日志缓冲区；手机端过滤命令未指定缓冲区时使用系统默认缓冲区，通常主要是 `main`，因此可能遗漏 `system` 或 `crash` 中的旁证。
- **`Tee-Object`**：只负责把电脑端收到的日志同时显示和写入文件，不改变日志内容，也不是额外的过滤器。
- **手动重启SystemUI**：两种方案都不再由命令自动重启，避免不同 ROM 的重启命令差异；应在开始抓取后手动重启，以便记录模块重新加载过程。

如果需要在电脑端使用与手机端相同的 Tag 过滤方案：

```powershell
adb logcat -v threadtime -b main -b system -b crash | Select-String 'HyperOS3FocusRestore|FocusedNotifPromptView|PromptViewAnimState|AndroidRuntime'
```

日志中常见的 `DIAG` 行用于确认阶段性状态，例如模块加载、模式选择、设备通知投递、图标解析、焦点显示、取消和失败原因。日志只用于判断运行阶段，不等同于所有 ROM 上的显示成功；最终显示效果仍需结合设备画面确认。

### 日志判读建议

按以下顺序检查：

1. **模块是否加载**：搜索 `HyperOS3FocusRestore`、`loaded`、`hook` 或模式初始化信息。
2. **作用域是否正确**：确认日志来自 `com.android.systemui`，并且选择的 HyperOS 版本与设备一致。
3. **配置是否生效**：检查模式、超级岛转换、媒体焦点、图标和点击行为等设置是否为预期值。
4. **事件是否进入**：设备通知、媒体通知或超级岛通知应出现对应的接收、解析或候选记录。
5. **显示链路是否完成**：重点查看显示、投递、图标解析、横幅创建或取消阶段；出现 `failed`、`fallback`、`missing`、`timeout` 时，连同前后文一起保留。
6. **是否只是 ROM 显示差异**：如果日志显示投递和显示阶段完成，但画面仍异常，应同时记录 ROM 版本、横竖屏、浅色/深色状态、反色设置和复现步骤。

提交问题时建议提供：完整日志文件、模块版本、HyperOS 版本、设备型号、选择的系统界面版本、相关设置截图、复现步骤和实际画面截图。不要只截取单行日志，也不要上传包含个人通知内容、账号信息或设备标识的原始日志。

## 构建

使用 Android SDK 35、AGP 8.7.3、Gradle 9.7.1 和 JDK 21，Java 源级兼容 8。仓库未附带 Gradle Wrapper，需配置本地工具链和签名；作者私钥不随仓库提供，不同签名无法覆盖作者发布包。

```shell
gradle :app:clean :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease
```

产物位于 `app/build/outputs/apk/`，命名为 `FocusRestore-<versionName>-<buildType>.apk`。构建及静态检查通过不代表已完成所有设备和 ROM 的兼容性验证。

## 许可证

FocusRestore 使用 **GNU General Public License v3.0 only（GPL-3.0-only）** 发布。声明见 [LICENSE](LICENSE)，完整正文参阅 [GNU 官方文本](https://www.gnu.org/licenses/gpl-3.0.txt)。

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

## 当前版本

版本：`0.29.4`（versionCode 249）

## 功能概述

- 在 HyperOS 3/4 上显示和管理焦点通知。
- 可将符合条件的超级岛内容转换为焦点通知，并支持应用白名单。
- 支持设备通知、媒体焦点通知、焦点内容宽度限制、滚动显示和分隔线显示。
- 支持按系统界面版本选择 HyperOS 3 或 HyperOS 4；切换后需重启 SystemUI 或设备生效。
- 支持实验性的焦点通知点击行为、媒体横幅和无缝流转入口。
- 支持实验性的超级岛图标显示、状态栏反色和通知 smallIcon 兜底。
- 模块作用域为 `com.android.systemui`，不要求 KernelSU 模块。

## 设置项说明

设置模块从 LSPosed 模块详情进入，不显示桌面图标。设置变更会保存；涉及 SystemUI Hook 的选项需要手动重启 SystemUI 或设备后完整生效。

### 系统界面版本

- **HyperOS 3 / HyperOS 4**：手动选择系统界面版本，默认 HyperOS 3。模块不会自动检测或回退。

### 超级岛转换

- **转换超级岛内容为焦点通知**：默认关闭。开启后尝试将符合条件的超级岛内容转换为焦点通知。
- **焦点内容模式**：支持“展开全文本解析”和“药丸左右拼接”。
- **超级岛白名单**：选择需要强制转换的应用。

### 焦点显示

- **限制焦点通知宽度**：默认开启。
- **最大宽度**、**横屏最大宽度**：分别调整竖屏和横屏焦点内容的最大宽度。
- **启用媒体焦点通知（实验性）**：默认关闭。
- **隐藏其他通知图标（HyperOS 4）**：默认开启，仅影响焦点显示期间的其他通知图标。
- **显示焦点分隔线（HyperOS 4）**：默认开启。
- **最大显示时间（秒）**：`0` 表示不限制，支持 `2～3600` 秒；超过时间只隐藏状态栏焦点，不取消通知。
- **超时豁免名单**：名单内的应用不受最大显示时间限制，按系统自己的时长显示；与超级岛白名单相互独立。

### 实验性图标

- **显示超级岛图标（实验性）**：默认关闭。
- **图标跟随状态栏反色（实验性）**：默认开启；开启时根据状态栏外观着色，保留透明度和透明孔洞。
- **优先使用通知 smallIcon 兜底（实验性）**：默认关闭。

实验性图标路径依赖 ROM 通知资源，可能显示异常或影响焦点图标。

### 点击行为

- **点击焦点通知展开横幅（实验性）**：默认关闭。普通焦点通知使用通知横幅，媒体焦点使用媒体横幅；点击外侧可收起。
- **通知横幅背景**：可选“统一使用纯色背景”或“使用普通通知背景”。
- **点击媒体焦点通知**：可选“展开媒体横幅”或“直接展开流转界面”。
- **无缝流转入口**：可选“小米妙播”或“安卓原生”；小米妙播不可用时自动回退安卓原生。
- **旧版：打开通知内容（实验性）**：保留旧版打开通知内容行为。
- **直接打开失败时模拟通知列表点击（实验性）**：作为旧版打开通知内容失败时的兜底行为。

“点击媒体焦点通知”和“无缝流转入口”需要同时启用媒体焦点通知；点击行为相关子项在“点击焦点通知展开横幅”关闭时不可用。

### 滚动与兼容

- **启用往返滚动**：默认开启，使焦点内容在两端之间往返滚动。
- **兼容重试模式**：默认关闭，用于兼容部分 ROM 的滚动刷新问题。
- **滚动启动延迟**：支持 `0～5` 秒。

### 内容连接符

- **普通内容**：设置普通焦点内容的连接符，默认 `·`，允许留空。
- **左右区域**：设置左右区域内容的连接符，默认 `·`，允许留空。

### Debug 测试版内部兼容开关

Debug 版本额外提供以下兼容选项，普通用户通常不需要修改：

- **覆盖 `feature.island.debug`**
- **禁用 `FEATURE_DYNAMIC_ISLAND`**

## 模块作用域与标识

- **Application ID**：`com.hyperos3.focusrestore`
- **LSPosed 作用域**：`com.android.systemui`
- **Xposed 入口类**：`com.hyperos3.focusrestore.HyperOS3FocusRestoreHook`
- **日志 Tag**：`HyperOS3FocusRestore`
- **设置页**：从 LSPosed 模块详情进入，不提供桌面启动图标
- **最低 Android 版本**：Android 8.1（API 27）
- **目标 Android 版本**：API 35

模块只注入 `com.android.systemui`，不需要 KernelSU。设置页和配置 Provider 属于模块自身应用组件，不代表额外的 SystemUI 作用域。

## 日志抓取

### 推荐：清空后复现并保存完整日志

连接设备并确认 ADB 可用后，在电脑执行：

```powershell
adb devices
adb logcat -c
adb shell am force-stop com.android.systemui
adb shell su -c 'killall com.android.systemui' 2>$null
adb logcat -v threadtime -b main -b system -b crash | Tee-Object -FilePath "focus-restore.log"
```

保持日志命令运行，然后在手机上复现问题。完成后按 `Ctrl+C` 停止抓取。

如果设备不允许通过 `su` 重启 SystemUI，只执行：

```powershell
adb shell am force-stop com.android.systemui
adb logcat -v threadtime -b main -b system -b crash | Tee-Object -FilePath "focus-restore.log"
```

### 已有日志中过滤模块相关内容

```powershell
Select-String -Path .\focus-restore.log -Pattern 'HyperOS3FocusRestore|FocusRestore|LSPosed|Xposed|DIAG'
```

也可以直接实时过滤：

```powershell
adb logcat -v threadtime -b main -b system -b crash | Select-String 'HyperOS3FocusRestore|FocusRestore|LSPosed|Xposed|DIAG'
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

项目使用本地 Gradle、Android SDK 和 JDK 环境，不依赖运行时第三方库；Xposed API 仅作为编译期依赖。

在工作区根目录执行 clean Debug 构建：

```powershell
$env:JAVA_HOME=(Resolve-Path '.\EnvTools\jdk-25.0.4+7').Path
$env:GRADLE_USER_HOME=(Resolve-Path '.').Path+'\.gradle-local'
$env:TEMP=$env:GRADLE_USER_HOME+'\tmp'
$env:TMP=$env:TEMP
& '.\EnvTools\gradle-9.7.1\bin\gradle.bat' -p '.\FocusRestoreLSPosed' :app:clean :app:assembleDebug --no-daemon --max-workers=1
```

clean Release 构建：

```powershell
& '.\EnvTools\gradle-9.7.1\bin\gradle.bat' -p '.\FocusRestoreLSPosed' :app:clean :app:assembleRelease --no-daemon --max-workers=1
```

当前版本产物命名：

- `app/build/outputs/apk/debug/FocusRestore-0.29.4-debug.apk`
- `app/build/outputs/apk/release/FocusRestore-0.29.4-release.apk`

本地 Release 构建使用项目保留的 Debug 证书，适合测试和直接分发，不是应用商店生产签名。固定证书 SHA-256：

```text
ab58b5e208e21aaa9a8628c3ceb661b2bb89cdcfb1d941fbf892ae4936e16809
```

如果当前 JDK 环境导致 Release 的 `lintVitalAnalyzeRelease` 单独失败，可在确认失败原因后跳过该分析任务完成打包；发布前应如实记录该限制：

```powershell
& '.\EnvTools\gradle-9.7.1\bin\gradle.bat' -p '.\FocusRestoreLSPosed' :app:assembleRelease --no-daemon --max-workers=1 -x lintVitalAnalyzeRelease
```

构建后检查版本和签名：

```powershell
& '.\EnvTools\Android\Sdk\build-tools\36.0.0\aapt2.exe' dump badging '.\FocusRestoreLSPosed\app\build\outputs\apk\release\FocusRestore-0.29.4-release.apk'
& '.\EnvTools\Android\Sdk\build-tools\36.0.0\apksigner.bat' verify --print-certs '.\FocusRestoreLSPosed\app\build\outputs\apk\release\FocusRestore-0.29.4-release.apk'
```

## 安装与验证

安装前确认设备已解锁、已启用 ADB，并已备份相关配置。安装不会自动启用 LSPosed 模块，也不会自动将作用域加入 SystemUI。

```powershell
adb install -r '.\FocusRestoreLSPosed\app\build\outputs\apk\debug\FocusRestore-0.29.4-debug.apk'
```

或安装 Release：

```powershell
adb install -r '.\FocusRestoreLSPosed\app\build\outputs\apk\release\FocusRestore-0.29.4-release.apk'
```

安装后：

1. 在 LSPosed 中启用 FocusRestore。
2. 仅勾选 `com.android.systemui` 作用域。
3. 打开模块设置，选择实际的 HyperOS 3 或 HyperOS 4 模式并保存。
4. 按设置提示重启 SystemUI 或设备，使 Hook 和配置完整生效。
5. 使用测试通知或实际通知复现，结合日志和画面验证。

卸载或回退前先关闭 LSPosed 模块并重启 SystemUI；出现 SystemUI 异常时优先禁用模块，不要反复安装不同签名的 APK。

## 版本发布说明

- Debug 和 Release 均使用当前项目保留签名，便于测试包覆盖安装。
- Release 不代表已完成所有设备和 ROM 的真机兼容性验证。
- 发布说明应注明版本号、APK 类型、签名摘要、已知限制和对应的源码 Tag。

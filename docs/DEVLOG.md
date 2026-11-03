# SilentIME 开发日志

> Android 原生智能输入法 · 本地优先 · 隐私安全
>
> 仓库：https://github.com/Thanatophilia/SilentIME
> 许可：GNU GPL v3.0

---

## 环境基线（首次记录：2026-11-03）

| 项目 | 值 |
|---|---|
| 目标设备 | realme GT Neo6（RMX3852） |
| 系统 | Android 16（SDK 36，build UKQ1.231108.001） |
| CPU | arm64-v8a（aarch64），高通 qcom |
| 内存 | 14.94 GB 总 |
| 构建环境 | **proot 容器内 Ubuntu 24.04.5 LTS**（非 Termux 原生） |
| 内核 | Linux 6.1.141-android14-11 |
| JDK | OpenJDK 17.0.20.1（arm64）`/usr/lib/jvm/java-17-openjdk-arm64` |
| Android SDK | `/root/android-sdk`（platforms 35/36，build-tools 34/35/36） |
| NDK | 25.2.9519653 / 28.0.13004108（本项目暂未使用） |
| 构建系统 | Gradle Wrapper **8.11.1**（系统 Gradle 4.4.1 过旧，不可用） |
| 工程路径 | `/root/SilentIME` |

### 关键构建命令

```bash
cd /root/SilentIME
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
export ANDROID_HOME=/root/android-sdk
./gradlew assembleDebug --no-daemon --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 安装到真机

```bash
# /sdcard 受 SELinux 限制，system_server 无法读取，必须中转
cp app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/x.apk
# 再通过 shell 复制到 /data/local/tmp
cp /sdcard/Download/x.apk /data/local/tmp/silentime.apk
pm install -r /data/local/tmp/silentime.apk
```

---

## 版本栈（当前锁定）

| 组件 | 版本 | 说明 |
|---|---|---|
| AGP | 8.9.1 | 被 androidx.core 1.18.0 强制要求（≥8.9.1） |
| Gradle | 8.11.1 | AGP 8.9.1 要求 |
| Kotlin | 2.3.21 | 本地缓存版本 |
| compileSdk | 36 | androidx.core 1.18.0 要求 ≥36 |
| targetSdk | 36 | 对齐 Android 16 |
| minSdk | 26 | Android 8.0 |
| JVM Target | 17 | |

---

## 已知环境陷阱（踩坑记录）

### 陷阱 1：AAPT2 架构不匹配（致命）
**现象**
```
AAPT2 aapt2-8.9.1-12782657-linux Daemon #0: Daemon startup failed
```

**根因**
运行环境是 arm64 + proot Ubuntu，而 Gradle 会从 Maven 下载 **x86_64 原生 aapt2**
（`androidx` 变体只有 x86_64 版本），在 arm64 上无法执行。
本机 SDK 里的 `aapt2` 实际是一个 177 字节的 qemu 包装脚本：
```bash
#!/bin/bash
exec /usr/bin/qemu-x86_64-static -L /tmp/glibc-amd64/usr /tmp/aapt2.35.0.0.x86_64.bak "$@"
```

**修复**（`gradle.properties`）
```properties
android.aapt2FromMavenOverride=/root/android-sdk/build-tools/36.0.0/aapt2
```

### 陷阱 2：Kotlin 2.x 移除 kotlinOptions DSL
**现象**
```
Line 34: jvmTarget = "17"
Using 'jvmTarget: String' is an error. Please migrate to the compilerOptions DSL.
```
**修复**（`app/build.gradle.kts`）
```kotlin
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
```

### 陷阱 3：terminal 输出渲染不可信
`super_admin:terminal` 的 `cat` 输出会**剥离前导空格和空行**，看起来像缩进错乱。
**教训**：核实文件真实内容一律用 `read_file`，不要用 `cat` 判断格式。

### 陷阱 4：pm install 无法直接读 /sdcard
**现象**
```
System server has no access to read file context u:object_r:fuse:s0
Error: Can't open file: /sdcard/Download/x.apk
```
**修复**：先 `cp` 到 `/data/local/tmp/`，再 `pm install`。
注意 Ubuntu(proot) 环境无 `/data/local/tmp` 写权限，须用 shell 工具中转。

### 陷阱 5：aapt 输出偶发为空
`build-tools/36.0.0/aapt dump badging` 有时无输出。
**改用** `aapt2 dump badging` / `aapt2 dump xmltree --file`。

---

## 阶段记录

### Stage 1 · 工程骨架（2026-11-03）

**目标**：建立可编译的 Kotlin + Gradle Android 工程，产出第一个 APK。

**变更文件（16）**
```
settings.gradle.kts          仓库配置、模块声明
build.gradle.kts             AGP 8.9.1 / Kotlin 2.3.21
gradle.properties            ★ 含 aapt2 override 关键修复
local.properties             sdk.dir=/root/android-sdk
gradlew / gradlew.bat        Wrapper 脚本
gradle/wrapper/              wrapper jar + 指向 Gradle 8.11.1
app/build.gradle.kts         compileSdk 36 / minSdk 26 / JVM 17
app/proguard-rules.pro
app/src/main/AndroidManifest.xml
app/src/main/java/com/silentime/ime/MainActivity.kt
app/src/main/res/values/{strings,colors,themes}.xml
app/src/main/res/drawable/ic_launcher_foreground.xml
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
```

**结果**
```
BUILD SUCCESSFUL in 1m 13s
app-debug.apk  3.5 MB  签名有效（Android Debug）
```

**遇到的错误**
1. `kotlinOptions` DSL 移除 → 改用 `compilerOptions`
2. AAPT2 守护进程启动失败 → 加 `android.aapt2FromMavenOverride`
3. androidx.core 1.18.0 强制要求 AGP ≥8.9.1 / compileSdk ≥36
   → AGP 8.5.2→8.9.1，Gradle 8.9→8.11.1，compileSdk 35→36

---

### Stage 1.1 · 命名统一（2026-11-03）

**目标**：项目名统一为 SilentIME，包名迁移 com.silentim.ime → com.silentime.ime。

**变更**
| 对象 | 变更 |
|---|---|
| `/root/SilentIM` | 目录重命名为 `/root/SilentIME` |
| `settings.gradle.kts` | `rootProject.name` = "SilentIME" |
| `strings.xml` | `app_name` = "SilentIME" |
| `themes.xml` / `AndroidManifest.xml` | `Theme.SilentIM` → `Theme.SilentIME` |
| `java/com/silentim/` | 目录迁移至 `java/com/silentime/` |
| `app/build.gradle.kts` | `namespace` / `applicationId` = com.silentime.ime |

**结果**
```
BUILD SUCCESSFUL in 1m 11s
APK badging: package: name='com.silentime.ime'
```

**失误记录**
- 一度误判 `app_name` 是 SilentIME（实际是 SilentIM），因只看 diff 片段未读原文
- 一度误判 `build.gradle.kts` 缩进错乱（实为 terminal 渲染剥离空格）
- 教训：内容核实一律用 `read_file`

---

### Stage 2 · 输入法服务注册（2026-11-03）

**目标**：让 Android 16 把 SilentIME 识别为可用输入法，可启用、可切换、可显示输入视图。

**变更文件（4）**
| 文件 | 类型 | 说明 |
|---|---|---|
| `app/src/main/java/com/silentime/ime/SilentImeService.kt` | 新增 | 最小 `InputMethodService`，`onCreateInputView()` 返回占位 TextView |
| `app/src/main/res/xml/method.xml` | 新增 | 输入法元数据，含 zh_CN / en_US 两个子类型 |
| `app/src/main/AndroidManifest.xml` | 修改 | 注册 service（三要素） |
| `app/src/main/res/values/strings.xml` | 修改 | 新增 ime_placeholder / ime_service_name / subtype_zh / subtype_en |
| `docs/DEVLOG.md` | 新增 | 本文件 |

**Service 注册三要素（强制）**
```xml
<service
    android:name=".SilentImeService"
    android:exported="true"
    android:permission="android.permission.BIND_INPUT_METHOD">   <!-- ① 权限 -->
    <intent-filter>
        <action android:name="android.view.InputMethod" />        <!-- ② Action -->
    </intent-filter>
    <meta-data
        android:name="android.view.im"
        android:resource="@xml/method" />                          <!-- ③ 元数据 -->
</service>
```

**结果**
```
BUILD SUCCESSFUL in 1m 13s
app-debug.apk  3.7 MB  签名有效（Android Debug 证书）
```

**真机验证（全部为实际执行）**

| 验收项 | 命令 | 实测结果 |
|---|---|---|
| APK 安装 | `pm install -r /data/local/tmp/silentime.apk` | `Success` |
| 系统识别 | `ime list -a -s` | 列表含 `com.silentime.ime/.SilentImeService` |
| 可启用 | `ime enable ...` | `now enabled for user #0` |
| 可切换 | `ime set ...` | `selected for user #0` |
| 服务启动 | `ps -A` | 进程 PID 27798 运行中 |
| 系统绑定 | `dumpsys activity services` | `permission=android.permission.BIND_INPUT_METHOD`，intent `android.view.InputMethod` |
| 输入视图显示 | `dumpsys input_method` | `mBoundToMethod=true`，`mInputShown=true`，`mIsInputViewShown=true` |
| 窗口类型 | `dumpsys window` | `ty=INPUT_METHOD gr=BOTTOM` |
| 屏幕实拍 | 截图 OCR | 屏幕显示「SilentIME已启动 / 键盘UI开发中」 |
| 不影响原输入法 | `ime list -a -s` | 原有 7 个输入法全部完好 |

**测试后清理**：默认输入法已还原为 `com.jimeng.ime/.JimengIME`，SilentIME 保持启用状态供用户自行切换。

**错误日志**
- 首次 `pm install` 失败：`System server has no access to read file context u:object_r:fuse:s0`
  → 中转至 `/data/local/tmp` 后成功
- `aapt dump badging` 输出为空 → 改用 `aapt2 dump badging`

---

## 隐私承诺（项目硬性约束）

输入法能接触用户输入的一切文字，包括密码。因此本项目遵守：

1. **不申请 INTERNET 权限**，输入内容永不出设备
2. **不采集、不落盘、不上传**任何键入内容与历史
3. **密码框特殊处理**：后续检测到 password inputType 时禁用联想与学习
4. **AI 功能必须用户显式触发**，且 API 密钥绝不硬编码进 APK
5. 全部功能默认本地运行

---

---

### Stage 3 · 键盘 UI + 基础英文输入（2026-11-03）

**目标**：Canvas 自绘 QWERTY 键盘，完成英文基础输入全链路（字母 / 大小写 / 数字符号 / 空格 / 回车 / 退格含长按连删）。

**变更文件（4）**
| 文件 | 类型 | 说明 |
|---|---|---|
| `app/src/main/java/com/silentime/ime/keyboard/Key.kt` | 新增 | `KeyType` 枚举（CHARACTER / SHIFT / BACKSPACE / SPACE / ENTER / SYMBOLS / LETTERS）+ `Key` 数据类（value / label / weight / isLetter） |
| `app/src/main/java/com/silentime/ime/keyboard/KeyboardLayout.kt` | 新增 | `LayoutMode` 枚举（LETTERS_LOWER / LETTERS_UPPER / SYMBOLS）+ 纯函数布局：QWERTY 3 行 + 数字符号行，weight 权重方案，无 View 依赖 |
| `app/src/main/java/com/silentime/ime/keyboard/KeyboardView.kt` | 新增 | 自定义 View（352 行）：Paint 全部 init 复用；`rebuildLayout()` 仅在尺寸/模式变化时重算；ACTION_DOWN/MOVE/UP/CANCEL 完整处理；`hitTest` 矩形命中；退格长按 Handler 延迟 400ms 后每 60ms 连删；Shift 一次性输入后自动复位 |
| `app/src/main/java/com/silentime/ime/SilentImeService.kt` | 重写 | 实现 `KeyboardView.Listener`：`commitText` 提交、`deleteSurroundingText(1,0)` + `sendKeyEvent(KEYCODE_DEL)` 兜底删除、回车按 ImeOptions 智能处理（多行换行 / SEARCH 触发搜索 / SEND·GO·DONE·NEXT 走 `performEditorAction`）、`resolveEnterLabel` 动态图标 |

**结果**
```
BUILD SUCCESSFUL in 1m 20s
app-debug.apk  3.7 MB  签名有效（Android Debug 证书）
```

**真机验证（全部为实际执行 + 截图 OCR / uiautomator 断言）**

| 验收项 | 实测结果 |
|---|---|
| 字母输入 | 输入 q、w → 输入框内容 `q` → `qw` ✅ |
| Shift 大写 | 点 Shift 键盘变 QWERTY 大写，输入 → `qwQ` ✅ |
| Shift 一次性 | 再按同一键得小写 → `qwQw` ✅（Shift 自动复位） |
| 退格单删 | `qwQw` → `qwQ` ✅ |
| 数字符号切换 | 点 ?123 → 键盘变数字符号布局，输入 @ → `qwQ@` ✅ |
| 数字输入 | 点 1 → `qwQ@1` ✅ |
| 布局切回 | 点 ABC → 恢复 QWERTY ✅ |
| 空格输入 | 内容追加空格 ✅ |
| 回车触发搜索 | heytap 设置搜索框回车 → `mInputShown=false`、焦点跳走（触发搜索）✅ |
| 长按退格连删 | 1.2s 长按退格，输入框 `qwQ@1 qqwe` → `qw`（连删 7 字符，400ms 延迟 + 每 60ms 连删）✅ |
| 光标中间插入 | `qwxc` 光标左移 2 次到 `qw\|xc`，输入 → `qwvxc`（v 精确插中间）✅ |
| 微信输入 | **用户手动测试**：字母 / Shift / 数字符号 / 空格 / 退格 / 回车均正常 ✅ |
| 横屏 / 多屏尺寸 | **用户手动测试**：横屏键盘正常铺底、高度合理、竖屏贴齐 ✅ |

**错误日志（真实 bug）**
1. **键盘铺满全屏**：初版未重写 `onMeasure`，在 INPUT_METHOD 窗口（fillxwrap）下 View 被拉伸至整个窗口高度。像素分析：键盘行 y=385/960/1535（占满屏）。
   修复：重写 `onMeasure`，高度 = `screenHeight × 0.40f` 并 `coerceIn(180dp, 320dp)`。修复后键盘行 y=1604/1846/2089，高度约 883px，正常铺底。
2. `KeyboardLayout.kt` KDoc 引用错误（写错函数名），已改正。

**构建 / 部署备注**
- 安装走 `/data/local/tmp` 中转（SELinux），`pm install -r` Success
- 调试分辨率 1080x2376（物理 1264x2780）；键盘 4 行中心 y≈1604/1846/2089/2270
- 测试结束后需还原默认输入法为 `com.jimeng.ime/.JimengIME`（SilentIME 保持启用，用户可自行切换）

---

### Stage 3.1 · 键盘布局增强（高度计算封装 + WindowInsets 避让）（2026-11-03）

**目标**：落实「键盘高度动态计算（36%~42%）+ 系统导航栏避让 + 横竖屏独立适配 + 尺寸变化重算」的布局规格。

**变更文件（2）**
| 文件 | 类型 | 说明 |
|---|---|---|
| `app/src/main/java/com/silentime/ime/keyboard/KeyboardMetrics.kt` | **新增** | 纯函数尺寸计算模块（不依赖 View）：高度比例、dp 上下限、键盘总高（内容+Insets）、按键矩形计算全部集中于此 |
| `app/src/main/java/com/silentime/ime/keyboard/KeyboardView.kt` | 重写 | 接入 WindowInsets 监听（systemBars），内容区与 inset 区分离绘制；横竖屏参数切换；Insets/尺寸变化时 `requestLayout + rebuildLayout` 自动重算 |

**最终布局计算公式（已落代码）**
```
键盘总高度 totalHeight = contentHeight + bottomInsetPx
  contentHeight = round(windowHeightPx × ratio) 夹在 [minDp, maxDp] × density 内

竖屏：ratio = 35%，min = 180dp，max = 320dp   （比例按用户要求 35%）
横屏：ratio = 49%，min = 120dp，max = 260dp   （独立参数，不沿用竖屏；比例按用户要求 49%）

按键布局（内容区，Insets 已扣除）：
  行高 rowHeight    = (contentHeight - 总行距) / 行数
  行距 rowGap       = contentHeight × 2%
  键宽 keyWidth     = (contentWidth - 2×padding) / Σweight × key.weight
  padding           = contentWidth × 1.2%
  键间 gap          = padding × 0.5
```

**Insets 处理方式**
- 使用 `ViewCompat.setOnApplyWindowInsetsListener` + `WindowInsetsCompat.Type.systemBars()`（不写死导航栏高度）
- 记录 bottom / left / right inset；**背景色铺满全 View（含 inset 区）**保持视觉连续，**按键只绘制在内容区** → 不遮挡手势条/三键、无双重 padding
- 消费 Insets（`WindowInsetsCompat.CONSUMED`），不向上传递
- Insets 或横竖屏变化 → `requestLayout() + rebuildLayout() + invalidate()`
- 未使用隐藏导航栏 / 沉浸式模式

**真机实测结果（全部实际执行）**

| 场景 | 实测 |
|---|---|
| 手势导航 · 竖屏 (480dpi,1080x2376) | **竖屏比例 35%（用户要求）**：键盘总高 ≈ 904px（≈38.0% 含 inset；内容区 ≈ 831px = 恰好 35.0%）；手势条 y≈2352 未被遮挡 ✅；输入 `q` 命中准确 ✅ |
| 手势导航 · 横屏 (ROTATION_90, 2376x1080) | **横屏比例 49%（用户要求）**：键盘顶 y≈528，总高 ≈ 552px（≈51.1% 含 inset；内容区 ≈ 529px = 恰好 49.0%）；手势条 y≈1054 未被遮挡 ✅；输入 `q` 命中准确 ✅ |
| 三键导航 · 竖屏 | 键盘正常铺底，三键区在底部（白底导航区），键盘不侵入 ✅ |
| 三键导航 · 横屏 | 系统导航栏移至**右侧竖排**，键盘右边界 x≈2240，与导航区间有明显间隙（135px），**未遮挡三键** ✅ |
| 不同显示尺寸 (density 480→420) | 键盘总高 972px ≈ 40.9%，仍落在 36%~42% 区间；手势条避让正常；输入 `q` 命中 ✅ |
| 功能回归（手势/竖屏） | q/w/e 输入、Shift 大写、退格单删全部通过 ✅ |

**注意**
- 切换导航模式（gestural↔threebutton）时系统可能自动旋转屏幕（竖屏→横屏），属系统行为，键盘随之重算正常。
- 三键导航 overlay 需确保 `navbar.gestural` 与 `navbar.threebutton` 不同时启用，否则导航栏行为异常。

---

### Stage 3.2 · 输入法切换后的窗口位置恢复与 Insets 兼容性排查（2026-11-03）

**目标**：解决 realme Android 16「键盘位置：抬高」在切换到其他输入法再切回 SilentIME 后失效的问题。**只准定位根因修复，禁止固定偏移 / 强制改窗口位置 / 隐藏导航栏 / 重复 padding**。

**复现与取证（真实执行）**

| 步骤 | 结果 |
|---|---|
| 定位开关存储 | secure settings：`keyboard_position=1`（「抬高」开）、`keyboard_prevent_touch=1`、`keyboard_quick_switch=1` |
| baseline（旧版无日志 APK） | 窗口 frame=[0,1472][1080,2376]（904px 贴底）；切 1→0 时 frame 顶 1472→1497（下移 25px），证明「抬高」由系统窗口层参与 |
| 连续 3 轮 jimeng 往返切换（旧 APK） | frame 恒 [0,1472][1080,2376]（904px）——旧版**从未体现抬高** |
| 强杀进程首次显示（旧 APK） | 仍 1472/904，排除进程态差异 |
| 对照 jimeng 键盘 | 顶≈1650、高≈726，更矮更靠下 |

**诊断日志埋点（Debug-only，release 自动关闭）**
- `build.gradle.kts`：`buildFeatures { buildConfig = true }`（AGP 8.x 默认关，需显式开启）
- `KeyboardView.kt`：`companion object { TAG="SilentIME/KbView"; DEBUG=BuildConfig.DEBUG; logD() }`；打点：init / onAttached / onDetached / insets 回调（bottom/left/right + changed）/ onMeasure（specH/mode/screenH/inset/calc/measured）/ onSizeChanged / rebuildLayout（view/content/insets）
- `SilentImeService.kt`：`TAG="SilentIME/Service"`；打点：onCreateInputView（记录 View 地址判定复用）/ onStartInput / onStartInputView / onFinishInput / onFinishInputView / onWindowShown / onWindowHidden / onDestroy
- **隐私红线**：日志只记 inputType/imeOptions 数值，绝不记录输入内容 / 密码 / 候选词 / 字段名

**根因（日志 + 窗口状态交叉验证锁定）**

realme「抬高」的机制 = **系统把 IME 窗口 `insets.bottom` 从 48→132 增大（多 84px 抬高留白），并把窗口高度预算（AT_MOST specH）压到 904**。

旧实现 `onMeasure` 无视 specH，用 `screenHeight×ratio + inset` 自算：
```
screenH=2376 × 0.35 = 831 + inset 132 = 963   ← 超出窗口预算 904 达 59px
```
→ View 963 > 窗口预算 904 → 窗口层按 904 裁剪（touchable 仍 (0,1472,1080,2376)），View 底部 59px 不可见，「抬高」视觉失效。

**修复（1 处，符合全部约束）**：`KeyboardView.onMeasure` 尊重系统窗口高度预算：
```kotlin
val total = when (specMode) {
    MeasureSpec.UNSPECIFIED -> calcTotal
    else -> minOf(calcTotal, specH)   // AT_MOST/EXACTLY 钳制到系统预算
}
```

**修复后实测（全部真实执行）**

| 场景 | inset | View | 内容区 | 判定 |
|---|---|---|---|---|
| 抬高开（keyboard_position=1）首次显示 | 132 | 1080x904 | 772 | 底部 132px 抬高留白 ✅ |
| 抬高开 + 连续 **5 轮** jimeng 往返切换 | 132 | 1080x904 | 772 | **稳定不退化** ✅ |
| 抬高关（keyboard_position=0）回底 | 48 | 1080x879 | 831 | 贴底、内容恢复全尺寸 ✅ |
| 横屏 (ROTATION_90) | 48 | 2256x553 | 505 | 49% 目标、无裁剪 ✅ |
| 英文输入回归（搜索框） | - | - | - | 输入 `q` 命中 ✅ |
| 窗口一致性 | - | Requested h=904 = View 904 | - | **无裁剪** ✅ |

**变更文件清单**
| 文件 | 类型 | 说明 |
|---|---|---|
| `app/src/main/java/com/silentime/ime/keyboard/KeyboardView.kt` | 修改 | diagnostics 埋点 + **onMeasure clamp specH（根因修复）** + 删除重复的 `onDetachedFromWindow()`（会导致编译失败的冗余重定义）+ rebuildLayout 打点 |
| `app/src/main/java/com/silentime/ime/SilentImeService.kt` | 修改 | 生命周期诊断日志（View 地址 / inputType / imeOptions / isInputViewShown） |
| `app/build.gradle.kts` | 修改 | `buildFeatures.buildConfig = true`（AGP 8.x 默认关闭 BuildConfig，需显式开启） |
| `docs/DEVLOG.md` | 修改 | 本记录 |

**错误日志（真实）**
1. `Unresolved reference 'BuildConfig'`（2 次）→ `buildFeatures { buildConfig = true }` + 子包 `import com.silentime.ime.BuildConfig`
2. 发现 `KeyboardView.kt` 文件尾存在**重复的 `onDetachedFromWindow()`**（旧编辑残留）→ 删除无日志的冗余版本
3. 搬运 APK 曾把 `cp` 写进 Android shell（无 /root 路径）导致安装未执行 → 区分 terminal（拷贝到 /sdcard）与 shell（中转 /data/local/tmp + pm install）

**结论**：根因是 **IME View 未尊重系统窗口高度预算 specH**（超预算 59px 被裁剪），非厂商系统 bug；修复后「抬高」在首次与 5 轮切换后均稳定保持，关闭抬高正确回底。横屏下「抬高」inset 保持 48（realme 不对横屏注入抬高留白，如实记录，属厂商行为）。

---

### Stage 3.2 补充 · 三键导航 + 抬高验证与分屏补测（2026-11-03）

**背景**：Stage 3.2 核心修复获用户认可后，按授权补充三键导航与分屏验证。**本阶段未修改任何代码**。

**测试前环境设置**
- 切换三键导航：`cmd overlay disable navbar.gestural` + `enable navbar.threebutton`（navigation_mode 0）
- `keyboard_position=1`（抬高开）、IME 切为 SilentIME、竖屏

**三键导航 + 抬高（keyboard_position=1）初次显示（真实日志）**
```
onMeasure specH=2376 screenH=2124 insetBottom=0   calc=743 -> measuredH=743
insets changed=true bottom=132
onMeasure specH=2376 screenH=2124 insetBottom=132 calc=875 -> measuredH=875
onMeasure specH=875 screenH=2376 insetBottom=132 calc=963 -> measuredH=875   ← clamp 生效
onSizeChanged 0x0 -> 1080x875
rebuildLayout view=1080x875 content=1080x743 insetsB=132
```
- 窗口：`Requested w=1080 h=875`、`touchable region=(0,1501,1080,2376)`（1501+875=2376 贴底）
- **三键导航下 screenH=2124**（手势导航为 2208；三键区更大，窗口可用高更小）
- 底部 132px 抬高留白 ✅

**三键导航 + 抬高 + 5 轮 jimeng 往返切换（每轮均真实抓取）**

| 轮次 | View | inset | 内容区 | touchable |
|---|---|---|---|---|
| 1 | 1080x875 | 132 | 743 | (0,1501,1080,2376) |
| 2 | 1080x875 | 132 | 743 | (0,1501,1080,2376) |
| 3 | 1080x875 | 132 | 743 | (0,1501,1080,2376) |
| 4 | 1080x875 | 132 | 743 | (0,1501,1080,2376) |
| 5 | 1080x875 | 132 | 743 | (0,1501,1080,2376) |

**全部稳定，无退化 ✅**

**三键导航不被键盘覆盖（像素级验证）**
- 导航栏窗口：`NavigationBar_displayId_0`，frame=[0,2244][1080,2376]，`isVisibleRequested=true`
- 键盘内容区止于 y=2244（1501+743），底部 2244~2376 为三键区
- PIL 像素扫描：导航区 (2244-2376) 发现 **279 个非背景像素**（三键图标，灰色系 #A4A5A8/#D1D2D4，x∈[276,996]，y∈[2292,2327]）→ **三键（返回/主页/最近任务）正常渲染，未被键盘遮挡 ✅**

**关闭抬高回底（keyboard_position=0，三键导航）**
- 过渡中间态出现过 `bottom=132` 与 963px 残留（insets 切换动画期），最终稳定：
```
onMeasure specH=2376 screenH=2124 insetBottom=48 calc=791 -> measuredH=791
onSizeChanged 1080x791
rebuildLayout view=1080x791 content=1080x743 insetsB=48
```
- 窗口：`Requested w=1080 h=791`、`touchable (0,1585,1080,2376)`
- 顶 y 1501→1585（**下降 84px**）、高 875→791（**减小 84px**）→ **正确回底 ✅**（内容区 743 恒定，仅 inset 132→48 变了留白）

**分屏补测（条件受限，未完成）**
- 尝试：`am start --windowingMode 2/3/4`、`-f 0x08000000/0x10000000`、`keyevent 187`（最近任务）——realme/ColorOS 均不响应命令行分屏触发（窗口模式全被忽略、最近任务键被拦截）
- **结论：命令行无法触发真实分屏，分屏场景未实测（如实标记未测试）**。若需补测，需用户手动进入分屏（最近任务长按卡片/双指），届时可再抓日志。

**过程中的环境坑（已记录）**
1. 系统可能在切换导航 overlay 或分屏尝试时**自动还原导航模式**（threebutton→gestural，mode 0→2），测试中需重新确认 overlay 状态
2. `default_input_method` 曾漂移到 `com.kingzcheung.xime`（第三方输入法拉起），导致键盘弹的是 xime 而非 SilentIME——验证前必须核对 `settings get secure default_input_method` 与 IME 窗口 `package=`
3. `keyboard_position` 切换后**非即时生效**，有动画过渡中间态（bottom 48↔132、View 791↔963），需等待 2-3s 后再读数

**三键导航验证结论**：✅ 通过。初次抬高正常、5 次切换稳定、三键不被遮挡、关闭抬高正确回底。

---

**v4.1 · 横屏→竖屏旋转回归修复（Stage 3.2.1 补充）**

**问题现象**：横屏（Chrome 地址栏）→ 旋转回竖屏后，键盘超高进入导航区
- 旋转导致 KeyboardView 重建（新 View b667dd1，attachSeq=1），但窗口复用、系统不再重新协商，只下发伪预算 specH=2376
- 原 v4 的 cachedContentBudget 是**实例变量**，随旧 View 销毁丢失 → 新 View 缓存=0 → 走 else 分支自算 963 → lastRow 止 824 > 743（进入三键导航区）

**根因**：旋转场景（窗口复用 + View 重建）下，系统只给 UNSPECIFIED 伪预算不协商；内容高度预算必须跨 View 存活

**修复（v4.1）**：KeyboardView.onMeasure 改造
- cachedContentBudget 改为 **companion 静态双缓存**：cachedContentPortrait / cachedContentLandscape
- onMeasure 开头同步 `isLandscape = resources.configuration.orientation == ORIENTATION_LANDSCAPE`（防 insets listener 晚触发）
- 有效预算（isEffectiveBudget）按当前方向写入静态缓存；读取时取当前方向缓存
- 不写死任何高度，自动适配三键/手势、抬高/回底、横/竖屏、热切换、旋转
- 代码备份：`KeyboardView.kt.bak_v40`

**验证（真机 realme GT Neo6，构建 SHA256=4e013306...）**
- 横屏（Chrome）：View=2256x577、cache=529、lastRow 止 524.77 ≤ 529 ✅
- 旋转回竖屏：新 View f830ac7 继承 portrait cache=789 → View=921、lastRow 止 782.69 ≤ 789 ✅ **不再超高**
- Settings 开关键盘（新 View）：921 / lastRow 止 782.69 ✅
- 热切换（launcher↔settings）Requested h 恒 875 ✅

**回归功能检查（Settings 搜索框实机验证）**：字母✅ Shift+大写+一次性自动回落✅ 符号页切换✅ 符号输入✅ 退格✅ 空格✅ Enter（onKeyEnter 已触发，Settings 不消费属应用行为）✅ 输入法切换✅

**如实记录**：`keyboard_position`（抬高/回底）设置当前未被源码读取（grep 无结果），键盘始终按 view=content+systemBars inset 布局——不影响正确性但属待办认知项。分屏场景命令行无法触发（realme/ColorOS 限制），未实测。

---
**Stage 4 · 中文拼音输入（待授权）**
- 拼音音节解析器（声母/韵母表，不含网络）
- 本地候选词引擎（词典随 APK 内置，不上传键入内容）
- 候选词条 UI（键盘上方水平滚动）
- 数字选择候选 / 空格上屏 / 退格回退拼音
- 中英文混合输入切换
- 密码框保持禁用联想（隐私红线）

## Stage 3.2.2 · v4.2 高度递归收缩最小修复 — 真机回归结果（2026-11-03）

**修复内容**（已部署 lastUpdateTime=2026-11-03 11:59:40，APK SHA256=5717d4fbaee3b4bab4f585cd8eb992b09d0abf0f323c193124fb6c52a08c911a）：
1. 持久缓存签名：新增 `cachedScreenHdpPortrait/Landscape` + `cachedDensity`，签名用 `resources.configuration.screenHeightDp`（不受 displayMetrics 在 IME 窗口上下文中的漂移影响）
2. `shrinkProtected` 缩水保护：有效预算显著小于签名缓存×0.85（或缓存缺失时 < calcTotal×0.8）时禁止写入持久缓存
3. 临时/持久分离：缩水预算只用于本次布局，不污染持久缓存
4. 日志新增 sig/shrink/hdp 字段

**回归测试矩阵（真机 RMX3852，Android 16，1080x2376/480dpi）**：

| 场景 | 结果 | 关键日志证据 |
|---|---|---|
| 三键导航 + Settings + 5x IME 切换（xime↔silentime） | ✅ 零收缩 | 每轮 measuredH 恒 921、cachedContent 恒 789、shrink=false、sig=true |
| 分屏多窗口（Settings 大窗 + Operit 侧窗） | ✅ 零收缩 | 同上，窗口 frame 正常，无缩水缓存写入 |
| 三键→手势导航切换 | ✅ | navMode=gestural 下 measuredH=921、缓存 789 恒定 |
| 手势→三键切换 | ✅ | 还原三键后 measuredH=921，缓存无残留污染 |
| 横竖屏 | ⚠️ 环境受限 | ColorOS UiAutomation 锁定 USER_ROTATION_LOCKED，无法 shell 旋转；旋转逻辑未被 v4.2 改动，待后续手测 |
| 像素级导航区验证 | ✅ | 导航区白色三键图标 y∈[2298,2321] x 分 3 簇；键盘按键色 (44,49,64) 在导航区 0 px |

**核心机制验证**：即使 screenH 仍漂移（2256↔2376）且 insetBottom 瞬态（0↔132），`cachedContent=789` 恒定、`measuredH=921` 恒定、`shrink` 恒 false —— 缩水预算被临时隔离，不再污染持久缓存。递归收缩根因闭环。

**回归日志**：/sdcard/Download/silentime_v42_regression.log（36,480 字节，含全部 onMeasure sig/shrink/hdp 现场）
**遗留**：横屏自动化受限；分屏精确实测待用户手测确认（本次已在真分屏下验证零收缩）

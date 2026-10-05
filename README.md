# KeyBridge —— 手机变电脑键盘

把安卓手机变成电脑的虚拟键盘：USB 数据线（或同一 WiFi）连接，手机上提供
**全键盘 / 游戏键盘 / 快捷键 / 文本输入** 四种模式，按键实时注入电脑。

同类思路参考：DeskDock、Unified Remote、KDE Connect 远程输入。

## 原理

```
┌──────────┐  TCP（adb reverse USB 隧道 / WiFi） ┌─────────────┐ SendInput ┌──────┐
│ 手机 App │ ──────────────────────────────────▶ │ pc/         │ ────────▶ │ 电脑 │
│ 触摸→JSON│   {"t":"k","c":"W","d":1}           │ keybridge_  │           │ 系统 │
└──────────┘                                     │ server.py   │           └──────┘
                                                 └─────────────┘
```

- **USB 模式（推荐，免 root）**：电脑端脚本自动执行
  `adb reverse tcp:27182 tcp:27182`，手机 App 连接自己的
  `127.0.0.1:27182` 即可经 USB 隧道到达电脑。
- **WiFi 模式**：电脑端 `--lan` 启动，手机 App「设置」里填电脑局域网 IP。
- 普通按键用**扫描码**注入，DirectInput/老游戏也能识别。

## 快速开始（USB 模式）

1. 手机开启「开发者选项 → USB 调试」，数据线连电脑，弹窗点允许。
2. 电脑需要 `adb`（已装 Android Studio / platform-tools 就有），
   Python 3.6+ 标准库即可，**无需 pip 安装任何依赖**。
3. 电脑双击 `pc/start_server.bat`（或 `python pc/keybridge_server.py`）。
4. 手机打开 App，顶部变绿「已连接」即可使用。

## WiFi 模式

1. 电脑：`python pc/keybridge_server.py --lan`（Windows 防火墙放行 Python）。
2. 手机与电脑连同一网络；App「设置」→ 主机地址填电脑 IP
   （电脑 `ipconfig` 查看 IPv4 地址）。

## 构建 APK

**方式一 —— 本机构建（本机已具备 JDK21 + SDK D:\Android\Sdk，Gradle 缺失需先装）：**

```bash
# 安装 Gradle 8.7 后在工程根目录执行：
gradle :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

**方式二 —— Android Studio：** 打开工程根目录，同步后 Run / Build APK。

**方式三 —— GitHub Actions（本地啥都不用装）：**
把仓库推到 GitHub，Actions 自动构建，在 Artifacts 下载 `app-debug.apk`。

## 使用说明

| 模式 | 内容 |
|---|---|
| 全键盘 | F1-F12、数字、完整字母区、修饰键、方向/编辑键。按住自动重复；修饰键可组合 |
| 游戏 | 固定核心区 WASD/Space/Ctrl/Shift/Alt/回车；其余按键「✎ 选键」自由勾选，「⇄ 调位置」点两个键互换（可跨行）；自适应铺满全屏；按住多久生效多久，绝不自动锁定；支持多指同按 |
| 快捷键 | Ctrl+C/V/Z/S…、Alt+Tab、Win+D/L/R、任务管理器、截屏、音量/播放控制 |
| 输入 | 手机上打字直接以 Unicode 注入电脑，回车/退格同步转发；建议英文输入法 |

- 横屏使用体验最佳；屏幕保持常亮；全面屏手势下侧滑可呼出系统栏。

## 常见问题

- **一直显示未连接？**
  检查电脑脚本是否在运行 → `adb devices` 是否列出设备且无 `unauthorized`
  → 换数据线/换 USB 口 → 都不行就用 WiFi 模式。
- **游戏里按键无效？** 以管理员身份重新运行电脑脚本
  （部分游戏拦截非管理员进程的注入输入）。
- **按键卡住？** 断开连接时电脑端会自动释放所有按住的键；
  也可以点 App 顶部「重连」。
- **部分网游反作弊** 会拦截/记录 SendInput 级注入。本工具适用于自有电脑的
  办公、演示、模拟器、单机游戏等场景，请遵守目标软件的使用条款。

## 自定义

- 按键布局：`app/src/main/java/com/keybridge/keyboard/KeyDefs.kt`
- 键名 → 扫描码映射：`pc/keybridge_server.py` 顶部 `SCAN` / `VK` 表
- 端口：App「设置」+ 电脑端 `--port`（两边保持一致）

## 目录结构

```
app/         安卓工程（Kotlin，零第三方依赖）
pc/          电脑端脚本 + 一键启动 bat
.github/     CI 自动构建 APK
```

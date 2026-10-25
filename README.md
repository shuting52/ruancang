# 软仓 · 软件仓（Android App）

> 一句话：手机上的文件 / 已安装应用，点一下上传，自动分到对应类别，
> 实时同步到你的 GitHub 仓库。界面就是你给的那套 NEU UI · 柔影主题。

- **App 名**：软仓
- **包名**：`com.ruancang.app`
- **版本**：v1.0.0（versionCode 1）
- **技术栈**：Kotlin + Jetpack Compose（Material 3），不引 OkHttp / Retrofit，
  GitHub 同步只用系统 `HttpURLConnection` + `org.json`
- **默认仓库**：`https://github.com/shuting52/ruancang`（分支 `main`，可在 App 内改）
- **锁定版本面**：AGP 8.5.2 / Kotlin 1.9.23 / Compose BOM 2024.10.00 /
  compileSdk 34 / minSdk 26 / Kotlin Compiler Extension 1.5.10
  （与 Text 字效原生工程一致，避免新 API 编不过）

---

## 目录

1. [功能总览](#1-功能总览)
2. [先说清楚：Token 怎么处理](#2-先说清楚token-怎么处理)
3. [快速上手：从源码到手机](#3-快速上手从源码到手机)
4. [使用方法：上传一次的完整流程](#4-使用方法上传一次的完整流程)
5. [自动分类规则](#5-自动分类规则)
6. [仓库里长什么样](#6-仓库里长什么样)
7. [“不限制文件大小”是怎么做到的（诚实版）](#7-不限制文件大小是怎么做到的诚实版)
8. [自定义主题代码：粘贴 HTML / CSS，点应用](#8-自定义主题代码粘贴-html--css点应用)
9. [图标](#9-图标)
10. [工程结构](#10-工程结构)
11. [常见问题 FAQ](#11-常见问题-faq)
12. [隐私与安全](#12-隐私与安全)
13. [已知限制 / 下一步](#13-已知限制--下一步)

---

## 1. 功能总览

| 你要的功能 | 软仓 v1.0.0 的实现 | 在代码里的位置 |
|---|---|---|
| 上传按钮，点开一个**独立的上传窗口** | 底部 Tab 的「＋ 上传」和仓库页的大按钮都会打开一个**占满屏的 Dialog 窗口**，关掉不影响仓库页 | `ui/UploadSheet.kt`（`UploadWindow`）、`ui/WarehouseScreen.kt` |
| 窗口里**选择一个应用程序** | 两个入口二选一：① **选择文件**（系统文件选择器，任意类型）；② **已安装应用**（列出本机已装应用，点一个即提取它的 APK，可切换“只看用户应用 / 含系统应用”） | `ui/UploadSheet.kt`、`data/UploadManager.kt`（`listInstalledApps`） |
| **截图**：上传文件的截图，知道这是干什么的 | 上传窗口第 3 步：**添加截图（可多选）**，预览数量、清空；截图传到该条目的 `screenshots/` 文件夹，并在 `info.json` 里记张数 | `ui/UploadSheet.kt`、`data/UploadManager.kt` |
| **类别自动识别**，并在仓库创建对应类别 | 按扩展名 → MIME 类型 → 是否为已安装应用三级判断；识别结果当场显示，**可手动改**；上传时自动以类别名建文件夹 | `model/Category.kt`（`Category.detect`） |
| 点上传，**同步实时更新到 GitHub** | 上传按钮 → 实时进度条（百分比）+ 逐行上传日志；主文件、截图、`info.json` 按顺序同步，完成后显示 GitHub 链接 | `data/GithubApi.kt`、`data/UploadManager.kt` |
| 主题用你给的 NEU 代码 | 背景 `#E0E8EF`、柔影蓝 `#5F9DE7`、深灰蓝 `#516D91`、文字灰蓝 `#7E97B8`、点缀金 `#F0A93C`、圆角 24px、2px 白描边、双向柔影，全部落到 Compose 组件 | `ui/NeuComponents.kt`、`data/Stores.kt`（`NeuThemeColors`） |
| **自定义输入代码，点应用全局生效** | 「主题」页：粘贴 HTML / CSS → 点「应用到全局」→ 全 App 按钮、卡片、Tab、进度条立刻换色；点「恢复默认柔影」一键回滚；下次启动自动沿用上次的代码 | `ui/WarehouseScreen.kt`（`ThemePage`）、`data/Stores.kt`（`ThemeParser`） |
| 图标是 SVG，以“软”字为核心 | `icon/ruancang-icon.svg`（512×512，柔影 tile + 内凹槽 + 蓝色渐变“软”字 + 金色点缀） | `icon/ruancang-icon.svg`，见 [第 9 节](#9-图标) |

---

## 2. 先说清楚：Token 怎么处理

**这份源码里没有任何 Token。以后也不会有。**

- Token 只在 App 内的「设置」页粘贴，存在**本机** `SharedPreferences`
 （`ruancang_prefs`），不进代码、不进 Git 仓库、不上报任何服务器。
- 换手机 / 卸载重装后要重新粘贴，这是故意的——Token 不应跟着源码走。
- **重要安全提醒**：任何曾经出现在聊天记录、截图、公开仓库里的 Token，
  都应视为**已泄露**。请去 GitHub → Settings → Developer settings →
  Personal access tokens 里**吊销旧 Token，换一个新 Token**，再粘贴到 App 里。
- 建议用**细粒度 Token（Fine-grained）**：只选 `shuting52/ruancang` 这一个仓库，
  权限只给 **Contents：读写**，不要给整个账号的 `repo` 全权限，也不要给
  删除仓库、管理组织的权限。经典 Token（`ghp_…`）能用，但权限面更宽，
  能用细粒度就优先用细粒度。

> 本次交付没有把你发来的 Token 写进任何文件、任何代码、任何记忆。
> 装好 App 后，用**新换的 Token**在「设置」页粘贴一次即可。

---

## 3. 快速上手：从源码到手机

本机开发环境其实有 Android SDK（`~/workspace/.android-sdk`，platform 34 +
build-tools 34.0.0）和 JDK 17 / 21，但 2026-10-25 实测：Gradle 守护进程
在本机运行时环境里无法通信（Gradle 8.7 / 8.9、JDK 17 / 21、新 Gradle
用户目录、连空项目都一样报 `Could not receive a message from the daemon`，
类型标签 109），所以这份工程**尚未在本机编译出 APK**
（没做过的不说已完成）。两条路都能拿到 APK，任选一条：

### 路线 A：Android Studio（推荐，第一次 10–20 分钟）

1. 把本文件夹用 Android Studio 打开（File → Open，选 `ruancang-app/`）。
2. 等 Gradle 同步完成（首次会下载 AGP / Compose，耐心等一次）。
3. 手机开 USB 调试连电脑，或用模拟器，点 ▶ Run，装到手机上。
4. 或者菜单 **Build → Build App Bundle(s) / APK(s) → Build APK(s)**，
   产物在 `app/build/outputs/apk/debug/app-debug.apk`，传到手机安装。

### 路线 B：GitHub Actions 自动打包（不用装 SDK）

1. 把本文件夹推到一个仓库（可以就是 `ruancang`，也可以另建一个源码仓库）。
2. 推到 `main` 后，Actions 会自动跑 `.github/workflows/build-apk.yml`。
3. 跑完在 Actions → Artifacts 里下载 `ruancang-debug-apk`。

### 装好之后的第一步（必做，2 分钟）

1. 打开软仓 → 底部「设置」。
2. 确认所有者 `shuting52`、仓库 `ruancang`、分支 `main`（默认就是这三个）。
3. 粘贴**新换的** GitHub Token → 点「保存」→ 点「测试连接」。
4. 看到 `✅ 连通！默认分支：main`，就可以去上传了。
5. 如果测试报 401：Token 错了 / 过期了 / 没给这个仓库权限；
   报 404：仓库名或所有者拼错了，或者 Token 没权限看到这个仓库。

---

## 4. 使用方法：上传一次的完整流程

1. 点底部「＋ 上传」或仓库页的大按钮，打开**独立上传窗口**。
2. **第 1 步 · 选来源**（二选一）：
   - **选择文件**：系统文件选择器，任意文件都能选（APK、图片、视频、文档、压缩包……）。
   - **已安装应用**：列表里点一个应用，App 会自动找到它的 APK 路径并提取
     （不需要 root；系统应用默认不列出，点“含系统应用”可放出）。
3. **第 2 步 · 类别**：窗口会当场显示“自动识别为「应用 / 图片 / 视频……」
   → 仓库文件夹 `apps/ / images/ / …`”。觉得不对，直接点芯片手动改。
4. **第 3 步 · 截图**：点「添加截图（可多选）」，从相册选 1 张或多张。
   截图是“这个文件是干什么的”最直观的说明，建议至少传 1 张。
5. **第 4 步 · 名称与说明**：名称默认取文件名（可改），说明写一句话用途、
   版本、注意事项。说明会写进 `info.json`，别人打开仓库就能看懂。
6. **第 5 步 · 上传**：点「上传应用程序 / 文件」。
   - 进度条实时走百分比，大文件是按块走的（见第 7 节）。
   - 下面有逐行日志：主文件 → 截图 → `info.json`，卡在哪一步一目了然。
   - 全部完成会显示 GitHub 链接和仓库路径。
7. 回到「仓库」页点「刷新」，按类别就能看到刚传的条目。

**已安装应用的命名**：`应用名-版本号.apk`，例如 `微信-8.0.50.apk`
（文件名里的 `\/:*?"<>|` 会自动换成 `-`，避免 GitHub 路径报错）。

---

## 5. 自动分类规则

优先级：**已安装应用 > 文件扩展名 > MIME 类型 > 其他**。

| 类别 | 仓库文件夹 | 扩展名（节选） |
|---|---|---|
| 📦 应用 | `apps/` | `apk` `aab` `xapk` `apks`，以及所有“已安装应用”来源 |
| 🖼️ 图片 | `images/` | `jpg` `jpeg` `png` `webp` `gif` `bmp` `svg` `heic` `avif` |
| 🎬 视频 | `videos/` | `mp4` `mkv` `mov` `webm` `avi` `flv` `m4v` |
| 🎵 音频 | `audio/` | `mp3` `wav` `flac` `aac` `ogg` `m4a` `opus` |
| 📄 文档 | `documents/` | `pdf` `doc` `docx` `xls` `xlsx` `ppt` `pptx` `txt` `md` `epub` `csv` |
| 🗜️ 压缩包 | `archives/` | `zip` `rar` `7z` `tar` `gz` `bz2` `xz` |
| 💻 代码 | `code/` | `html` `css` `js` `ts` `json` `xml` `kt` `java` `py` `sh` `c` `cpp` |
| 🔤 字体 | `fonts/` | `ttf` `otf` `woff` `woff2` |
| 📁 其他 | `others/` | 以上都认不出来时落这里，上传窗口可手动改 |

完整映射表在 `app/src/main/java/com/ruancang/app/model/Category.kt`，
加一个类别就是加一行扩展名 + 一个枚举项，不用改上传逻辑。

---

## 6. 仓库里长什么样

上传一个文件，仓库里会长成这样（以“微信 8.0.50 + 2 张截图”为例）：

```
ruancang/
├── apps/
│   └── 微信-8-0-50/
│       ├── 微信-8.0.50.apk
│       ├── info.json
│       └── screenshots/
│           ├── shot-1.jpg
│           └── shot-2.jpg
├── images/
│   └── …（同理，每个条目一个文件夹）
├── videos/
├── documents/
├── archives/
├── code/
├── fonts/
└── others/
```

每个条目的 `info.json` 示例：

```json
{
  "title": "微信",
  "fileName": "微信-8.0.50.apk",
  "category": "apps",
  "categoryLabel": "应用",
  "description": "社交应用，8.0.50 版",
  "size": 251658240,
  "uploadedAt": 1761364086000,
  "source": "installed-app",
  "packageName": "com.tencent.mm",
  "appVersion": "8.0.50",
  "screenshots": 2
}
```

这样设计的好处：类别文件夹一目了然；每个条目自带说明和截图，
别人（或以后的你）打开仓库，不用猜这个文件是干什么的；
同名文件再传一次会**更新**（PUT 时带上旧 `sha`），不会满仓库都是重名文件。

---

## 7. “不限制文件大小”是怎么做到的（诚实版）

先说 GitHub 侧的硬限制，App 绕不过、也不假装能绕过：

- GitHub Contents API 单个文件**建议 ≤ 50 MB、硬上限 100 MB**；
  超过 100 MB 的单文件，GitHub 网页端和 API 都会拒绝。
- GitHub 对单个仓库也有总量上的软性建议（过大仓库克隆会变慢），
  这是仓库层面的事，不是 App 能改的。

App 的做法是：**App 本身不设大小上限，超过 API 单文件上限就自动切块**，

| 文件大小 | App 的做法 |
|---|---|
| < 10 MB | 一次流式上传，原文件名不变 |
| ≥ 10 MB | 按 **10 MB 一块**切分，每块流式 PUT 为 `parts/part-001`、`part-002`……，并附一份 `manifest.json`（v1.0.3 起：Base64 流式编码，不拼大字符串，防 OOM） |

大文件在仓库里的样子：

```
apps/某个大应用/
├── parts/
│   ├── part-001
│   ├── part-002
│   └── part-003
├── manifest.json
├── info.json
└── screenshots/
    └── shot-1.jpg
```

`manifest.json` 示例：

```json
{
  "originalName": "某个大应用.apk",
  "totalSize": 134217728,
  "chunkSize": 47185920,
  "parts": ["part-001", "part-002", "part-003"],
  "sha256": ["…每块的 SHA-256…"],
  "note": "用 tools/reassemble.py 或 App 内合并功能按 parts 顺序拼回原文件"
}
```

**下载后拼回原文件**（任选其一）：

```bash
# 把仓库里 apps/某个大应用/ 整个文件夹下载下来后：
python3 tools/reassemble.py apps/某个大应用
# 得到 某个大应用.apk，并逐块校验 SHA-256
```

> 所以“不限制”是指：App 不拦你传多大的文件，分块数也不设上限；
> 但最终还是要受 GitHub 仓库总量、网络、手机存储的客观限制。
> 如果未来要传 TB 级、频繁更新的大文件，更合适的方案是 Git LFS 或对象存储，
> 那是 v2 再考虑的路线，v1 先用分块把“能传、能拼回”做扎实。

---

## 8. 自定义主题代码：粘贴 HTML / CSS，点应用

### 操作

1. 底部「主题」页，把代码粘贴进输入框。
2. 点「应用到全局」：下面实时预览的按钮、卡片、芯片、进度条会立刻换色，
   整个 App（仓库页、上传窗口、设置页）同步换成这套颜色。
3. 点「恢复默认柔影」：一键回到你给的那套默认主题。
4. 代码存在本机，下次启动自动沿用，不用每次重贴。

### 能解析什么

`ThemeParser` 会先取 `<style>…</style>` 块（没有 `<style>` 就按整段 CSS 解析），
再提取 `:root` 里的变量：

| CSS 变量 | 对应到 App 的哪里 |
|---|---|
| `--bg` | 全局背景、卡片表面 |
| `--txt` | 次要文字 |
| `--txt-d` | 标题、主要文字 |
| `--blue` | 主按钮、进度条、选中态 |
| `--blue-d` | 选中文字、链接色 |
| `--gold` | 点缀色（图标金点、警告类点缀） |
| `--r` | 卡片圆角（`24px` → 24dp） |

示例（直接粘贴你给的那套就能用）：

```css
:root{
  --bg:#e0e8ef;
  --txt:#7e97b8;
  --txt-d:#516d91;
  --blue:#5f9de7;
  --blue-d:#3f7fc4;
  --gold:#f0a93c;
  --r:24px;
}
```

点「应用」后，页面会显示一行摘要，例如：

```
已识别：背景 #E0E8EF · 主色 #5F9DE7 · 深色 #3F7FC4 · 文字 #7E97B8 · 圆角 24px
```

### 诚实说明（重要）

- **原生 Android App 跑不了整页 HTML 布局**。一段 HTML 的 `<div>` 排版、
  动画、伪类，不可能直接变成 Android 界面。
- 所以这里的“全局应用”指的是：**把代码里的设计 token（颜色、圆角）
  提取出来，全 App 的组件换成这套 token**。这是原生 App 里最实在的
  “换主题”方式，也是 Material 主题本身的做法。
- 想要看整页 HTML 效果：把那段 HTML 存成 `.html` 用浏览器打开即可；
  App 里不假装渲染它。

---

## 9. 图标

### 正式图标：SVG（以“软”字为核心）

文件：`icon/ruancang-icon.svg`（512×512，可直接用于网页、README、应用商店素材）

- 外层：柔影凸起 tile（背景 `#E0E8EF`，2px 白描边，左上白高光 + 右下柔影蓝阴影，
  与你给的 HTML 阴影同源）。
- 内层：内凹槽（`inset` 阴影，呼应主题里输入框 / 进度条的凹陷感）。
- 核心：蓝色渐变（`#9CC3EF → #5F9DE7 → #3F7FC4`）的粗体**“软”**字，
  228px，居中。
- 点缀：右下角金色小圆点（`#F0A93C`），取自主题 `--gold`。

### Android 启动器图标

- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`（自适应图标）
  + `app/src/main/res/drawable/ic_launcher_foreground.xml`（蓝色圆 + 白色柔弧 + 金点）。
- **为什么前景里没有“软”字**：Vector Drawable 不支持 `<text>`，
  所以前景目前是主题色占位版。想要带软字形的启动器图标：
  1. 在 Figma / Illustrator 里打开 `icon/ruancang-icon.svg`；
  2. 把“软”字转曲（Create Outlines / 转为路径）；
  3. 用 Android Studio 的 **Vector Asset → Local file** 导入转曲后的 SVG，
     替换 `ic_launcher_foreground.xml` 即可。

---

## 10. 工程结构

```
ruancang-app/
├── README.md                        # 本文件
├── settings.gradle.kts              # 根工程：google() + mavenCentral()
├── build.gradle.kts                 # AGP 8.5.2 / Kotlin 1.9.23（锁定版）
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties   # Gradle 8.7
├── gradlew / gradlew.bat
├── .github/workflows/build-apk.yml # 推 main 自动打 debug APK
├── .gitignore                       # 源码里永远不出现 Token
├── icon/
│   └── ruancang-icon.svg            # 正式图标（软字核心）
├── tools/
│   └── reassemble.py                # 大文件分块拼回工具
└── app/
    ├── build.gradle.kts             # Compose BOM 2024.10.00，compileSdk 34
    ├── proguard-rules.pro           # 不混淆
    └── src/main/
        ├── AndroidManifest.xml     # INTERNET；文件选择走 SAF，不用存储权限
        ├── java/com/ruancang/app/
        │   ├── MainActivity.kt     # 入口：读本机主题代码 → 套全局主题 → 仓库页
        │   ├── model/
        │   │   └── Category.kt     # 类别枚举 + 自动识别 + slug + 文件大小格式化
        │   ├── data/
        │   │   ├── GithubApi.kt    # GitHub REST 最小客户端 + 流式分块上传
        │   │   ├── UploadManager.kt# 上传全流程 + 已安装应用列表 + info.json
        │   │   └── Stores.kt       # 本机设置（Token 只存本机）+ 主题解析 + 主题状态
        │   └── ui/
        │       ├── NeuComponents.kt# 柔影按钮 / 卡片 / 芯片 / 输入框 / 进度条
        │       ├── UploadSheet.kt  # 独立上传窗口 + 已安装应用选择器
        │       └── WarehouseScreen.kt # 仓库 / 设置 / 主题三页 + 底部 Tab
        └── res/
            ├── values/strings.xml  # App 名“软仓”+ 柔影主题
            ├── values/colors.xml   # 与 HTML :root 一一对应的色板
            ├── drawable/ic_launcher_foreground.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml
            └── xml/filepaths.xml
```

---

## 11. 常见问题 FAQ

**Q：上传时报 401 / Bad credentials？**
A：Token 错了、过期了，或是细粒度 Token 没给 `ruancang` 这个仓库的
Contents 读写权限。去 GitHub 换一个新 Token，确认权限后重贴。

**Q：上传时报 404？**
A：仓库名 / 所有者拼错，或 Token 没权限看到这个仓库（私有仓库尤其注意）。
先在「设置」页点「测试连接」。

**Q：上传时报 422 / Invalid request？**
A：多数是同名文件已存在但没带对 `sha`。v1 会先 GET 取旧 `sha` 再 PUT，
正常不会出现；如果还出现，把仓库里那个同名条目改个名再传。

**Q：大文件传到一半断网了怎么办？**
A：已传完的块已经在仓库里了（`parts/part-001…`），重传一次会按块覆盖，
不会从头再算；但 v1 没有“断点续传跳过已传块”的优化，重传会重写所有块。
网络不稳时建议先传小文件验证链路，再传大文件。

**Q：已安装应用列表里找不到某个应用？**
A：先确认它是不是系统应用——默认只列用户应用，点上传窗口应用选择器里的
“含系统应用”再找。Android 11+ 对应用可见性有限制，个别应用可能仍列不出，
那就走「选择文件」直接选它的 APK 文件。

**Q：截图传上去是原图吗？**
A：是，截图按原字节上传，不压缩、不改格式，文件名统一为 `shot-1.jpg`、
`shot-2.jpg`……（按选择顺序）。如果选的不是 JPG，内容仍是原图字节，
扩展名统一写 `.jpg` 只是为了仓库里好认，下载后按实际格式打开即可；
v2 可以按原扩展名保留。

**Q：自定义主题代码粘贴后没变化？**
A：先看「主题」页的摘要行：如果显示“未输入自定义代码”，说明代码没保存；
如果摘要里的颜色和你想要的不一样，检查代码里是不是用了 `--bg` 之外的
变量名（例如 `--background`），解析器只认第 8 节表格里的变量名。

**Q：能把这个 App 的源码也推到 ruancang 仓库吗？**
A：可以，但建议分开：`ruancang` 是**文件仓库**（存上传的文件），
源码另建一个仓库（例如 `ruancang-app`），避免源码和上传的文件混在一起，
也避免 CI 产物、构建缓存污染文件仓库。推源码前务必确认源码里没有 Token
（本工程默认就没有，`.gitignore` 也拦了一道）。

---

## 12. 隐私与安全

- App 只在两种情况下联网：① 读仓库列表；② 上传你亲手选的文件 / 应用 / 截图。
  不上传通讯录、短信、位置等任何无关数据。
- Token、仓库地址、自定义代码只存本机，不上传、不分享。
- 上传到 GitHub 的文件，访问权限 = 该仓库的访问权限：
  **公开仓库 = 全网可见**。传隐私文件前，先确认 `ruancang` 是私有仓库。
- 已安装应用的 APK 提取：只读应用的安装包文件，不读应用内部数据。
- 本工程不收集、不上报崩溃日志和行为统计。

---

## 13. 已知限制 / 下一步

v1 已做扎实的部分：上传窗口、文件 / 已安装应用双入口、截图、
自动分类 + 手动改、实时进度 + 日志、GitHub 同步、分块上传、
自定义主题 token、SVG 图标、完整 README。

v1 明确没做的（不假装已做）：

1. **本机未编译出 APK**：SDK / JDK 本机都有，但 Gradle 守护进程在当前
   运行时环境里无法通信（见第 3 节），APK 请按第 3 节
   用 Android Studio 或 GitHub Actions 打包。
2. **断点续传**：大文件重传会重写所有块，未做“跳过已传块”。
3. **下载 / 合并分块**：下载拼回目前靠 `tools/reassemble.py`（电脑端），
   App 内“从仓库下载并拼回”还没做。
4. **像素级双阴影**：Compose 的 `shadow` 是单向阴影，柔影的
   “左上白 + 右下蓝”双阴影目前用“蓝向阴影 + 白描边”近似，
   像素级复刻需后续用 `drawBehind` 手绘两层阴影。
5. **图标软字形**：Android 前景目前是主题色占位版，带软字形的
   Vector 前景需按第 9 节转曲后导入。
6. **截图原扩展名保留**：目前统一写 `shot-N.jpg`，见 FAQ。
7. **多文件批量上传**：目前一次传一个文件 / 应用（可多张截图），
   批量队列是 v2 的功能。

---

## 版本记录

- **v1.0.0（2026-10-25）**：首版。上传窗口、双入口、截图、自动分类、
  GitHub 实时同步、分块上传、自定义主题代码、NEU 柔影主题、SVG 图标、
  完整 README 与 CI 打包工作流。

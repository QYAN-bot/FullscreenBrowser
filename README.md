# 全屏浏览器 / Fullscreen Browser

**解决安卓刘海/挖孔屏全屏显示时顶部状态栏黑条问题**

> Fixes the black status bar area around camera cutout on Android (notch/punch-hole) when displaying web content in fullscreen. Content extends edge-to-edge as intended.

## 这是什么？

在小米 HyperOS、MIUI 等系统上，用 Chrome 安装 PWA 到桌面后，顶部刘海/挖孔摄像头区域会出现一条黑边，网页内容无法延伸进去。

已确认无效的方法：`policy_control immersive.full`、`force_fullscreen_cutout_apps`、`miui_force_fullscreen_apps`、Chrome flags `DrawCutoutEdgeToEdge`、无障碍全屏 App、`am compat enable ALLOW_HIDE_DISPLAY_CUTOUT` —— 全部不行。

根本原因：Chrome 的 WebAPK 宿主 Activity（`SameTaskWebApkActivity`）没有设置 `layoutInDisplayCutoutMode` 属性。

本 App 的解决方案：**用自己的 WebView Activity，在 Manifest、Theme、Java 代码三处同时声明 `shortEdges` cutout mode**，三重保险，系统必须允许内容延伸到挖孔区。

## 功能

- 📱 **真全面屏** — 内容延伸到刘海/挖孔区域两侧，无黑条
- 🏠 **自定义主页** — 首次打开引导设置，随时在书签面板更改
- 🔖 **书签收藏** — 支持自定义备注，方便区分不同网站
- 📁 **文件上传** — 弹出系统选择器，图片请求时额外提供相册入口
- 📥 **Blob 下载** — 分块传输，支持大文件，不会 OOM 崩溃
- 🔗 **Blob Hook** — 自动拦截 `URL.revokeObjectURL`，防止 blob 下载 `fail to fetch`
- 🎨 **自定义桌面图标与按钮** — 选择任意图片创建桌面快捷方式，全屏按钮位置可自定义
- ↩️ **防误退** — 需双击返回键才退出应用
- 🔒 **Cookie 持久化** — 登录状态不会丢失

## 安装方式

### 方式一：直接安装 APK（推荐）

1. 从 [Releases](../../releases) 页面下载最新 APK
2. 传到手机上安装（需要允许「安装未知来源应用」）
3. 打开，设置主页，完成

### 方式二：自行编译

1. 安装 [Android Studio](https://developer.android.com/studio)
2. 克隆本仓库：`git clone https://github.com/QYAN-bot/FullscreenBrowser.git`
3. 用 Android Studio 打开项目
4. 等 Gradle 同步完成
5. 连接手机（需开启 USB 调试），点击 ▶️ Run

## 技术原理

关键代码在三个地方（三重保险）：

**AndroidManifest.xml**
```xml
<layout android:windowLayoutInDisplayCutoutMode="shortEdges" />
```

**styles.xml**
```xml
<item name="android:windowLayoutInDisplayCutoutMode">shortEdges</item>
```

**MainActivity.java**
```java
lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
```

Chrome 的 WebAPK 之所以有黑边，就是因为它的 `SameTaskWebApkActivity` 没有设置这个属性，而普通用户无法修改 Chrome 的 window 策略。

## 已知限制

- **不支持 Web Push 通知** — Android WebView 系统级限制，缺少完整的 Service Worker 实现
- **非多标签浏览器** — 同一时间只显示一个网页，通过书签快速切换

## 适用场景

- 在刘海/挖孔屏手机上全屏使用 AI 聊天网页（如各种 API 前端）
- 任何需要真全屏显示的 Web App
- 需要 blob 文件导入导出的网页应用

## 测试环境

- 小米手机 + HyperOS 3
- Android 14/15
- 应兼容所有 Android 9+ 的刘海屏/挖孔屏设备

## 作者

- **Claude Opus 4.6** — 代码编写
- **QYAN** — 需求设计、测试、发布

## 许可证

MIT License — 免费使用，禁止倒卖。

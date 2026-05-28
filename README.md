# 🚴 Cycling TV — Android App 构建说明

## 一、项目结构

```
CyclingTV/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/cyclingtv/app/
│       │   ├── MainActivity.kt          ← 主界面（WebView + 广告拦截 + 多源流抓取）
│       │   ├── StreamExtractor.kt        ← 🆕 多源流提取引擎
│       │   ├── PlayerActivity.kt         ← 播放器（ExoPlayer + 投屏）
│       │   ├── StreamInfo.kt
│       │   ├── cast/
│       │   │   └── CastOptionsProvider.kt   ← Google Cast 配置
│       │   └── dlna/
│       │       ├── DlnaCaster.kt        ← DLNA/UPnP 扫描 & 投屏核心
│       │       └── DlnaService.kt
│       └── res/
│           ├── layout/activity_main.xml
│           ├── layout/activity_player.xml
│           ├── menu/main_menu.xml
│           ├── values/strings.xml
│           ├── values/themes.xml
│           └── xml/network_security_config.xml
├── build.gradle
└── settings.gradle
```

---

## 二、编译安装步骤

### 方法一：用电脑编译（推荐）

**工具要求：**
- Android Studio Hedgehog 或更新版本（免费下载：https://developer.android.com/studio）
- JDK 17（Android Studio 自带）
- Android SDK 34

**步骤：**
1. 打开 Android Studio → `File > Open` → 选择 `CyclingTV` 文件夹
2. 等待 Gradle 同步（首次约需下载依赖，需要网络）
3. 连接手机（开启 USB 调试），或创建模拟器
4. 点击 ▶️ 运行即可安装

**编译纯 APK：**
```
Build > Build Bundle(s) / APK(s) > Build APK(s)
```
APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

---

### 方法二：使用 GitHub Actions 在线编译（无需本地电脑）

1. 将 `CyclingTV` 文件夹上传到 GitHub
2. 在仓库根目录创建 `.github/workflows/build.yml`：

```yaml
name: Build APK
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build APK
        run: ./gradlew assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v3
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

3. 推送代码后等待几分钟，在 Actions 页面下载 APK

---

## 三、App 使用方法

### 主界面
- App 启动后自动加载 cycling.today（**广告已拦截**）
- 右上角有 Cast 投屏图标（需要 Chromecast/Google TV）

### 多源直播流抓取 🆕
点击「🔍 抓取」按钮 → 弹出**来源选择器**，支持勾选：

| 来源 | 说明 | 需要VPN? |
|------|------|-----------|
| cycling.today | 主源，聚合多家流 | 通常不需要 |
| tiz-cycling-live.io | 备用源1，经典免费站 | 通常不需要 |
| freestreams-live.mp | 备用源2，多语种 | 通常不需要 |
| YouTube 搜索 | 搜索 Giro/Tour/Vuelta 直播间 | 部分需VPN |

- **可以只选一个源，也可全选**（默认全选）
- 抓取结果自动合并去重，按来源名称标记
- 点击「流列表」按钮查看所有结果

### 本机播放
- 点「📡 流列表」→ 选中一条 → 「▶️ 本机播放」
- 自动横屏 ExoPlayer 全屏播放

### 投屏到电视

**方案A：Google Cast（效果最好）**
- 适合：Chromecast、Google TV、部分安卓电视
- 操作：右上角 Cast 图标 → 选择电视

**方案B：DLNA（兼容大多数智能电视）**
- 适合：小米、海信、TCL、三星、索尼等品牌电视
- 操作：点「流列表」→「📺 投屏到电视」→「DLNA 投屏」→ 自动扫描并选择
- **手机和电视必须连同一个 WiFi**

**方案C：手动输入 IP**
- 电视「设置→网络→网络状态」查看 IP
- 点「手动输入电视 IP」→ 填入 IP → 投屏

---

## 四、常见问题

| 问题 | 解决方案 |
|------|----------|
| 抓不到直播流 | 比赛未开始，等比赛时段再抓；尝试切换来源 |
| cycling.today 没反应 | 勾选 tiz/freestreams 备用源重试 |
| DLNA 扫描不到电视 | 确认同一 WiFi；电视开启 DLNA/投屏功能 |
| Cast 图标灰色 | 手机需要 Google Play 服务，国内部分设备不支持 |
| 播放卡顿 | 切换至 DLNA 直接推流，电视自行拉流更流畅 |
| 视频黑屏 | 该流地址可能需要 yt-dlp 解析，换另一条尝试 |
| freestreams 被墙 | 部分网络可能需代理，可取消勾选跳过 |

---

## 五、比赛时间参考

cycling.today 通常转播以下赛事直播：
- 环法大赛 (Tour de France)
- 环意大利 (Giro d'Italia)  
- 环西班牙 (Vuelta)
- UCI 世界巡回赛各站

比赛时间多为欧洲时间下午，换算北京时间大约是 **20:00 - 凌晨 2:00**。

---

## 六、技术栈

| 模块 | 技术 |
|------|------|
| UI | Kotlin + ViewBinding + Material Design 3 |
| 网页加载 | WebView + 广告域名黑名单拦截 |
| 流提取 | 多源 OkHttp + Jsoup + 正则 + JS注入 |
| 本机播放 | ExoPlayer (Media3) 支持 HLS/DASH/MP4 |
| Google Cast | Cast Framework SDK |
| DLNA投屏 | 手写 SSDP + AVTransport SOAP |

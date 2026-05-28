# 🚴 Cycling TV v2 — 多源直播流抓取

> 专为国产安卓手机打造的自行车赛事直播观看 & 投屏工具

## v2 新特性

- **6 源并发抓取**：cycling.today / tiz-cycling-live / freestreams-live / steephill.tv / cyclingfans.com / YouTube
- **mindsleep.net 解码**：自动穿透 iframe → 双层 base64 混淆解码 → 提取真实 HLS 流
- **源状态面板**：🟢成功 / 🟡无信号 / 🔴失败，一目了然
- **自动抓取**：启动后自动执行多源抓取，下拉刷新重新抓取
- **深色主题**：Material Design 3 暗色 UI，对话框统一风格

## 支持来源

| 来源 | 类型 | 抓取策略 |
|------|------|----------|
| cycling.today | 主源 | iframe → mindsleep.net → _econfig 双层base64解码 |
| tiz-cycling-live.io | 备用 | 赛事链接 → iframe嵌套 → 正则+DOM+econfig |
| freestreams-live | 备用 | 赛事列表 → 详情页 → iframe → 正则匹配 |
| steephill.tv | 聚合 | 赛事列表 → 外部链接浅抓 → 正则 |
| cyclingfans.com | 聚合 | 外部链接遍历 → 正则匹配 |
| YouTube | 搜索 | 3组关键词搜索 Giro/Tour/Vuelta 直播 |

## 编译 & 安装

### 环境要求
- Android Studio Hedgehog+ 
- JDK 17
- Android SDK 34
- 手机 Android 8.0+ (API 26)

### 编译步骤
1. Android Studio → `File > Open` → 选择 `CyclingTV` 文件夹
2. 等待 Gradle 同步（首次需下载依赖）
3. 连接手机（USB调试已开启）
4. 点击 ▶️ 运行

### 打 APK
```
Build > Build Bundle(s) / APK(s) > Build APK(s)
```
输出：`app/build/outputs/apk/debug/app-debug.apk`

### GitHub Actions 在线编译
仓库已包含 `.github/workflows/build.yml`，push 后自动构建并产出 APK。

## 使用方法

1. **启动 App** → 自动加载 cycling.today 网页（广告已拦截），同时后台多源抓取
2. **顶部状态条** → 🟢🟡🔴 显示各源抓取状态
3. **点「多源抓取」** → 重新并发请求 6 个源
4. **点「流列表」** → 查看所有发现的直播流，按来源标记
5. **选一条流** → ▶️本机播放 / 📺投屏到电视 / 📋复制链接

## 投屏

| 方式 | 适用设备 | 操作 |
|------|----------|------|
| DLNA 扫描 | 小米/海信/TCL/索尼/三星等 | 选流 → 投屏 → 自动扫描 |
| 手动 IP | 任何 DLNA 电视 | 查看电视IP → 手动输入 |

手机和电视必须连接 **同一 WiFi**。

## 比赛时间参考

欧洲时间下午 → 北京时间约 **20:00 - 凌晨 2:00**
- 环意 (Giro d'Italia) — 5月
- 环法 (Tour de France) — 7月
- 环西 (Vuelta a España) — 8-9月

## 技术栈

| 模块 | 技术 |
|------|------|
| 语言 | Kotlin 1.9 |
| UI | ViewBinding + Material Design 3 |
| 网页 | WebView + 广告域名拦截 |
| 流提取 | OkHttp 4 + Jsoup + 正则 + Base64解码 |
| 播放 | ExoPlayer (Media3) HLS/DASH/RTSP |
| DLNA | 手写 SSDP + AVTransport SOAP |

## 常见问题

| 问题 | 解决 |
|------|------|
| 所有源都无流 | 比赛未开始，等比赛时段再试 |
| cycling.today 失败 | 可能是 mindsleep.net 换了混淆方式，切换到备用源 |
| YouTube 无结果 | 需要 VPN；可取消勾选该源 |
| DLNA 扫描不到 | 确认同一WiFi；电视开启投屏/DLNA功能 |

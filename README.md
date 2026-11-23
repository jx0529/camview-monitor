# 多路监控 CamView Monitor（Android）

把原先 Windows 上那套 Tkinter + OpenCV 的多摄像头 RTSP 监控系统，重做成 **手机原生 Android 应用**。
保留原有全部功能，并把"GPU 加速"换成手机上真正有意义的方案：**MediaCodec 硬件解码（GPU）+ GPU 纹理上屏**。

- 安装包：`release/camview-monitor-1.0.0.apk`（或 GitHub Releases 下载）
- 包名：`com.camview.monitor`
- 支持系统：Android 7.0（API 24）及以上

## 为什么手机端不卡

| 桌面版做法 | 手机版做法 |
|---|---|
| 9 宫格同时解码 9 路 | **单列布局，一行一路**，一屏 2~4 路 |
| 全部摄像头常驻解码 | **懒加载**：只有出现在屏幕上的摄像头才启动解码，滚出屏幕自动暂停，暂停超过 8 秒回到屏幕时重连，保证看到的是最新画面 |
| CuPy / OpenCV OpenCL 做缩放、转色 | **MediaCodec 硬件解码**（H.264/H.265 解码在 GPU 上完成），画面通过 TextureView（GPU 纹理）上屏，CPU 占用很低 |
| Python 线程 + GIL | 原生播放器 + 硬件管线，无 GIL |

设置里还可以选「自动（硬件优先）」「强制硬件解码」「软件兼容」三种模式。

## 功能对照

| 桌面版功能 | 手机版 |
|---|---|
| 添加单个摄像头 | 📷 摄像头 → 添加单个（IP / 用户名 / 密码，留空用默认） |
| 批量添加（IP 段） | 批量添加，支持 `192.168.31.88-120`、`192.168.1.1-254`、`10.0.0.0/24`，逗号分隔多个 |
| 扫描网段（554 端口） | 🔍 扫描网段，支持手动网段与本机网段自动探测，32 线程并发探测，进度实时显示 |
| 删除摄像头（单个/批量） | 长按某一路 → 删除；或 📷 摄像头 → 删除摄像头（多选） |
| 分组增删改、移动、默认分组保护 | 📁 分组（点顶部当前分组名进入）：切换 / 新建 / 重命名 / 删除；长按某一路或菜单里移动到分组 |
| 排序（当前组 IP / 所有组 IP / 分组名） | 🔃 排序 |
| 分页（4/6/9/12） | 顶部 ◀ 第 x/y 页 ▶，每页数量在设置里改 |
| 每路旋转 0/90/180/270、水平/垂直翻转 | 长按某一路 → 旋转画面 / 水平翻转 / 垂直翻转（按摄像头持久保存） |
| 放大窗口，可拖动画面 | 点击某一路进入全屏，支持 **双指缩放、拖动平移、双击还原**，旋转翻转同步生效 |
| 显示 FPS、状态点、在线数统计 | 每行显示 IP、FPS、状态点；底部状态栏显示解码方式、活跃数、总数、时钟 |
| 系统设置（用户名/密码/超时/模板/每页/GPU/硬件解码） | ⚙ 设置：全部保留，另加 RTSP over TCP、自动检查更新 |
| 重启所有摄像头 | 🔄 重启 |
| 配置文件持久化 | 仍是 `camera_system_config.json`，字段与电脑端一致，可从「更多 → 导入电脑端配置」直接迁移 |
| 深色主题 | 同一套配色（#0D1117 / #161B22 / #58A6FF / #238636） |

## 构建

需要 JDK 17 + Android SDK（compileSdk 36）。

```bash
git clone https://github.com/jx0529/camview-monitor.git
cd camview-monitor
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

- 仓库里带的是签名证书的 base64 文本（`keystore/camview.jks.base64`），构建时会自动还原成 `keystore/camview.jks`，**请勿更换证书**，否则已安装的 App 无法覆盖升级（只能卸载重装）。
- 用 Android Studio 打开直接 Build 也行（会自动下载 Gradle）。
- 命令行首次构建如果提示找不到 Gradle，执行一次 `gradle wrapper --gradle-version 8.14.3` 或在 Android Studio 里同步一次。

## 在线更新（GitHub）

App 内置更新检查，来源是 GitHub：

1. `设置 → 在线更新` 里填 `owner/repo`（默认已填 `jx0529/camview-monitor`）。
2. 每次检查优先读仓库 `update.json`，没有则读 **Releases 的 latest**，取里面的 `.apk` 资产。
3. 发现新版本 → 弹窗显示更新说明 → 下载 → 调起系统安装（首次需要允许"安装未知应用"）。
4. 国内访问 `raw.githubusercontent.com` 慢时会自动切 jsDelivr 镜像。

**发新版本的流程**

```bash
# 1. 改版本号
#    app/build.gradle: versionCode +1, versionName "x.y.z"
./gradlew assembleRelease

# 2. 建 Release 并上传 APK（保留所有历史 Release）
gh release create v1.0.1 app/build/outputs/apk/release/app-release.apk \
   --title "v1.0.1" --notes "更新说明"
```

也可以用 `update.json` 方式（无需 Release）：把 APK 以 base64 文本提交到仓库，然后改 `update.json` 的 version/notes/apk_path。

## 常见问题

- **画面连不上**：先确认手机和摄像头在同一局域网；RTSP 路径不同品牌不一样，默认模板已按海康/大华/ONVIF 的顺序自动尝试，连不上会依次换模板。可在设置里增删模板。密码里有 `@ : /` 等特殊字符时，建议改用摄像头的另一组账号或先改简单密码。
- **卡顿**：优先用摄像头的**子码流**（模板 102 / subtype=1，一般 640×360 左右），或在摄像头后台把子码流分辨率调低；手机端可把每页数量调到 4，减少同时解码路数。
- **黑屏但已连接**：部分摄像头是 H.265，老机型硬解不支持，把解码模式切成「软件兼容」。
- **升级失败/解析包错误**：说明签名和已安装版本不一致，卸载旧版再装；正常用 Release 里的 APK 不会出现。

## 目录结构

```
app/src/main/java/com/camview/monitor/
  App.java              配置单例
  AppConfig.java        配置读写 / 分组 / 分页 / 导入（兼容电脑端 JSON）
  CameraInfo.java       单路摄像头数据
  CameraStream.java     ExoPlayer(RTSP) 封装：硬件解码、FPS 统计、模板轮换、自动重连、旋转翻转
  CameraRow.java        列表一行（懒加载/自动暂停）
  MainActivity.java     主界面：工具栏、单列列表、分页、状态栏
  FullscreenActivity.java  放大画面（缩放/拖动）
  SettingsActivity.java    设置
  ScanActivity.java     网段扫描
  NetUtil.java          IP 段解析 / 本机网段探测
  UpdateChecker.java    GitHub 在线更新
  Ui.java               深色弹窗等 UI 小工具
```

## License

个人使用项目。

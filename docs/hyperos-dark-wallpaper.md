# HyperOS 4 深色模式壁纸调暗

2026-11-23 在小米 17 Pro（`pandora`、`OS4.0.8.0.XBLCNXM`、Android 17）实测。
桌面为 `801030308`，小米壁纸为 `7.1.9.1-ALPHA-11022109`，主题壁纸为
`11.5.6.0`。当时桌面绑定小米 `ImageWallpaper`，锁屏绑定
`MiuiKeyguardPictorialWallpaper`。以下结论对应这些实机版本和当前壁纸。

## 设置入口和旧机制

`Settings.apk` 仍有 `string/dark_wallpaper_mode`（`0x7f151061`），文案是
“深色模式下调暗壁纸”。`DarkModeActivity` 打开的 `DarkModeFragment`
加载 `res/xml/dark_mode_settings.xml`，其中有定时切换和应用列表。资源 ID
和旧开关键的交叉引用检查也未找到把该选项加回深色模式页的代码。

旧键是当前用户的 `Settings.System.darken_wallpaper_under_dark_mode`。
小米壁纸服务启动时读取它（缺省为 `1`），并注册 `ContentObserver`。
实机的内容服务列表也能看到小米壁纸和桌面等进程的订阅。写入该键时，
小米壁纸服务中的 `mDarkModeDimmingWallpaper` 会更新。

旧的通用图片调暗逻辑有额外的系统版本判断：

1. 桌面 `DesktopImageEngineImpl.Y(Bitmap)` 和锁屏
   `KeyguardImageEngineImpl.Y(Bitmap)` 调用 `WallpaperServiceController.F(which)`。
2. `F` 对没有选中 `dark` 图源的场景检查 `MiuiWallpaperManagerService.C()`。
   `C` 只有在旧开关开启且 `SystemBuildUtil.f()` 返回 `false` 时才返回 `true`。
3. `SystemBuildUtil.f()` 检查 `ro.mi.os.version.code >= 1`。实机属性为 `4`，
   因此改旧键无法让普通桌面或锁屏壁纸走这条调暗分支。

这说明当前构建移除了设置入口，并通过版本判断停用了普通壁纸的旧调暗
分支。判断阈值为 `1`，仅凭该构建无法确定入口或行为最早在哪个版本改变。

## 仍存在的路径

- **主题和动态壁纸。** `WallpaperServiceController` 会区分 `dark` 图源，
  主题壁纸仍有自己的 `darken_bitmap` 路径。MAML 仍能读取旧键并向组件传递
  深色壁纸标志。超级壁纸的主题侧代码另有 `dark_wallpaper_mode_enable`。
  本次未替换在用壁纸，未验证各个主题、动态壁纸和 AOD 的可见效果。
- **Android 的壁纸调暗。** `cmd wallpaper get-dim-amount` 可读到平台调暗
  强度，`set-dim-amount` 可独立改变它。小米壁纸的 `UniversalEngine`
  仍实现 `onDimAmountChanged(float)`。对照实验中，这条路径能实际调暗
  当前桌面和锁屏。命令直接指定强度，深浅色模式自动跟随需要另外接入。
  [AOSP WallpaperManagerShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/wallpaper/WallpaperManagerShellCommand.java)

## 实机对照

每个画面的比对区域均排除顶端 130 像素和底端 115 像素，共 2,941,420 像素。
平均 RGB 统计覆盖整块区域，包括区域内的图标或锁屏文字。

| 操作 | 桌面 | 锁屏 |
| --- | --- | --- |
| 深色模式下将旧键切换 `0 → 1 → 0` | 像素完全一致 | 像素完全一致 |
| 将平台 `dim amount` 从 `0` 改为 `0.25` | 比对区域平均 RGB 从 118.50 降至 97.29 | 从 125.13 降至 106.34 |
| 将平台 `dim amount` 恢复为 `0` | 与同组基准像素一致 | 与同组基准像素一致 |

额外在旧键为 `1` 时切换 `深 → 浅 → 深`，确认壁纸服务检测到了模式变化，
且回到深色模式后的锁屏比对区域与旧键设为 `0` 时像素一致。
实机最终恢复原来的深色模式、未写入旧键的状态、平台调暗 `0.0`
和原来的深色模式设置页。

原始截图、像素比对、实机状态、APK 校验和及关键方法反汇编保存在
`.artifacts/research/hyperos4-dark-wallpaper-20261123/`。

## KeiHome 恢复开关

在「桌面 → 桌面外观」开启「深色模式下调暗壁纸」，也可以从设置搜索进入。
默认关闭，需要 LSPosed 的系统框架作用域。设置经 API 102 远程偏好写入，
由系统进程监听。切换深浅色或更换壁纸时，系统控制线程重新读取当前状态，
并且只在 KeiHome 的调暗请求需要改变时调用壁纸服务。

实机的 Android 17 壁纸服务以 `setWallpaperDimAmountForUid` 维护各 UID 的
调暗请求。KeiHome 对机主用户在深色模式下请求 `0.25`，切回浅色或关闭
开关时只删除自己 UID 的请求。平台取各请求的最大值，两个请求同时存在
时的实际强度为 `max(系统或其他应用的请求, 0.25)`。KeiHome 不写旧的
`darken_wallpaper_under_dark_mode`，用户的主题仍可独立选择深色图源。

签名 Release b0738（`114000738`）覆盖安装后，实机的系统进程继续使用
PID 4013。桌面和锁屏均检查了开关开启、切回浅色、重新进入深色和深色中
关闭开关。设置搜索输入 `wallpaper` 也能找到该开关。

通过另一个 UID 模拟平台已有 `0.12` 和 `0.40` 的调暗请求，两次开启后
实际强度分别为 `0.25` 和 `0.40`。浅色或关闭开关时，KeiHome 的 UID
从平台请求列表删除，`0.40` 继续保留，撤掉模拟请求后回到 `0.0`。
桌面和锁屏截图、覆盖安装及请求列表保存在
`.artifacts/research/wallpaper-dimming-20261123/`。

## 分区强度与景深锁屏

「桌面外观 → 深色模式下调暗壁纸 → 调暗强度」打开底部面板。桌面和锁屏
各自保存 `0%–100%` 的强度，沿用原来 `25%` 的默认值。拖动即时更新预览，
远程偏好写入合并连续值并保留最后一次选择。预览并排显示当前静态壁纸和
该强度下的效果，动态壁纸无法提供静态图时显示标明的示例。

支持分区的系统在 `DisplayConnector.applyDimming` 处按壁纸引擎的类型应用
用户强度，继续走原生引擎的渲染与动画。共用一个引擎时跟随系统锁屏状态
选取强度。分区实现将旧版 KeiHome 的请求从平台 UID 表移除，保留其余
请求；每个引擎取平台强度与该区强度的较大值。缺少分区接口的系统继续
使用平台全局调暗，面板只显示一套强度。

HyperOS 的景深人物由 SystemUI 单独绘制在壁纸表面上方。锁屏套用同一强度，
只降低分割人物的 RGB，人物透明边缘、时钟及其遮挡关系继续由 HyperOS
绘制。系统夜间模式变化和人物图片重新绑定时均重新对齐强度。热重载清理
上一代持有的人物滤镜、动画及监听。

`b0741`（`114000741`）在同一台实机覆盖安装，LSPosed 日志确认框架
（PID 4013）和 SystemUI（PID 6340）接管新版。桌面设 `18%` 时，
同一壁纸区域的像素通道中位数为原图的 `0.819`。锁屏设 `55%` 时，
背景为 `0.452`，脸部、头发和肩部为 `0.452–0.453`。系统请求
`40%` 时只提高桌面的实际调暗程度，锁屏维持 `55%`。请求 `64%`
时锁屏背景和人物一同跟随。浅色、关闭开关以及仅将锁屏调为 `0%`
都恢复了对应区域的原始亮度。

实机录制从 `25%` 拖至 `73%` 的过程，松手前调暗预览已连续变化，
左侧原图保持同一亮度。测试后将两区强度恢复为 `25%`。

实机截图、取样位置与热重载记录保存在
`.artifacts/research/wallpaper-depth-20261123/`。设备使用独立的桌面、
锁屏引擎，公共引擎和动态壁纸示例由兼容分支处理。

### 调暗强度面板切换

预览与滑块从面板打开时就占据固定位置。两张壁纸在同一次打开期间预读并
复用。切换到尚在读取的标签时保留已经显示的预览，目标图就绪后淡入；
较慢的读取和重试提示显示在预览区域内。快速反向切换沿用当前动画进度。

在同一台小米 17 Pro 对照 `b0741` 与 `b0742`。旧版首次切换约 `130 ms`
内收起预览后重新展开。新版连续切换及快速反向切换时，录像中预览始终
占据原来的位置；桌面 `18%` 和锁屏 `67%` 的滑块坐标相同，数值随标签
切换。两区测试后恢复为 `25%`。对照录像和逐帧截图保存在
`.artifacts/research/wallpaper-depth-20261123/`。

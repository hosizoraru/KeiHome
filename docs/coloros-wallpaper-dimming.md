# ColorOS 17 的深色模式壁纸调暗

2026-11-24 在 `ColorOS17_API37`（`emulator-5574`、Android 17、ColorOS 17.0、
`C.2161edb_4b876b_4b876a`）实测。设置版本为 `17.0.0`，桌面为 `17.3.11`，
壁纸选择器为 `17.0.22`。用户 0 的桌面和锁屏共用 `Mars1VideoWallpaper`
动态壁纸引擎，`dumpsys wallpaper` 显示 `mWhich=3`。

## 设置到渲染

1. `Settings.apk` 的 `DarkModeSettingUtils.setWallpaperDarken` 为当前用户写入
   `Settings.Secure.oplus_customize_settings_dark_wallpaper`，并通知对应的 URI。
2. `oplus-framework.jar` 的 `OplusWallpaperServiceHelper` 监听该 URI，遍历活动引擎，
   调用它们的 `SurfaceHolder.onUpdateSurface()`。刷新窗口时，`WallpaperService.Engine.updateSurface`
   调用 `WallpaperManagerExtImpl.getDarkModeWallpaperWindowAlpha`，将结果写入壁纸窗口的
   `WindowManager.LayoutParams.alpha`。
3. 普通活动引擎读取自己的合并配置。`uiMode` 为深色且开关开启时取 `0.76`，
   其余状态取 `1.0`。预览引擎沿用窗口原有 alpha，声明 `supportLocalDarkMode()` 的引擎
   由扩展返回 `1.0`。本次在设备上确认了普通活动引擎及同款壁纸预览。

Android 的平台调暗经 `WallpaperManagerService.DisplayConnector.applyDimming` 传到引擎，
由 `WallpaperService.Engine.updateWallpaperDimming` 调节内层 `Wallpaper BBQ wrapper`
的 alpha。当前 ColorOS 构建的壁纸窗口位于该层外侧，两层同时生效时 alpha 相乘。
平台调暗动画和内层 surface 的关系也可见于
[AOSP WallpaperService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/service/wallpaper/WallpaperService.java)。

## AVD 实测

| 画面 | 原生开关 | 平台调暗请求 | 外层 alpha | 内层 alpha |
| --- | --- | ---: | ---: | ---: |
| 深色桌面及锁屏 | 关 | 0 | 1 | 1 |
| 深色桌面及锁屏 | 开 | 0 | 0.760 | 1 |
| 深色桌面 | 开 | 12% | 0.760 | 0.880 |
| 深色桌面 | 开 | 40% | 0.760 | 0.600 |
| 熄屏唤醒后停在深色锁屏 | 开 | 40% | 0.760 | 1 |
| 锁屏保持原状，重发 40% | 开 | 40% | 0.760 | 1 |
| 锁屏保持原状，改发 41% | 开 | 41% | 0.760 | 0.590 |
| 从深色切到浅色桌面，保留平台请求 | 开 | 40% | 1 | 1 |
| 浅色桌面及锁屏 | 开 | 0 | 1 | 1 |
| 从浅色切回深色桌面 | 开 | 0 | 0.760 | 1 |
| 深色下进入同款动态壁纸的系统预览 | 开 | 0 | 1 | 1 |

两张深色开关对照图中，避开桌面控件和锁屏时钟，对同一片壁纸的 RGB 通道逐像素取比，
桌面中位数为 `0.755`，锁屏为 `0.754`。桌面上的原生 `24%` 和平台 `40%`
叠加后，壁纸层的合成 alpha 约为 `0.76 × 0.60 = 0.456`。

两轮熄屏唤醒都复现了平台请求仍报 `40%`、内层 alpha 已回到 `1` 的情况。
第二轮等待约 2 秒并重发原值，内层仍保持 `1`。内层层号始终为 `#43`，
目前只确认了 alpha 被重置，具体写入方仍待定位。框架引擎存在同值提前退出，
服务端也会按已有请求和聚合值判断是否派发。仅重发同一数值无法作为恢复成功的证据。

设置页遮住壁纸时，开关值先变化，隐藏窗口的 SurfaceFlinger 快照可能还显示旧 alpha。
回到实际桌面后，窗口才确认使用对应的值。系统预览同时有两个引擎，原来应用的壁纸
仍为 `0.760`，预览为 `1.0`。

## 对 KeiHome 的改进方向

- **优先验证共用动态壁纸在锁屏切换时的第一帧和最终状态。** KeiHome 已监听引擎
  `attachEngine`，桌面与锁屏强度不同时也会在锁屏切换后请求重派发。
  已有验收覆盖独立桌面和锁屏引擎。
  下一轮使用同一张动态壁纸，先把桌面和锁屏都设为 `25%`，检查熄屏唤醒时相同强度
  能否保住，再设为 `18%` 和 `55%` 检查分区切换。配对记录设定值、当前可见壁纸层的
  alpha 和可见画面，覆盖锁屏出现、解锁、深浅色往返及更换壁纸。
- **把恢复绑定到已确认会重置调暗的渲染事件。** 同一台小米实机留存的
  `system-framework.jar` 也有 `mPreviousWallpaperDimAmount` 同值短路，
  `initializeSurface` 没有把已保存的调暗量写入新建的内层。若 HyperOS 上复现，
  先确认是同一个 surface 的 alpha 被重置，还是出现了新的 surface，再选相应回调。
  若要让有效目标值相同时也能恢复，缓存需能区分数值相同和当前层已应用，
  并验证动画结束后旧回调不会覆盖新状态。
- **维持单一有效强度和预览语义。** KeiHome 当前取 `max(平台请求, 分区强度)`，
  同时保留桌面和锁屏独立调节。以后若验证了更合适的窗口层，需把平台已经应用的 alpha
  计入结果，避免平台和模块各做一次完整调暗。系统壁纸选择器应继续展示原图，
  模块强度面板继续展示原图与调暗效果，景深锁屏的人物与背景应使用一致的有效强度。

后续在 HyperOS Phone AVD 上用小米共用动态壁纸复测，未观察到同值调暗丢失，
并把分区调暗移到锁屏切换的派发路径，见
[共用动态壁纸的锁屏派发](hyperos-dark-wallpaper.md#共用动态壁纸的锁屏派发)。
ColorOS 的静态壁纸、AOD、独立锁屏壁纸和本地深色引擎分支的可见效果尚未实测。
测试结束后，ColorOS 回到深色模式设置页，
原生开关为 `0`，平台请求为 `0.0`，壁纸 ID、组件和息屏超时均恢复到测试前的值。

## ColorOS 实机和 HyperOS 移植

在有线 OnePlus PJZ110 上核对了 ColorOS 17.0.0
`V.569eeef-2fa606a-2fc7f15` 的 Settings、`oplus-framework` 和
`oplus-services`。实机依次点击增强、适中、柔和，`DarkMode_BackgroundMaxL`
读到 `0 / 8 / 20`，对话框背景上限和前景下限保持 `27 / 100`。测试后恢复了
增强档，壁纸调暗与低亮度对比度仍为设备原先启用的状态。

- 「设置 → 主题设置」里的 KeiHome 暗色风格继续使用原有应用偏好，调整
  KeiHome 的 MIUIX 深色配色。
- 「桌面 → 深色模式设置」里的系统暗色风格由系统框架作用域管理。增强档恢复
  HyperOS 原色，适中和柔和以 ColorOS 的背景明度为参考，通过 Android 运行时
  资源覆盖修改框架深色表面、HyperOS 设置和通知背景。覆盖仅匹配夜间配置，
  与 KeiHome 应用配色独立。使用 Android 17、Xiaomi 系统和主用户，具体资源须
  由当前系统提供。采用自身配色的应用仍使用其主题。
- ColorOS 壁纸档在 HyperOS 复用已验证的系统壁纸调暗链路，以 24% 为桌面和
  锁屏的目标，仍尊重更高的平台调暗请求。HyperOS 档保留原先的分区值和预览。
- ColorOS 的 `OplusDarkModeMaskManager` 根据面板亮度管理显示遮罩。HyperOS
  移植在主显示屏点亮、系统深色且亮度低于 20 尼特时加入黑色合成层，最大
  alpha 为 0.30，并向阈值平滑衰减。20 尼特阈值和衰减曲线是移植参数。

在 Xiaomi 17 Pro `OS4.0.8.0.XBLCNXM`、Home `801030308`、LSPosed 7953
上验收了手机布局。打开、返回「深色模式设置」下的状态栏反色页，切换了
暗色风格和两种壁纸档。桌面同一块亮色壁纸的归一化灰度均值从关闭时
`0.825` 降到 ColorOS 档的 `0.768` 和原 HyperOS 档的 `0.765`。桌面壁纸
与图标布局保持原样。遮罩在约 `1.10` 尼特时实际创建，SurfaceFlinger
读到 alpha `0.283`，升到 `26.26` 尼特后移除。亮度和调暗设置随后恢复。
这次没有测量锁屏实际像素、息屏、横屏或宽屏布局。证据保存在
`.artifacts/research/physical-coloros-dark-mode-20261125/`。

### 系统暗色风格真机复核

同一台 Xiaomi 17 Pro 原位安装了 KeiOS 签名 Release `115000791`。API 102
热重载后，从 KeiHome 切换系统三档，HyperOS「显示与亮度」同一区域的实际
底色依次为 `#000000`、`#181818`、`#303030`。系统通知背景资源也随档位
变化，通知栏截图可见底色变化。柔和档和增强档下的浅色模式底色均为
`#F3F3F3`。测试结束后恢复原来的系统深色模式和增强档，三个覆盖全部注销。
系统服务和桌面进程保持原 PID。覆盖跨重启的持久化、其他 HyperOS 版本和
Pad 尚未实测。截图、资源解析、安装签名和热重载证据位于上方目录的
`system-style/`。

### 覆盖范围与逐应用 Force Dark

控制中心的玻璃、锁屏以及桌面的文件夹、应用抽屉和最近任务继续使用 HyperOS
自身配色。调查中的临时系统资源覆盖和测试通知已从实体机移除。

同一台 HyperOS 实体机的 `ForceDarkHelperStubImpl.applyDarkRules` 把屏幕密度
和 `mainRule`、`secondaryRule`、`tertiaryRule` 三组整数传给
`MiuiForceDarkConfigManagerImpl.setConfig(FIII)`，再交给原生渲染器。
软件画布的矩形反色由 `mainRule & 0x200` 启用，亮度使用固定的 CIELAB
变换。已检查的配置链路没有 ColorOS 那种独立的背景明度参数，因而没有把这三组
兼容规则映射成「增强／适中／柔和」。硬件渲染未用第三方应用做三档像素测试。
框架解码、设备规则和清理回执在
`.artifacts/research/hyperos-system-dark-surfaces-20261125/`。

2026 年 11 月 25 日确认保留这些场景各自的原生暗色表现。实体 Xiaomi 17 Pro
运行 `OS4.0.8.0.XBLCNXM` 与桌面 `801030308`。SystemUI 和控制中心插件的
临时资源覆盖可分别注册，但覆盖注册只验证了资源入口，未建立各场景三档配色的
视觉一致性。逐应用 Force Dark 三组规则继续交由 HyperOS 处理。这次调查结束时，
两份临时覆盖和测试通知均已清理，系统深色模式仍为开启。

原始证据位于 `.artifacts/research/coloros17-wallpaper-dimming-20261124/`。其中
`runtime/layer-matrix.txt` 保留每次采样的层号和 alpha，`runtime/comparison.jpg`
按桌面关、桌面开、锁屏关、锁屏开、系统预览排列。设备输出、原尺寸截图、APK/JAR
哈希及关键方法解码分别位于该目录和 `runtime/`、`dex/` 子目录。
`input-paths.txt` 记录原设备路径，可从同一构建复取并按 `sha256.txt` 对照。

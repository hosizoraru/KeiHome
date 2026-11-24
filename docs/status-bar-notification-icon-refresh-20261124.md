# 状态栏通知图标刷新链路研究（2026-11-24）

## 现状链路

HyperOS 4.0.31.0.XFRCNXM 的 `MiuiSystemUI.apk`（SHA-256
`067556deaa7ad2791debcef6a13903a8f6aa76b79a29a9c3a7aea9107ec5672b`）在
`com.miui.systemui.graphics.AppIconsManager` 中同时保留
`getAppIconBitmap(user, package)`、`getAppIconBitmapCache(key, user, quiet)`、
`loadAppIcon(...)` 和 `onReceive(...)`。状态栏小图标在
`StatusBarIconView.updateDrawable(boolean)` 中重新读取 `StatusBarIcon` 的
`Icon`，并按系统的 drawable、tint 和动画逻辑绑定。通知卡片和状态栏图标由不同的
producer/cache 提供，不能用通知面板截图代替状态栏验证。

KeiHome 的状态栏 hook 只在首次 producer 调用时认证原始 `Icon` 和 package/user，
随后把已显示的最多 64 个 `ImageView` 记录在弱引用表中。主题归档由
`/data/system/theme/icons` 提供，缓存读取和 ZIP 解码在线程池完成，主线程只做
`updateDrawable(false)` 重绑。之前的缺口是归档被原子替换或删除时，已有绑定可能
继续持有旧 `Bitmap`。系统配置切换也可能只更新 `StatusBarIconView` 的尺寸和 tint，
不会自动让 KeiHome 的主题快照失效。

## 本次修复

- 为 SystemUI 专用 `CrossSurfaceIconCache` 增加父目录 `FileObserver`。监听归档的
  `MOVED_TO`、`MOVED_FROM`、`CLOSE_WRITE` 和 `DELETE`，不在 hook 热路径中读取磁盘。
- 把配置、`ui_night_mode`、主题广播和文件事件合并到 64 ms 的单次刷新。刷新会递增
  generation、清空内存快照并按原有 wanted 集合排队，只处理仍被追踪的可见通知。
- 启用 miss 通知。主题包暂时没有某个应用图标时，已经使用 KeiHome 快照的绑定会
  回退到 SystemUI 原生 `Icon`，归档恢复后再通过同一 `updateDrawable(false)` 路径
  取回主题图标。未曾使用主题快照的绑定不会被无条件改写。
- 状态栏 producer 生成描述符时不再把同一请求线程中的主题快照再次喂回
  `AppIconsManager`。这样 native producer 负责系统 drawable 的 fallback，避免旧快照
  在一次描述符创建中重新获得优先级。
- 原有 64 条绑定上限、弱引用、generation 检查和异步 ZIP 读取保持不变。常态没有
  新的轮询或跨进程查询，主题事件合并后最多增加一次刷新和受限的可见通知重绑。

## 验证与边界

已完成源码编译、`SystemUiStatusBarIconProfileTest`、
`SystemUiStatusBarOwnerPriorityTest` 和包含新增状态栏回退测试的
`SystemUiStatusBarIconRuntimeDeviceTest` 编译，Gradle 任务成功。签名版本
`115000751` 已安装到 HyperOS Phone AVD `emulator-5580`，Java/API 102 热重载的
10 个已观察目标全部成功，部署记录位于
`.artifacts/research/status-bar-refresh-20261124/deploy/`。

设备上的 instrumentation 未执行成功。当前 release APK 使用正式签名，而现有
debug test APK 与目标签名不一致，Android 拒绝启动测试包。该失败不影响源码和
release 构建结果，但没有形成存量真实通知的像素级结论。用户反馈也尚未提供日志，
所以报告设备上的具体通知、图标包和模式仍待匹配。

建议收集一次最小日志样本，包括系统版本、图标包包名/版本、切换前后浅深模式、通知
来源包名，以及 `KeiHomeSurfaceIcons` 和 `KeiHomeSystemUiIcons` 的同一时间段日志。
这足以区分归档未更新、已有 view 未重绑、native producer fallback，还是仅有状态栏
前景 tint 变化。

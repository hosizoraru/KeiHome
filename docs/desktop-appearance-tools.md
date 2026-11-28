# 桌面外观诊断与手动刷新

入口：桌面 → 小工具。读取状态只检查配置；只有点击“刷新外观”才触发外观配置变化。

## 现场对照

2026-11-17 同时检查实体机 Home 8602（OS4.0.6.0）和 AVD Home 9259
（OS4.0.24.0）。两者的壁纸服务都报告 `status_bar_rect=1`，即该区域为深色。
AVD 的服务列表存在存活的 Home 壁纸订阅；实体机复发时没有相应订阅，
桌面窗口却请求了 `LIGHT_STATUS_BARS`，也就是深色前景图标。

实体机原生日志还出现 `Service is not bound`；只读检查发现桌面壁纸连接缺失。
这些证据支持“桌面未拿到正确的区域状态或没有及时更新”的方向，
不能单独证明由 KeiHome、KeiMi 或某个桌面版本的代码引入。
两台机器的系统版本和运行状态也不同，不能把对照等同于桌面版本修复证明。

状态卡分别显示：

- 断联自愈保护（通过桌面原生握手能力位 `LauncherRuntimeCapabilities.WALLPAPER_RECONNECT` 实时确认）。
- 桌面壁纸订阅状态及存活监听数（`mWallpaperChangeListeners` 中 `com.miui.home` 且 `binderAlive=true`）。
- 系统壁纸服务的状态栏区域深浅（`status_bar_rect`）。
- 当前用户、默认屏幕、Home 主窗口最近请求的前景色。
- 模式服务当前要求的外观与用户选择的深浅色模式。
- 系统桌面（`com.miui.home`）与壁纸服务（`com.miui.miwallpaper`）的完整版本名与版本号，以及快照读取时间。
- 一键复制纯文本诊断摘要（含 KeiHome、系统桌面、壁纸服务版本与实时状态），便于后续桌面或壁纸高频更新导致自愈失效时直接反馈。

桌面窗口请求不等于 SystemUI 最终像素，也不是 KeiHome 当前页面的状态栏颜色。
Home 在后台时仍显示其保留窗口的最近请求。壁纸与窗口是依次读取的快照，
可能跨越一次变化；发现不一致时提示返回桌面确认，不自动强制白色。
没有窗口、服务未运行、输出过大或格式不识别时都显示“未知”。

## 刷新链路

App 使用既有 API 102 `runtime_status` 请求通道，系统框架作用域中的既有
Monet 工作队列执行。回复和刷新认领只允许系统 UID；App 的其他进程不能
直接调用 Provider 冒充系统回复。

刷新先读取 `UiModeManagerInternal.getDisplayUiMode(0)` 的权威配置，
通过 `IActivityTaskManager.updateConfiguration` 发送仅含 `uiMode` 的
非持久配置增量，短暂切换外观后，在 `finally` 中重新读取模式服务并恢复。
不调用 `UiModeManager.setNightMode`，不写选定模式、自动覆盖或定时设置。
如果期间用户或自动计划改变了应有配置，恢复时采用新值。
这也避免将一次手动刷新变成关闭自动计划或改写 OEM 夜间模式设置的操作。

接口能力不匹配时返回不支持，不尝试猜测原生地址或调用未知 ABI。
页面关闭不取消系统端的最终恢复。中断、部分写入后抛错、恢复失败会分别处理；
恢复无法确认时不会给出成功回执。

按钮检查期间不可重复提交，刷新 UUID 在 App 私有存储先认领再执行，
避免模块热重载时重放同一请求。未确认结束的操作保留为“未确认完成”；
最后一次结果持久保存，诊断原始数据不保存。

## 诊断传输

按已安装壁纸服务的 Intent 解析组件，再读取 ActivityManager 服务诊断及
WindowManager 窗口诊断。使用系统 Binder，不执行 shell、su 或 app_process。

两个来源复用 `BoundedBinderDiagnostics`：以可写的匿名 memfd 接收输出，
预分配 512 KiB，并用 Linux seal 禁止扩容和缩容。空输出、达到上限、
读取不完整都拒绝解析。给 dump 的写描述符与读取端分离，最终关闭全部描述符。
不把 ashmem 设备描述符当成普通可写文件，也不使用有管道背压风险的同步 dump。
服务诊断沿用框架的 TransferPipe 等待机制；读取位于既有后台工作队列，
客户端有请求期限。没有新增常驻监听或轮询线程，也没有壁纸文件副本。

解析器以用户、默认屏幕、Home 包名、主应用窗口和完整监听列表为边界。
锁屏订阅、死亡回调、同名前缀包、其他应用的窗口都不算 Home 的有效状态。
测试资源是两台设备现场输出的最小化样本，已去掉壁纸路径、签名、进程号等数据。

## 验证

- JVM：外观配置恢复、用户或计划中途变化、中断、部分修改后异常、恢复失败、
  过期请求、未知模式、回复校验，以及两种现场格式。
- Android：真实 Binder 输出大于管道缓冲区、写端自行关闭、空输出和超限输出；
  非系统调用者拒绝、认领重放防护、显式开启的跨进程 HyperOS 信息读取。
- 设备：需从 App 点击刷新，回到同一桌面比较；单有“刷新已执行”的回执
  不足以证明状态栏已经恢复，也不证明 Home 壁纸连接已经恢复。

这是一项手动恢复与诊断工具，不是原生壁纸连接问题的自动修复。

### 本次复发的实际结果与根因确认

实体机更新测试构建后，回到同一壁纸桌面仍可复现深色前景。
2026-11-17 20:52 从 App 点击刷新，确认收到配置已刷新并恢复的回执，
返回桌面后的截图和窗口请求仍为深色前景。随后对照之前的固定深浅色
模式切换入口，切回原来的深色模式后，本次故障也未消失。
因此不能把早先一次用户观察到恢复，推广为稳定有效的修复流程。
工具不追加强制白色或定期切换设置等补丁。

2026-11-28 实体机再次复发后，结合实机日志、`com.miui.miwallpaper`
`7.1.9.1-ALPHA-11022109` 与 `7.2.2-ALPHA-11061607` 的对比分析，以及
`com.miui.home`（0308 等 13 个 Phone/Pad 版本）`libapp_launcher.so` 与
`libapp.so` 反汇编，确认完整根因为桌面原生 Rust 客户端
`mi_wallpaper_manager` 断联永久失活：

1. `com.miui.miwallpaper` 服务端两版本间 `com.miui.miwallpaper.server.*`
   全部 194 个方法及组件清单完全一致，触发源是壁纸包更新/重启杀掉了旧的
   `MiuiWallPaperControllerService`，而 `com.miui.home` 进程继续存活。
2. 桌面 Rust 客户端 `MiuiWallpaperManagerInner::trigger_reconnect` 仅做一次
   `do_bind_service("reconnect_immediate")`，若撞上壁纸更新冻结窗口失败或超时
   即直接退出不再重试；业务查询 `MiuiWallpaperManagerInner::get_service` 在
   `service == None` 时直接返回 `Err("Service is not bound")`，没有任何懒重连。
3. Dart 层 `WallpaperGetxController.adaptHomeToWallpaper` 在调用
   `isMiuiWallpaperComponentUsing()` 抛出 `"Service is not bound"` 时缺少
   异常兜底，导致后续深浅更新整段中止，状态栏永远停留在断联前的旧值
   （如白天浅色模式的 `colorMode=0, isLight=true`），直到桌面进程重启。

### 原生自愈修复（`libkeihome_hyos_lsp.so`）

KeiHome 在既有桌面原生模块中新增 `wallpaper_reconnect_profile` 与
`wallpaper_reconnect_hooks`，通过字符串锚点与 ARM64 结构化校验动态解析
`libapp_launcher.so` 中的 `do_bind_service`、`trigger_reconnect`、`get_service`
及 `ArcInner` 字段偏移（全部 13 个本地 Phone/Pad 版本通过严格校验，不匹配时
安全静默关闭）：

- **`do_bind_service` 退避重试**：仅针对 `reconnect*` 原因（不阻塞冷启动
  `bind_service_sync`），在原生重连后台线程内按 `2s → 4s → 8s → 15s → 20s → 30s`
  退避重试，按 250ms 切片检查 `destroyed` 标志并在重连前正确释放旧 `anyhow::Error`。
  连接成功后，桌面原生 `on_service_connected → restore_callbacks_after_reconnect`
  自动恢复全部壁纸回调监听。
- **`get_service` 懒重连与内联自愈**：仅在 `!destroyed && !is_bound` 确认服务
  未绑定时，以 3 秒节流触发原生 `trigger_reconnect("reconnect_lazy")`；若当前
  位于非 `.ui` 工作线程（如 `app-frb-pool`），最多微等待 500ms，一旦绑定恢复
  即释放旧错误并内联重试 `get_service`，让 `adaptHomeToWallpaper()` 无感成功。

#### 实机演练回执（Xiaomi 17 Pro，2026-11-28）

- 桌面进程 `com.miui.home`（0308，PID `27989`）运行期间覆盖更新 `com.miui.miwallpaper`
  （新进程 PID `29018`）：`19:43:15.215` 桌面两个原生壁纸客户端命中 OEM 竞态并遭遇
  10s 绑定超时（`on_binding_died` 被 `Reconnect already in progress` 跳过）；
  `19:43:27.217`（10s 超时 + 2s 退避）KeiHome 触发第 1 次重试，5ms 内双实例均打印
  `KeiHomeWallpaperReconnect: event=retry_succeeded arg=1` 与
  `Restored listener id=1, which=1`，`MiuiWallPaperControllerService` 中两条
  `com.miui.home` 监听均恢复 `binderAlive=true`，状态栏保持 `colorMode=1, isLight=false`。

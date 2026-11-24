# 通知中心与状态栏图标日志分析（2026-11-24）

## 输入与环境

本次分析使用用户提供的 `LSPosed_20261124_092835.zip`，原始输入保存在
`.artifacts/research/notification-icon-log-20261124/input`。日志中的 SystemUI 是
HyperOS 4.0.31.0.XFRCNXM，进程先后为 PID 6481 和 26774。system_server
进程为 4490。对应的实体机验收串号是 `192.168.31.202:5325`，同时使用
`emulator-5580` 做 AVD 对照。

## 证据时间线

- 08:05:52 到 08:05:59，SystemUI 安装了 native folded icon、缓存通知 app-icon、状态栏 descriptor 和 native refresh 边界。bootstrap 扫描了 867 个候选 view，但 `rows=0`、`native-cache entries=0`、`bound=0`。
- 08:06:17，system_server 完成一次 163 个应用的 icon refresh。08:08:33 到 08:08:41，SystemUI 第二次初始化，bootstrap 扫描 864 个候选，仍为 `rows=0`、`native-cache entries=0`、`bound=0`。
- 08:19:46，日志第一次出现 `Authenticated notification app-icon view bound`，随后只记录了 native-owner 和 `Published notification icon snapshot applied`，没有记录通知行 handoff replay。
- 09:03:29、09:27:18、09:28:15，状态栏 replay 分别刷新 1、6、6 个 view。相同时间附近 system_server 的 icon probe 已经是 generation 64 到 67，说明图标源确实发生过资源变化。
- 整份 verbose 日志中 `Native notification handoff rows refreshed` 的计数为 0。这个字段不是日志采集遗漏，而是现有 replay 队列从未接纳并刷新普通应用通知行。

## 根因

现有 `SystemUiAppIconRuntime` 在通知图标 consumer hook 中额外限制
`frameworkNotice.packageName == "android"`，因此只有 framework 通知才会进入
`SystemUiNotificationRowReplay`。普通应用通知在 native producer 已经换成 KeiHome
图标后，已有通知行仍持有旧的 ImageView/drawable，资源变化后不会重绑。

replay 还把 `cache?.get("android")` 的位图是否存在当作整个通知行链路的 readiness。
这个条件与通知所属包无关，缓存没有 `android` 图标时会阻止所有通知行刷新。现已改为
检查共享图标缓存是否 active，并在缓存 ready 或显式 invalidate 时把已认证行标记为
pending。队列只保存最多 8 个弱引用行，仍要求当前用户 UID，避免跨用户刷新和长期持有
SystemUI view。重复 bind、未挂载 view 和 native exception 都有明确状态处理。

## 修复内容

- 接纳当前用户的普通应用通知行，移除只允许 `android`/UID 1000 的错误过滤。
- 以共享缓存 active 状态作为 replay readiness，不再依赖 `android` 包的偶然命中。
- 缓存 ready、主题/资源 invalidate 时标记已认证通知行 pending，触发一次 coalesced replay。
- 保留弱引用、8 行上限、当前用户 UID 校验、attached view 校验和异常传播，避免为修复而持续扫描或重绘所有 SystemUI。
- 同一行在一次 replay 成功后不会重复执行，只有下一次资源刷新重新标记 pending。

## 性能与代价

额外成本只发生在已有认证行和缓存发生变化时。每次 replay 最多处理 8 行，按现有主线程
coalescing 规则执行，不引入全量通知扫描。普通应用通知会多一次 native rebind，代价是
正确刷新当前可见通知图标。旧通知行不再受 30 秒启动窗口限制，换取跨越主题切换和图标包
切换的可靠性。弱引用和 clear/retirement 路径保留，SystemUI 重启后不会复用旧 view。

## 验收

- `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --no-daemon` 通过。
- `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon` 通过。
- `SystemUiNotificationRowReplayDeviceTest` 在 `emulator-5580` 和实体机
  `192.168.31.202:5325` 均通过 6 项。
- `SystemUiStatusBarIconRuntimeDeviceTest` 在两个设备均通过 43 项。
- 两个设备均安装了当前 debug APK 和测试 APK。未伪造通知内容或图标包像素结论，因
  本次压缩日志没有提供可关联的第三方通知包、前后图标包标识和截图。下一次真实用户复现
  可直接观察 `Native notification handoff rows refreshed=N`，并按通知包名核对状态栏与通知中心。

## 提交边界

源码修复和研究报告应分开提交。当前分支已有未提交的 1.15.0 更新日志改动，本次不把
它们混入通知图标修复提交，也不推送或创建 release tag。

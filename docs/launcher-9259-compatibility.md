# Launcher 9259 兼容性与验收

2026-11-16 已完成 HyperOS Phone AVD `emulator-5580` 原位升级和验收。
后续暂以 **9259** 为该 AVD 的桌面版本，保留安装，不回退到 9258。

## 输入身份

- 包名：`com.miui.home`。
- versionCode：`801029259`。
- versionName：`RELEASE-8.01.02.9259-261013-11161138-R`。
- 输入：`/Users/voyager/Downloads/系统桌面_RELEASE-8.01.02.9259-261013-11161138-R.apk`。
- APK SHA-256：`bc4c6a4961ed2ec152b55acd222f22b220a9333c30ef8767ea92ca2847944247`。
- 签名证书 SHA-256：`c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`。
- 运行库：Rust 3.9.0、Flutter 3.38.3，arm64-v8a。

APK 没有 DEX。`libapp.so` 与 9258 不同，其 SHA-256 为
`8c0f51741c2c36b7b640ec79af1a40edfe73442a864d01a36e92a0649f752863`。
`libapp_launcher.so`、`libresources_frb.so`、
`libnative_widget_sdk.so` 与保留的 9258 样本逐字节一致。

## 动态解析与回归

现有生产解析器直接通过新版的结构、调用关系和 ABI 检查，本次未修改运行时规则。
9259 加入运行样本清单及标签测试清单，当前共保留 11 个版本。

契约生成仍使用 7722、8593、8602、8604、8607、9209、9226、9239、9248
这 9 个样本；9258 / 9259 都是未参与该契约生成的运行测试输入。
本次针对 9258 / 9259 执行四套 Monet 回归，不宣称重新跑完全部 11 个版本的矩阵。

证据目录：`.artifacts/research/launcher-9259-20261116/`。

- `monet-matrix/report.json`：selection、tint、image recovery、async support gate 全部通过；
  包含生产模式重生成一致性和 ARM64 桥接验证。
- `profiles/dock-9259.log`：128 项 Dock 结构与拒绝测试通过。
- `material/material-profile-test-report.json`：9258 / 9259 及负样本共 2789 项检查，
  含 162 项拒绝检查，生产解析代码和模式保持不变。
- `labels.log`：标签相关的 1372 项 ARM64 用例通过；
  该数量为离线桥接测试，不能替代实际 Flutter/GC 验收。
- `highlight.log`：9259 高光解析、ABI、精确补丁测试通过；
  独立调试符号 oracle 可重现既有生产模式。
- `shipping-native-identity.json`：测试使用的未裁剪 native 库与已安装 b0571
  Release 载荷的 `.text`、`.rodata`、`.data.rel.ro` 内容一致。

已有 KeiHome `1.12.0+g7765405.b0571.dirty` 可直接运行于该桌面。
本次未因新增样本而另行构建、安装或发布 KeiHome APK。

## AVD 验收

使用 `adb -s emulator-5580 install -r` 原位更新桌面，保留用户数据。
安装后设备上的 APK SHA-256 与输入一致，应用内状态页显示 `Home 9259`、
Rust 3.9.0、Flutter 3.38.3。

系统安装替换桌面进程：29500 → 31211。更新后至验收结束保持 31211；
system_server 8846、SystemUI 25943 未变化。没有重启模拟器、ADB 或上述系统进程。

运行日志确认 native 初始化、Monet 门控、双层图标、Tint、图像恢复、标签、
高光、Dock 和扩展材质 Hook 安装。所采集日志内未发现 native 错误或崩溃记录；
这不构成所有设置组合或长期稳定性的保证。

已验证：

- 原有主题图标、蓝色 Monet、桌面与 Dock 正常显示。
- 临时将已提交配色改为绿色，图标即时变化；随后恢复原蓝色，
  过程中桌面进程未变化。仅修改共享配色状态作测试，不修改用户取色偏好。
- 启动设置、返回桌面、最近任务、展开系统工具文件夹与返回均正常。
  `motion-final.webm` 和相关截图保存了实际流程。
- 更新前后 70 条布局记录的 ID、类型、容器、屏幕、位置、尺寸和 Intent 一致，
  布局摘要 SHA-256 为
  `5111dc609f6f19fe2e82537d8f1fccc7f20cb60f8243394cb8575f2f348111f0`。
- 当前主题文件 SHA 和配色状态恢复至任务开始值，最终停留在 9259 桌面。

主要设备证据：`native-runtime-summary.json`、`status-9259-main.png`、
`live-palette.json`、`layout-comparison.json`、`final-device-state.json`。
桌面数据库、WAL 与偏好已在升级前备份，仅作本地恢复凭据。

本次不是所有图标包的重新导入、应用矩阵，也没有执行实体机安装、commit、
push 或外部发布。

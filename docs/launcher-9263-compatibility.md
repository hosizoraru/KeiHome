# Launcher 9263 兼容性

本记录覆盖 `com.miui.home` 的 `RELEASE-8.01.02.9263-261013-11192009-R`
（`versionCode=801029263`，`minSdkVersion=35`，`targetSdkVersion=36`，
`compileSdkVersion=35`）。

## 输入与解析

- APK SHA-256 为 `7fe7e649a2634ced916e0b99daddc29e9a21462f54b8a77c19f73dc96db1a861`。
  签名证书 SHA-256 与 9259、9261 同为
  `c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025`。
- DexSQL 确认 9263 不含 DEX（`android:hasCode="false"`），由 4 个 ARM64 原生库组成
  （`build_info.txt` 记录 `cdp-pipeline-1795089927326`，Flutter
  `3.38.3-mi-1.17.25-beta3`）：
  - `libapp.so`：`c68b62dd4cbceb89b26e21ba7eb030a4422392da45cf40c21fae0c6d1d72085a`（30,386,688 B）
  - `libapp_launcher.so`：`40e611a265bca571ec2ca108fa8137b70027a613ffbc04c7d6504f143e5a3b00`（16,529,344 B）
  - `libnative_widget_sdk.so`：`a4d6b0e19069fa61659038d170ff00cf4dd82494035e6e0acb79413abce39b89`（2,752,104 B，与 9248 字节一致）
  - `libresources_frb.so`：`a24147b751338805e2180e522401c881687a2d3060e2e31dd43c191779240b30`（1,473,496 B）
  - 解压 `.gnu_debugdata` 得到的独立符号对照 `debug.elf`：`2b14284ac51938a63f5cdf3e23429d870b90dd301e8bb4ee134e6ea2af58fe1d`（5,160,280 B）
- **Monet 选择契约更新**：9263 的 30 个 Monet 选择根角色与既有契约全部一致，仅
  391 个闭合依赖图节点中的 1 个子节点发生变化——`FolderPreviewIcon._buildFallbackIcon+0x5a4`
  调用的匿名辅助函数（`0xa6eb44`，128 B，位于 `WorkProfileHelper._instance` 与
  `DeviceConfig.iconHeight` 之间）在 `+0x58` 处由 `MOV x6, x22`（规范 `null`）变为
  `ADD x6, x22, #0x20`（规范 `true`），其余 31 条指令与 9261 完全一致。将 `9263` 纳入
  `scripts/tests/monet-selection/contract_inventory.json` 并重新生成
  `app/src/main/cpp/monet_selection_patterns.h`，仅增量追加 2 个 body（新叶节点与
  `kFolderFallback` 变体）、37 条边与 1 条模式，不改动或删除任何旧版本契约。
- **其余原生解析器零修改直接兼容**：
  - **Monet 取色 / 图像恢复 / 异步单色门**：`monet_tint_variants.h` 与
    `monet_image_recovery_patterns.h` 重新生成均与现网字节完全一致；`9263` 异步门位于
    `0x1b5df70`。
  - **Dock 几何、模式、刷新与玻璃作用域**：`dock_glass_patterns.inc` 纳入 9263 后保持字节完全一致，
    10 个独立 `.gnu_debugdata` 调试符号基线全部精确命中（`phone=0x10e8cc4`、
    `outer=0x10e82e4`、`phone_size=0x4c0`、`mode=0x10e0d1c`、`glass=0x147a16c`）。
  - **Dock 与搜索栏材质**：Dock（`closure=0x10e12a0`、`branch=0x10e1308`、`slot=0x29e8`）
    与独立搜索胶囊（`source=0x1130794`、`branch=0x11307e0`）均唯一解析。
  - **应用名称**：直接原生 `Marquee` 构造路径（`builder=0x145d478`、`ctor=0x109c06c`、
    `wrapper=0`）直接命中。
  - **图标高光**：`IconLightConfig.isHighLightEnabled`（`entry=0xe3e0d0`、`patch=0xe3e12c`）
    及 5 个符号基线全部唯一命中且重现现网模式。
  - **MAML ZIP 元数据释放**：9263 自带 `libnative_widget_sdk.so` 含 3,130 条调试符号，
    不含 `zip_archive` 符号，已纳入负例清单并确认解析器返回 `0` 拒绝。
- **清单能力与版本标签**：`AndroidManifest.xml` 保留 `supportUsingFolderGlassStyleApi=true`、
  `support_mixed_content_picker_version=1` 与 `hyperos_app_lib_name=libapp_launcher.so`；
  `libapp.so` 保留 `usingFolderGlassStyle`、`MiuiWidgetManager.notifyAllWidgetBlurOptionsForMaterialChange`
  与 `FolderBlurUtils.buildFolderGlass`。`compactLauncherVersion` 正常提取为 `Home 9263`。

## 验证

所有主机测试均使用本次重新编译的未裁剪 Release 原生库
`libkeihome_hyos_lsp.so`（SHA-256 `890cc02e6464fd8a5808b9a75ab19d21374391383b7b196ec364de302fae8f2b`）
与外部夹具 `.artifacts/inputs/launchers/9263/` 执行：

| 套件 | 实测结果 |
| --- | --- |
| Monet 离线矩阵与链接产物（`run_matrix.py` + `run_shipping.py`） | `9263` / `0308` / `9261` 的 `selection`、`tint`、`image`、`gate` 全部通过；三份契约头文件跨 12 个版本逐字节复现；`libkeihome_hyos_lsp.so` 链接产物全项 `0` 通过。 |
| Dock 几何、模式、刷新与玻璃作用域 | 129 项结构/拒绝校验 + 99 项刷新校验 + 10 项 `.gnu_debugdata` 符号核对通过；链接产物通过 3,561 项渲染仿真、9263 上 35 项模式/曲线仿真、12 版本共 1,128 项刷新仿真（含 9263 的 94 项）与 593 项玻璃作用域仿真。 |
| Dock 与搜索栏材质（`run_material_tests.py --shipping-only` + `test_material_bridge.py`） | 9263/0308/9261 共 1,328 项校验与 159 项拒绝用例通过（9263 为 420 校验 / 51 拒绝）；链接产物在 9263 上通过 2,792 项桥接仿真（覆盖 Dock 与 `searchCapsule`）。 |
| 应用名称（`launcher-labels/run.py`） | 9263 图结构、拒绝与共享刷新隔离校验通过；源码与链接产物各通过 686 项 ARM64 仿真用例，且桥接字节与 `launcher_label_bridge.S` 一致。 |
| 图标高光（`icon-highlight/run.py`） | 9263 通过 242 项解析/构建校验、945 项精确补丁断言、288 项原生基线与 1,440 项补丁仿真（含 1,120 项关闭旁路）；链接产物与 `icon_highlight_bridge.S` 一致。 |
| MAML ZIP 释放（`maml-zip-release/run.py`） | 2 份框架 SDK 各 328 项正例通过；9263 等 5 份桌面 `libnative_widget_sdk.so` 负例均返回 `0` 拒绝。 |
| JVM 单元测试与 Release 构建 | `:app:testDebugUnitTest`（含 `LauncherVersionLabelTest` 的 `9263` 用例）与 `:app:externalNativeBuildRelease` / `:app:assembleRelease` 通过。 |

本地研究输入与验证证据位于 `.artifacts/inputs/launchers/9263/` 与
`.artifacts/research/launcher-9263-20261128/`（包含 `monet-matrix/`、`monet-shipping/`、
`material-sidecar/`、`material-linked-bridge.json`、`launcher-labels/`、`icon-highlight-linked/`、
`dock-*.json` 与 `maml-zip-release/`）。

**边界说明**：本次验证覆盖全量离线结构解析、独立 `.gnu_debugdata` 符号核对与真实 Release
链接产物的 ARM64 Unicorn 仿真，未在真机/AVD 上覆盖安装 `com.miui.home` 9263；若后续在设备上安装
9263，建议补充实机 Dart GC 与视觉验收。

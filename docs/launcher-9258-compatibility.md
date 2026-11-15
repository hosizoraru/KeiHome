# Launcher 9258 兼容性

本文承接 [9248 兼容性记录](launcher-9248-compatibility.md)，记录 2026-11-15
的输入身份、静态差异、已确认根因与适配方向。**当前状态：适配进行中，待最终验收。**
Dock 十样本及 ImageRecovery 单样本阶段检查已通过；这些结果不能替代正在执行的
最终四套十样本 matrix、shipping 载荷、Native 与设备验证。

用户已明确：**9258 保留为后续 HyperOS-AVD 的主要验收版本，不回退。**
9248 文档中的临时升级、恢复快照及恢复旧桌面属于历史记录，不是本次的收尾方案。
本次只使用 HyperOS Phone AVD，不启动 ColorOS，不修改实体机。

## 输入与身份

输入版本为 `RELEASE-8.01.02.9258-261013-11132207-R`，包名
`com.miui.home`，version code `801029258`，APK 大小 `58823851` 字节。
原始 Downloads 文件保留，留存副本位于
`.artifacts/inputs/launchers/9258/launcher.apk`。

APK SHA-256：
`9fcd6d47d132efaeebbe30daf096840ecd228f7ba659a966fe5f48b6f1b8774d`。
APK 不含 DEX，以下库均为 ARM64 输入：

| 库 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `libapp.so` | 30385496 | `61c281d7f470208706481324893a02bd6e9572bff8128964ee0354d5e95e6bdf` |
| `libapp_launcher.so` | 16519824 | `4ee2df55b5478a6b477be0c8feed4b8d57e468c3e2c028cdd9b520993724a987` |
| `libresources_frb.so` | 1473496 | `a24147b751338805e2180e522401c881687a2d3060e2e31dd43c191779240b30` |
| `libnative_widget_sdk.so` | 2752104 | `a4d6b0e19069fa61659038d170ff00cf4dd82494035e6e0acb79413abce39b89` |

`libapp.so` 的 Build ID 为 `4f1bdaed1c63fa9556bde6440e475c34`；
其余三库在身份清单中没有 Build ID。留存 debugdata 的 SHA-256 为
`dd73fa9d0ff3cbd891e999cd658217655aa1b262260b769f455a59ab9e2b1a98`。
完整来源、复制状态、package badging 及签名收据见
[9258 provenance](../.artifacts/inputs/launchers/9258/provenance.json)。

这些版本号、哈希及 Build ID 用于复查测试输入、绑定解析与交付证据，
**不作为生产版本允许列表，也不用于选择固定 RVA 或字段偏移。**
留存输入身份不等于运行中的 APK/Native 映射身份；后者须在最终验收单独确认。

## 结构变化与 Rust/FRB 审计

独立审计比较 9248 与 9258 的 `libapp_launcher.so`，覆盖原 23 个图标/调色板
重点角色、扩展的 324 个相关函数，以及 4083 个 FRB generated 相关函数。
四个取图标包装 `ActivityInfo_get_icon_impl`、`ApplicationInfo_get_icon_impl`、
`ShortcutInfo_get_icon_impl`、`LauncherActivityInfo_get_icon_impl` 的大小、
地址归一后的指令、直接调用和已解析地址引用身份一致。

原 23 个角色中，2 个指令字节相同，20 个归为已解析的地址移动。
`WallpaperPaletteProvider` 的 `ContentProvider_MV::query` 剩余 6 处访存位移与
同一 `.bss` 槽位移动吻合，但完整寄存器溢出/重载来源尚未证明，保留为地址移动候选。
已验证的 Cortex-A53 勘误跳板与业务结构变化分开记录，未将链接器跳板误判为接口变化。

Native FRB 的 160 个导出名称、大小、类型、绑定及可见性一致，但导出 RVA 全部变化。
`frb_get_rust_content_hash` 的静态返回常量仍为 `0x07d3d1a2`。
primary 的 1103 个表槽、670 个有效 wire 路由，以及 sync 的 1101 个表槽、
442 个有效 wire 路由，在两版中保持相同的 Native ID 映射、目标与有效路由参数搬运。
这是 Native 静态表分析，不是 Dart AOT 生产 resolver 或桥接执行结果。

尚需保留的边界包括 `RemoteAction` 池容器中的全局槽位移动候选、
`DeviceLevelEnum` SSE 转换与 `HyperExecutor::execute_async` 的固定长度数据窗口差异。
符号相等、哈希相等、分发表相等或函数形状相等，均不能单独证明 Rust 类型布局、
opaque 所有权、序列化、间接回调及完整 ABI 一致。

同时存在不能归为重定位的真实 workspace 变化：

- `check_database_data_orientation_when_loading` 从 6400 增至 7592 字节，
  栈帧、局部分支及直接调用序列改变。
- 新增 `layout_transform::make_grid`、`layout_transform::x_order` 及相关
  BTree / VecDeque / TransformItem 实例，旧 `transform_make_space_grid` 移除。
- 应继续核对 workspace 数据方向修复、网格重排与初始加载相关假设。
  上述静态变化既不是具体运行效果证明，也不能直接归因为本次 Selection/Dock 误拒。

`libnative_widget_sdk.so` 与 9248 整文件逐字节相同；
这不自动证明跨库集成或 MAML 运行支持。`libresources_frb.so` 并非整文件相同，
不能把 widget SDK 的结论外推到其他库。

可复查证据：
[Rust 审计报告](../.artifacts/research/launcher-9258-20261115/rust-audit/REPORT.md)、
[函数与结论索引](../.artifacts/research/launcher-9258-20261115/rust-audit/findings.json)、
[Native FRB 契约](../.artifacts/research/launcher-9258-20261115/rust-audit/native-frb-contract.json)、
[局部补充分析及未决项](../.artifacts/research/launcher-9258-20261115/rust-audit/targeted-refinements.json)、
[完整证据哈希清单](../.artifacts/research/launcher-9258-20261115/rust-audit/manifest.json)。

## MiuiBackGestureHook 参考边界

参考研究基于 MiuiBackGestureHook 的
`e696bbd4ef58240de9193a9aab434bffcc181334` 源码快照；
它是只读设计研究，没有证明 9258 运行兼容。完整依据见
[动态解析研究](../.artifacts/research/launcher-9258-20261115/reference-research/miuibackgesturehook-dynamic-resolution.md)。

可借鉴的是：从当前映射的 ELF、导入和指令关系发现候选，用调用者、共享槽位、
字段来源及 ABI 约束消歧，最终要求角色或整图唯一；安装前再复核当前镜像原始指令，
缺失或歧义时不发布对应能力。当前映射的 fingerprint 只能证明后续一致性，
不能补上最初选错候选时缺失的语义证明。

参考项目并非完全无版本特例。其静态 profile 优先，动态路径中仍存在 6144 的
固定 RVA 回退：`ResolveSide` 失败后，根据已知 entry 地址采用旧 handler/字段偏移，
没有重新证明刚才未通过的调用图。**这一回退不能照搬到 KeiHome。**
不能以可执行地址、通用栈序言或首个命中代替完整结构证明，也不新增
`9258 -> 固定 RVA/字段`、版本号或哈希触发的回退。

`libapp_launcher.so` 的 Rust/ELF GOT 与 `libapp.so` 的 Dart AOT object pool
属于不同证明域，不能混用 load bias、槽位或寻址语义。静态定位到 FRB wrapper
也不代表它拥有需要 hook 的实际 Flutter 行为；参考项目的 observer 安全性
不能替代 KeiHome 对 allocation、GC、liveness、stackmap 与 replay 的独立约束。

## Selection：已确认根因与迭代方向

旧 Selection 依赖契约只展开两层，随后以 `raw32` 前缀终止。深层前缀中的
FP PP 槽及 external branch 仍可能被当作原始指令比较，因此合法重定位会触发误拒。
只修复浮点槽并不解决深层外部分支问题，不能把剩余拒绝解释为 9258 的业务 ABI 改变。

新 Selection 正在改为闭合依赖前缀图：在已审查的前缀/scope 内迭代跟随
**所有 external edges**，持续补充依赖，直到图闭合；不再以两层后的 `raw32`
叶节点替代未解析的外部依赖。局部 scope 内的 relative 分支仍保留精确关系，
不能把 local branch 和 external relocation 一并抹去。
PP double 与 pointer 槽采用一致的规范化规则，同时保留访问类型、来源寄存器、
槽位重复/共享关系及后续恢复约束，不扩大为任意立即数 mask。

诊断中的闭合前缀图快照显示，9248 与 9258 均为 391 个节点，依赖深度到 5，
最大 scope 为 3452 字节。这说明固定两层模型不足；这些图统计不是最终
生产契约或运行验收证明。证据见
[初始 Selection 差异](../.artifacts/research/launcher-9258-20261115/selection-differences.json)、
[FP 处理后的剩余差异](../.artifacts/research/launcher-9258-20261115/selection-differences-after-fp.json)、
[闭合前缀图快照](../.artifacts/research/launcher-9258-20261115/selection-prefix-closure.json)。

## Dock：已确认根因、修复与十样本阶段结果

Dock 的 closure 形态包含
`PP page + low ADD + LDP(X30, X5) + BLR(X30)`。
旧规则遗漏了这条链中的低地址 ADD 重定位，因此在 PP 槽移动时，即使 closure
加载与间接调用角色保持相同，也会在形态比较中误拒。差异证据见
[Dock differences](../.artifacts/research/launcher-9258-20261115/dock-differences.json)。

已补齐完整 PP page/low ADD 寻址链的解码，把 closure 槽位低地址重定位纳入规范化，
继续核对 `LDP` 的 X30/X5 角色和 `BLR(X30)` 调用关系。
这不是新增 9258 专用 pattern/RVA 分支，也不是统一放宽所有 ADD 立即数；
对象布局、寄存器、局部分支、调用关系及既有 bridge/GC 安全边界仍须保留。

使用旧 9 个样本生成 `.inc` 后，7722、8593、8602、8604、8607、9209、9226、
9239、9248、9258 共 10 个样本均通过每样本 128 项结构/拒绝检查。
检查覆盖 PP 闭包地址同时跨页与低地址移动的正例，以及 page/low-shift、
context-reg、BLR-reg 破坏的 4 个反例。证据见
[Dock 十样本阶段 matrix](../.artifacts/research/launcher-9258-20261115/profiles/dock-matrix.json)。

该 JSON 的 9258 记录为 `2 relocated closure calls`；8604、8607 各记录 1 条，
其余样本各记录 2 条，全部均为 128 项通过。因此不能把阶段结果扩大为每个样本
都实际包含两个 closure call，也不能将此阶段 matrix 等同于最终四套 matrix
或设备上的 Dock 运行/视觉验收。

## ImageRecovery：GuardedClone 同类遗漏与修复

完整 matrix 暴露了 ImageRecovery GuardedClone 的同类误拒：
`UninstallAppHelper._buildShortCutDoBoomAnim` 前缀中的外部 `B` 发生重定位，
同时 PP 闭包低地址 ADD 移动，而实际 return-helper 的 32 字节完全相同。
不能据此前缀原始指令不等推断 return-helper 或 Thread/return 布局改变。

已修正规范化规则，覆盖完整 PP closure 4 指令。
外部 `B` 只在认证其目标为 position-independent 的 32 字节前缀后才规范化；
生成器若发现该目标前缀含 PC-relative 指令，必须拒绝生成对应契约。
这不是无条件 mask 外部分支，也不允许以原始前缀相同替代位置独立性认证。
所有 Thread/return 字段保持 exact，不因本次重定位修复放宽。

契约仍仅由旧 9 个样本生成，未把 9258 加入生成集合。
9258 新 Image 测试已通过 **11 positives / 132 rejections**，证据见
[ImageRecovery 9258 日志](../.artifacts/research/launcher-9258-20261115/profiles/image-recovery-9258.log)。
这是新增规则的单样本阶段结果，不代表最终十样本 ImageRecovery、shipping、
Native 或设备验证已经完成。

## 契约生成与 holdout 边界

Selection、Dock 与 ImageRecovery 均不新增 9258 版本、库哈希到 RVA 的分支。
**生产契约仍仅由 7722..9248 的旧 9 个留存样本生成，9258 作为 holdout，**
不得把其函数/RVA/完整摘要加入生产训练样本后再称为未知版本泛化。
基线输入记录见
[旧九样本 Selection 生成证据](../.artifacts/research/launcher-9258-20261115/selection-contracts-baseline-only.json)。

独立实验中，仅用 9248 生成的新 Selection 契约，已在 9258 输入上通过现有
80 项检查；单样本生成记录见
[9248-only](../.artifacts/research/launcher-9258-20261115/selection-9248-only.json)，
执行记录见
[Selection 9258 日志](../.artifacts/research/launcher-9258-20261115/profiles/selection-9258.log)。
日志明确为 `host_only`，其中包括不同加载基址、未知文件身份、指令变异拒绝、
PP 槽重定位及 publication 检查。

该 Selection 结果支持继续迭代结构发现模型；**它不代表最终九样本生产契约已经完成
最终四套十样本回归，也不代表 shipping、Native 桥接或 HyperOS-AVD 运行/视觉验收通过。**
这里的“仅用 9248”是隔离泛化实验，不替代旧九样本生产输入集合。

## 验证（最终结果待补）

当前已确认上述静态审计、9248-only Selection 的 host-only 80 项检查、
旧九样本生成契约后的 Dock 十样本各 128 项检查，以及 9258 ImageRecovery 的
11 positives / 132 rejections。各项属于不同阶段，不能合并为最终全链路通过。

最终四套十样本 matrix 正在
[verified-matrix](../.artifacts/research/launcher-9258-20261115/verified-matrix/)
中执行，最终汇总尚待补入；构建及 HyperOS-AVD 未验收，
shipping/Native/device 结论仍待确认，整体保持**待验收**。
本节最终结果由主任务负责人填写，不能从 9248 历史记录或阶段日志借用通过结论。

| 验证门槛 | 最终状态与证据 |
| --- | --- |
| 旧九样本生成输入隔离、9258 holdout 身份及最终契约产物 | 待补最终核对 |
| Dock 阶段十样本结构/拒绝检查 | 已通过：每样本 128 项，见 `profiles/dock-matrix.json` |
| ImageRecovery 9258 阶段正例/拒绝检查 | 已通过：11 positives / 132 rejections，见 `profiles/image-recovery-9258.log` |
| 最终四套十样本 resolver 与拒绝 matrix | 正在 `verified-matrix/` 执行，最终结果待填 |
| 干净源构建 | 待验证，结果待填 |
| shipping 链接/打包载荷及精确身份 | 待验收，结果待填 |
| Native bridge、安装/发布及相关安全验证 | 待验收，结果待填 |
| HyperOS-AVD 实际 APK/Native 映射、运行及逐项视觉验收 | 待验收，结果待填 |

9258 继续作为后续主要验收版本；系统分区内置桌面、安装更新与实际运行映射身份
必须分开记录，不以旧基线元数据推断回退。

## 交付（最终结果待补）

由主任务负责人补入最终源码提交、构建版本、APK/载荷哈希、签名、
R8 mapping/Native symbols 留存路径及验收证据。当前不填写尚未产生的交付身份，
也不宣称已经提交、发布或完成设备验收。

本次文档子任务只写入本文；未修改其余源码或文档，未执行构建、设备操作或 commit。

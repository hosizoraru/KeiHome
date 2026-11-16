# 图标包与 Monet 发版验收

每次公开发版都运行本流程。导入成功、打开系统主题详情、安装新版模块和真正应用图标包是不同的检查，不能互相替代。

## 设备与产物准备

- 仅操作已启动、已解锁的 HyperOS Phone AVD `emulator-5580`，当前桌面基线为 9258。不回退桌面，不启动其他 AVD。
- 脚本不会启动、关闭或重启任何模拟器，也不会重启桌面、系统进程、ADB 或网络。
- 保留全部 APK、MTZ/ZIP、内置和生成包测试资源，尤其完整 iOS 样式、大 MTZ、Arcticons Material You 和 Lawnicons。默认要求至少 21 个去重后来源；新增资源后提高这个下限，不能为了通过而降低。
- 安装与候选源码对应的正式签名 Debug QA APK 和测试 APK，使用相同的已验证 Release 原生库。参考 `scripts/deploy-hyperos-avd.py --require-all-reloads`；普通 Java 热重载不包含原生重载。
- 新的原生库需要单独完成原生验证和必要的系统重启；本脚本不会代替维护者做设备生命周期操作。
- 不卸载 App、不清空数据。每次发版使用新的证据目录。

## 执行顺序

下面的目录和 APK 是占位路径，应替换为本次发版的绝对路径。MTZ 参数是**设备上的文件路径**，需指向已确认包含真正 Monet 程序的源包。

```bash
python3 scripts/release_iconpack_gate.py \
  --phase snapshot --evidence /absolute/path/to/evidence

python3 scripts/release_iconpack_gate.py \
  --phase imports --evidence /absolute/path/to/evidence --minimum-packs 21

python3 scripts/release_iconpack_gate.py \
  --phase monet --evidence /absolute/path/to/evidence \
  --monet-mtz /data/user/0/os.kei.hyper.home/no_backup/icon-packs/SOURCE_ID/original.zip

python3 scripts/release_iconpack_gate.py \
  --phase apply --evidence /absolute/path/to/evidence \
  --apk /absolute/path/to/signed-release/KeiHome.apk
```

1. `snapshot` 保存当前系统图标归档、可恢复的主题 ID、深浅色状态、自定义选择和自定义资源的哈希。现有主题必须能从系统主题库恢复。
2. `imports` 重新扫描全部来源，按内容去重后逐个通过生产导入链路导入。库存扫描失败、导入待定、授权未完成、测试被跳过或用户偏好改变均失败。报告含所有来源，不只选择一个小样本。
3. `monet` 先真正应用 Monet 主题，然后验证已安装 APK 的资源、原始像素、预览、导出、MAML 选择器及大 MTZ。编辑器测试运行在支持 Monet 的主题下，避免把普通 ColorOS 风格按设计禁用控件误认为功能异常。
   随后分别应用 Arcticons Material You、Lawnicons 和矩阵中的真实 Monet MTZ：通过 API 102 切换两种种子色，在亮色与暗色下等待原生 APPLIED ACK，核对 MAML 标量及 packed 状态、真实桌面图标的预期前景/背景像素和深浅色差异，并保存图标区域与桌面截图。关闭时应用配色必须保留关闭状态、MAML 禁用状态和稳定的图标像素。所有用户设置和原主题必须恢复。
4. `apply` 安装已归档的正式 R8 APK，要求 APK 哈希、源码和 mapping 一致，使用严格 Java 热重载验收。逐个从系统主题详情实际应用，核对 `/data/system/theme/icons` 与对应系统归档哈希、来源标记及每条已有自定义图标的资源哈希，检查桌面可见并截图。无论成功或失败，都尝试恢复原主题；恢复失败也是发版阻断项。

每阶段单独保存结果，失败可在修复后重跑相应阶段；不能复用旧版本的 `imports` / `monet` 成功标记来代替新一轮检查。代码修改涉及解析、转换、生成、映射、刷新或 Monet 时应重新运行受影响的完整阶段。

重新导入会先作废旧导入、Monet 和应用成功结果；重新检查 Monet 会作废旧 Monet 与应用结果。正式应用阶段在身份校验前即写入未通过状态，失败重跑不能继续留下旧的绿色结果。

Monet 成功标记在恢复完成后才写入，绑定实际安装的 QA APK、测试 APK、原生库哈希、源代码指纹和导入矩阵。R8 应用阶段要求同一源代码指纹与原生库；版本号、更新日志与 catalog 的发布身份变更不改变该指纹。报告分别保留 QA 的 Monet 测试身份与正式 R8 产物身份，不把它们混为同一个 APK。开始新一轮 Monet 检查会删除旧成功标记。

当前 Phone AVD 的桌面固定将“设置”放在 Dock 第二格，截图为 1120×2436；默认仅采样其内部 `(375,2160)-(480,2265)` 区域，避开角标、文字与壁纸。该区域已通过桌面截图核对。桌面布局或分辨率变更后，应先重新确认设置图标位置，再显式传入 `--monet-screen-size` 和 `--monet-icon-bounds`。脚本要求桌面在前台、截图尺寸匹配，并命中预期的两种配色；不会用整张截图的任意差异替代图标验证。普通 PackageManager 图标不是桌面的 MAML 实际渲染结果，不能用作此处的颜色证明。

## 检查证据

- `matrix.json`：库存完整性、来源列表、逐包导入结果和原生授权。
- `monet-device.log`、`monet-runtime-*.log`：必须真实执行且没有失败、假设跳过或忽略。
- `monet-runtime-*/`：两个种子色 × 两个深浅色状态的框架图标和桌面截图。
- `application.json`：每个主题的实际应用结果和正式 APK 身份。
- `restoration.json`：原主题、深浅色和用户自定义恢复检查。
- `release-deployment/`：进程身份、热重载回执及未确定覆盖范围。

发布前还需查看截图，检查桌面图标、标签、动态日历和时钟、Monet 形状/底色与切换落点。脚本核对文件和渲染结果，不能证明所有 OEM 合成和动画都没有视觉问题。保留失败尝试，勿将中断或仅打开详情的状态写为通过。

与设备检查一起运行：

```bash
python3 -m unittest discover -s scripts/tests/release-iconpacks
bash scripts/tests/test-monet-policy-server.sh
bash scripts/tests/test-monet-install-readiness.sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
python3 scripts/i18n/check_resources.py
python3 scripts/tests/release-notes/check_catalog.py
```

签名配置沿用本地构建流程，不能把密钥或密码加入命令、脚本、日志和文档。创建标签不等于推送或公开发布；只有全部检查通过、正式产物与最终源码对应后才创建本次 release tag。

# MIUIX 07d48590 Snapshot

KeiHome 从 `0.9.5-84ce2a0a-SNAPSHOT` 升级到 `0.9.5-07d48590-SNAPSHOT`。
2026-11-24 核对了 `main` 的提交和成功的 GitHub Packages 发布。十个 Android AAR
均解析到目标 Snapshot，AndroidX 与 JetBrains Compose UI 仍解析到 `1.13.0-rc01`。
[上游提交对照][comparison] · [发布回执][publish]

- `LocalizedInputField` 在启用状态下复用 MIUIX 的 `SearchClearButton`，继续提供本地化无障碍名称和 `48 dp` 点击区域。禁用状态沿用现有控件语义。
- 现有 Glass 底部面板继承上游 `SheetMotion.Glass`。壁纸强度滑块随上游版本支持轨道点按，共用面板继续管理页面滚动和预览状态。
- 导航浮层和折叠顶栏随依赖升级。组件测试覆盖宽屏和窄屏导航、折叠标题、菜单定位与返回。

## 验证

- 签名 Release 与 Android 测试 APK 构建通过。Release Lint 通过。JVM 测试通过 2,146 项，跳过 8 项。三语资源检查零错误、零警告。
- HyperOS Phone（`OS4.0.31.0.XFRCNXM`）原位更新至 `b0749`，关键进程 API 102 热重载通过。检查了壁纸强度面板的打开、切换和退出动画，滑块点按从 `25%` 调到 `68%` 后恢复 `25%`，设置搜索清除及返回通过。
- 在 HyperOS Phone 和 Pad（`OS4.0.15.0.XBMCNXM`）上分别通过 13 项独立 Debug 组件测试，覆盖本地化搜索输入、禁用时的清除行为、顶栏、导航和下拉菜单。Pad 原有应用使用开发签名，签名 Release 覆盖安装被系统拒绝。两台设备的独立测试包已移除。

构建日志、依赖图、AAR 哈希、测试结果、Phone 录屏及部署回执位于
`.artifacts/research/miuix-snapshot-20261124/`。

[comparison]: https://github.com/compose-miuix-ui/miuix/compare/84ce2a0a577ccc34803e39df2e570e1f1068f4f9...07d4859021d7aed1a1f78afaee12c5c8a6917466
[publish]: https://github.com/compose-miuix-ui/miuix/actions/runs/45226040998

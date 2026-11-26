# KeiHome

简体中文 · [English](README.en.md) · [日本語](README.ja.md)

![KeiHome 图标](branding/exports/keihome-light.svg)

KeiHome 是面向小米 HyperOS 的桌面和系统外观定制工具，通过 LSPosed 接入受支持的系统组件。你可以整理桌面图标、使用 Monet 动态配色，并在一个界面里调整桌面、深色模式和手势栏。

[下载稳定版](https://github.com/hosizoraru/KeiHome/releases/latest) · [更新日志](https://github.com/hosizoraru/KeiHome/releases) · [Telegram 更新频道](https://t.me/VoyagerMIUIUpdate) · [反馈问题](https://github.com/hosizoraru/KeiHome/issues)

## 主要功能

- **图标包与补全**。浏览内置方案，使用完美图标补全计划或 Apple 图标方案，导入 APK、MTZ、ZIP 图标包。可以逐应用保留原图标、替换映射，或为静态分层图标单独提供底图与前景图。[图标定制](icon-customization.md) · [完美图标补全计划接入](perfect-icons.md)
- **Monet 动态配色**。选择取色来源，预览亮色和暗色调色板，按需对受支持的图标和系统界面启用配色。[Monet 文档](monet-customization.md)
- **桌面与系统外观**。在支持的版本上调整 Dock、桌面图标、启动效果、壁纸调暗和系统暗色风格。应用详情、通知等系统界面的图标适配，以及沉浸手势栏的效果，取决于宿主版本和已启用的作用域。[桌面设置入口](desktop-navigation.md) · [手势栏适配](gesture-navigation-backdrop.md)

## 安装与启用

当前安装包面向 `arm64-v8a`，最低要求 Android API 37。模块功能需要可用的 LSPosed 环境，支持现代 Xposed API 102。Android 版本满足要求后，具体功能仍取决于 HyperOS、系统桌面、主题壁纸和 SystemUI 的版本。

1. 从 GitHub Releases 下载 APK 并安装。
2. 在 LSPosed 管理器启用 KeiHome，按使用向导选择所需作用域。
3. 首次启用后完整重启设备，解锁回到系统桌面，再在 KeiHome 的「状态」页确认模块运行情况。
4. 更换图标包时，先预览，再按页面提示生成或更新，并在主题壁纸中应用。修改图标映射后按提示更新图标包并重新应用。

覆盖升级请遵循对应版本的发布说明。更新包含原生库变更时，需要重启系统。使用向导可从「设置 → 更新日志」重新打开。

## 开发与反馈

KeiHome 使用 Kotlin、Jetpack Compose 和 MIUIX。按 [应用构建说明](../app/README.md) 准备 SDK 并运行 `./gradlew :app:assembleDebug`。

| 从哪里开始 | 文档 |
| --- | --- |
| 代码与构建职责 | [项目结构](project-structure.md) · [build-logic](../build-logic/README.md) |
| 测试与 CI | [本地脚本](../scripts/README.md) · [验证指南](testing.md) · [GitHub Actions](ci.md) |
| UI 和图标渲染 | [MIUIX OS4 / Glass](miuix/README.md) · [图标渲染契约](icon-rendering-contracts.md) |
| 提交改动或翻译 | [贡献指南](../CONTRIBUTING.md) · [本地化指南](localization.md) |

反馈时请提供 KeiHome 完整版本、HyperOS 和桌面版本、触发步骤及截图。图标、桌面或通知等模块问题请附 LSPosed 日志，KeiHome 界面异常请附应用日志。

## 许可与致谢

KeiHome 项目代码使用 [Apache License 2.0](../LICENSE)。第三方组件、图标和素材沿用各自许可，见 [项目声明](../NOTICE) 和 [第三方声明](../THIRD_PARTY_NOTICES.md)。

感谢 [MIUIX](https://github.com/compose-miuix-ui/miuix)、[完美图标补全计划](https://github.com/pzcn/Perfect-Icons-Completion-Project)及各上游项目。感谢 [@jizizr](https://github.com/jizizr) 贡献静态图标明暗切换与桌面图标批量刷新。

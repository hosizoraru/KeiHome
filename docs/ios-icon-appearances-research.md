# iOS 图标外观研究

2026-11-22，Xcode 27.3 beta（27C5035i）、Icon Composer 27.3、iPhone 17e 模拟器（iOS 27.3）。以下系统渲染结论取自主屏幕截图。设备图标查询、App Store 图片和 Icon Composer 预览各自独立取证。

## 素材与渲染

| 主屏幕外观 | 可用的作者素材 | 系统处理 |
| --- | --- | --- |
| 默认 | 彩色平面图或 `.icon` 分层图 | 根据目标尺寸、图标轮廓和素材渲染 |
| 深色 | AppIcon 的 Dark 图片，或 `.icon` 的 Dark 外观设置 | 有专用资源时沿用作者设计。只有默认平面图时，系统可为适合的图形生成深色效果 |
| 透明 | `.icon` 的 Mono 外观设置 | 保留单色结构，结合明暗子模式、玻璃和背后内容渲染 |
| 色调 | `.icon` 的 Mono 外观设置，或传统 AppIcon 的灰阶 Tinted 图片 | 用用户选定的色调和明暗子模式渲染单色结构，同时保留明度差异 |

Icon Composer 的 **Mono** 是透明和色调共用的一组灰阶外观设置。它可以保留多个前景层、明度差异和透明度。Light、Dark、是否着色、色相和强度都可以在 Mono 预览选项中切换。材质属性由分组和图层控制。工具栏可以比较第 26、27 代材质和关闭效果后的画面。Apple 还明确说明第 27 代的折射设置在更早的系统上没有可见效果。 [Icon Composer 文档](https://developer.apple.com/documentation/xcode/creating-your-app-icon-using-icon-composer) · [WWDC26 Mono 与透明／色调讨论](https://developer.apple.com/videos/play/wwdc2026/8012/) · [AppIcon 素材配置](https://developer.apple.com/documentation/xcode/configuring-your-app-icon#Add-dark-and-tinted-icon-variants-to-iOS-and-iPadOS)

## iOS 27.3 主屏幕对照

平面样本来自只含默认图像的 AppIcon。`FlatLab` 与 `AuthoredLab` 使用同一默认图像，后者额外提供一张带深底、去掉细下划线的 Dark 图像。`assetutil --info` 确认前者只有默认 rendition，后者有 `UIAppearanceDark`。`LayeredLab` 使用带前景 SVG、分组材质和背景的 `.icon`，其编译产物有图层栈。

| 样本 | 深色 | 透明与色调 |
| --- | --- | --- |
| 白底蓝圆、暖白底蓝圆 | 底板变深，圆保留蓝色 | 圆变为亮色主体，底板随子模式变化 |
| 蓝底白圆 | 底板变深，白圆变为蓝色 | 圆仍是亮主体，与白底蓝圆在结构上接近 |
| 白底黑圆 | 白底和黑圆保持原样 | 保持亮底、暗圆的明暗关系 |
| 蓝色矩形接触左边缘 | 变为深底蓝矩形 | 贴边轮廓仍在 |
| 米白底、蓝字形、橙点和细线 | 原彩保留 | 背景变为暗玻璃，字形、圆点和细线仍可见 |
| 浅渐变、紫红品牌底、整幅纹理 | 这批样本保留原彩 | 渐变样本仍可辨认，紫红样本的前景对比明显下降，纹理样本保留了灰阶细节 |
| 显式 Dark 对照 | 深底和缺失的细线与作者资源一致 | 细线重新出现，匹配有细线的默认来源。Dark 的像素结果没有被直接着色 |
| `.icon` 分层对照 | 深底保留蓝字形和橙点 | 字形和圆点共用 Mono 的明度关系，玻璃边缘和底色随外观变动 |

在同一图标的底板和圆心取样，白底蓝圆分别从约 `(254,255,254)`、`(35,101,243)` 变为 `(25,27,25)`、`(33,99,240)`。蓝底白圆从 `(20,101,243)`、`(241,245,255)` 变为 `(27,27,25)`、`(33,106,241)`。样本支持这样的推断：自动深色可能识别适合重排的色块，并在重排后保留品牌色。现有观测无法确定决策阈值、分层算法、缓存时机或是否使用了模型。

透明和色调的截图里可以看到壁纸亮线穿过某些图标，壁纸、底板、前景在不同模式下仍有各自的明度。Icon Composer 的同一份 `LayeredLab.icon` 中，关闭效果以后仍有灰阶结构和着色，边缘高光和层间质感随效果和设计代际变化。这样的分工允许同一份 Mono 素材适配不同色调和明暗，而材质由渲染端完成。实测范围为一台 iPhone 模拟器、一套壁纸和一组主屏幕色调。截图覆盖静置外观，未测滚动时的折射或 iPadOS。

## Salt Player 的来源核对

- 断开前的实体 iPhone 上，Salt Player `com.xuncorp.sp` 1.1.0（6）在深色主屏幕中显示深底图标。Apple 的设备图标服务返回的是浅底图，与主屏幕渲染不同。
- App Store 的 Salt Player（ID `6787021904`）有可解码且像素不同的浅色和深色图片。获取入口见 [Apple 深色图标来源说明](apple-dark-icons-for-hq-icon.md)。设备服务、App Store 图片和主屏幕图标对应三个观测入口。现有证据无法确认安装包是否带有作者编写的 Dark 或 `.icon` 素材，也无法用 CDN 的 `appearance=dark` 证明生成方式。
- 已检查实体 Android 上提取的 Salt Player APK。其启动资源声明了白色 `ic_launcher_background`、彩色 `ic_launcher_foreground` 和 `ic_launcher_monochrome`。已核对的启动图标引用没有独立的 `night` 资源。彩色深色补全可以从现有前景和底板动手，单色补全已有作者提供的蒙版可复用。

## KeiHome 的接入建议

1. **先复用分层输入。** 沿用共享解析器的优先级，保留用户选择和现成深色资源。对于 Android 自适应图标，通过 `PerfectStaticNightRenderer` 复用作者前景，主要调整底板。用 `PerfectNightPixels` 的前景对比修复处理确实需要抬亮的区域，保留字形、Logo 颜色和局部留白。
2. **平面图做保守补全。** 整合进已有的 `PerfectCompositeForegroundProof`，用底板一致性、主体可分离性和输出对比度决定是否重排。对多色品牌全幅、复杂纹理和分离不稳的图保留有辨识度的原版。验收样本应包括贴边形状、多色主体、深色细线和容易误判的黑白图。
3. **单色结构与最终外观分开。** 先使用原生 monochrome 或图标包已有的 Monet 蒙版，再进入共享 `GenericMonetMask` 回退。后续若尝试 iOS 风格，可以先比较同一蒙版在高对比浅、深底板上的表现，具备可靠分层时再考虑额外明度和边缘信息。预览、生成与应用应复用同一个资源选择结果。
4. **静态资源和桌面材质分别验证。** 深色和色调可以生成并缓存静态图。对壁纸透射有要求时，另外验证目标桌面的材质和合成能力，静态 PNG 只适合固定底板的近似外观。保持现有静态图标路径，避免因全包引入 MAML 而增加桌面动效成本。

## KeiHome 实验实现与验收

静态深色渲染第 15 版与图标包生成格式第 19 版共享双色图形的覆盖率分析。只有底板占比、前景面积、边缘分布和像素混合都符合预期，才尝试重建双色图。浅底转为深底时，抗锯齿像素按新底板重混。高饱和底板上的浅标可以继承原底板色，颜色调整过大或最终对比不足时回退。取色补全优先用已有作者蒙版，其次使用符合条件的原生分层前景。两者都缺失时才从平面图推导。该实现继续使用 Android 的现有图标输出格式。

Xiaomi 17 Pro（`OS4.0.8.0.XBLCNXM`，Home `801030308`）上的 Android 位图渲染对照覆盖 8 组素材。所有浅色 PNG 与上一版逐字节一致。蓝底白标得到深底蓝标，白底和暖白底蓝标的边缘重新混合。多色品牌、渐变与纹理样本的深色输出保持一致。低对比度的分层样本能通过原始前景透明度生成有效 Monet 蒙版，归档测试同时比对了保留的源图层。

用同一台实体机上的 KeiOS 签名 Release `b0726` 从本地快照生成 iOS 样式（启用深浅色适配、仅已安装应用、保留原版动态）。在正式 KeiHome 入口完成本地生成及系统导入和应用，记录了亮暗桌面。随后从 Monet 的安装更新页打开取色补全并更新当前图标包，开启 Monet 并应用 Pixel / AOSP 壁纸标准配色。桌面的相机、应用商店等图标显示出配色变化。系统已应用归档新增 212 份 `mask.png`，同期 376 份编号彩色图层逐字节一致。验收后恢复了原 ColorOS · 经典 · ART+ 图标包和原有暗色及 Monet 开关状态，图标归档哈希与测试前一致。此轮端到端验收覆盖 iOS 样式。

工件位于 `.artifacts/research/perfect-icon-appearance-20261122/`。`android-appearance-comparison.png` 按浅色、旧深色、新深色、旧蒙版、新蒙版排列。`settled-monet-comparison.jpg` 记录应用相同配色后桌面亮暗两种状态。原生归档对照使用 `ios-b0726-applied.zip` 和 `ios-b0726-monet-applied.zip`。

## 复验资料

Apple 平台实验目录为 `.artifacts/research/ios-auto-dark-icons-20261122/`。`fixtures/` 保存平面与分层输入，`build-fixtures.py`、`build-followup.py`、`build-controls.py` 将样本编译并安装到 `sim-id.txt` 指定的实验模拟器。`*.assets.json` 记录素材表；`sim-controls-*.png` 和 `sim-settled-tint-light.png` 是主屏幕截图；`appearance-grid.py` 生成 `sim-icon-appearance-grid.png`，其中 N/A 表示对应样本被深色色调面板遮住。`composer-*.jpg` 保存工具预览，`salt-source-comparison.jpg` 保存 Salt Player 来源对照。

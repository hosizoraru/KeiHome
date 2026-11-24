# KeiHome 1.15.0 ColorOS 图标包应用回归

## 原因与修复

在 HyperOS 4 真机上，KeiHome `115000729` 生成 ColorOS 经典 ART+ 后，
`KeiHomePackImport` 在 `PACKAGING` 阶段报告
`Missing generated native resource: fancy_icons/com.miui.miservice/night.png`。
这发生在系统主题导入之前。

ColorOS 导出器在日夜图像相同时使用共用 `day.png` 的紧凑选择器。
「KeiHome 额外补全」为服务与反馈添加深色图案时，发布链路把选择器的共用图像
误判成可单独替换的夜间图。结果是普通回退资源变成一对不同的图像，
而 `fancy_icons` 仍只有 `day.png`。导入校验拒绝了这组不完整的资源。

发布时用已有的生成器把紧凑选择器展开为独立的日间图和夜间图，保留日间图，
并同步写入夜间回退图。独立夜间资源仍沿用原来的替换路径。Monet 选择器
按原有资源布局保留已存在的夜间文件，并继续使用原调色板绑定格式。

## 验收

小米 17 Pro `25098PN5AC`，系统 `OS4.0.8.0.XBLCNXM`，
桌面 `RELEASE-8.01.03.308-261102-11171006-R`，主题壁纸 `11.5.6.0`。
本轮使用同一台实体机。

签名 R8 候选 `115000730` 在 KeiHome 的 ColorOS 页面完成经典 ART+ 的
重新生成、导入和应用。活动图标包包含原版日间图和独立夜间图，
夜间图与普通图标回退资源逐字节相同。Java API 102 热重载记录已核对，
且候选 APK 与已安装公开版携带相同的原生库字节。

现有图标包回归在同源码的签名 QA 构建上跑通 23 项，覆盖 ColorOS 五种风格
各自的 ART+ 开关、五款已安装 APK、本地 MTZ／ZIP、Apple 和完美图标发布包。
每一项手动应用均核对原生主题组件与活动包哈希、KeiHome 来源标记、已有自定义图标资源
和前台 Launcher。首轮导入仪器的偏好一致性断言把主题组件登记也计作偏好变化。
逐包导入回执均成功，矩阵现仅排除本轮已确认组件的调色能力登记。

### 自动应用专项

使用与目标应用相同正式签名的测试 APK，在小米 17 Pro 上开启「生成后自动应用」，
对 ColorOS Classic ART+ 重跑真实导入。仪器返回 `OK (1 test)`，回执为
`imports_and_auto_application_passed`，`applicationVerified=true`，主题侧返回
`ALREADY_IMPORTED`，活动包和 KeiHome 来源标记均通过。该回执证明自动应用路径已被
执行并确认，不需要再点击系统主题的「应用」按钮。

Release 构建、Release Lint、2,145 项 JVM 测试、11 项验收脚本测试与
4 项额外补全仪器测试通过。设备矩阵包含 19 个组合、88 个资源包，所有生成、导入和应用回执均通过。
JVM 套件另有 12 项按条件跳过。
设备验收文件位于 `.artifacts/research/physical-iconpack-regression-20261124/`。

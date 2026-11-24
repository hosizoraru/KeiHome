# 本地空间清理记录（2026-11-24）

本次只清理 KeiHome checkout 内的可再生缓存、已经被清理索引取代的旧压缩归档，以及未被文档或当前研究引用的过期实验输出。没有修改全局 Gradle/Android SDK 缓存、AVD 数据、设备应用、Git 历史、正式 release 目录或当前未提交的 1.15.0 更新日志。

## 清理结果

- 第一批删除 266 个缓存/旧归档条目，逻辑文件大小约 7.65 GiB。
- 第二批删除 16 个 1.12–1.13 时代的未引用实验目录，逻辑文件大小约 8.18 GiB。
- 深度研究清理删除 13,835 个旧二进制研究输出，逻辑文件大小约 20.54 GiB。每个旧研究目录的小型报告、脚本、摘要和 JSON/XML 元数据得到保留。
- 历史 release 目录清理删除 221 个未引用 APK、AAB、native 库和签名文件，逻辑文件大小约 6.07 GiB。文档引用的 release 组和每条版本线的最新组得到保留。
- 输入裁剪删除 29 个过期 Security Center 输入和调试符号，逻辑文件大小约 0.51 GiB。Launcher 的 `debug.elf` 已从对应 `libapp.so` 内嵌的 `.gnu_debugdata` 重新生成，并通过输入完整性检查。
- 合计回收逻辑文件约 42.95 GiB。项目目录从约 58 GiB 降至约 16 GiB，主卷可用空间由约 70 GiB 增至约 124 GiB。
- 版本号分配目录 `.gradle/keihome-version`、正式 release 元数据、`.artifacts/inputs`、壁纸研究、MIUIX 迁移和状态栏通知图标研究证据均已复核存在。
- 修正了被清理的历史归档、旧 Themes APK 和过期 release APK 的文档路径，使报告明确指向当前保留的 canonical 输入或 receipt。
- 清理后 `git diff --check` 通过，当前源码未提交改动保持不变。

清理计划、删除结果、输入恢复记录和复核信息位于
`.artifacts/research/cleanup-20261124/`。下次 Gradle 构建会按需重新生成已删除的项目
build 目录。

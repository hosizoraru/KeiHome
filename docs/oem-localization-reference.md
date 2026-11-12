# OEM localization reference

This is a small terminology reference for KeiHome translators, researched on
2026-11-12 against the configured HyperOS Phone AVD. It is not a claim that all
HyperOS releases or regions use identical copy. No system language, provisioning
flag or user data was changed for this research.

## Inputs and extraction

DexSQL read the compiled resource tables of the installed `com.android.settings`
(`/system_ext/priv-app/Settings/Settings.apk`), framework `android`
(`/system/framework/framework-res.apk`), and first-boot `com.android.provision`
(`/system_ext/priv-app/Provision/Provision.apk`). English is the default resource
configuration for the entries below; Simplified Chinese is `zh-rCN`, Japanese
is `ja`. Resource names, not translated text or version numbers, join the rows.

Locally retained inputs and SHA-256 receipts live in ignored `.artifacts/inputs/localization/`
and `.artifacts/research/release-1.8.0-20261112/`. The latter retains
`oem-localization-inputs.json`, the DexSQL extraction script and
`oem-terminology.json` with exact resource IDs and values. Do not add OEM APKs,
full resource dumps, code, artwork or long descriptions to translation PRs.
This table uses only short functional labels to clarify meaning.

## Observed terms

`S` = Settings, `F` = framework, `P` = Provision.

| Resource | 简体中文 | English | 日本語 |
| --- | --- | --- | --- |
| `S:home_title` | 桌面 | Home screen | ホーム画面 |
| `S:storage_settings` | 存储空间 | Storage | ストレージ |
| `S:app_info_storage_title` | 已用空间 | Space used | 使用済み容量 |
| `S:cache_header_label` | 缓存 | Cache | キャッシュ |
| `S:clear_cache_btn_text` | 清除缓存 | Clear cache | キャッシュを削除 |
| `S:application_info_label` | 应用信息 | App info | アプリ情報 |
| `S:theme_settings_title` | 个性主题 | Themes | テーマ |
| `S:wallpaper_settings_title` | 壁纸 | Wallpaper | 壁紙 |
| `S:dark_color_mode` | 深色模式 | Dark mode | ダークモード |
| `S:filter_apps_third_party` | 已下载 | Downloaded | ダウンロード済み |
| `S:captioning_preview_title` | 预览 | Preview | プレビュー |
| `S:account_sync_settings_title` | 同步 | Sync | 同期 |
| `S:chip_reset_menu` | 恢复默认配置 | Restore defaults | デフォルトに戻す |
| `S:application_item_already_permission` | 已允许 | Allowed | 許可済み |
| `S:application_item_permission` | 允许 | Allow | 許可 |
| `S:bluetooth_refresh` | 刷新 | Refresh | 更新 |
| `F:cancel` | 取消 | Cancel | キャンセル |
| `F:delete` | 删除 | Delete | 削除 |
| `F:action_mode_done` | 完成 | Done | 完了 |
| `F:selectAll` | 全选 | Select all | すべて選択 |
| `F:autofill_update_yes` | 更新 | Update | 更新 |
| `P:next` | 继续 | Next | 次へ |
| `P:boot_video_skip` | 跳过 | Skip | スキップ |
| `P:miuix_appcompat_deselect_all` | 取消全选 | Deselect all | すべての選択を解除 |

## Applying terms by meaning

- Use **Home screen / ホーム画面** for the user's surface and navigation.
  Use **launcher / ランチャー** for the host component, version or process.
  Do not mechanically replace every occurrence of either word.
- **Downloaded / ダウンロード済み** is a local resource state, not proof that
  an icon pack has been generated, imported into Themes or applied.
- **Refresh** rereads a list or status; **Check for updates** checks the remote
  revision; **Update** changes resources. Japanese may use 更新 for all three,
  so retain objects such as 状態を更新 or 更新を確認 where necessary.
- **Clear cache / キャッシュを削除** is a familiar storage operation. The preview
  memory action actually releases in-memory previews; its explanation must not
  promise to delete downloaded sources or generated packs.
- **Delete** downloaded resources is not **Uninstall** an app. Confirmation
  text must identify exactly what is removed and what is retained.
- **Deselect all / すべての選択を解除** clears selection. It does not delete items.
- Scope authorization is an LSPosed grant, not an Android permission prompt.
  Keep スコープの許可, framework connection and module loading distinct;
  許可済み does not imply 読み込み済み.
- Mask import is **読み込み**, while the resource download is ダウンロード.
  A mask comparison or preview does not apply the theme.
- These sources do not define KeiHome-specific concepts such as Git resource
  transactions, layered icon completion, API 102 or custom-mask precedence.
  Write original, concise descriptions of their actual behavior; do not
  repurpose an unrelated OEM label merely because its Chinese text matches.

See [Localization](localization.md) for resource syntax, fallbacks, plural rules,
placeholder checks and language registration. Preserve earlier supplied release
announcements when correcting current UI terminology.

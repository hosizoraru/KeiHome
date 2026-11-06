# App entry and update channel

## Launcher visibility

The App settings switch disables only `LauncherEntry`, the exported launcher
alias. `MainActivity` remains enabled and exports MAIN/INFO and the LSPosed
MODULE_SETTINGS category, including implicit default-category resolution.
PackageManager's persisted component state is the source of truth. No duplicate
MMKV preference is needed.

Changes use `DONT_KILL_APP | SYNCHRONOUS` on a serialized IO operation. Returning
to settings rereads the component state. Restoring the icon resets the alias to
its manifest default. APK updates retain an explicit disabled state.

Android may expose a synthetic `android.app.AppDetailsActivity` through
LauncherApps. This is a system app-management entry, not the launcher alias.
The module does not attempt to disable this system-generated component or hide
the app from Android's installed-app settings.

## Telegram

The About link opens `tg://resolve?domain=VoyagerMIUIUpdate` with an available
handler, without requiring a specific Telegram client. If no client can open
that URI, it falls back to `https://t.me/VoyagerMIUIUpdate`. If neither route is
available, a localized message explains the missing handler. Opening a channel
does not subscribe the user.

Both features include English, Simplified Chinese and Japanese strings, and are
searchable from settings.

## Verification

Device tests cover component visibility, preserved module-settings and INFO
routes, restoration without killing the app, Telegram client routing, HTTPS
fallback and absent handlers. Actual UI, install persistence and process
identity receipts are retained separately under
`.artifacts/research/app-entry-20261106/`.

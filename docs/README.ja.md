# KeiHome

[简体中文](README.md) · [English](README.en.md) · 日本語

![KeiHome のアイコン](branding/exports/keihome-light.svg)

KeiHome は Xiaomi HyperOS のホーム画面とシステムの外観をカスタマイズするツールです。LSPosed を通じて対応するシステムコンポーネントと連携し、アイコン、Monet の配色、ダークモード、ジェスチャーバーを調整できます。

[安定版をダウンロード](https://github.com/hosizoraru/KeiHome/releases/latest) · [リリースノート](https://github.com/hosizoraru/KeiHome/releases) · [Telegram 更新チャンネル](https://t.me/VoyagerMIUIUpdate) · [問題を報告](https://github.com/hosizoraru/KeiHome/issues)

## 主な機能

- **アイコンパックとアイコン補完**。内蔵のスタイル、Perfect Icons 補完プロジェクト、Apple のアイコンスタイルを利用できます。APK、MTZ、ZIP のパックのインポートにも対応します。アプリごとに元のアイコンを維持したり、割り当てを変更したり、静的なレイヤーアイコンの背景と前景を指定したりできます。[アイコンのカスタマイズ](icon-customization.md) · [Perfect Icons の連携](perfect-icons.md)
- **Monet の動的配色**。色の取得元を選び、ライトとダークのパレットをプレビューできます。対応するアイコンやシステム画面への適用は個別に有効にできます。[Monet の詳細](monet-customization.md)
- **ホーム画面とシステムの外観**。対応するバージョンでは、Dock、ホーム画面のアイコン、起動エフェクト、壁紙の暗さ、システムのダークスタイルを調整できます。アプリ情報や通知のアイコン、ジェスチャーバーの挙動は、対象のシステムコンポーネントと有効にしたスコープに応じて変わります。[ホーム画面の設定](desktop-navigation.md) · [ジェスチャーバーの連携](gesture-navigation-backdrop.md)

## インストールと有効化

APK は `arm64-v8a` 向けで、Android API 37 以降が必要です。モジュール機能には、モダン Xposed API 102 に対応した LSPosed 環境が必要です。各機能の対応状況は HyperOS、ホームアプリ、テーマアプリ、SystemUI のバージョンによって異なります。

1. GitHub Releases から APK をダウンロードしてインストールします。
2. LSPosed マネージャーで KeiHome を有効にし、セットアップガイドに沿って使いたい機能のスコープを選びます。
3. 初回は端末を再起動し、ロックを解除してホーム画面を開きます。その後、KeiHome の「状態」で稼働状況を確認します。
4. アイコンパックをプレビューし、案内に沿って生成または更新してからテーマアプリで適用します。アイコンの割り当てを変更した場合は、パックを更新して再適用します。

アップデートの際は、対象バージョンのリリースノートを確認してください。ネイティブライブラリを含む変更を反映するには再起動が必要です。セットアップガイドは「設定 → 更新履歴」から開き直せます。

## 開発と問題報告

KeiHome は Kotlin、Jetpack Compose、MIUIX を使用しています。[アプリのビルド手順](../app/README.md)に沿って SDK を用意し、`./gradlew :app:assembleDebug` を実行してください。

| 項目 | ドキュメント |
| --- | --- |
| コードとビルドの構成 | [プロジェクト構成](project-structure.md) · [ビルドロジック](../build-logic/README.md) |
| テストと CI | [開発用スクリプト](../scripts/README.md) · [テストガイド](testing.md) · [GitHub Actions](ci.md) |
| UI とアイコンの描画 | [MIUIX OS4 / Glass](miuix/README.md) · [アイコン描画の仕様](icon-rendering-contracts.md) |
| コードと翻訳への貢献 | [コントリビューションガイド](../CONTRIBUTING.md) · [翻訳ガイド](localization.md) |

問題を報告する際は、KeiHome の完全なバージョン、HyperOS とホームアプリのバージョン、再現手順、スクリーンショットを添えてください。アイコンや通知などのモジュール機能には LSPosed のログ、KeiHome 自体の画面にはアプリのログを添えてください。

## ライセンスと謝辞

KeiHome のプロジェクトコードは [Apache License 2.0](../LICENSE) を採用しています。サードパーティのコンポーネント、アイコン、素材にはそれぞれのライセンスが適用されます。[プロジェクトの通知](../NOTICE)と[サードパーティの通知](../THIRD_PARTY_NOTICES.md)を参照してください。

[MIUIX](https://github.com/compose-miuix-ui/miuix)、[Perfect Icons 補完プロジェクト](https://github.com/pzcn/Perfect-Icons-Completion-Project)、各上流プロジェクトに感謝します。静的アイコンのライト・ダーク切り替えとホーム画面の一括更新を提供してくれた [@jizizr](https://github.com/jizizr) に感謝します。

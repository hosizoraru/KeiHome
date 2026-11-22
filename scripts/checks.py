#!/usr/bin/env python3
"""Preview or run a deliberately selected check group; never build/install device APKs."""

import argparse
from pathlib import Path
import re
import shlex
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
UI = "os.kei.hyper.home.ui.main."
GROUPS = {
    "navigation-backdrop": ("jvm", "Opt-in native policy delivery, bounds and configuration identity", (
        "NavigationRuleOverlayTest",)),
    "navigation-backdrop-device": ("device", "Native rules, footer sampling, gesture glass and conditional OEM UID fixture", (
        "os.kei.hyper.home.xposed.NavigationBackdropDeviceTest",
        "os.kei.hyper.home.xposed.GestureGlassFinishDeviceTest",
        "os.kei.hyper.home.xposed.FooterSamplingDeviceTest",)),
    "apple-matching": ("jvm", "Conservative matching, explicit retry eligibility and browser selection", (
        "AppleAutoMatchPolicyTest", "AppleMappingSelectionTest", "AppleMappingBrowserTest")),
    "apple-matching-device": ("device", "Persisted matching history, cached retries and HTTP backoff (fake transport)", (
        "os.kei.hyper.home.iconpack.apple.AppleBatchMatchingDeviceTest",
        "os.kei.hyper.home.iconpack.apple.AppleRequestSchedulingDeviceTest")),
    "hook-initialization": ("jvm", "Independent startup stages, coalesced work, host loader and API 102 drain", (
        "SystemUiInitializationTest", "CoalescingInitializationWorkTest",
        "SystemUiHostClassLoaderTest", "SystemUiHookGuardTest", "SystemUiThemeCompatibilityTest")),
    "perfect-night": ("jvm", "Perfect dark resource roles, selectors, caches and local date-frame admission", (
        "PerfectDynamicNightTest", "PerfectDynamicNightSwitchTest", "PerfectIconRepositoryTest",
        "LocalLayerTimeVisualValidatorTest", "LocalWeatherLayerProgramTest", "LocalNativeIconValidatorTest")),
    "perfect-night-device": ("device", "Static/dynamic dark pixels and masked cross-surface snapshots", (
        "os.kei.hyper.home.iconpack.perfect.PerfectStaticNightRendererDeviceTest",
        "os.kei.hyper.home.iconpack.perfect.PerfectDynamicNightRendererDeviceTest",
        "os.kei.hyper.home.xposed.SurfaceIconSnapshotRendererDeviceTest")),
    "statusbar-icons": ("jvm", "Status-bar descriptor roles, content identity and cache rejection", (
        "SystemUiStatusBarIconProfileTest", "SystemUiStatusBarOwnerPriorityTest")),
    "statusbar-icons-device": ("device", "App-only masking, native branches, cached view replay and retirement", (
        "os.kei.hyper.home.xposed.SystemUiStatusBarIconRuntimeDeviceTest",)),
    "native-default-aliases": ("jvm", "Native default-entry resource identity, explicit art and export invalidation", (
        "NativeDefaultAliasCompletionTest",)),
    "native-default-aliases-device": ("device", "Real package-manager proof and isolated native alias exports", (
        "os.kei.hyper.home.iconpack.NativeDefaultAliasCompletionDeviceTest",)),
    "native-artwork-import": ("jvm", "Raster headers, archive bounds, Perfect resources and local native admission", (
        "IconPackArchiveTest", "PerfectIconFormatTest", "PerfectLayeredIconTest",
        "LocalNativeIconValidatorTest", "LocalLayerTimeVisualValidatorTest", "ConvertedIconArchiveTest")),
    "native-artwork-import-device": ("device", "Local publication, mixed image previews and native layer rendering", (
        "os.kei.hyper.home.iconpack.PerfectPublicationDeviceTest",
        "os.kei.hyper.home.iconpack.ArchiveLayerRendererTest",
        "os.kei.hyper.home.status.IconImportLocalStoreTest", UI + "PerfectPackRecoveryDeviceTest")),
    "recommended-mappings": ("jvm", "Mapping choices, legacy defaults, alias ownership and update identity", (
        "RecommendedIconMappingsTest", "PhoneCompanionIconsTest", "TelegramChannelIconsTest", "BuiltinColorOsMappingsTest",
        "IconGenerationManifestTest", "IconPackUpdateNoticeTest", "SettingsSearchTest")),
    "ui-recommended-mappings": ("device", "Recommended mapping navigation, OS4 controls and actual generation", (
        UI + "RecommendedMappingsUiTest", UI + "RecommendedMappingsNavigationTest",
        "os.kei.hyper.home.iconpack.RecommendedMappingPipelineDeviceTest",
        "os.kei.hyper.home.iconpack.PhoneCompanionCompletionDeviceTest")),
    "icon-preservation": ("jvm", "Preserved packages, Telegram channels and applied generation notices", (
        "PreservedIconsTest", "TelegramChannelIconsTest", "PreservedIconResourcesTest",
        "IconGenerationManifestTest", "IconPackUpdateNoticeTest")),
    "list-menus": ("jvm", "Pack filters and stable Perfect resource/preview ordering", (
        "IconPackCatalogFilterTest", "PerfectListOrderingTest")),
    "ui-preserved-icons": ("device", "OS4 filters, sorting, search and original icon identity", (
        UI + "PreservedIconsUiTest",)),
    "icon-preview-device": ("device", "Original and published shapes, cache identity and Monet rendering", (
        "os.kei.hyper.home.data.IconPreviewRepositoryTest",
        UI + "CurrentIconPreviewRepositoryTest", UI + "IconPreviewShapeUiTest")),
    "telegram-icons-device": ("device", "APK/native Telegram channel export and preservation", (
        "os.kei.hyper.home.iconpack.TelegramChannelCompletionDeviceTest",)),
    "ui-i18n": ("device", "Localized input semantics, clear behavior and search callbacks", (UI + "LocalizedInputFieldTest",)),
    "i18n": ("python", "Resource XML, locale coverage and formatter compatibility (stdlib only)", ()),
    "release-notes": ("jvm", "Fresh installs, real upgrades, skipped releases and acknowledgment", (
        "ReleaseNotesPolicyTest", "ReleaseNotesMarkdownTest", "UpgradeRestartPolicyTest")),
    "ui-release-notes": ("device", "Changelog navigation, persistence, disclosures and large text", (
        "os.kei.hyper.home.data.ReleaseNotesPreferencesTest",
        "os.kei.hyper.home.ui.main.ReleaseNotesNavigationTest")),
    "ui-upgrade-restart": ("device", "Monotonic countdown and once-only native upgrade advice", (
        UI + "UpgradeRestartReminderTest",)),
    "notice-categories": ("jvm", "Exhaustive categories, stable routes and attribution identities", (
        "ThirdPartyCategoryTest",)),
    "ui-notices": ("device", "Category navigation, global search, source and license readers", (
        UI + "NoticesUiTest",)),
    "ui-actions": ("device", "FABs, disclosures, links and metadata pills", (
        UI + "UiActionPolishDeviceTest",)),
    "ui-navigation": ("device", "Both navbar styles, release/cancel gestures, spring offsets, themes and large text", (
        UI + "Os4ChromeDeviceTest", UI + "Os4ScrollInteractionTest", UI + "MainTabNavigationDeviceTest",
        "os.kei.hyper.home.data.PagerGestureMigrationDeviceTest")),
    "ui-miuix-migration": ("device", "Collapsed title groups, toolbar actions, navigation and pack help", (
        UI + "MiuixTopBarMigrationTest", UI + "Os4ChromeDeviceTest",
        UI + "Os4ScrollInteractionTest", UI + "PackGuidanceDeviceTest")),
    "ui-menus": ("device", "Native Glass/opaque menus, preferences, coordinates and Back", (
        UI + "Os4DropdownMenuDeviceTest",)),
    "ui-os4-controls": ("device", "Glass selectors, disabled options, dialog actions and menus", (
        UI + "Os4SelectorPreferenceUiTest", UI + "Os4DialogDeviceTest",
        UI + "Os4DropdownMenuDeviceTest")),
    "ui-os4-context": ("device", "Search editing, joined view modes, anchored help and Gaussian pipeline reuse", (
        UI + "Os4SearchInteractionTest", UI + "Os4SegmentedTabsDeviceTest",
        UI + "Os4InfoPopupDeviceTest",
        "os.kei.hyper.home.ui.components.KeiHomeBackdropPipelineDeviceTest",
        UI + "SettingsSearchUiTest", UI + "CacheStorageUiTest", UI + "PackGuidanceDeviceTest",
        UI + "PreservedIconsUiTest", UI + "PreservedIconsScreenTest")),
    "ui-glass-interactions": ("device", "Disclosure geometry, press cancellation, sheet tabs and Apple selection", (
        UI + "GlassInteractionsUiTest", UI + "AppleSelectionToolbarDeviceTest",
        UI + "MonetColorEditorTest", UI + "CacheStorageUiTest", UI + "IconMaskSettingsUiTest")),
    "ui-status": ("device", "Status cards, readiness and detail navigation", (
        UI + "MonetStatusContentTest",)),
    "startup-status": ("jvm", "Pending startup, bounded binding and genuine status failures", (
        "FrameworkBindingTest", "StartupStatusPresentationTest", "StartupDesktopFeatureStatusTest",
        "RuntimeStatusDashboardTest", "DesktopConnectionCardTest", "DesktopFeatureStatusTest",
        "StaticScopeRequestsTest", "StatusDashboardTest")),
    "ui-startup-status": ("device", "Pending status transitions, scope reads and genuine failures", (
        UI + "StartupStatusUiTest", UI + "ScopeAuthorizationUiTest")),
    "ui-theme-effects": ("device", "Theme-effect controls and scope availability", (
        UI + "ThemeEffectsCardTest",)),
    "ui-color-editor": ("device", "Color input, confirmation and draft recreation", (
        UI + "MonetColorEditorTest",)),
    "ui-pack-settings": ("device", "Pack preferences, navigation, and manual Themes fallback", (
        UI + "PackSettingsNavigationTest", UI + "PackSettingsOpenCurrentIconPackTest",
        UI + "NativeThemeManualFallbackUiTest")),
    "status-pack-shortcut": ("jvm", "Default-on preference and applied-source navigation", (
        "OpenCurrentIconPackPreferencesTest", "StatusIconPackDestinationTest")),
    "icon-pack-read": ("jvm", "Archive validation receipts, bounded metadata reuse and progressive loading", (
        "IconPackArchiveTest", "IconPackValidationCacheTest", "ResourceOverlayCacheTest",
        "ProgressiveIconPackCatalogTest", "ApplePackLoadGateTest", "AppsBrowserDisplayTest")),
    "ui-pack-read": ("device", "Apple entry reuse, invalidation and asynchronous rows", (
        UI + "ApplePackLoadingDeviceTest", UI + "ApplePackNavigationTest")),
    "ui-pack-previews": ("device", "Compact actions, artwork alpha, published masks and preview invalidation", (
        UI + "CompactPackPreviewUiTest", UI + "AppliedPackThumbnailPolicyUiTest",
        UI + "MaskComparisonPreservationUiTest", UI + "IconPreviewShapeUiTest",
        UI + "PackGuidanceDeviceTest", "os.kei.hyper.home.iconpack.OriginalIconShapePolicyDeviceTest",
        "os.kei.hyper.home.iconpack.IconMaskPreviewPipelineDeviceTest")),
    "pack-guidance-retention": ("jvm", "Versioned help, generated archive ownership and cover routing", (
        "PackGuidancePreferencesTest", "SystemFallbackPreferencesTest", "GeneratedPackRetentionTest",
        "IconPackArchiveTest", "PerfectIconRepositoryTest", "PerfectCompositeTextCoverageTest")),
    "ui-pack-guidance-retention": ("device", "First-visit sheets, shared retention setting and manual help", (
        UI + "PackGuidanceDeviceTest", UI + "PackSettingsGuidanceUiTest",
        UI + "PackSettingsOpenCurrentIconPackTest",
        "os.kei.hyper.home.data.PackGuidanceStorageTest")),
    "generated-pack-retention": ("device", "Perfect publication copies and Apple generation retention", (
        "os.kei.hyper.home.iconpack.PerfectPublicationDeviceTest",
        "os.kei.hyper.home.iconpack.AppleIconRepositoryDeviceTest")),
    "icon-mask": ("jvm", "Imported XML, shape preservation and generation identity", (
        "NativeIconMaskImportTest", "NativeIconShapeConfigTest", "IconGenerationManifestTest",
        "IconPackUpdateNoticeTest")),
    "icon-mask-device": ("device", "Private mask import, alpha-only rendering and preview parity", (
        "os.kei.hyper.home.iconpack.CustomIconMaskStoreDeviceTest",
        "os.kei.hyper.home.iconpack.IconMaskPolicyDeviceTest",
        "os.kei.hyper.home.iconpack.IconMaskPreviewPipelineDeviceTest",
        "os.kei.hyper.home.iconpack.NativeIconShapeCompletionDeviceTest")),
    "ui-icon-mask": ("device", "Mask child navigation, comparison and restore confirmation", (
        UI + "IconMaskSettingsUiTest",)),
    "ui-about": ("device", "Fixed About copy action, scrolling and hero semantics", (
        UI + "AboutCopyUiTest",)),
    "ui-settings-search": ("device", "Settings search, preview placement and persistence", (
        UI + "SettingsSearchUiTest",)),
    "ui-cache": ("device", "Cache pills, equal chart columns, reminder editor and resource links", (
        UI + "CacheStorageUiTest",)),
    "ui-log-export": ("device", "App log warning, five-second export gate, cancellation and reopening", (
        UI + "LogExportConfirmationTest",)),
    "ui-pack-file-import": ("device", "Floating APK/archive import guides, cancellation and picker handoff", (
        UI + "PackFileImportUiTest",)),
    "ui-status-sheets": ("device", "Dashboard sheets, freshness, failure recovery and navigation handoff", (
        UI + "StatusDetailSheetTest", UI + "MonetStatusContentTest",)),
    "ui-local-packs": ("device", "Pack counts, confirmed local-copy removal and storage links", (
        UI + "LocalPackRemovalDeviceTest", UI + "CacheStorageUiTest")),
    "ui-scope": ("device", "OS4 scope page, component versions, authorization states and large text", (
        UI + "ScopeAuthorizationUiTest",)),
    "ui-onboarding": ("device", "OS4 first use, resume, replay, optional routes and large text", (
        UI + "OnboardingNavigationTest", UI + "OnboardingChromeTest")),
    "initial-activation": ("jvm", "Initial scope activation, boot identity and persisted completion", (
        "InitialActivationPolicyTest",)),
    "ui-initial-activation": ("device", "Independent reboot reminder, skipped guide and restart persistence", (
        "os.kei.hyper.home.data.InitialActivationPreferencesTest", UI + "InitialActivationStatusTest")),
    "cache-budget": ("jvm", "Fixed reminder, log accounting and protected resource storage", (
        "CacheReminderBudgetTest", "CacheUsageTest", "AppLogExportCacheTest", "PerfectIconRepositoryTest")),
    "hook-hot-paths": ("jvm", "Reusable request keys and live authenticated connection contracts", (
        "SurfaceIconKeyIndexTest", "NativeMonetClientTest", "NativeMonetSocketBrokerTest",
        "NativeRuntimeIdentitySelectionTest")),
    "ui-desktop": ("device", "Desktop categories, scope viewer and wizard back stack", (
        UI + "DesktopNavigationTest", UI + "DesktopOperationProgressTest", UI + "NavigationBackdropScreenTest",)),
    "desktop-state": ("jvm", "Desktop acknowledgement and capability states", (
        "DesktopFeatureStatusTest", "DesktopConnectionCardTest", "LauncherVersionLabelTest")),
    "desktop-materials": ("jvm", "Material preferences, leases, protocol and independent search acknowledgements", (
        "LauncherGlassPreferencesTest", "LauncherGlassControllerTest", "LauncherGlassLeaseTest",
        "NativeMonetClientTest", "RuntimeStatusReportTest", "RuntimeSearchGlassAckTest")),
    "ui-settings": ("jvm", "Appearance preferences and recovery", (
        "ThemeEffectsPreferencesTest", "ThemeEffectsRecoveryTest", "ThemeEffectsScopeTest")),
    "pack-time": ("jvm", "Compact dates, locales, time zones and year boundaries", (
        "PackEventTimeTest",)),
    "perfect-sync": ("jvm", "Sync history, health, cached source and cancellation", (
        "PerfectSyncHistoryTest", "PerfectIconSyncHealthTest", "PerfectLocalHealthProbeTest",
        "PerfectIconRepositoryTest", "PerfectIconTransportCancellationTest", "PerfectGitRepositoryTest",
        "PerfectIconGitTest", "PerfectPackStateTest", "PerfectIconStyleMetadataTest", "PerfectIconStylesTest")),
    "ui-perfect": ("device", "Perfect page options, style actions, transfer status and OS4 confirmations", (
        UI + "PerfectGitUiTest", UI + "PerfectResourceNoticeUiTest", UI + "PerfectConnectionUiTest",
        UI + "PerfectPackNavigationTest")),
    "perfect-network": ("jvm", "Git route, proxy policy, cancellation and direct-entry catalog recovery", (
        "PerfectGitNetworkOptionsTest", "PerfectIconGitHttpTest", "PerfectCatalogBootstrapTest")),
    "icon-completion": ("jvm", "Perfect completion, source sessions, native choice restoration and update detection", (
        "SystemFallbackPreferencesTest", "SystemIconFallbackTest", "SystemFallbackChoicesTest",
        "PerfectFallbackSessionTest", "PerfectIconRepositoryTest", "PerfectGitRepositoryTest",
        "IconPackUpdateNoticeTest")),
    "icon-completion-device": ("device", "Perfect/ColorOS export priority and local resource enable checks", (
        "os.kei.hyper.home.iconpack.PerfectCompletionExportDeviceTest",
        "os.kei.hyper.home.ui.main.PerfectCompletionUiTest")),
    "scope": ("jvm", "Scope authorization requests and missing targets", (
        "StaticScopeRequestsTest",)),
    "icon-shape": ("jvm", "Native config completion, generation flags and converted archive boundaries", (
        "NativeIconShapeConfigTest", "IconGenerationManifestTest", "ConvertedIconArchiveTest",
        "IconPackUpdateNoticeTest", "SystemFallbackPreferencesTest")),
    "icon-shape-device": ("device", "Native archive preservation, completion policy and fallback alpha edges", (
        "os.kei.hyper.home.iconpack.NativeIconShapeCompletionDeviceTest",
        "os.kei.hyper.home.iconpack.SystemIconFallbackEdgeTest")),
    "hook-lifecycle": ("jvm", "Generation retirement bookkeeping (no device action)", (
        "ModuleRetirementMaintenanceTest",)),
    "release-jvm": ("jvm", "Full JVM suite; opt in for broad changes or releases", ()),
}


def command_for(group, serial, method):
    kind, _, classes = GROUPS[group]
    if kind == "python":
        if method:
            raise ValueError("--method is not supported for the i18n resource audit")
        return [sys.executable, "scripts/i18n/check_resources.py"]
    if method and len(classes) != 1:
        raise ValueError("--method requires a group containing exactly one test class")
    if kind == "device":
        target = ",".join(classes) + (f"#{method}" if method else "")
        return ["adb", "-s", serial or "DEVICE_SERIAL", "shell", "am", "instrument", "-w", "-r",
                "-e", "class", target,
                "os.kei.hyper.home.test/androidx.test.runner.AndroidJUnitRunner"]
    command = ["./gradlew", ":app:testDebugUnitTest"]
    for name in classes:
        command += ["--tests", f"*{name}" + (f".{method}" if method else "")]
    return command + ["--console=plain"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("group", nargs="?", choices=GROUPS)
    parser.add_argument("--list", action="store_true", help="list the available groups")
    parser.add_argument("--run", action="store_true", help="execute; default is preview only")
    parser.add_argument("--serial", help="explicit adb target, required for device execution")
    parser.add_argument("--method", help="one test method in a single-class group")
    args = parser.parse_args()
    if args.list or args.group is None:
        for name, (kind, description, _) in GROUPS.items():
            print(f"{name:18} {kind:6} {description}")
        return 0
    try:
        command = command_for(args.group, args.serial, args.method)
    except ValueError as error:
        parser.error(str(error))
    print(shlex.join(command), flush=True)
    if not args.run:
        return 0
    if GROUPS[args.group][0] == "device" and not args.serial:
        parser.error("device execution requires --serial; no target is selected automatically")
    if GROUPS[args.group][0] in ("jvm", "python"):
        return subprocess.run(command, cwd=ROOT).returncode
    result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    print(result.stdout, end="", flush=True)
    # adb itself can exit zero even when the instrumentation runner reports failure.
    passed = re.search(r"^OK \(([1-9][0-9]*) tests?\)\s*$", result.stdout, re.MULTILINE)
    return 0 if result.returncode == 0 and passed and "FAILURES!!!" not in result.stdout else 1


if __name__ == "__main__":
    sys.exit(main())

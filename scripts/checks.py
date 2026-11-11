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
    "release-notes": ("jvm", "Fresh installs, real upgrades, skipped releases and acknowledgment", (
        "ReleaseNotesPolicyTest", "ReleaseNotesMarkdownTest")),
    "ui-release-notes": ("device", "Changelog navigation, persistence, disclosures and large text", (
        "os.kei.hyper.home.data.ReleaseNotesPreferencesTest",
        "os.kei.hyper.home.ui.main.ReleaseNotesNavigationTest")),
    "ui-actions": ("device", "FABs, disclosures, links and metadata pills", (
        UI + "UiActionPolishDeviceTest",)),
    "ui-navigation": ("device", "Both navbar styles, tabs, themes and large text", (
        UI + "Os4ChromeDeviceTest",)),
    "ui-status": ("device", "Status cards, disclosures and detail navigation", (
        UI + "MonetStatusContentTest",)),
    "ui-theme-effects": ("device", "Theme-effect controls and scope availability", (
        UI + "ThemeEffectsCardTest",)),
    "ui-color-editor": ("device", "Color input, confirmation and draft recreation", (
        UI + "MonetColorEditorTest",)),
    "ui-pack-settings": ("device", "Pack preferences entry, back stack and wizard shortcut", (
        UI + "PackSettingsNavigationTest",)),
    "ui-about": ("device", "Fixed About copy action, scrolling and hero semantics", (
        UI + "AboutCopyUiTest",)),
    "ui-settings-search": ("device", "Settings search, preview placement and persistence", (
        UI + "SettingsSearchUiTest",)),
    "ui-cache": ("device", "Cache pills, equal chart columns, reminder editor and resource links", (
        UI + "CacheStorageUiTest",)),
    "ui-scope": ("device", "OS4 scope page, component versions, authorization states and large text", (
        UI + "ScopeAuthorizationUiTest",)),
    "ui-onboarding": ("device", "OS4 first use, resume, replay, optional routes and large text", (
        UI + "OnboardingNavigationTest",)),
    "cache-budget": ("jvm", "Reminder preference and protected Perfect storage accounting", (
        "CacheReminderBudgetTest", "PerfectIconRepositoryTest")),
    "ui-desktop": ("device", "Desktop categories, scope viewer and wizard back stack", (
        UI + "DesktopNavigationTest", UI + "DesktopOperationProgressTest",)),
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
        "os.kei.hyper.home.ui.main.PerfectGitUiTest",)),
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
    if GROUPS[args.group][0] == "jvm":
        return subprocess.run(command, cwd=ROOT).returncode
    result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True)
    print(result.stdout, end="", flush=True)
    # adb itself can exit zero even when the instrumentation runner reports failure.
    passed = re.search(r"^OK \(([1-9][0-9]*) tests?\)\s*$", result.stdout, re.MULTILINE)
    return 0 if result.returncode == 0 and passed and "FAILURES!!!" not in result.stdout else 1


if __name__ == "__main__":
    sys.exit(main())

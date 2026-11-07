# First-use guide

The guide is a native MIUIX flow, not a permission gate. Its five pages introduce
KeiHome, check LSPosed and scopes, open existing icon catalogs, open the existing
desktop controls, and explain actual runtime verification and logging.

## Entry and persistence

- A new installation starts in the guide. `onboarding_v1` is an app-owned MMKV
  store; one integer records the current page or explicit completion/deferment.
- The Application samples existing display configuration before the theme opens
  AppPreferences. Existing MMKV configuration or a legacy display XML file
  identifies an upgrade, which retains its normal entry point.
- Back navigation saves the previous page. Returning after process loss rebuilds
  the pending guide stack, including earlier pages. A completion saved just before
  process death also overrides an obsolete restored first-use navigation stack.
- Completing or deferring the guide prevents another automatic presentation.
- Settings has a searchable replay entry. Replaying starts at Welcome and does
  not reset preferences or rearm first-use presentation.

## Actions and lifecycle

The guide reuses `StaticScopeCard`, the catalog destinations, `DesktopPage`, and
the log destination. It does not request scopes, enable features, pick defaults,
or import/apply a pack without a user action. Native theme handoff remains
available, and returning from a catalog or desktop page returns to the same
guide page.

Framework inspection uses existing lifecycle-bound observers. The desktop child
uses the same status observer as the main Desktop page. No additional background
timer or scanning service was introduced.

Service connectivity, scope grants and loaded hooks are different states.
Finishing the guide never claims all hooks are active. Runtime health remains on
the Status page; an unavailable framework does not block Continue or Set up later.

The footer uses MIUIX Scaffold's native `floatingToolbar` slot with a rounded
FloatingToolbar, divider and native buttons. There is no full-width bottom bar.
Its measured height, floating spacing and navigation safe area are reserved
inside the scroll content. Padding outside the scroll modifier previously
shortened the viewport and left a solid block below it; the body now draws
behind the floating controls and can scroll its final item above them. The TopAppBar
uses MIUIX scroll behavior and the existing preference-aware blurred header.
MIUIX navigation provides page transitions and normal Back behavior; strings
cover English, Simplified Chinese and Japanese.

The icon step first explains automatic light/dark adaptation for supported
system Default, Classic and Boundless icons after activation. Changing packs
and enabling Monet are optional. The three entry points follow the main page:
available packs, Monet, then completion/fallback. Each returns to the same guide
step without changing preferences. A fourth entry opens **Settings > Icon packs
and actions**, explaining automatic application and the default all-app mapping
scope versus installed-app-only generation. The guide identifies the floating
update action and distinguishes generation from supported live palette/night
changes. The final page links to preview-cache management and gives LSPosed
feature logs a separate explanation card. App logs are explicitly limited to
KeiHome's own interface/settings issues.

The main icon page shows the system-icon notice only for a fresh native DEFAULT
source report. A third-party theme hides it; a selected fallback preference does
not imply that the active theme is native. The builtin catalog always shows the
notice as a separate information card, including while a third-party theme is
active.

## Community references

These references informed the sequencing and explicit action model. No source
code or assets were copied.

- [Android DataBackup setup screens](https://github.com/XayahSuSuSu/Android-DataBackup/blob/main/source-next/app/src/main/java/com/xayah/databackup/feature/setup/SetupScreens.kt):
  separates welcome from permission checks and presents discrete actions.
- [Canta Android service requirement dialog](https://github.com/samolego/Canta/blob/HEAD/composeApp/src/androidMain/kotlin/io/github/samolego/canta/ui/dialog/ShizukuRequirementDialog.android.kt):
  checks service requirements and provides explicit system/manager actions.
  KeiHome uses its actual LSPosed requirements instead of copying Shizuku or
  unrelated runtime-permission steps.

## Verification

`OnboardingPreferencesTest` covers fresh and upgrade bootstrap, legacy migration
recognition, persisted progress/completion, malformed state and unchanged app
preferences. `OnboardingNavigationTest` uses isolated MMKV IDs to exercise the
real navigation flow, catalog/desktop round trips, completion/replay, resume and
large text without clearing user data or changing scope grants. The current
footer tests scroll every page to its end at standard and 1.6 font scale and
compare the final content bounds against the floating toolbar.

Device receipts and screenshots live in
`.artifacts/research/onboarding-20261106/`. Acceptance is limited to the HyperOS
Phone AVD; source coverage and device acceptance are separate.

The five focused instrumentation tests passed: three storage/bootstrap tests,
the complete flow with catalog/desktop/log round trips and Settings replay, and
resumed setup at 1.6 font scale with visible scope/footer actions and deferment.
The existing 610 JVM tests and Release lint passed. Tests use the real navigation
and feature controls with isolated guide stores; they do not claim a destructive
fresh installation was performed.

On this AVD, background launch of the test ComponentActivity is blocked. The test
host is explicitly brought forward by a test-only outer rule before
ActivityScenario starts; the system's cross-package startup
confirmation was approved. Back regression dispatches through the real
Activity back dispatcher because this AVD denies Espresso's global key injection.
These are test-host constraints, not new runtime grants requested by the guide.

### 2026-11-07 guide refresh

Both navigation tests passed again on the Phone AVD, including pack-actions and
cache-management round trips to the same guide step. The existing large-text
resume/defer test also passed. All five pages were additionally captured at
system font scale 2.0, including their scroll ends; the final content remained
above the floating actions. System font scale was restored to 1.0.
This checkpoint is recorded separately in
`.artifacts/research/pad-glass-icons-20261107/guide-device-tests.log`; it does not
claim a rerun of the historical full JVM suite. No native payload was changed.

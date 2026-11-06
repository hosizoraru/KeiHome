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

The fixed footer uses MIUIX FloatingToolbar with a rounded container, divider and
native buttons, rather than an opaque full-width bar. Its actual measured height
and navigation safe area are reserved outside the body scroll viewport, so
scroll-to-focus does not place scope actions behind the footer. The TopAppBar
uses MIUIX scroll behavior and the existing preference-aware blurred header.
MIUIX navigation provides page transitions and normal Back behavior; strings
cover English, Simplified Chinese and Japanese.

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
large text without clearing user data or changing scope grants.

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
host was explicitly launched through adb; the system's cross-package startup
confirmation was approved. Back regression dispatches through the real
Activity back dispatcher because this AVD denies Espresso's global key injection.
These are test-host constraints, not new runtime grants requested by the guide.

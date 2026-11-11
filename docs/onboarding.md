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
The scroll viewport reserves the measured toolbar height, floating spacing,
navigation safe area and top-app-bar inset. This keeps rows brought into view
outside both overlay bars; padding only the end of the scroll content can leave
a middle row behind the toolbar and route a touch to Continue instead.
The full-page backdrop remains on the outer container. The TopAppBar uses MIUIX
scroll behavior and the existing preference-aware blurred header.
MIUIX navigation provides page transitions and normal Back behavior; strings
cover English, Simplified Chinese and Japanese.

The icon step first explains automatic light/dark adaptation for supported
system Default, Classic and Boundless icons after activation. Changing packs
and enabling Monet are optional. Six links open Library, Perfect, Apple, Monet,
completion/fallback and **Settings > Icon packs and actions**. Each returns to the
same guide step; opening a destination does not apply a pack or change settings.
Perfect reuses unchanged resources when generating locally after a repository
sync. Apple offers iOS/iPadOS/macOS artwork, per-app mappings and incremental
generation from cached images. The pack-action summary identifies automatic
application and all-app versus installed-only generation; the default retains
uninstalled-app mappings.

The guide's shorter generate/apply/check hint distinguishes generation from
supported live palette/night changes. A compact local-image note explains
limited memory reuse, original-icon fallback and network use for requested
Perfect syncs or Apple searches/downloads. Clearing preview memory preserves
downloaded artwork and applied packs. This describes KeiHome's app-icon path,
not the network behavior of host apps.

The final page links to System and Hook, preview-cache management and App logs,
and gives LSPosed feature logs a separate explanation card. App logs cover
KeiHome's own interface/settings issues. Status reports loading and launcher
acknowledgements; it does not confirm every host or the appearance of every icon.
The user should check the share sheet, Settings or Security Sidebar as used.
Existing host, user and profile predicates remain authoritative.

After first enablement and scope authorization, one system reboot is recommended.
Later updates normally use API 102 automatic hot reload; supported settings sync
automatically. A newly authorized host may need its next process start to load
the module. Authorization, loading and visible results remain separate checks.

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

### UI and documentation refresh handoff

The five steps, stored progress, floating-toolbar spacing and pinned MIUIX APIs
remain in place. No dependency, build, native, Xposed or status implementation is
changed by this slice.

Changed paths:

- `app/src/main/kotlin/os/kei/hyper/home/ui/main/OnboardingScreen.kt`: direct
  Perfect/Apple links, cache guidance and final System and Hook link.
- `app/src/main/kotlin/os/kei/hyper/home/ui/main/StaticScopeCard.kt` and
  `SettingsContent.kt`: surface guidance, wrapping title/count and actions, and
  searchable scope purposes.
- `app/src/main/res/values{,-zh-rCN,-ja}/onboarding.xml`, `static_scope.xml`,
  `strings.xml`, `runtime_status.xml`, `icon_packs.xml`, `settings_search.xml` and
  `pack_actions.xml`: matching EN/ZH/JA explanations for supported surfaces,
  authorization/loading, update behavior and pack choices.
- `app/src/androidTest/kotlin/os/kei/hyper/home/ui/main/OnboardingNavigationTest.kt`:
  extends the existing real-navigation test with Perfect/Apple and final scope
  round trips, plus large-text reachability checks for the added content.
- `docs/onboarding.md` and `docs/static-scope.md`: current decisions and handoff.

Static resource validation parses XML, checks duplicate names, compares locale
keys and format placeholders, resolves UI string references and checks diff
whitespace. This slice runs no Gradle or ADB. Historical acceptance above predates
these changes; the parent owns build/device checks and fresh screenshots.
The static pass parsed 113 resource XML files, verified all 25 changed keys across
EN/ZH/JA, resolved string references in 74 UI/test Kotlin files, and confirmed the
seven scope labels and linked destination tokens. `git diff --check` passed.

Suggested commands for the parent (not run in this slice):

```bash
./gradlew :app:processDebugResources :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=os.kei.hyper.home.ui.main.OnboardingNavigationTest
```

Capture all five steps in EN/ZH/JA at standard and large text. On step 3, open
Perfect and Apple and return to the same step; scroll to every link and the final
cache note. On step 5, open System and Hook and return. Check that the last item
clears the floating toolbar, that scope grants and reported loading stay distinct,
and that unavailable hosts are not presented as active features. Retain coverage
for Back, later/replay, light/dark mode and enlarged display size.
## Icon pack preferences shortcut

The icon step now opens **Icon packs > More settings** as a Packs child.
Returning restores the same wizard step. Its generation/application and
experience cards use the same saved preferences as global Settings search;
the Settings root no longer exposes a separate icon-pack actions entry.

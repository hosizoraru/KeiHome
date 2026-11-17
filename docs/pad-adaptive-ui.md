# Adaptive Pad navigation and shared MIUIX chrome

The application uses the pinned `0.9.4-76504ab0-SNAPSHOT` MIUIX components.
This implements the adoption options in
[the source review](miuix-d8efe187-to-76504ab0-review.md).

## Navigation and width

`KeiHomeAdaptiveNavigation` chooses the navigation surface from its measured
window constraints, not a tablet model name or the physical display size:

- At least 840dp wide and 480dp high: native `GlassNavigationRail`.
- Smaller windows: the existing bottom navigation, including its saved style.
- Expanded rail width is 256dp, rising to 320dp with large fonts.
- Content reserves the rail width plus 24dp while expanded. During collapse
  it opens a separate 64dp top band for the native capsule. The capsule does
  not share the title/action row.
- Animated content offsets and constraints read native rail progress during
  measurement, not composition. Start-relative placement supports RTL.
- The native rail owns its saveable expanded state.

There is one content slot throughout window and rail changes. It keeps the
existing pager, destination mapping, saveable page states, focus clearing,
browser state and list state. Selection and the icon-pack update badge use
the same logic for the rail and bottom bar. Badge subscriptions stay in
their own composition rather than making runtime reports invalidate the
entire root.

The rail sits outside backdrop recording. The root overlay host stays above
both the rail and content, so popups/sheets do not render behind navigation.
Toggle descriptions are localized in English, Simplified Chinese and Japanese.

`KeiHomeScaffold` centers its complete chrome/content at a maximum 1040dp.
This also gives secondary screens and floating-action placement a shared
readable frame on landscape Pad windows. It leaves narrower phone windows
at their existing width.

## Long lists

`Os4LazyColumn` composes native `VerticalScrollBar` with the caller's existing
`LazyListState`. Application browser, custom icons, preserved originals and
Perfect resource preview use this shared path.

- No second scrolling container, duplicate list data or independent position.
- Track top/bottom padding matches the existing list content padding,
  including the owning page's floating-action clearance.
- Native hit width stays in the outer margin instead of covering trailing
  controls. The strip moves to the start edge for RTL.
- A list that fits its viewport does not add a scrollbar.
- Native fade/drag behavior and normal list accessibility are retained.

## Compact tools and floating actions

`KeiHomeTopAppBar` exposes native `expandable`, with its previous `true`
default. Desktop tools, log settings, hook/environment details and cache
details opt into compact mode. Main category pages and the About hero keep
their existing title hierarchy.

`Os4FloatingToolbar` limits width to 640dp before applying caller modifiers,
so a caller's `fillMaxWidth` cannot expand the toolbar across a Pad. This
continues to serve labeled page actions and Apple/Perfect batch actions.
Existing labels, busy/disabled state, button semantics, safe insets and
toolbar-height clearance remain in their established shared implementations.
No icon-only replacement was introduced for actions needing explanatory text.

## Verification

Evidence is retained in
`.artifacts/research/pad-adaptive-ui-20261118/`.

`AdaptiveChromeDeviceTest` covers:

1. Resizing between wide/compact windows and toggling the rail without
   recreating content, losing selection/scroll or changing origin filters.
2. Start-relative content placement in both layout directions, both themes
   and 160% font.
3. Saving/restoring collapsed rail state, selected destination and list position.
4. Compact navigation for short windows.
5. Dragging the shared list scrollbar while retaining trailing actions.
6. Absence of an inactive scrollbar for short lists.
7. Compact title height and scaffold/toolbar maximum widths.

The component fixtures control density and bounds, so breakpoint and state
tests run on both the Phone and Pad. Actual application screenshots check
the real Pad window in portrait/landscape and expanded/collapsed navigation.
Phone component, navigation, popup, refresh and toolbar regression coverage
is retained alongside these tests.

The final debug verification on 2026-11-18 passed 45 device cases on Phone
and the seven adaptive cases on Pad, with no failures or skips. JVM results
were 2069 passed and eight conditional skips out of 2077 cases. The seven
adaptive cases are included in the Phone total, not additional unique cases.

This is a UI change. It does not update MIUIX again, modify native source,
enable desktop features, import/apply icon packs or alter theme/Monet choices.
Device identities, rotation restoration, final APK/native-library comparison,
build results and installed-state checks belong to the acceptance receipts.

# Adaptive Pad navigation and shared MIUIX chrome

The application uses the pinned `0.9.4-76504ab0-SNAPSHOT` MIUIX components.
This implements the adoption options in
[the source review](miuix/snapshots/miuix-d8efe187-to-76504ab0-review.md).

## Navigation and width

`KeiHomeAdaptiveNavigation` chooses the navigation surface from its measured
window constraints, not a tablet model name or the physical display size:

- At least 720dp wide and 480dp high: native `GlassNavigationRail` on main
  destinations and their detail routes.
- Smaller windows use the existing bottom navigation on the main destinations
  and the ordinary back flow on details.
- Expanded rail width targets 256dp, rising to 320dp with larger text. The
  measured window can reduce that width to leave at least 472dp for the page.
- Content reserves the rail width plus 24dp while expanded. During collapse
  it opens a separate 64dp top band for the native capsule. The capsule does
  not share the title/action row.
- Animated content offsets and constraints read native rail progress during
  measurement, not composition. Start-relative placement supports RTL.
- The native rail owns its saveable expanded state.

The rail surrounds the navigation display and stays visible in detail pages.
The expanded rail puts the icon-pack library, Monet, icon completion,
customization and desktop tools below the five primary destinations, separated
by the native divider. Collapsing the rail shows the current detail's parent
in the compact capsule. Opening a shortcut replaces the current detail;
Back then returns to the shortcut's parent tab. Setup and mandatory upgrade
notes have their own navigation flow.

There is one content slot throughout window and rail changes. The pager and
navigation state stay mounted across these changes, so a resize retains the
current detail and its return destination. Root tabs share their labels,
selection and icon-pack update badge between the rail and bottom bar.

Non-status root titles also use the selected navigation label. This removes
the legacy desktop-title override on the icon-pack destination.

The rail sits outside backdrop recording. Detail pages keep their own backdrop
and the rail uses its unsampled material while a detail is active. The content
pane clips navigation transitions at its edge. The adaptive container owns the
root overlay slot above both rail and content. Toggle descriptions are localized
in English, Simplified Chinese and Japanese.

The grouped rail uses MIUIX `0.9.5-beta01`. All eight MIUIX Android modules use
the same published version.

`KeiHomeScaffold` centers its complete chrome/content at a maximum 1040dp.
This also gives secondary screens and floating-action placement a shared
readable frame on landscape Pad windows. It leaves narrower phone windows
at their existing width. The container background still covers the complete
window, preventing the previous navigation entry from showing in side gutters.

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
8. Full-window overlay coverage and shared backdrop inheritance, blocking
   rail/content clicks in wide/compact windows with sampling on/off.

The component fixtures control density and bounds, so breakpoint and state
tests run on both the Phone and Pad. Actual application screenshots check
the real Pad window in portrait/landscape and expanded/collapsed navigation.
Phone component, navigation, popup, refresh and toolbar regression coverage
is retained alongside these tests.

The initial debug verification on 2026-11-18 passed 45 device cases on Phone
and the first seven adaptive cases on Pad, with no failures or skips. JVM
results were 2069 passed and eight conditional skips out of 2077 cases.
The additional overlay regression and final source/build results are retained
in the acceptance receipts; repeated device runs are not additional unique cases.

Record the exact device and APK for visual checks. Exercise the rail across
details, then resize to a compact window and check the detail and its Back
destination. Restore the device rotation after orientation checks.

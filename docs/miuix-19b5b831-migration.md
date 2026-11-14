# MIUIX 19b5b831 migration

## Source and dependency boundary

All eight direct MIUIX dependencies use `0.9.4-19b5b831-SNAPSHOT`.
The published artifacts resolved successfully from GitHub Packages on
2026-11-14. The retained upstream checkout was advanced with a clean
fast-forward to `19b5b831679a45d3d8a1484fce00ef12b9b494db`, then the full
commit message, updated guides, component contracts and relevant production
implementations were reviewed.

Primary references:

- [Commit and full message](https://github.com/compose-miuix-ui/miuix/commit/19b5b831679a45d3d8a1484fce00ef12b9b494db)
- [Glass guide](https://github.com/compose-miuix-ui/miuix/blob/19b5b831/docs/guide/glass.md)
- [Scroll utilities](https://github.com/compose-miuix-ui/miuix/blob/19b5b831/docs/guide/utils.md)
- [Glass component contracts](https://github.com/compose-miuix-ui/miuix/tree/19b5b831/docs/components/glass)

This is an application migration against Apache-2.0 public library APIs.
No implementation from GPL applications was copied. The upstream demo
reorganization does not require reorganizing KeiHome's navigation.
The library still provides solid content cards, switches and sliders;
applying glass to every content surface would not follow its component
boundaries.

## Scroll and chrome

`keiHomeTopBarScroll` supplies spring displacement separately from real
scroll to `TopAppBarState`. It remembers the callback by state and attaches
the app-bar connection inside the explicit vertical spring. All 16 existing
app-bar scroll owners use it, including the main pager, source previews,
release notes, scopes, desktop settings and icon settings.

The corresponding vertical scroll containers disable their implicit
overscroll effect. This avoids an inner default spring intercepting input
before the explicit outer spring can report its offset. Short pages and
long lists share the same connection; ordinary content scroll is not
rewritten to fake a spring position.

The new upstream band brush, progressive blur and button-underlay behavior
can therefore use `visualContentOffset`, including rebound frames. A
transparent band remains intentional for Gaussian chrome. About's
transparent shader header and the independent onboarding hero remain
intentional exceptions, without an expanding title to drive.

Turning off top-bar blur now retains `GlassTopAppBar` and its toolbar
button geometry using the library's nullable-backdrop material fallback.
It no longer switches the whole header back to basic chrome. Disabled,
held and explicitly styled utility buttons retain their existing semantics.

The OS4 navigation bar adopts upstream's preview-until-release interaction.
Dragging across destinations does not repeatedly invoke page changes;
cancellation retains the current destination. The independent iOS-like
bar remains selectable and the saved user choice is preserved.

## Overlay audit

The audit covers shared dialog, details-sheet, popup, tab and selection
panel wrappers, plus their actual callers. Existing explicit panel fallback
modifiers already supply their own clipping and background, as required by
the new override contract. They are not wrapped in a second glass layer.
The blur recorder continues to exclude its glass surfaces and overlay
scrims.

Perfect resource details now retain their content during native dismissal.
A small session token guards repeated callbacks and reopen races; only
after exit finishes can a download start, the removal confirmation open,
or a preview route be pushed. Pending actions contain values, not saved
closures. Native `GlassBottomSheet` supplies ancestor IME padding through its
default `defaultWindowInsetsPadding = true`. `Os4DetailsSheet` adds
`navigationBarsPadding()` to its body; Compose applies only the navigation-bar
inset left unconsumed by the ancestor, avoiding duplicate space while the
keyboard is open. The Apple mapping inspector's edit
action uses the shared native dialog button.

Final device results are recorded in the acceptance evidence rather than
inferred from successful compilation.

## Validation

Evidence is retained under
`.artifacts/research/miuix-19b5b831-20261114/`.
`dependency-resolution.json` records resolved Maven artifact hashes.
The new gesture tests join the existing `ui-navigation` check group;
dialog, menu and expansion checks remain independently runnable.

This migration does not change the native payload, module scope, feature
defaults, version prefix or published release notes. A successful UI test
or Java handoff does not substitute for native activation or physical-device
acceptance.

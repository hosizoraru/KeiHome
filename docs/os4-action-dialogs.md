# OS4 action dialogs

`Os4ActionDialog` provides the common action/confirmation layout on top of the
existing MIUIX `GlassDialog` adapter. The composition is KeiHome-authored and
uses the retained Apache-2.0 MIUIX APIs; no OEM or reference-app implementation
or assets are copied.

- A small semantic icon, centered title, readable summary and separated actions
  replace nested content padding. The MIUIX host already supplies 24dp insets.
- Positive operations explicitly use `Primary`; destructive operations use
  `Destructive`; dismissal and secondary operations use `Normal`. Text uses
  the button's supplied content color.
- Three or more actions, narrow dialogs, and font scales above 1.3 use a
  vertical group with the main operations first. Other pairs share a row.
- Long content has a bounded scroll viewport. Existing busy gates, callbacks,
  cancel behavior and action tags remain owned by each caller.

The shared layout covers Perfect style download/update, repair, removal and
cache confirmation, log cleanup/export, framework-entry help, and Apple mapping
transfer results. Cache-budget and Monet editors retain their specialized
content and use the same adaptive action group. Guidance/link lists and source
information retain their content-oriented layouts.

App log export has a dedicated confirmation before any ZIP creation or share
intent. It distinguishes KeiHome UI/settings/crash reports from module-feature
reports, which require LSPosed logs. Each presentation waits twenty seconds using
elapsed realtime, displays the remaining seconds, and independently checks the
deadline in its callback. Cancel/back/outside dismissal never exports; reopening
or recreating the page starts a new wait. The selected time range is captured
when opening the dialog. Export starts once, after the native dismissal finishes;
the originating action remains disabled through that handoff. No log level,
retention, or existing export format changes.
This confirmation uses full-width stacked native actions so countdown labels
remain readable across languages; other dialogs keep their existing adaptive
action layout.

Focused device coverage: `python3 scripts/checks.py ui-log-export --serial
emulator-5580 --run`. Tests use the real monotonic clock and verify early/stale
callbacks, one-time delivery, cancellation, Back, and a fresh wait after reopening.

Perfect resource filtering lives in the top-right OS4 menu. It explicitly
selects all styles or downloaded styles, marks the current option, and clears
selection only when the filter changes. The filtered state is also reflected
by the icon color, accessible name and list heading.

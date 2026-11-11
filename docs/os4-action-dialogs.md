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
cache confirmation, log cleanup, framework-entry help, and Apple mapping
transfer results. Cache-budget and Monet editors retain their specialized
content and use the same adaptive action group. Guidance/link lists and source
information retain their content-oriented layouts.

Perfect resource filtering lives in the top-right OS4 menu. It explicitly
selects all styles or downloaded styles, marks the current option, and clears
selection only when the filter changes. The filtered state is also reflected
by the icon color, accessible name and list heading.

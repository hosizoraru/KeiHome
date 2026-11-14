# OS4 search, view selectors and contextual help

This work builds on MIUIX `0.9.4-19b5b831-SNAPSHOT`. It keeps the existing OS4
theme, typography and navigation preferences. It does not change Hook code,
static scopes, icon generation or published release notes.

## Interaction design

### Search mode

Search remains a separate field above the actual list. Focusing it enters an
editing mode with a localized Cancel action. Cancel, keyboard submission,
Escape and Back end editing without clearing the query. Clearing text remains
an explicit action.

The page continues to own the query, filters, sorting and list position. Search
does not create a second results list or a full-screen overlay. Nested list
searches follow the same pattern; non-search text inputs remain ordinary fields.

The app-owned field uses Compose text editing and MIUIX tokens/icons. MIUIX's
public InputField resets text on expansion changes, which conflicts with
preserving a user's active filter.

### Joined view selectors

Small, mutually exclusive views of the same content use
`GlassSegmentedTabRow`. Content-category navigation retains `GlassTabRow`.
Labels grow with font scale instead of fitting large text into a fixed-height
track. When full labels need more width, the track scrolls inside the page
boundary and brings the selected option into view. This also applies to the
separate category pills; five truncated labels are not a useful navigation aid.

Inline selectors sample a leaf surface backdrop. They must not sample the page
recording that contains the selector itself; that would introduce a RenderNode
cycle. Disabling blur keeps the controls and their selection semantics usable.

### Anchored explanations

Short, read-only explanations use an information button and
`GlassTransformPopup`. The popup is hosted outside the recorded page, samples
the underlying page and remains composed through the native exit animation.
It supports dismissal by Back, outside tap, Escape and accessibility action.

Long guidance, editors, previews and multi-action dialogs remain sheets or
dedicated pages. An expanding status card still serves a different purpose:
its live diagnostic content is not a tooltip.

### Backdrop pipeline

The custom Gaussian top-bar surface uses the value-based `BackdropPipeline`
overload. Its material remains MIUIX's texture blur effect with the existing
radius and theme blend colors. Equal parameters allow the modifier to retain
its rendering state; changes in theme colors or blur parameters update it.

Progressive blur, blur-off fallback and the independent iOS-like bottom bar
remain distinct rendering paths. This change does not add a second blur layer.
Render-node reuse is an implementation property, not a measured FPS claim.

## Validation

Run the focused `ui-os4-context` group for these controls. Adjacent cache,
search, preview and overlay tests protect the affected page integrations.
Device acceptance uses the Phone AVD after user unlock and framework readiness.
Evidence is retained under:

`.artifacts/research/os4-search-popup-pipeline-20261114/`

The final acceptance receipt records the exact test counts, signed artifact,
native identity and inspected screenshots. No physical-device validation or
general performance improvement is implied by a successful UI test.

The focused device work verified 37 distinct cases across the initial run and
targeted reruns. Failed runs remain in the evidence directory; `device-results.json`
records each run and the final passing result for every case. Coverage includes
IME/Back/cancellation, query and child-return state, filtering, both themes,
large fonts, native popup exit and focus restoration, opaque popup fallback,
category overflow, and legacy/pipeline pixel parity through color, radius and
density changes.

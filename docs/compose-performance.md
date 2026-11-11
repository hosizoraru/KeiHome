# Compose performance checks

Use a focused screen or update path before changing state collection. Keep
runtime freshness, pending acknowledgements, operation progress, retry deadlines
and settings changes observable. A smaller recomposition count does not justify
stale status cards.

## Current improvements

- Apps builds and sorts browser rows when capabilities, source or browser options
  change. Search filters those rows separately on the worker dispatcher instead
  of rebuilding and sorting them for every query. Each filtering request captures
  one rows/query pair; a superseded request cannot publish after cancellation.
- Status caches icon coverage counts against the effective browser rows, so an
  unrelated runtime report does not rescan the catalogue.
- Apple only runs mapping filtering on browser destinations. Batch eligibility,
  regions, mapping counts and selection membership are keyed to their actual
  input data; the existing deadline clock remains responsible for retry expiry.
- Perfect's options have their own restartable composition scope. Progress and
  runtime readiness remain in the generated card, while option controls still
  receive option, catalogue and enabled-state changes.
- Storage's cylinder caches its outline and shading brush by draw-cache inputs.
  Animated fill values are still read during Draw, preserving the fill transition
  without rebuilding those objects for every animation frame.

Status and Monet already have lifecycle-aware collection and scoped expiry/age
updates. Their existing timers are retained. No debounce, static stability
annotation, new document cache or image-quality reduction is introduced.

## Compiler reports

Generate complete reports explicitly:

```sh
./gradlew :app:compileReleaseKotlin \
  -PcomposeCompilerReports=true -Pkotlin.incremental=false --no-build-cache
```

Reports are under `app/build/compose_reports/`. A normal incremental invocation
can describe only the recompiled subset; do not compare that subset's counts to
a full compilation. Skippable scopes show compiler eligibility, not measured
runtime skip counts or frame-rate gains.

The opt-in is already supplied by build logic and does not change ordinary builds.

## Focused acceptance

Run the browser, Apple deadline/mapping and status-dashboard unit tests for these
changes. Device checks cover search restoration and live Monet status changes;
the guide and release-history checks cover navigation, disclosure restoration,
large text, toolbar material selection and one-time upgrade presentation.

Keep optimized signed Release runtime captures separate from Debug functional
tests. Emulator frame/CPU data is diagnostic evidence for that exact run, not a
physical-device performance claim or a controlled before/after comparison.

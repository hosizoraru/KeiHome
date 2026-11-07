# Generated icon runtime and archive overhead

## Scope and source findings

This checkpoint investigates a report about public build
`1.4.0+g112166c.b0202`. The connected physical device initially ran build224,
launcher8602; the Phone AVD ran build229, launcher8593. The physical comparison
therefore does not measure the total improvement since the public build.

Generated fancy icons are not all animated content. The retained Rust MAML
SDKs and both launcher consumers show that zero-rate resume bootstrap requests
1 fps, which becomes a 1000 ms Dart Timer. Intervals of 1..33 ms use a Ticker;
intervals of at least 34 ms stop the Ticker and use a Timer. Consequently the
bootstrap message alone does not prove continuous 120 fps rendering.

Theme PNG night-directory selection is not generally consumed by the current
static launcher path. The existing MAML selectors bridge native day/night
state and live Monet changes. Replacing every selector with a day PNG would
lose those behaviors. Genuine supplied animations and their resource trees
remain intact; this change adds no native hook, timer or frame-rate vote.

## Changes

- Encoded images and nested icon archives use ZIP `STORED`, with exact size
  and CRC. XML/text remain deflated. Preserved image bytes are streamed rather
  than decoded/re-encoded for this container change.
- The MTZ handoff stages its completed icon archive to obtain CRC/size before
  writing a stored `icons` member. Temporary files are cleaned on failure and
  cancellation. This trades bounded temporary disk I/O for avoiding repeated
  compression; it is not a PNG recompression or quality change.
- Generated Monet selectors observe one numeric settings value instead of
  three separate enable/foreground/background values. Bit48 is enable;
  bits24..47 and bits0..23 are opaque foreground/background RGB. All values
  fit exactly in a double. Native-supported `argb(...)` tint expressions
  extract individual channels without converting the full value to int32.
- The existing monitor publishes this state after the legacy scalar keys,
  preserving older applied themes. Each new selector receives one coherent
  value, and unchanged publication emits no further notifications. The
  importer still admits the exact legacy templates.
- `installed_icons_only` is a default-off MMKV preference under App settings.
  It restricts generated mappings and additions to the installed launcher
  targets. Original imported native resource trees are preserved. With it
  enabled, newly installed mapped apps may require an icon-pack update.
  Default-off retains the existing offline coverage.
- Generation metadata v2 records scope, reads canonical v1, and requests one
  upgrade from v1. Scope changes participate in update detection; successful
  application clears the notice. The browser recognizes both palette protocols,
  preserving Monet access for ordinary ColorOS schemes.

## Acceptance

Evidence is retained under `.artifacts/research/icon-runtime-20261107/`.
All updates use API102 Java reload; no new native activation is required.

On Phone AVD:

- The installed-only theme changed from 56,760,840 to 10,574,925 bytes;
  fancy manifests changed from 723 to 154. This is optional coverage reduction,
  not a general memory or frame-time improvement percentage.
- All 715 PNG entries in the filtered applied theme use `STORED`.
- A controlled atomic-color probe rendered the requested blue/yellow pair;
  the actual MIUIX Monet switch subsequently published the packed state and
  rendered the system palette. Off and native light/dark cycles were checked.
- Existing original artwork, Dock material and native animations are retained.
  The installed-only preference is restored off after acceptance.

On Xiaomi17Pro, with full offline coverage retained:

- Fancy manifests remain 937; ContentProviderBinder count changes from
  1995 to 665. This is an exact two-thirds reduction in per-template binding
  count, not a claim that all are queried on each application launch.
- All 5058 retained PNG payloads are byte-identical and stored. The 38 removed
  PNGs are redundant `fancy_icons/.../night.png` copies from the older layout.
- Four Settings launches through ACTION_SETTINGS and Home returns in 24 seconds
  produce 701 versus 704 launcher frames, with zero App Deadline Missed in
  both samples. Mean frame spans are 5.399 versus 5.660 ms; maxima are
  14.321 versus 13.395 ms. Guest CPU is 12.34 versus 13.09 percent of one core.
  This bounded comparison does not establish a stable overall CPU improvement,
  and it does not reproduce the exact icon-tap path from the external report.
- Twelve-second stationary samples produce zero versus one launcher frame,
  with approximately 0.161 versus 0.166 percent of one core. The reported
  sustained 120 fps behavior is not reproduced in this scene.
- RSS and swap differ after theme application: observed peak RSS is about
  773.79 versus 996.77 MiB while swap falls from about224.87 to10.36 MiB.
  Those residency changes prevent a simple memory-reduction claim. The
  process-lifetime RSS watermark is not the peak of the measured interaction.
- Launcher PID8201/start21458321 is retained. Server and SystemUI identity
  continuity is recorded separately. No social app was used for this test.

Trace acceptance requires a fresh output and the successful Perfetto completion
footer. Failed transport/configuration attempts are excluded. Loss/overwrite
checks for the accepted interaction traces are empty.

Desktop buffer production and panel refresh are separate observations. This
checkpoint does not force a low refresh rate or claim to measure the panel's
internal LTPO cadence. Remaining cold layout, GPU composition and GC costs need
event-aligned traces when a reproducible long frame remains.

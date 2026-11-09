# Archive and theme reader options

## Current boundary

KeiHome already uses `java.util.zip` for ZIP parsing and Android's streaming
`XmlPullParser` for XML. The application-specific code identifies HyperOS
resource layouts, enforces budgets, preserves unknown MAML, and proves which
resources can safely participate in Monet.

The submitted Monet MTZ exposed pipeline defects: nested resource sizes were
counted twice, copied PNG entries changed compression methods, partial scans
discarded useful identity, and status previews copied an entire large theme.
Changing the ZIP parser alone would not correct these defects.

## Community candidates

| Candidate | Useful capability | Intended evaluation |
| --- | --- | --- |
| [AOSP Zipflinger](https://android.googlesource.com/platform/tools/base/+/mirror-goog-studio-main/zipflinger/README.md) | Copies compressed entries between ZIPs without recompression; supports archive edits | First candidate for native archive rewrite and snapshot output |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/zip.html) | `getRawInputStream` / `addRawArchiveEntry`; explicit ZIP metadata handling | Alternative when Android compatibility or memory favors its implementation |
| [Zip4j](https://github.com/srikanth-lingala/zip4j) | Android support, encrypted/split ZIPs and high-level ZIP operations | Useful if actual input requirements expand; encryption is not a current MTZ requirement |
| [Android XmlPullParser](https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser) | Existing streaming XML parser, including Android resource integration | Retain; keep bounded HyperOS/MAML interpretation above it |

The three ZIP candidates identify their licenses as Apache-2.0. This does not
change the licensing of any third-party artwork they process.

GitHub API observations during this research were 2,228 stars for Zip4j and
410 for the Commons Compress mirror. Stars are recorded as observations, not
compatibility or performance evidence. Zipflinger's relevant provenance is the
Android tools source tree.

## Bounded host experiment

The harness used the actual generated Monet archive, approximately 264 MB and
40,448 output entries, replacing one metadata entry and adding another. It ran
two fresh JVMs per backend with a warm filesystem cache. Validation recomputed
the unchanged entries' decoded hashes, CRCs and raw compressed payload hashes.
Large temporary outputs were removed after validation.

| Backend tested | Rewrite time, two runs | Output bytes | Sampled heap peak |
| --- | --- | --- | --- |
| Zipflinger 9.5.1, raw transfer | 0.862 / 0.733 s | 264,275,948 | 135.9-137.3 MiB |
| Commons Compress 1.27.1, raw transfer | 1.048 / 1.582 s | 265,004,016 | 127.2 MiB |
| `java.util.zip`, streamed recompression | 5.102 / 5.711 s | 265,646,232 | 49.6-49.7 MiB |

All six outputs preserved all 40,446 unchanged entries' compression methods and
decoded bytes. Both raw backends also preserved their compressed payload bytes.
ZIP container headers, timestamps,
extra fields and ordering were outside these preservation assertions.
The source archive remained unchanged.

These are host rewrite results, not Android timings, parse-only benchmarks,
total process memory, or evidence of less launcher jank. The higher sampled
heap use is material. Commons 1.27.1 was the locally available test artifact;
the upstream website reported 1.28.0 during research. Zip4j's latest GitHub
release reported 2.11.6.

Evidence and reproducible harness:
`.artifacts/research/monet-mtz-20261109/archive-library-poc/REPORT.md`.

## Recommended integration

Zipflinger is the implementation of a single archive-copy layer, running in
the app's IO work rather than in a hooked system process. Android validation
includes cancellation, memory sampling, malformed input rejection and the real
large fixture. Final Release acceptance also exercises the R8 build and Themes
import; details are recorded in the acceptance receipt.

Keep archive admission separate from copying: path normalization, duplicate
rejection, CRC checks, per-level expansion budgets and resource validation
remain application responsibilities. Raw copying should operate on admitted
immutable inputs, not become a shortcut around validation.

Retain Android XML parsing and a small, shared theme adapter for metadata,
resource indexing and source-proven Monet conversion. Preserve unknown native
programs rather than forcing them through a schema that loses their fields.
The host experiment itself changed no production dependencies. The subsequent
integration adds Zipflinger 9.5.1 behind `RawIconArchive`: native Monet conversion,
fallback merging and final handoff metadata stamping share this backend.
The system worker's small installed-app snapshot retains its streaming writer,
so the bulk library work stays in the app.

## Android checkpoint

On HyperOS Phone AVD, the 40,228-entry original native module was rewritten
with one added metadata entry in 1,997 ms. Output was 263,547,310 bytes and all
original entries passed decoded-content, CRC, compression-method and compressed
payload checks. An installed-name selection produced 658 entries / 4,190,146
bytes in 453 ms.

The 10 ms sampler observed approximately 66.8 MiB Java heap and 8.6 MiB native
heap for the full rewrite. These are approximate process-wide samples, not
isolated library allocations or complete process PSS. The measurement covers
archive rewriting, excluding fixture validation, icon analysis, outer MTZ
generation and system import/application. There is no paired Android timing
for the old writer, so the host speed ratio is not a device claim.

`raw-device-tests.log` contains five raw-archive tests and five native/APK
Monet tests, all passing. The host suite reported 1,051 tests, zero failures,
and six skips; `lintRelease` passed.

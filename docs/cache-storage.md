# Cache and resource storage

Cache management presents a compact status card with usage, reminder threshold,
measurement time and health pills. The cylinder and category legend occupy equal
widths, with a 32 dp gutter and 12 dp between category rows. The OS4 edit icon
immediately before toolbar Refresh opens the reminder
editor; the footer only explains cleanup scope and shows preview RAM.
Eight colors identify snapshots, built-in copies, transfers, temporary
files, Apple downloads, Perfect resources, logs and imported copies. Apple/Perfect rows open their
management pages; Logs opens the dedicated log page and imported copies open
the icon library. Existing toolbar refresh
and preview-memory clearing remain.

The cylinder capacity is a reminder budget, not device free space or a download
limit. The fixed default is decimal **520 MB**, regardless of downloaded Perfect
styles. Existing custom values from 50 to 20000 MB remain unchanged. The OS4 editor
can restore the default. Reaching the
threshold shows a cleanup suggestion on this page; it never deletes files or
schedules a background scan.

When usage exceeds the threshold, the cylinder is full and its segments retain
their relative proportions. The legend and total continue to show actual bytes.
Unmeasured values remain placeholders. Partial or failed measurements do not
claim healthy usage; a failed refresh retains the last sample and its timestamp.

Perfect storage includes its protected source ZIP, catalog and generated output,
not just the deletable download cache. Existing cleanup rules continue to protect
these resources. The disk chart excludes preview RAM, which is listed separately.
Apple download accounting and existing private cache-directory buckets retain
their prior scope; the diagram is not a complete APK/application-data inventory.
The Logs category includes `files/app-logs/` and owned exports/temporary exports
in `cache/share/`. Export filenames use the same ownership rules as the existing
export cleanup helper. They are removed from the temporary-files category, so
each file contributes once to both total usage and cylinder segments. Unrelated
files remain temporary files. Logs are managed separately from preview clearing.
The first four categories have separate information buttons with 48dp touch
targets. Each opens a compact OS4 description card for that bucket; opening
help does not navigate or mutate storage. Apple, Perfect and Logs retain their
dedicated management links.

Imported copies include `noBackupFilesDir/icon-packs/`: original APK/MTZ/ZIP
archives, extracted icon ZIPs and their metadata. They have their own category
and information button. This persistent data is included in the storage total
and reminder threshold, but is preserved by preview/temporary-cache clearing.
Its help dialog links to the icon library, where individual local copies can
be removed. Files are measured once without following links, even when roots
overlap. A successful local import/removal emits a lightweight storage revision
that remeasures the cache page; normal entry/resume still remeasures independently.
There is no periodic scan or archive decoding.

Private-cache and Perfect metadata walks run on IO, do not follow symlinks,
do not decode archives/catalogs, and do not wait for the Perfect download mutex.
Apple retains its existing download-cache measurement. The page samples on entry/resume,
manual refresh and preview clearing. It has no polling loop. Editing the budget
reuses the sample without traversing storage again.

The fill transition runs only when values change and reads animation state in
Draw, leaving text/preferences out of frame-by-frame recomposition. Font scaling
grows rows and wraps pills; the page remains scrollable.

Focused validation: 56 budget/repository JVM checks, 16 related preference
checks, and five Phone AVD UI checks across focused invocations. UI coverage
includes equal column widths, automatic/custom thresholds, invalid input,
returning to automatic mode, toolbar ordering/dialog access, resource links,
partial statistics and 1.6x text.
Evidence lives in `.artifacts/research/cache-storage-20261111/`.

The fixed-budget/log-accounting follow-up uses `cache-budget`, `ui-cache` and
the guide/navigation checks where affected. Its evidence is under
`.artifacts/research/cache-guide-release-20261112/`; the earlier counts above
describe the original UI validation, not the follow-up run.

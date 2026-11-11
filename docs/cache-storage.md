# Cache and resource storage

Cache management presents a compact status card with usage, reminder threshold,
measurement time and health pills. The cylinder and category legend occupy equal
widths. The OS4 edit icon immediately before toolbar Refresh opens the reminder
editor; the footer only explains cleanup scope and shows preview RAM.
Six colors identify snapshots, built-in copies, transfers, temporary
files, Apple downloads and Perfect resources. Apple/Perfect rows open their
management pages. Existing toolbar refresh and preview-memory clearing remain.

The cylinder capacity is a reminder budget, not device free space or a download
limit. Automatic mode uses decimal 500 MB without local Perfect resources and
1 GB with them. Custom values from 50 to 20000 MB persist independently of
resource changes. The OS4 editor can restore automatic mode. Reaching the
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

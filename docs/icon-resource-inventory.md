# Large active icon resources

The import and current-theme readers must agree on archive capacity. A successfully
applied theme must not become unreadable solely because its archive exceeds an
older browser or inventory limit.

## November 6, 2026 regression

The Xiaomi 17 Pro report included a valid applied `icons` archive:

- 71,847,473 bytes, 6,036 ZIP entries, 71,742,038 expanded bytes.
- 937 XML descriptors totaling 1,039,745 bytes.
- All source entry CRCs passed verification.

The previous active-resource reader silently treated archives over 64 MiB as
unavailable. On the exact extracted archive, its production class returned
`complete=false`, zero resources and no covered packages. Import accepts up to
256 MiB, so applying the icon pack succeeded while the status card reported
that some resources could not be read.

Preview publication independently limited expanded data to 64 MiB and individual
files to 4 MiB. Lifting only the inventory limit would leave previews failing.

## Bounded read and publication

Archive size and entry count now use the existing `IconPackLimits` contract:
256 MiB input, 100,000 entries. Inventory still reads image signatures rather
than loading image payloads. XML remains limited to 64 KiB per descriptor, with
a separate 32 MiB cumulative budget for large collections of small descriptors.
Incomplete or malformed sources remain incomplete; the warning is not hidden.

The preview worker streams through a reusable 32 KiB buffer. Its source,
metadata and stock fallback entries share one budget: 512 MiB expanded, 96 MiB
per entry, 256 MiB output including the ZIP directory, and 100,000 entries.
Source lengths, CRCs, safe paths and cancellation are checked. Native-name
metadata permits up to the shared entry count and 16 MiB, with canonical
producer/consumer validation.

The snapshot cache namespace is `browser-v4`. Opening a writer invalidates the
old ready marker before truncation. Publication validates bounded ZIP metadata,
prunes obsolete pairs, and writes the ready marker last. The provider does not
inflate image payloads. Current and previous complete pairs are retained;
failed or interrupted writes cannot be reported as ready.

These operations remain on the existing authenticated worker/provider path.
No launcher restart, native payload update, new polling loop or OEM-version
exception is required.

## Verification

Focused tests cover archives above 64 MiB, more than 16,384 entries, XML
collections above 1 MiB, exact limits, over-budget failure, streaming,
cancellation, metadata round trips and failed cache publication.

The reproducible source statistics, before/after production-reader output,
test/build logs and physical-device receipt are retained in the ignored
`.artifacts/research/theme-inventory-20261106/` directory. Device acceptance uses
the existing theme without reapplying it; source analysis and actual phone
verification are recorded separately.

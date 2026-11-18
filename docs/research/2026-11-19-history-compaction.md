# November 19 research compaction

Source baseline `ff9cffd`. The workspace contained 51.62 GiB before this pass,
with 37.85 GiB in research and 9.92 GiB in local releases. After compaction,
the same `du -sk` measurement was 31.10 GiB, a reduction of 20.52 GiB.

## Findings and repeatable checks

| Investigation | What to carry forward | Verification note |
| --- | --- | --- |
| Shared icon rendering | Resolve identity and precedence before rendering. Raw adaptive layers and completed masks have different viewport contracts. Use the reporter MTZ with paired and flat assets to verify that the generated artwork follows the native pair while preserving the authored files. | [Rendering contracts](../icon-rendering-contracts.md), [Monet geometry](../monet-layer-geometry.md) |
| Large icon packs | Bound memory through the full pipeline. Release source indexes before the output ZIP directory is assembled. Check the weighted progress bar and processed count during real generation, then wait for the Themes result and verify the applied resources. Debug b0686 covered four installed APK packs with 19,477 to 49,252 mappings, plus file-based APK, MTZ and ZIP cases. The earlier memory failure remains useful as a regression reference. | [Archive memory](../raw-icon-archive-memory.md), [Large-pack acceptance](../generic-monet-icons.md#large-pack-acceptance) |
| Home 9261 | Resolve the native scrolling wrapper, allocator, state fields and linked call graph from the current library. Let the native wrapper construct its completion callback. The 12-build offline matrix and Phone motion evidence cover distinct checks. Launcher fixtures and the 9261 evidence directory remain directly available. | [9261 compatibility](../launcher-9261-compatibility.md) |
| Gesture backdrop | Verify native rule delivery separately from what the app draws. Health received the rule but its forced edge-to-edge path still left a black strip. Bilibili's scoped artwork path covered Compose and Views at two portrait sizes. Motion samples matched after the theme drawable became available. | [Gesture backdrop](../gesture-navigation-backdrop.md) |
| Recent tasks | Compare the same retained task IDs before and after refresh. Keep explicit artwork checks and validate app ownership independently from the launch action. Phone AVD and physical device were on Debug b0739 when this pass began. The retained phone, Themes and Security Center cards were visually verified on physical Home 9259 in that run. | [Cross-surface refresh](../cross-surface-icon-refresh.md) |
| APK alias investigation | Preserve exact resource identities across package variants. The ErinAO comparison used 142 manifest, resource-table and `res/` entries. The 2.11 GiB full pull was retired after verifying those entries byte-for-byte in `erinao-resources-only.apk`. The original APK hash and comparison report remain beside the sample. | [Alias investigation](../icon-rendering-alias-regression-20261112.md) |

## Retention

Original import fixtures, launcher inputs, signed release APKs and current
build outputs stay directly available. Keep b0739 and its device evidence,
the b0736 recents baseline, the current release mapping and the local version
counter. Historical QA APKs and bulky diagnostics are stored in verified
lossless archives. Small reports, receipts and reproduction commands remain
beside the original evidence paths. Older R8 mappings retain their release
directory and are available as `mapping.txt.gz`.

| Material | Retained form | Size after compaction |
| --- | --- | --- |
| 120 historical KeiHome QA APKs | 10 `qa-apks-*.tar.zst` archives | 1.69 GiB |
| 1,350 historical diagnostics | 10 `diagnostics-*.tar.zst` archives | 3.78 GiB |
| 60 historical R8 mappings | Individual `mapping.txt.gz` files | 0.40 GiB |

Every archived member and decompressed mapping matched the original SHA-256.
A trial extraction restored all 14 APKs in the first batch. The final check
also matched the SHA-256 of 19,633 retained files, including original inputs,
release artifacts and the live test baselines. The scope and byte counts are
recorded locally in `result.json` and `space-summary.json`.

## Recover a historical file

The local index is
`.artifacts/research/cleanup-history-20261119/index.json`. Look up the original
repository-relative path in the archive `members` or `mappings` records. Each
record includes the original SHA-256. Archive members retain their original
relative paths. Extract into a scratch directory to avoid overwriting a newer
file. For example, from the repository root:

```sh
set -o pipefail
archive=.artifacts/research/cleanup-history-20261119/qa-apks-01.tar.zst
scratch=$(mktemp -d)
zstd --long=28 -dc "$archive" | tar -xf - -C "$scratch"
```

Use the archive named by the index. Extracting a whole batch also restores
hard links before accessing the linked member. For an R8 mapping, use
`gzip -dc` on the listed `mapping.txt.gz` and verify the decompressed hash
before retracing. Local indexes, archives and exact byte counts remain in the
cleanup directory. These storage checks reuse previous device results.

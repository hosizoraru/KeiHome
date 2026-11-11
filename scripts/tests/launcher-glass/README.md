# Native wallpaper material checks

Production resolves Flutter AOT source relationships; the fixture symbol/address
oracles here are host-only. Keep the seven exact launcher inputs in
`.artifacts/inputs/launchers/{7722,8593,8602,8604,9209,9226,9239}/`.
Set `KEIHOME_MATERIAL_METADATA_DIR` to an evidence directory when regenerating
source oracles and running `run_material_tests.py`, so an earlier checkpoint
is retained. The extra/native-style generators use the same version inventory.

Generate independent source oracles and run the native profile/rejection suite:

```sh
python3 scripts/tests/launcher-glass/material_fixture_metadata.py
python3 scripts/tests/launcher-glass/material_extra_fixture_metadata.py
python3 scripts/tests/launcher-glass/material_native_style_metadata.py
python3 scripts/tests/launcher-glass/run_material_tests.py
```

Run source-only bridge regression before the main native build, without Gradle:

```sh
python3 scripts/tests/launcher-glass/test_material_bridge.py \
  --source --ndk "$ANDROID_NDK_HOME" \
  --fixtures .artifacts/inputs/launchers \
  --metadata "$KEIHOME_MATERIAL_METADATA_DIR" \
  --report /tmp/keihome-material-source-bridge.json
```

Run actual linked production ARM64 leaves, including native Rx.value/Rx.refresh
and the original return PC for Dock and search in each image. The shipping
configuration is 136 bytes, with a trusted role shift at offset 128 (0 for
Dock/folder, 2 for search); the harness rejects folder bridge/global symbols.
After the main native build, rerun against its exact unstripped linked SO:

```sh
python3 scripts/tests/launcher-glass/test_material_bridge.py \
  --payload app/build/intermediates/cxx/RelWithDebInfo/v2v5s392/obj/arm64-v8a/libkeihome_hyos_lsp.so \
  --fixtures .artifacts/inputs/launchers \
  --metadata .artifacts/research/dock-material-20261105/material-sidecar \
  --report /tmp/keihome-material-bridge.json
```

Use a Python environment with Unicorn, pyelftools and Capstone. The source suite
uses clang++, ASan and UBSan. The linked suite covers native and forced choices,
live subject guards, no wallpaper writes, relocating roots and reentrant/newer
revisions. Host execution does not establish real VM GC or GPU correctness;
device screenshots, process identities and trace acceptance remain separate.
The pair regression contains 3,656 cases, including 1,008 native fixture scenarios
across all seven binaries. It executes both role shifts, all nine valid pairs,
reserved-field fallback, register/flags preservation and original native choices.
The source-only report is not a final linked shipping receipt.

Protocol command 10/capability 64 uses foreground = Dock | (effectiveSearch << 2),
each field 0..2, with high bits/background/count all zero. Java resolves follow
behavior before sending. Legacy command 9 accepts only 0..2 and sets both fields.
Both commands share the 45-second glass lease, ACK, expiry and worker retirement;
only a changed pair increments the material revision. Capability 64 is advertised
alongside capability 16 after glass installation, which requires verified search.

Folder default colors, press light and a folder-refresh route remain host-only
research in the source-profile suite. The unaccepted runtime experiment is
archived under `.artifacts/research/dock-material-20261105/unshipped-folder/`.
Android neither scans nor installs these roles. Source-proof totals do not
establish resting-folder or full-device material acceptance.

Unshipped backend-forcing, descriptor, XZ and GPU-cache prototypes are retained
outside production under `.artifacts/research/dock-material-20261105/engine-experiment-source/`.

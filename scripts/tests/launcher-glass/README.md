# Native wallpaper material checks

Production resolves Flutter AOT source relationships; the fixture symbol/address
oracles here are host-only. Keep the five exact launcher inputs in
`.artifacts/inputs/launchers/{7722,8593,8602,9209,9226}/`.

Generate independent source oracles and run the native profile/rejection suite:

```sh
python3 scripts/tests/launcher-glass/run_material_tests.py
```

Run actual linked production ARM64 leaves, including native Rx.value/Rx.refresh
and the original return PC for Dock and search in each image. The shipping
configuration is 128 bytes; the harness rejects folder bridge/global symbols:

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
The shipping receipt contains 1,343 cases, including 240 native fixture scenarios.

Folder default colors, press light and a folder-refresh route remain host-only
research in the source-profile suite. The unaccepted runtime experiment is
archived under `.artifacts/research/dock-material-20261105/unshipped-folder/`.
Android neither scans nor installs these roles. Source-proof totals do not
establish resting-folder or full-device material acceptance.

Unshipped backend-forcing, descriptor, XZ and GPU-cache prototypes are retained
outside production under `.artifacts/research/dock-material-20261105/engine-experiment-source/`.

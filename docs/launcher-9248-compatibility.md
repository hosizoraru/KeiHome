# Launcher 9248 compatibility

Input: `RELEASE-8.01.02.9248-261013-11112305-R`, package
`com.miui.home`, version code `801029248`.

APK SHA-256:
`7b2099c0631946c33969345174097be64e77cda3a83ae8b58ef06c4bf54142fb`.
The APK contains no DEX. Its ARM64 `libapp.so` Build ID is
`4f1bdaed67e9b0e056bde644f6278969`.

The original APK, extracted libraries, authenticated `.gnu_debugdata`, signer
receipt and provenance are retained under `.artifacts/inputs/launchers/9248/`.
The Downloads source was copied without modification and is no longer needed
for future regressions. These identities identify test inputs; they are not
runtime admission rules.

## Structural changes

The shipping parsers safely reject three new shapes: Monet selection, guarded
image recovery, and Dock geometry. The adaptation adds instruction/call-graph
alternatives while preserving historical contracts and rejection checks.
Tint, app labels, icon highlighting and shared launcher material contracts
already admit this input.

The selection graph keeps the old 130 roots, 1,083 bodies and 4,249 edges as
exact prefixes, adding 19 roots, 147 bodies and 658 edges. Image recovery keeps
all 21 old shapes and adds three. Dock keeps all 121 previous word/mask arrays
and all 56 dispatchers, adding nine strict candidates. No resolver guard,
matching mask or native bridge ABI was loosened.

An independent Rust audit compares 23 selected icon/palette bridge roles with
9239. All have equal instructions after normalizing address relocations. This
is static evidence about the inspected roles, not proof of every Rust ABI.

The launcher widget SDK is retained as a MAML negative fixture. External
framework MAML SDK fixtures remain the positive controls; their release-role
support must not be attributed to this launcher APK.

## Validation

Source regression covers all nine retained launcher fixtures. It includes 715
selection assertions, 234 tint rejection checks, 621 tint ARM64 cases, 72 image
positives and 1,143 image rejections, 486 image ARM64 cases, 360 gate checks and
1,152 Dock profile checks. Selection bridge validation includes decoded native
path modeling; those modeled cases must not be described as Unicorn execution.
The disabled theme-night candidate retains its existing acceptance/rejection
boundary, including rejection of 9248.

The focused 9248/9239 checks also cover label ownership/layout mutations,
highlight patch transactions and bridge execution, launcher material profiles,
and MAML positive/negative controls.

Final linked-payload and temporary Phone AVD acceptance are recorded under
`.artifacts/research/launcher-9248-20261114/`. Device results are pending.

No physical device upgrade, tag, version-prefix change, or public changelog
change is part of this adaptation.

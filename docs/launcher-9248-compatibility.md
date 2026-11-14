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
`.artifacts/research/launcher-9248-20261114/`. The optimized, unstripped payload
SHA-256 is
`3f8efc24a81863a9c91a9882ff3582c4332533fb87a63ceaeca7e4ad62b5dff1`;
the packaged payload SHA-256 is
`e37d921c3fa20af6da2a507127f7c12452b8a4f94e516c6486c83e8753714b8a`.
Linked validation authenticates this exact payload rather than borrowing
results from the previous shipping library.

On the unlocked Phone AVD, the running launcher mapped an updated `/data/app`
APK whose full SHA-256 matched the retained 9248 input. Its live native log
reported identity `2c624c28` and ready Monet, Dock, labels and shared material
profiles. The App reported Home 9248, system loaded and desktop ready.

The system partition also contains launcher 7722. That built-in baseline,
the installed update and the running APK/native library are distinct
identities. An earlier boot with conflicting metadata is excluded from
acceptance; it does not establish a rollback to 7722.

Dock settings received native confirmation, and the enabled glass background
was visually checked with four Dock apps. Monet enablement produced an
acknowledged palette and icon refresh; this is not visual acceptance of every
Monet icon. Five-app geometry remains covered by the native regression matrix,
not a five-app device demonstration.

An ordinary API 102 update from diagnostic 459 to Release 460 passed both
generation handoffs without changing the launcher, SystemUI or system_server
process identities. The initial native activation used a managed cold start;
that activation is separate from the subsequent ordinary hot-reload result.

The signed internal Release is `1.9.0+g81ccbbc.b0460` (109000460), built from
`81ccbbc1aaeb809940b9686f080f383a82d3961f`. Its APK, R8 mapping and native symbols
are retained under `.artifacts/releases/109000460/`.

After temporary 9248 acceptance, the original Phone AVD snapshot was restored.
The starting launcher update was 8593, with 7722 still present as its system
baseline. The restored AVD was explicitly unlocked and entered HOME before
admission. Release 460 then passed its Java handoff and an explicitly requested
development native activation. The latter replaced the launcher spawner;
system_server and SystemUI kept their identities. It is not counted as a
restart-free native update.

The final status reports Home 8593, system loaded and desktop ready, with the
original Perfect Icons HyperOS4 package and Monet disabled. The final health
check passes with the keyguard dismissed. The owned test disk, original
checkpoint copy and the automatic backup created during restoration were
removed after checking that no active QCOW2 backing chain or open file
depended on them. Backup metadata and the cleanup receipt remain in the
research directory.

No physical device upgrade, tag, version-prefix change, or public changelog
change is part of this adaptation.

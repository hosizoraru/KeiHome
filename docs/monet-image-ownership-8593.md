# 8593 disposed Image: leaf ownership recovery and optional capture

Status: **recovery implemented and host-validated; runtime exercise pending**.
The default native build now recovers the authenticated RawImage update boundary
when its RenderImage still owns a live wrapper for the exact same backing image.
Both complete Android native Release variants (trace OFF/ON) compile and link.
No APK build, deployment, device action, continuity run or API-102 reload was
performed by this recovery task. Main owns those steps and icon-pack UI work.

This follows the October 31 section of `anr-investigation.md`. The reproduced
exception occurs in `Image.clone`, called by `RawImage.updateRenderObject` after
Monet off/on and night switching. Existing tint/selection behavior remains under
its existing owner. This work does not suppress exceptions, omit disposal, modify
Dart objects, or invoke allocating managed code from an assembly bridge.

## Evidence from the actual image

Analysis inputs are `/tmp/keihome-launcher-8593/{libapp.so,symbols.txt}` and the
7722 `unpacked/lib/arm64-v8a/libapp.so` plus `debug/symbols.txt` under
`/tmp/keihome-launcher-analysis-20261029/`. Their library SHA-256 values are
`68f62bbd0a73ae0096c27a4cb4bf1789a4452220510a05c658a3968fa38f470d` and
`fb8b1f91eba2d9c43875f49bbded70cca462be341fc379b9864e7c6136d6e463` respectively.
These identify the inspected artifacts; they are not runtime allowlists.

The following RVAs are **8593 analysis evidence**, not production lookup keys:

| Path | Observed ownership behavior |
| --- | --- |
| `Image.dispose` `0x93e38c` | Sets this wrapper's disposed flag at `0x93e3a0`, removes it from the backing image's handle set, and only disposes the backing image when that set is empty. Retaining another engine handle cannot make this wrapper clonable again. |
| `Image.clone` `0xa06b10` | Checks that wrapper flag before allocating another wrapper. The throw call is `0xa06ba8`. |
| `MonochromeIconCache.get` `0x13a3b24` | Removes and reinserts the same record for recency, then returns it. No image clone occurs. |
| `MonochromeIconCache.put` `0x13a39d4` | Stores the incoming first/optional second wrappers directly. An existing key returns early. Above 200 entries, eviction removes an old record and calls `Image.dispose` at `0x13a3ae8` and `0x13a3b00`. Thus clear is not the only cache disposal source. |
| `MonochromeIconCache.clear` `0xed26d0` | Disposes first and optional second wrappers at `0xed2774`/`0xed278c`, then clears the map. `_refreshIcons` calls it at `0x1a5e658`, before controller notifications. |
| Widget `build` `0x11b9850` | Loads State image fields at tagged offsets `+0x13`/`+0x17`, then copies them into newly allocated RawImages at `0x11b993c`/`0x11b9ab0`. It does not inspect disposed state. |
| `RawImage.updateRenderObject` `0x158b914` | Calls `Image.clone` at `0x158b98c`, before setting the render object's image. |
| Widget `didUpdateWidget` `0x13a387c` | Compares source/resource/dimensions before `_loadIcon`; it does not compare a cache generation or either wrapper's disposed state. |

`_loadIcon` at `0x1aee580` gets the shared cache record at `0x1aee5e8`. On a
miss, it awaits the first image conversion, styled image, and optional foreground
conversion. The subsequent checks at `0x1aee808`, `0x1aee874`, and `0x1aee9f0`
test mounted state; they do not authenticate a load/cache generation. Before
insertion it calls `_cacheKey` again at `0x1aeeab0`, then `put` at `0x1aeead0`.
The final mounted check and `setState` follow that insertion. No direct
`Image.dispose` call appears in this entire body or `didUpdateWidget`.

Consequently, a pre-clear async operation can finish after invalidation, and a
later key read is not proof that the result belongs to that key's generation.
The checks also have unmounted early-return paths without a direct dispose in
this body. This does not establish which lower-level helper owns every temporary
result, or that a particular captured failure came from an in-flight operation.

There is no named `_MonochromeIconWidgetState.dispose` override in either
inspected symbol listing. **Absence of that symbol does not prove a no-op or an
ownership rule.** `StatefulElement.unmount` at `0xe8e0c8` actually dispatches
dispose through live GDT at `(State CID + 0xfd80)`, then clears the State's
element field and the Element's State field. The observer records that exact
live dispatch target during build. Its scalar code RVA can be inspected against
the actual ELF, including unnamed/shared implementations, before assigning
widget-owned clones or changing any release responsibility.

Main's saved 8593 capture `/tmp/keihome-icon-packs/image-trace/threadtime.log`,
PID 24832, reports State dispose GDT target `0x1915b0c`. The exact ELF contains
`mov x0, x22; ret` there (8 bytes); `RustLibApiImpl.sse_decode_unit` is a shared
symbol alias, not evidence that widget disposal calls Rust. The capture has
209 events, 36 widget builds, zero disposed clone events, and ends with
`lost=1 pending=0 full=0`. It is a lossy non-reproduction after a fresh startup,
with page1 stock icons, not a fix or clean lifetime proof. Read the log with
`errors='replace'` because unrelated messages contain invalid UTF-8. The prior
`/tmp/keihome-runtime-status/private-cycle.log` does contain the actual disposed
clone failure; its frame PC `0x158b98f` corresponds to the update BL at `0x158b98c`.

An ordinary-icon comparison in this same ELF supplies a concrete ownership
reference: `_RawIconWidgetState.dispose` at `0x1563e44` calls
`_disposeCurrentImage` at `0x13a3598`, which disposes and clears its first image.
Its `_renderAndCache` has explicit clone calls at `0x1aef0c4`/`0x1aef164` and
cleanup calls at `0x1aeef50`, `0x1aef074` and `0x1aef248`. These belong to that
different State's original managed lifecycle; they are not a proven drop-in
replacement for the monochrome State's two-image/async ownership.

## Why recovery is limited to the update owner

Cloning only a cache hit would leave the fresh-load/put sharing path unchanged.
Making all returned wrappers independent would additionally require verified
replacement, widget disposal, duplicate-put, unmounted completion and superseded
load cleanup. Clearing without disposal leaks resources; always calling
`_loadIcon` does not prevent the intervening build from reading an old disposed
wrapper. None of these is a justified one-instruction fix from the current
evidence. The disposal caller for a particular failing wrapper remains uncaptured.
The live no-op dispose target does not supply a complete async/cache owner model.

The implemented recovery instead uses the live RenderImage already rooted by
this exact update call. The following values are analysis evidence; production
resolves instruction families, call edges and allocator metadata dynamically.

| Contract | 8593 | 7722 |
| --- | --- | --- |
| Image.clone | `0xa06b10` | `0xa1e5e0` |
| RawImage.updateRenderObject | `0x158b914` | `0x1539834` |
| Authenticated LR at clone entry | `0x158b990` | `0x15398b0` |
| Original receiver spill / frame size | `FP-0x18` / `0x28` | same |
| RenderImage.image= | `0x1574f14` | `0x151d860` |
| Image CID / RenderImage CID | 9346 / 5381 | 9173 / 5237 |
| Tagged RenderImage image / Image disposed / backing offsets | `+0x63` / `+0x1f` / `+7` | same |

`RawImage.updateRenderObject` spills its incoming x3 at `+0x20`, checks its
RenderImage CID at `+0x3c`, calls clone at `+0x78`, then reloads the same spill
at `+0x84` and calls the image setter at `+0x8c`. At clone entry, x29 is still
the **original caller FP** and x15 equals `FP-0x28`. No bridge FP is created.
The RenderImage CID is independently checked against the allocator called by
`RawImage.createRenderObject`; the Image CID is tied to clone's allocator and
its generated type test. Both allocators must use the validated generic header
store and tagged return. The setter and renderer teardown agree on the owned
image field and their direct Image.dispose targets.

The leaf checks, in order: enabled; exact caller LR; original FP alignment and
FP/SP relation; requested Image class and canonical disposed=true; rooted
RenderImage class; owned Image class and canonical disposed=false; identical,
non-null, tagged backing references in the same compressed-pointer heap. Only
then does it redirect x1 to the owned wrapper and tail-transfer to original
clone. Every rejected case keeps the original x1 and behavior. It does not
return null, reset a disposed bit, skip dispose, resurrect a wrapper, or choose
an image with merely equal dimensions. No new-mount replacement is fabricated.

The original clone loads and roots the owner's backing before allocating. The
original setter compares backing identities, disposes this new clone, and keeps
the existing renderer-owned wrapper. Thus the recovery preserves identical
pixels and the original managed allocation/handle registration/disposal path.
It does not repair cache/State ownership, refresh a stale generation, or recover
new mounts, missing/disposed owners or differing backing images.

The bridge uses 48 bytes below Dart SP, no more than original clone's 16+32-byte
frame. It restores SP before tail transfer; there are no calls, safepoints,
native CSP changes, heap writes or retained Dart references. x2-x30, SIMD,
NZCV, FPCR/FPSR and CSP are preserved; x1 changes only on recovery. x0 carries
the trampoline because the authenticated clone entry kills incoming x0 before
any read or call. Direct branches into the possible 32-byte displaced clone
entry reject the profile. Indirect interior entries and the actual LSPosed
detour/trampoline are not whole-program proofs supplied by these host checks.

`KHIMG_RECOVERY installed ... repairs=0 update_only=1` identifies an active
profile. A saturating native counter (maximum 65,535) records repair selection.
It uses one exclusive attempt with no retry/spin; failure sets sticky
`uncounted=1` and still performs the recovery. The counter is a lower bound
when uncounted or saturated. The existing native policy worker emits changed
values as `KHIMG_RECOVERY repairs=N uncounted=N saturated=N ...`, at most once
per second at its existing maintenance points. Idle channel waits can delay
reporting up to the existing 20-second wait; Binder stalls can delay it further.
No extra timer/thread or Dart-thread logging is introduced. Counts survive
policy and Java owner replacement, and reset only with the process. A positive
count proves entry selection, not completion of managed clone or visible UI.

## Native observer contract

`KEIHOME_MONET_IMAGE_TRACE` is a CMake option, **OFF by default**. OFF excludes all
diagnostic observer/resolver/worker objects and includes actual recovery. ON
logs `KHIMG_RECOVERY disabled diagnostic_stock=1` and installs three observers only during the existing first
pre-isolate `libapp` load in the launcher. A profile rejection disables only this
diagnostic. There is no double-hook of Image.clone: the diagnostic owns it
exclusively and recovery stays disabled even if diagnostic setup fails.
Partial installation leaves installed observers disabled and passing
through; no worker is started. There is no on-device control endpoint, property,
marker file, policy opcode, or Java activation path.

The resolver follows the local Apache-2.0 MiuiBackGestureHook approach: bounded
readable/executable mappings, unique instruction families, decoded branch and
layout relationships, and rejection on ambiguity. Seven roles have nine shapes
across both fixtures. It retains stack/register/object-field/local-branch
instructions, normalizes BL displacements and PP indices, and decodes the GDT
selector. All normalized BLs must still target executable mappings; clear/put
must each call the same dispose twice and RawImage must call the resolved clone.
The clone allocator's tags, adjacent type check and generic allocator's actual
header store authenticate the Image CID. Incoming direct branches into the
possible 32-byte displaced entries are rejected. Arbitrary indirect entry into
an instruction interior is not a whole-program proof supplied by this scan.

The leaf assembly makes no calls/syscalls, allocations or waits, and writes no
Dart heap. It preserves x1–x30, SIMD, NZCV, FPCR/FPSR and native CSP. An 80-byte
scratch area below Dart SP is restored before tail transfer; no additional frame
or saved root crosses a call/safepoint. **x0 alone** carries the original
trampoline address: all three authenticated entries overwrite incoming x0
unconditionally before reading it or branching/calling. x16 is preserved. The
actual LSPosed detour/trampoline backend is outside the host emulator test.

Each event contains scalar thread/address bits, original LR, both image flags,
and (for build) the State and its live dispose-dispatch code PC. Image class and
bool encodings are checked before reading image fields. The worker never
dereferences recorded address bits. A later event re-reads only that invocation's
live arguments. There are no Dart roots, global handles, or retained heap
objects. Address equality across events can be affected by GC movement/reuse;
the report describes correlations, not persistent object IDs.

After a 30-second native-worker delay, one 120-second window begins at `KHIMG
armed`. Capacity is 8,192 immutable 80-byte records (640 KiB), with no wrap or
reset. A producer makes one exclusive try-lock attempt; contention sets sticky
`lost=1` and immediately continues the original code. A native worker drains to
logcat; the Dart thread never logs. Buffer exhaustion and the deadline disable
capture. A pending producer at bounded drain expiry is explicitly reported.
Static storage remains valid for process lifetime, including after API-102 Java
reload. The observers cannot be rearmed by policy updates.

Kinds are `1=dispose entry`, `2=clone entry`, `3=widget build entry`; flags are
`0=live`, `1=disposed`, `2=absent`, `3=unrecognized`. Clone records are limited to
the verified RawImage update caller or an already-disposed input. Dispose entry
records precede the original flag write; they are not a completion event.

## Host checks

Run from the repository with the existing Python environment (Capstone, Unicorn,
pyelftools), or another environment with those packages:

```bash
/tmp/keihome-capstone-venv/bin/python scripts/tests/monet-image-trace/validate.py \
  --library /tmp/keihome-launcher-8593/libapp.so \
  --library /tmp/keihome-launcher-analysis-20261029/unpacked/lib/arm64-v8a/libapp.so \
  --output /tmp/keihome-image-trace-validation
```

The host runner compiles a standalone resolver test and diagnostic translation
units, links a synthetic ARM64 bridge harness, and executes it in Unicorn. It
does not build an APK or connect to Android. Both ELF profiles are checked
against their independently decoded `.gnu_debugdata` symbols. It checks body,
field, allocator, call-edge, range, ambiguity and patch-interior mutations;
positive tests move dispose and its callers, move the CID/GDT selector coherently,
and change an unrelated identity byte.

There are 30 rejected and 6 accepted resolver cases per fixture, and 50 leaf
machine-code cases per derived layout. The latter check register/vector/status
preservation, record publication, guards, full/last-slot behavior, disabled
retired roots, failed-exclusive and disarm races, and no heap writes. Explicit
race injection is a path test, not an ARM weak-memory or Dart GC stress proof.
The log parser separately tests incomplete, lossy, duplicate, cross-PID and
cross-thread evidence. Retain `validation.json`, `commands.log`, both layouts and
bridge reports with the later runtime capture.

## Recovery host checks and native builds

Run from `/Users/voyager/voyager space/androidGit/KeiHome`:

```bash
/tmp/keihome-capstone-venv/bin/python scripts/tests/monet-image-recovery/validate.py \
  --library /tmp/keihome-launcher-8593/libapp.so \
  --library /tmp/keihome-launcher-analysis-20261029/unpacked/lib/arm64-v8a/libapp.so \
  --output /tmp/keihome-image-recovery-validation

bash scripts/tests/test-monet-policy-server.sh
```

The recovery runner requires the existing Capstone/Unicorn/pyelftools Python
environment, a host C++17 compiler, CMake and Android NDK API 37. `NDK_DIR` can
select a specific NDK. It builds the **entire native shared library** in Release
mode with trace OFF and ON, outside the worktree; it does not invoke Gradle,
create an APK or connect to Android.

Final focused results (October 31):

- Both ELFs: **8 accepted / 58 rejected resolver cases each**. Independent ELF
  symbols verify entries. Mutations cover frame/field/bool/decompression changes,
  setter/dispose/allocator call edges, class tags, actual allocation header/tag
  instructions, malformed/range/overlap/non-executable inputs, duplicate entries,
  and all direct branch families into displaced clone bytes. Coherent CID
  changes, moved clone and moved update caller (including its new LR) pass.
- Both layouts: **54 ARM64 cases each**, executing the actual assembled bridge.
  They check every GPR/vector/status register, original FP/SP and heap immutability,
  null/Smi/misaligned/wrong-class/foreign-heap inputs, live/disposed/unknown flags,
  original-frame versus x3/bridge-frame decoys, backing mismatch, new mounts,
  all 16 NZCV combinations, saturation and forced exclusive failure. Real ELF
  clone/setter bodies run with explicitly mocked allocation/handle registration;
  they show normal cloning followed by disposal of the new clone and retention
  of the owner, and the unchanged throw branch on missing/different owners and
  new mounts. This is not real managed allocation or Dart GC validation.
- **6 host install/worker scenarios**: ordinary and diagnostic builds, each with
  success/rejected-profile/hook-failure inputs. Verify one installation, no
  diagnostic recovery hook/resolution, fail-closed activation, silent unchanged
  counters, rate bounds, zero/rewound clock, sticky undercount and saturation.
- Complete native Release **OFF and ON builds pass** with project `-Werror` and
  `--no-undefined`. Linked symbol checks confirm the ordinary library contains
  the recovery bridge and no trace clone bridge; diagnostic contains the trace
  clone bridge and no recovery bridge.
- Existing diagnostic suite: **30 rejected / 6 accepted resolver cases per ELF,
  50 ARM64 cases per layout, 11 report cases**, all pass. Existing policy server
  ASan/UBSan tests (including concurrent publication/readers) pass.

Receipts: `/tmp/keihome-image-recovery-validation/{validation.json,commands.log,
layout-0.json,layout-1.json,bridge-0.json,bridge-1.json}` and
`/tmp/keihome-image-trace-recovery-regression/validation.json`. The two native
libraries are in `native-off/` and `native-on/` under the recovery output.
The validation JSON pins sources and artifacts by SHA-256.

## Exact next procedure for Main

1. Build the **normal** increasing-version Release APK, explicitly overriding
   any cached diagnostic CMake option:

   ```bash
   ./gradlew -I scripts/tests/monet-image-trace/diagnostic.init.gradle \
     -PkeihomeImageTrace=off :app:assembleRelease
   ```

   Check generated CMake cache for `KEIHOME_MONET_IMAGE_TRACE=OFF`. This recovery
   task did not run that APK command. Use the authorized native development
   replacement to load the new library; Java-only API-102 reload cannot update
   native code. Development restart authorization is distinct from final normal
   switching, which must not restart the launcher.

2. Preserve continuous threadtime logs and launcher/system_server/SystemUI PID
   plus start-tick identities, exact launcher ELF/native payload, original night
   mode, `key_monochrome`, wallpaper/source choices and visible page. Require
   `KHIMG_RECOVERY installed` with the appropriate dynamically resolved contract.
   A diagnostic-stock disabled message or inactive profile cannot exercise repair.

3. **Warm the visible page2 Monet icons**, letting their renderers acquire live
   images before cycling. The previous fresh-start page1 stock-icon sequence did
   not exercise the disposed case. Keep page2 visible, repeat controlled Monet
   off/on and night switching in both directions at the failing pacing, and
   record action times and counter baseline/deltas. Allow the existing worker
   maintenance to flush counts. `repairs>baseline` (or first `uncounted=1`) proves
   the leaf selected recovery; unchanged zero is explicitly **not exercised**.

4. Pair counter evidence with continued native icon rendering, actual source/
   artwork correctness, no disposed clone error, and unchanged process identities.
   A positive counter alone does not establish managed completion, pixel
   correctness, absence of other failures, or fixed cache lifetime. Preserve any
   remaining exception: it can be a different backing, absent/disposed renderer
   owner or new-mount path, which intentionally retains original failure behavior.

5. Exercise subsequent normal switching without launcher restart and the final
   increasing-version API-102 Java-only reload with the same native payload.
   The native counter and hook survive that reload. Restore original user state.
   Main owns AVD/runtime/visual acceptance; none of those gates passed in this
   host-only task.

For another stock disposal capture, use the same Gradle command with
`-PkeihomeImageTrace=on`. It explicitly disables recovery, so zero errors there
must not be attributed to this fix. Analyze the original PID's saved file with
`scripts/tests/monet-image-trace/report.py`; keep its loss/completeness flags and
never merge replacement processes or infer persistent object identity from
address equality across safepoints.

## AVD follow-up, October 31

Normal native recovery loaded in launcher 8593, PID 19437/start 1778502.
A visible page-2 off/on/light/off/on/dark sequence preserved launcher, SystemUI
and system-server identities and produced no disposed-image exception. Its
8-second on captures preceded callback completion and showed mixed black/tinted
foreground; this was not a finished-frame tint failure. The acknowledged package
queue still used 1500 ms + 220 ms per package.

After an authorized development recovery boot and the Java pacing update, the
explicitly foreground page-2 off/on check preserved launcher 9698/start 3925,
system-server 8820/start 2936 and SystemUI 9674/start 3921. Eligible palette sweeps
completed 73 callbacks in 3.223 and 3.429 seconds after enumeration. Night/source
changes retain the original pacing. Captures were taken after matching completion
receipts. Evidence: /tmp/keihome-icon-packs/fast-palette-home/.

No positive recovery counter was observed in these runs. They do not prove the
recovery branch was exercised or close the underlying cache/State ownership bug.
The preceding AVD session also hit system-wide UIAutomation/install response
timeouts and SystemUI SCOUT hangs; its logs are retained under
/tmp/keihome-icon-packs/pre-recovery-*. Those failures are not erased by recovery
or by the narrower successful switching checks.

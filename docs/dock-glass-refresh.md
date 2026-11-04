# Dock glass native refresh handoff

This is an assembly component and resolver-role contract, not an installer.
The parent owns the native rendering bridge, profile preparation, BL patches,
rollback and capability publication. No Kotlin or existing hook is changed by
this component. Capability/command 8 and the existing server-owned Dock globals
remain the transport contract.

## Parent integration

1. Prepare the Dock profile from the original executable graph **before**
   installing labels. Authenticate both discarded logger calls while original.
2. Install labels normally: labels retain the **first** logger call.
3. Include dock_glass_refresh.inc with #include in the parent's preprocessed
   AArch64 .S, in its copied template section after a terminal branch/return,
   before the parent's final template-end label. The include changes no section.
4. Fill its embedded DockGlassRefreshConfig before publishing executable code.
   The struct and shared assembler offsets are in dock_glass_refresh.h.
   Copy the block contiguously; its ADRs must resolve to the copied config.
5. Install Dock after labels. Compare the still-original **second** logger BL
   and getter Rx.value BL against the words saved during preparation. Do not
   require the first logger BL to remain original at this stage.
6. Patch those two BLs to kh_dock_refresh_event and
   kh_dock_refresh_value, respectively. Both must remain single-instruction
   calls with original return PCs. Validate BL reach from both sites, executable
   publication, instruction-cache synchronization and transactional rollback.
7. Release-store kh_dock_glass_installed = 1 only after rendering **and** both
   refresh patches are ready. Failure must leave capability 8 unpublished.

The bridge consumes pending revisions for both enable and disable, including
lease expiry. It deliberately does not gate refresh on enabled.

## Config ABI

All 13 entries are uint64_t, 104 bytes total, aligned to 8. The three global
addresses and all native code/return addresses are absolute live addresses.
Field offsets are bytes from tagged objects; table slots are bytes from the
full-width field table. The config itself contains no managed reference.

| Byte | Member | Source |
| ---: | --- | --- |
| 0 | installed | Address of uint32_t kh_dock_glass_installed |
| 8 | revision | Address of uint64_t kh_dock_glass_revision |
| 16 | notified | Address of uint64_t kh_dock_glass_notified |
| 24 | logger | Original second discarded logger BL target |
| 32 | getter | Authenticated GridController.currentConfig entry |
| 40 | event_return | Original second logger BL address + 4 |
| 48 | value | Original getter BL's generic Rx.value target |
| 56 | refresh | Matching generic Rx.refresh entry; not RxList.refresh |
| 64 | grid_slot | Getter's cached GridController table slot |
| 72 | config_rx_field | Getter's GridController-to-Rx tagged field |
| 80 | rx_value_field | Generic Rx.value's current-value tagged field |
| 88 | rx_subject_field | Generic Rx.refresh's subject tagged field |
| 96 | observer_slot | Generic Rx.value's active-observer table slot |

The event acquires the pending revision into **x9**. The authenticated cached
getter path must preserve x9 and contain no call, allocation or safepoint before
the intercepted Rx.value BL. This is a mandatory profile property, not an
assumption for other versions. No stack slot or process-global token is used.

The interceptor first compares the getter's saved caller LR at **[x29 + 8]**
with event_return. Ordinary getter callers go directly to original Rx.value.
For the event path it checks installed, live cached controller/Rx, initialized
subject/value, no active Rx observer, and x1 equality with that cached owner's
Rx. It then requires the acquired revision still equal x9 and still pending.
Only then does it release-store x9 to notified and tail to generic Rx.refresh.
An intervening newer revision before this comparison leaves notified untouched.
A publication after the comparison remains pending because the stored token is
the original x9, not a second revision load.

Notification is marked before the native call to prevent reentrant duplicates.
It means native notification was attempted; it is not a rendered-frame ACK.
Native scheduling can coalesce rebuilds. Unexpected native subscriber exceptions
are not caught by this assembly.

## Production resolver API

The parent compiles dock_glass_refresh.cpp and calls:

~~~cpp
bool ResolveDockGlassRefresh(const IconHighlightImage& image,
                            const LauncherLabelProfile& labels,
                            DockGlassRefreshProfile* out,
                            const char** why = nullptr);
~~~

Pass the complete resolved labels from this same original image, before label
installation. Every output code location is an **ELF-relative offset**. The
result includes second_event_call, second_event_target, second_event_expected,
getter_value_call, getter_value_expected, refresh, stream_add,
rx_subject_field and observer_slot. The config ABI remains unchanged.

The resolver rechecks the complete native getter and generic value shapes,
including the exact cached path that preserves x9. It derives observer_slot
from the value body's field-table lookup and checks the input label slot/field
linkage. It finds both original info-logger calls in the bounded item-change
prefix, requires the first to be labels.refresh_call, and proves the second
overwrites x0 before consuming the change set.

It scans readable executable ranges for a unique complete **0x38-byte** refresh
body whose first BL targets labels.value. The subject field must match the one
used by that value body's NotifyManager.addListener path. Its second BL must
match the complete GetStream.add shape, whose own notification call must match
the _notifyData subscription-list prefix. Other refresh specializations are
excluded by the linked value target. Missing, changed or ambiguous roles fail
closed with cleared output and a static rejection string.

No runtime symbol/debugdata reads, version checks, image hashes or fixed image
addresses are used by this resolver. It performs no allocation or installation.

## Resolver roles and exact 8593 oracle

Only the following fixture addresses are constants in this report. Do not use
them as production resolution or an address allowlist.

Fixture: .artifacts/inputs/launchers/8593/libapp.so

SHA-256:
68f62bbd0a73ae0096c27a4cb4bf1789a4452220510a05c658a3968fa38f470d

| Role | ELF VA | Relationship / derivation |
| --- | ---: | --- |
| ItemChangeProcessor.processItemsChanged | 0x19aa0d0 | Native item-change root |
| First logger BL | 0x19aa170 | root + 0xa0; label-owned |
| Second logger BL | **0x19aa194** | root + 0xc4; Dock-owned |
| Second logger return PC | **0x19aa198** | second BL + 4 |
| Both original logger targets | 0x8ce6c4 | _LoggerImpl.info |
| GridController.currentConfig | **0xb47210** | Native getter with frame and cached path |
| Getter value BL | **0xb47264** | getter + 0x54 |
| Getter continuation | **0xb47268** | native epilogue; preserve this x30 |
| Generic Rx.value | **0xf64c1c** | getter's original BL target |
| Generic Rx.refresh | **0xb464b0** | same value target, then GetStream.add |
| GetStream.add | 0x8b2904 | refresh + 0x24 call target |
| grid_slot | 0x27c8 | getter + 0x10 LDR immediate |
| config_rx_field | 0x2b | getter + 0x4c LDUR immediate |
| rx_value_field | 0x13 | value + 0x3c LDUR immediate |
| rx_subject_field | 0xb | refresh + 0xc LDUR immediate |
| observer_slot | 0x1d60 | value + 0x18 LDR immediate |

The saved instruction words for the two Dock patch sites in 8593 are
0x97bc914c (second logger BL) and 0x9410766e (getter value BL).

### Original second logger and discarded-result evidence

~~~asm
019aa170: bl    0x8ce6c4       // FIRST logger; labels retain this slot
019aa174: mov   x17, #-0x158
019aa178: ldr   x2, [x29, x17] // overwrites args, not a use of returned x0
019aa17c: ldr   x1, [x27, #0x7be0]
019aa180: bl    0x1bdc498      // closure allocation
019aa184: mov   x17, #-0x148
019aa188: ldr   x1, [x29, x17]
019aa18c: mov   x2, x0         // new closure result
019aa190: ldr   x4, [x27, #0x70]
019aa194: bl    0x8ce6c4       // SECOND logger; Dock patches ONLY this call
019aa198: mov   x17, #-0x158   // original event_return
019aa19c: ldr   x2, [x29, x17]
019aa1a0: ldur  w0, [x2, #0xf] // overwrites logger/getter/refresh result
019aa1a4: add   x0, x0, x28, lsl #32
~~~

Authenticate the item-change root with the original role signatures
kLabelChangeEvent / kLabelChangeEventExpanded as applicable, or equivalent
independent patterns. Its early body must contain exactly two calls to an
authenticated info-logger shape (kLabelRefreshLog), ordered and discarded as
above. Preserve the closure/argument setup and overwrite evidence; merely
finding any two logger calls is insufficient.

### Original getter and revision-token path

~~~asm
00b47210: stp   x29, x30, [x15, #-0x10]!
00b47214: mov   x29, x15
00b47218: sub   x15, x15, #8
00b4721c: ldr   x0, [x26, #0x78]
00b47220: ldr   x0, [x0, #0x27c8]
00b47224: cmp   w0, w22
00b47228: b.ne  0xb4725c       // authenticated cached path; no call before hook
// Null-cache fallback resolves through Inst.find. The event readiness checks
// prevent this path: do not carry x9 through arbitrary resolver/native calls.
00b4725c: ldur  w1, [x0, #0x2b]
00b47260: add   x1, x1, x28, lsl #32
00b47264: bl    0xf64c1c       // replace BL target, preserve native x30
00b47268: mov   x15, x29
00b4726c: ldp   x29, x30, [x15], #0x10
00b47270: ret
~~~

Match the getter's complete frame/cache/fallback/field-load/epilogue shape
(kLabelConfig is the existing 8593 role pattern). Follow its value BL to the
generic value role. Derive fields/table slots, not just masked branch targets.
The native field-table ABI at x26+0x78 and late-sentinel pool entry x27+0x40
must agree with these authenticated functions.

Select a refresh body with its own native frame and subject root, whose first
native call targets **that same generic Rx.value** and whose second call is
authenticated GetStream.add:

~~~asm
00b464b0: stp   x29, x30, [x15, #-0x10]!
00b464b4: mov   x29, x15
00b464b8: sub   x15, x15, #8
00b464bc: ldur  w0, [x1, #0xb]
00b464c0: add   x0, x0, x28, lsl #32
00b464c4: stur  x0, [x29, #-8] // native subject root
00b464c8: bl    0xf64c1c
00b464cc: ldur  x1, [x29, #-8]
00b464d0: mov   x2, x0
00b464d4: bl    0x8b2904
00b464d8: mov   x0, x22       // null; safe ONLY for discarded event result
00b464dc: mov   x15, x29
00b464e0: ldp   x29, x30, [x15], #0x10
00b464e4: ret
~~~

Reject RxList.refresh at 0x8b28cc: its first call targets RxList.value at
0xe6325c and uses the list specialization, not this GridConfig Rx.

### Parent rebuild proof

The outer Hotseat Obx builder at 0x117c738 directly calls currentConfig at
0x117cba8. Generic Rx.value registers the active native observer through
NotifyManager.addListener. Generic refresh re-emits the current value through
GetStream.add, whose subscription callbacks reach mounted ObxState._updateTree
and native State.setState.

The outer builder constructs a fresh LayoutBuilder (allocation at 0x117cc14),
whose inner closure at 0x117d11c calls _buildBackground at 0x117d298. Its native
background-suppression gate remains authoritative. This is a parent rebuild
dependency, unlike a title-only GetBuilder ID notification.

## Validation boundary and suggested parent checks

The assembly has not been assembled, installed or exercised on a device in this
work. The production resolver has a separate host fixture/rejection runner:

~~~sh
clang++ -std=c++17 -Wall -Wextra -Wpedantic -Werror -O2 \
  -fsanitize=address,undefined -fno-omit-frame-pointer -Iapp/src/main/cpp \
  app/src/test/cpp/dock_glass_refresh_profile_test.cpp \
  app/src/main/cpp/dock_glass_refresh.cpp \
  app/src/main/cpp/launcher_label_profile.cpp \
  app/src/main/cpp/icon_highlight_profile.cpp \
  -o /tmp/keihome-dock-refresh-profile-test
/tmp/keihome-dock-refresh-profile-test \
  .artifacts/inputs/launchers/{7722,8593,8602,9209,9226}/libapp.so
~~~

It emits one PROFILE JSON line per fixture for independent symbol-oracle checks.
The five saved fixtures above passed 495 host fixture/rejection checks under
ASan/UBSan. Their exported second logger, getter call, generic refresh, stream
add and observer slot also matched the independent saved debugdata oracle.
Shared GridConfig refresh can notify other grid consumers too. Native assembly
execution, Dart GC, rendering and device acceptance remain separate checks.

The integrated payload subsequently passed 470 linked getter/refresh/stream
execution cases. Source fixtures, actual AVD results, refresh limitations, and
the other renderer/mode tests are recorded in [Dock glass](dock-glass.md).

Parent host checks should cover unchanged/uninstalled event fallbacks;
enable and disable revisions;
null/sentinel/Smi cache, Rx, value and subject; active observer deferral;
ordinary getter return preservation; caller-LR and Rx-owner mismatches; revision
change between event and interceptor; reentrant suppression; a newer revision
remaining pending; both original return PCs; config layout and relocation; BL
reach; and prepare-before-label/install-after-label patch ownership.

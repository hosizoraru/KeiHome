// SPDX-License-Identifier: Apache-2.0
// Include with #include from the parent's preprocessed AArch64 .S template,
// before the parent's final end label. Keep this entire block together when
// copying it: local ADRs address the embedded config, not the source template.
// No section switch, managed frame, C/JNI call, stack write or retained Dart
// reference. Native calls keep the ORIGINAL mapped caller/return PCs.
#include "dock_glass_refresh.h"

    .balign 8
    .global kh_dock_refresh_begin, kh_dock_refresh_event, kh_dock_refresh_value
    .global kh_dock_refresh_config, kh_dock_refresh_end
    .hidden kh_dock_refresh_begin, kh_dock_refresh_event, kh_dock_refresh_value
    .hidden kh_dock_refresh_config, kh_dock_refresh_end
kh_dock_refresh_begin:

// x17 = immutable config; preserve x0-x9 (especially receiver x1 and the
// revision token x9). Return x10 = the current cached controller's own Rx.
// x11-x13 are scratch. Authenticated native getters use compressed object
// fields, full-width field-table entries, and pool +0x40 for the late sentinel.
    .macro kh_dock_refresh_ready fail
    ldr x10, [x26, #0x78]
    ldr x11, [x17, #KH_DOCK_REFRESH_GRID_SLOT]
    ldr x10, [x10, x11]
    cmp w10, w22
    b.eq \fail
    tbz x10, #0, \fail
    ldr x12, [x27, #0x40]
    cmp w10, w12
    b.eq \fail
    ldr x11, [x17, #KH_DOCK_REFRESH_CONFIG_RX_FIELD]
    ldr w10, [x10, x11]
    add x10, x10, x28, lsl #32
    cmp w10, w22
    b.eq \fail
    tbz x10, #0, \fail
    cmp w10, w12
    b.eq \fail
    ldr x11, [x17, #KH_DOCK_REFRESH_RX_SUBJECT_FIELD]
    ldr w13, [x10, x11]
    add x13, x13, x28, lsl #32
    cmp w13, w22
    b.eq \fail
    tbz x13, #0, \fail
    cmp w13, w12
    b.eq \fail
    ldr x11, [x17, #KH_DOCK_REFRESH_RX_VALUE_FIELD]
    ldr w13, [x10, x11]
    add x13, x13, x28, lsl #32
    cmp w13, w22
    b.eq \fail
    tbz x13, #0, \fail
    cmp w13, w12
    b.eq \fail
    // An active Rx observer means a reactive builder is running. Leave the
    // revision pending rather than notifying Obx while collecting dependencies.
    ldr x13, [x26, #0x78]
    ldr x11, [x17, #KH_DOCK_REFRESH_OBSERVER_SLOT]
    ldr x13, [x13, x11]
    cmp w13, w22
    b.ne \fail
    .endm

// Called ONLY by the second discarded-result logger BL in processItemsChanged.
// x30 remains its original return PC. x9 carries a primitive revision across
// the getter's authenticated cached fast path, which must neither modify x9
// nor call/allocate before its Rx.value BL. There is no Dart safepoint here.
kh_dock_refresh_event:
    hint #38
    adr x17, kh_dock_refresh_config
    ldr x16, [x17, #KH_DOCK_REFRESH_INSTALLED]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Lkh_dock_refresh_logger
    ldr x16, [x17, #KH_DOCK_REFRESH_REVISION]
    ldar x9, [x16]
    ldr x16, [x17, #KH_DOCK_REFRESH_NOTIFIED]
    ldar x16, [x16]
    cmp x9, x16
    b.eq .Lkh_dock_refresh_logger
    kh_dock_refresh_ready .Lkh_dock_refresh_logger
    ldr x16, [x17, #KH_DOCK_REFRESH_GETTER]
    br x16
.Lkh_dock_refresh_logger:
    adr x17, kh_dock_refresh_config
    ldr x16, [x17, #KH_DOCK_REFRESH_LOGGER]
    br x16

// Called by the getter's original Rx.value BL: x1 = native GridConfig Rx,
// x30 = ORIGINAL getter continuation, [fp+8] = getter's original caller LR.
// Ordinary getter calls always tail to the untouched generic Rx.value entry.
kh_dock_refresh_value:
    hint #38
    adr x17, kh_dock_refresh_config
    ldr x16, [x29, #8]
    ldr x10, [x17, #KH_DOCK_REFRESH_EVENT_RETURN]
    cmp x16, x10
    b.ne .Lkh_dock_refresh_original_value
    ldr x16, [x17, #KH_DOCK_REFRESH_INSTALLED]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Lkh_dock_refresh_original_value
    kh_dock_refresh_ready .Lkh_dock_refresh_original_value
    cmp x10, x1                  // same cached native owner, not just any Rx
    b.ne .Lkh_dock_refresh_original_value
    ldr x16, [x17, #KH_DOCK_REFRESH_REVISION]
    ldar x10, [x16]
    cmp x10, x9                 // consume only the event's acquired revision
    b.ne .Lkh_dock_refresh_original_value
    ldr x16, [x17, #KH_DOCK_REFRESH_NOTIFIED]
    ldar x10, [x16]
    cmp x10, x9
    b.eq .Lkh_dock_refresh_original_value
    // UI isolate is the sole notified writer. Store BEFORE native notification
    // to suppress reentrant duplicate events. A later worker publication stays
    // pending because this stores x9, never a newly reloaded revision.
    stlr x9, [x16]
    ldr x16, [x17, #KH_DOCK_REFRESH_REFRESH]
    br x16
.Lkh_dock_refresh_original_value:
    adr x17, kh_dock_refresh_config
    ldr x16, [x17, #KH_DOCK_REFRESH_VALUE]
    br x16

    .purgem kh_dock_refresh_ready
    .balign 8
kh_dock_refresh_config:
    .zero KH_DOCK_REFRESH_CONFIG_SIZE
kh_dock_refresh_end:

// SPDX-License-Identifier: Apache-2.0
// Only primitive policy loads between the native Rx read and preset
// selection. Keep subscription collection, filter caches and animation native.
#include "launcher_glass_material_bridge.h"
    .section .rodata.launcher_glass_material,"a",%progbits
    .balign 8
    .global kh_glass_material_begin, kh_glass_material_choose
    .global kh_glass_material_original, kh_glass_material_taken, kh_glass_material_fall
    .global kh_glass_material_light, kh_glass_material_dark, kh_glass_material_refresh
    .global kh_glass_material_event_original, kh_glass_material_event_resume
    .global kh_glass_material_config, kh_glass_material_end
    .hidden kh_glass_material_begin, kh_glass_material_choose
    .hidden kh_glass_material_original, kh_glass_material_taken, kh_glass_material_fall
    .hidden kh_glass_material_light, kh_glass_material_dark, kh_glass_material_refresh
    .hidden kh_glass_material_event_original, kh_glass_material_event_resume
    .hidden kh_glass_material_config, kh_glass_material_end
kh_glass_material_begin:
kh_glass_material_choose:
    hint #38
    adr x17, kh_glass_material_config
    ldr x16, [x17, #KH_GLASS_MATERIAL_INSTALLED]
    ldar w16, [x16]
    cbz w16, kh_glass_material_original
    ldr x16, [x17, #KH_GLASS_MATERIAL_CONTROL]
    ldar x16, [x16]
    and w16, w16, #3
    cbz w16, kh_glass_material_original
    tbz w16, #1, kh_glass_material_light
    tbnz w16, #0, kh_glass_material_original
kh_glass_material_dark:
    b .
kh_glass_material_light:
    b .
kh_glass_material_original:
    // Relocated original conditional, targeting the local taken stub.
    nop
kh_glass_material_fall:
    b .
kh_glass_material_taken:
    b .

// Original FIRST discarded logger return PC. Its native frame/stack map stays
// valid across Rx.refresh; on return replay the displaced instruction once.
kh_glass_material_refresh:
    hint #38
    adr x17, kh_glass_material_config
    ldr x16, [x17, #KH_GLASS_MATERIAL_INSTALLED]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Lmaterial_event_original
    ldr x16, [x17, #KH_GLASS_MATERIAL_REVISION]
    ldar x9, [x16]
    ldr x16, [x17, #KH_GLASS_MATERIAL_NOTIFIED]
    ldar x16, [x16]
    cmp x9, x16
    b.eq .Lmaterial_event_original
    ldr x11, [x17, #KH_GLASS_MATERIAL_CONTROLLER_SLOT]
    ldr x12, [x17, #KH_GLASS_MATERIAL_RX_FIELD]
    ldr x10, [x26, #0x78]
    ldr x16, [x17, #KH_GLASS_MATERIAL_OBSERVER_SLOT]
    ldr x16, [x10, x16]
    cmp w16, w22
    b.ne .Lmaterial_unready
    ldr x10, [x10, x11]
    cmp w10, w22
    b.eq .Lmaterial_unready
    tbz x10, #0, .Lmaterial_unready
    ldr x16, [x27, #0x40]
    cmp w10, w16
    b.eq .Lmaterial_unready
    ldr w10, [x10, x12]
    add x10, x10, x28, lsl #32
    cmp w10, w22
    b.eq .Lmaterial_unready
    tbz x10, #0, .Lmaterial_unready
    cmp w10, w16
    b.eq .Lmaterial_unready
    ldr x11, [x17, #KH_GLASS_MATERIAL_SUBJECT_FIELD]
    ldr w13, [x10, x11]
    add x13, x13, x28, lsl #32
    cmp w13, w22
    b.eq .Lmaterial_unready
    tbz x13, #0, .Lmaterial_unready
    cmp w13, w16
    b.eq .Lmaterial_unready
    // Native close clears listeners and busy; only notify a live, idle subject.
    ldr x11, [x17, #KH_GLASS_MATERIAL_LISTENERS_FIELD]
    ldr w11, [x13, x11]
    add x11, x11, x28, lsl #32
    cmp w11, w22
    b.eq .Lmaterial_unready
    tbz x11, #0, .Lmaterial_unready
    cmp w11, w16
    b.eq .Lmaterial_unready
    ldr x16, [x17, #KH_GLASS_MATERIAL_LENGTH_FIELD]
    ldr w16, [x11, x16]
    tbnz w16, #0, .Lmaterial_unready
    cmp w16, #0
    b.le .Lmaterial_unready
    ldr x11, [x17, #KH_GLASS_MATERIAL_BUSY_FIELD]
    ldr w11, [x13, x11]
    add x11, x11, x28, lsl #32
    ldr x16, [x17, #KH_GLASS_MATERIAL_FALSE_OFFSET]
    add x16, x22, x16
    cmp w11, w16
    b.ne .Lmaterial_unready
    ldr x11, [x17, #KH_GLASS_MATERIAL_VALUE_FIELD]
    ldr w13, [x10, x11]
    add x13, x13, x28, lsl #32
    ldr x11, [x17, #KH_GLASS_MATERIAL_TRUE_OFFSET]
    add x11, x22, x11
    cmp w13, w11
    b.eq .Lmaterial_value_ready
    ldr x11, [x17, #KH_GLASS_MATERIAL_FALSE_OFFSET]
    add x11, x22, x11
    cmp w13, w11
    b.ne .Lmaterial_unready
.Lmaterial_value_ready:
    ldr x16, [x17, #KH_GLASS_MATERIAL_NOTIFIED]
    stlr x9, [x16]
    mov x1, x10
    ldr x30, [x17, #KH_GLASS_MATERIAL_EVENT_RETURN]
    ldr x16, [x17, #KH_GLASS_MATERIAL_REFRESH]
    br x16
.Lmaterial_unready:
.Lmaterial_event_original:
kh_glass_material_event_original:
    nop
kh_glass_material_event_resume:
    b .
    .balign 8
kh_glass_material_config:
    .zero KH_GLASS_MATERIAL_CONFIG_SIZE
kh_glass_material_end:
    .section .note.GNU-stack,"",%progbits

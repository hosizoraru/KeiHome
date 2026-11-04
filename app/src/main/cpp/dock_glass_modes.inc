// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_modes.h"
    .balign 8
    .global kh_dock_mode_post,kh_dock_mode_replay,kh_dock_mode_config,kh_dock_mode_end
    .hidden kh_dock_mode_post,kh_dock_mode_replay,kh_dock_mode_config,kh_dock_mode_end
// Single B replaces the first instruction AFTER the untouched native sync BL.
// Every authenticated sync return sets x0=x22 (canonical null). computeMode
// always returns an enum; native curve eligibility returns canonical Bool.
// These make x0 a three-phase discriminator without saved
// state, a stack write, a new frame, or a retained Dart reference.
kh_dock_mode_post:
    hint #38
    adr x17,kh_dock_mode_config
    ldr x16,[x17,#KH_DOCK_MODE_CALLER]
    cmp x30,x16
    b.ne .Ldock_mode_replay
    cmp x0,x22
    b.ne .Ldock_mode_result
    ldr x16,[x17,#KH_DOCK_MODE_ENABLED]
    ldar w16,[x16]
    cmp w16,#1
    b.ne .Ldock_mode_replay
    ldr x16,[x26,#0x78]
    ldr x14,[x17,#KH_DOCK_MODE_RX_SLOT]
    ldr x16,[x16,x14]
    cmp w16,w22
    b.eq .Ldock_mode_replay
    tbz x16,#0,.Ldock_mode_replay
    ldr x14,[x27,#0x40]
    cmp w16,w14
    b.eq .Ldock_mode_replay
    // BR leaves x30 at the ORIGINAL post-sync PC, which owns a real native
    // caller stack map. computeMode ignores the incoming receiver and creates
    // its own native frame. Its untouched RET reenters this patched PC.
    ldr x16,[x17,#KH_DOCK_MODE_COMPUTE]
    br x16
.Ldock_mode_result:
    add w16,w22,#0x20
    cmp w0,w16
    b.eq .Ldock_curve_true
    add w16,w22,#0x30
    cmp w0,w16
    b.eq .Ldock_curve_false
    // No managed call here. Reject a non-object or out-of-range raw enum index
    // and never resample on this phase, including if enabled changed meanwhile.
    tbz x0,#0,.Ldock_mode_replay
    ldur x14,[x0,#7]
    cmp x14,#3
    b.hi .Ldock_mode_replay
    ldr x16,[x17,#KH_DOCK_MODE_VALUE]
    stlr w14,[x16]
    // The original native caller owns this exact return PC and its stack map.
    // No object from computeMode is needed past the primitive publication.
    // Native eligibility includes its own initialization and device policy.
    ldr x16,[x17,#KH_DOCK_MODE_CURVE_GETTER]
    cbz x16,.Ldock_mode_replay
    br x16
.Ldock_curve_true:
    mov w14,#1
    b .Ldock_curve_publish
.Ldock_curve_false:
    mov w14,#0
.Ldock_curve_publish:
    ldr x16,[x17,#KH_DOCK_MODE_CURVE_VALUE]
    stlr w14,[x16]
.Ldock_mode_replay:
kh_dock_mode_replay:
    // Installer copies the authenticated original four bytes here. This
    // default is the native LDUR x2,[x29,#-0x18] on all five saved fixtures.
    .inst 0xf85e83a2
    ldr x16,[x17,#KH_DOCK_MODE_RESUME]
    br x16
    .balign 8
kh_dock_mode_config:
    .zero KH_DOCK_MODE_CONFIG_SIZE
kh_dock_mode_end:

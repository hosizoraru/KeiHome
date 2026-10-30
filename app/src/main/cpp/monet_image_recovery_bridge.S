// SPDX-License-Identifier: Apache-2.0
#include "monet_image_recovery.h"
#if !defined(__aarch64__)
#error "Image ownership recovery requires the reviewed AArch64 Dart ABI"
#endif
    .text
    .hidden kh_image_recovery_config
    .hidden kh_image_recovery_counts
    .hidden kh_image_recovery_original
    .macro GLOBAL reg, symbol
    adrp \reg, \symbol
    add \reg, \reg, :lo12:\symbol
    .endm

// A currently rooted full heap pointer must be tagged, aligned, non-null and
// belong to this isolate's compressed-pointer heap. This is not an arbitrary
// pointer validator: only the authenticated call's arguments/roots are read.
    .macro HEAP reg, wreg
    cmp \wreg, w22
    b.eq .Ldone
    and w10, \wreg, #7
    cmp w10, #1
    b.ne .Ldone
    lsr x10, \reg, #32
    cmp w10, w28
    b.ne .Ldone
    .endm
    .macro CID reg, offset
    ldur w10, [\reg, #-1]
    ubfx w10, w10, #12, #20
    ldr w11, [x0, #\offset]
    cmp w10, w11
    b.ne .Ldone
    .endm

    .p2align 2
    .global kh_image_recovery_bridge
    .hidden kh_image_recovery_bridge
    .type kh_image_recovery_bridge, %function
kh_image_recovery_bridge:
    bti c
    // No native CSP use, managed frame, calls, safepoints, SIMD, or heap writes.
    // FP still denotes ORIGINAL RawImage.updateRenderObject, not a bridge frame.
    // 48 bytes, no deeper than the original clone's 16+32-byte frame.
    sub x15, x15, #48
    stp x9, x10, [x15]
    stp x11, x12, [x15, #16]
    mrs x10, nzcv
    str x10, [x15, #32]
    GLOBAL x0, kh_image_recovery_config
    ldr w10, [x0, #KH_IR_ENABLED]
    cbz w10, .Ldone
    ldr x10, [x0, #KH_IR_RETURN]
    cmp x30, x10
    b.ne .Ldone
    // The reviewed caller has no outgoing stack arguments at this BL. Check
    // its exact FP/Dart-SP relation before touching the original rooted spill.
    tst x29, #7
    b.ne .Ldone
    ldr w10, [x0, #KH_IR_FRAME_SIZE]
    sub x10, x29, x10
    add x11, x15, #48
    cmp x10, x11
    b.ne .Ldone
    HEAP x1, w1
    CID x1, KH_IR_IMAGE_CID
    ldr w10, [x0, #KH_IR_DISPOSED]
    ldr w11, [x1, x10]
    add w10, w22, #0x20 // exact canonical true, authenticated by dispose
    cmp w11, w10
    b.ne .Ldone
    ldrsw x10, [x0, #KH_IR_SPILL]
    ldr x12, [x29, x10]
    HEAP x12, w12
    CID x12, KH_IR_RENDER_CID
    ldr w10, [x0, #KH_IR_RENDER_IMAGE]
    ldr w12, [x12, x10]
    add x12, x12, x28, lsl #32
    HEAP x12, w12
    CID x12, KH_IR_IMAGE_CID
    ldr w10, [x0, #KH_IR_DISPOSED]
    ldr w11, [x12, x10]
    add w10, w22, #0x30 // exact canonical false, authenticated by clone
    cmp w11, w10
    b.ne .Ldone
    ldr w10, [x0, #KH_IR_BACKING]
    ldr w11, [x1, x10]
    ldr w9, [x12, x10]
    cmp w11, w9 // identical compressed backing references in the SAME heap
    b.ne .Ldone
    cmp w11, w22
    b.eq .Ldone
    and w10, w11, #7
    cmp w10, #1
    b.ne .Ldone

    // A valid owner remains rooted through the ORIGINAL renderer spill. Only
    // redirect the clone receiver; original clone/setter retain all ownership.
    mov x1, x12
    // Saturating lower-bound counter. One exclusive attempt, never a spin/wait.
    // Failed publication does NOT cancel recovery; sticky uncounted reports it.
    GLOBAL x9, kh_image_recovery_counts
    ldaxr w10, [x9]
    mov w11, #KH_IMAGE_RECOVERY_LIMIT
    cmp w10, w11
    b.hs .Lsaturated
    add w10, w10, #1
    stlxr w11, w10, [x9]
    cbz w11, .Ldone
    add x9, x9, #4
    mov w10, #1
    stlr w10, [x9]
    b .Ldone
.Lsaturated:
    clrex
.Ldone:
    ldr x10, [x15, #32]
    msr nzcv, x10
    ldp x11, x12, [x15, #16]
    ldp x9, x10, [x15]
    add x15, x15, #48
    // Resolver proves clone unconditionally kills incoming x0 before reading
    // it. All x2-x30, SIMD/status/CSP and LR survive, x1 changes only on recovery.
    GLOBAL x0, kh_image_recovery_original
    ldr x0, [x0]
    br x0
    .size kh_image_recovery_bridge, .-kh_image_recovery_bridge
    .section .note.GNU-stack,"",%progbits

// SPDX-License-Identifier: Apache-2.0
#include "theme_night_profile.h"
#if !defined(__aarch64__)
#error "Theme night bridge requires the verified AArch64 Dart contract"
#endif
    .text
    .hidden kh_theme_night_config
    .hidden kh_theme_night_original
    .macro GLOBAL reg, symbol
    adrp \reg, \symbol
    add \reg, \reg, :lo12:\symbol
    .endm

// Only live roots from this reviewed call/PP are inspected. These checks are
// not a general arbitrary-pointer validator.
    .macro HEAP reg, wreg
    cmp \wreg, w22
    b.eq .Ldone
    and w10, \wreg, #15
    cmp w10, #1
    b.ne .Ldone
    lsr x10, \reg, #32
    cmp w10, w28
    b.ne .Ldone
    .endm
    .macro STRING pool, length
    ldr w10, [x0, #\pool]
    ldr x13, [x27, x10]
    HEAP x13, w13
    ldur w10, [x13, #-1]
    ubfx w10, w10, #12, #20
    ldr w11, [x0, #KH_TN_STRING_CID]
    cmp w10, w11
    b.ne .Ldone
    ldr w10, [x0, #KH_TN_STRING_LENGTH]
    ldr x11, [x13, x10]
    cmp x11, #(\length * 2)
    b.ne .Ldone
    ldr w10, [x0, #KH_TN_STRING_DATA]
    add x13, x13, x10
    .endm

    .p2align 2
    .global kh_theme_night_bitmap_bridge
    .hidden kh_theme_night_bitmap_bridge
    .type kh_theme_night_bitmap_bridge, %function
kh_theme_night_bitmap_bridge:
    bti c
    // Call-free leaf: no native stack, allocation, safepoint, loop, Dart call,
    // syscall, retained reference or SIMD use. Restore the full original DSP.
    sub x15, x15, #80
    stp x9, x10, [x15]
    stp x11, x12, [x15, #16]
    stp x13, x14, [x15, #32]
    stp x16, x17, [x15, #48]
    mrs x9, nzcv
    str x9, [x15, #64]
    GLOBAL x0, kh_theme_night_config
    ldar w14, [x0]
    cbz w14, .Ldone
    cmp w14, #2
    b.hi .Ldone

    ldr w10, [x0, #KH_TN_TABLE]
    ldr x9, [x26, x10]
    cbz x9, .Ldone
    ldr w10, [x0, #KH_TN_ICONS]
    ldr x10, [x9, x10]
    cmp x10, x1
    b.ne .Ldone // No framework, dynamicicons or default-child mutation.
    HEAP x1, w1
    ldur w10, [x1, #-1]
    ubfx w10, w10, #12, #20
    ldr w11, [x0, #KH_TN_CID]
    cmp w10, w11
    b.ne .Ldone

    // Verify the constants of THIS live PP, not a historical heap capture.
    STRING KH_TN_ICONS_POOL, 5
    ldr w10, [x13]
    mov w11, #0x6369
    movk w11, #0x6e6f, lsl #16 // "icon"
    cmp w10, w11
    b.ne .Ldone
    ldrb w10, [x13, #4]
    cmp w10, #0x73
    b.ne .Ldone
    // The reader prepends either "" or "nightmode/"; res/drawable-* is
    // appended later by the OEM reader. No drawable path belongs in this slot.
    STRING KH_TN_NORMAL_POOL, 0
    STRING KH_TN_NIGHT_POOL, 10
    ldr x10, [x13]
    mov x11, #0x696e
    movk x11, #0x6867, lsl #16
    movk x11, #0x6d74, lsl #32
    movk x11, #0x646f, lsl #48 // "nightmod"
    cmp x10, x11
    b.ne .Ldone
    ldrh w10, [x13, #8]
    mov w11, #0x2f65
    cmp w10, w11
    b.ne .Ldone

    ldr w10, [x0, #KH_TN_FALSE]
    add w16, w22, w10
    ldr w10, [x0, #KH_TN_TRUE]
    add w17, w22, w10
    ldr w10, [x0, #KH_TN_NIGHT]
    ldr x12, [x9, x10]
    // Validate the full canonical boolean, including its heap base.
    lsr x10, x12, #32
    cmp w10, w28
    b.ne .Ldone
    cmp w12, w16
    b.eq .Lglobal_valid
    cmp w12, w17
    b.ne .Ldone
.Lglobal_valid:
    ldr w10, [x0, #KH_TN_FIELD]
    ldr w11, [x1, x10]
    cmp w11, w16
    b.eq .Lfield_valid
    cmp w11, w17
    b.ne .Ldone
.Lfield_valid:
    cmp w14, #2
    csel w12, w16, w12, eq
    cmp w11, w12
    b.eq .Ldone
    // The verified OEM setter uses this same barrier-free compressed boolean
    // store. Both choices are canonical VM booleans, never new heap objects.
    str w12, [x1, x10]
.Ldone:
    ldr x9, [x15, #64]
    msr nzcv, x9
    ldp x16, x17, [x15, #48]
    ldp x13, x14, [x15, #32]
    ldp x11, x12, [x15, #16]
    ldp x9, x10, [x15]
    add x15, x15, #80
    // Incoming x0 is unconditionally killed before any read by the verified
    // original reader. All other GPRs, FP/LR, SIMD, status and CSP survive.
    GLOBAL x0, kh_theme_night_original
    ldr x0, [x0]
    br x0
    .size kh_theme_night_bitmap_bridge, .-kh_theme_night_bitmap_bridge
    .section .note.GNU-stack,"",%progbits

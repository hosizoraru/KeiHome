.text
.p2align 2

.macro save_dart_call_context
    sub sp, sp, #0xb0
    stp x0, x1, [sp, #0x00]
    stp x2, x3, [sp, #0x10]
    stp x4, x5, [sp, #0x20]
    stp x6, x7, [sp, #0x30]
    stp x8, x9, [sp, #0x40]
    stp x10, x11, [sp, #0x50]
    stp x12, x13, [sp, #0x60]
    stp x14, x15, [sp, #0x70]
    stp x16, x17, [sp, #0x80]
    mrs x16, nzcv
    str x16, [sp, #0x90]
    stp x29, x30, [sp, #0xa0]
.endm

.macro restore_dart_call_context
    ldr x16, [sp, #0x90]
    msr nzcv, x16
    ldp x0, x1, [sp, #0x00]
    ldp x2, x3, [sp, #0x10]
    ldp x4, x5, [sp, #0x20]
    ldp x6, x7, [sp, #0x30]
    ldp x8, x9, [sp, #0x40]
    ldp x10, x11, [sp, #0x50]
    ldp x12, x13, [sp, #0x60]
    ldp x14, x15, [sp, #0x70]
    ldp x16, x17, [sp, #0x80]
    ldr x29, [sp, #0xa0]
.endm

.global KeiHomeApplyNightModeHook
.hidden KeiHomeApplyNightModeHook
.type KeiHomeApplyNightModeHook, %function
KeiHomeApplyNightModeHook:
    bti c

    // Dart AOT uses x15 as its frame stack pointer. C++ callbacks may clobber
    // every caller-saved register, so preserve the exact Dart call context.
    save_dart_call_context
    mov x3, x22
    bl KeiHomeObserveApplyNightMode
    restore_dart_call_context

    adrp x16, g_kei_home_apply_night_original
    add x16, x16, :lo12:g_kei_home_apply_night_original
    ldr x16, [x16]
    cbz x16, .Lapply_night_missing_original
    blr x16
    ldp x29, x30, [sp, #0xa0]
    add sp, sp, #0xb0
    ret

.Lapply_night_missing_original:
    mov x0, x22
    ldp x29, x30, [sp, #0xa0]
    add sp, sp, #0xb0
    ret
.size KeiHomeApplyNightModeHook, .-KeiHomeApplyNightModeHook

.section .note.GNU-stack, "", %progbits

// Host fixture wrapper only. Calls the real bridge with a synthetic Dart ABI.
#ifdef __APPLE__
#define KH_SYMBOL(x) _##x
#else
#define KH_SYMBOL(x) x
#endif
.text
.p2align 2
.global KH_SYMBOL(kh_selection_native_case)
KH_SYMBOL(kh_selection_native_case):
    stp x19, x20, [sp, #-96]!
    stp x21, x22, [sp, #16]
    stp x23, x24, [sp, #32]
    stp x25, x26, [sp, #48]
    stp x27, x28, [sp, #64]
    stp x29, x30, [sp, #80]
    mov x19, x0
    ldr x22, [x19, #144]
    ldr x27, [x19, #152]
    ldr x20, [x19, #160]
    msr nzcv, x20
    ldp x0, x1, [x19, #0]
    ldp x2, x3, [x19, #16]
    ldp x4, x5, [x19, #32]
    ldp x6, x7, [x19, #48]
    ldp x8, x9, [x19, #64]
    ldp x10, x11, [x19, #80]
    ldp x12, x13, [x19, #96]
    ldp x14, x15, [x19, #112]
    ldp x16, x17, [x19, #128]
    bl KH_SYMBOL(kh_monet_selection_bridge)
    stp x0, x1, [x19, #168]
    stp x2, x3, [x19, #184]
    stp x4, x5, [x19, #200]
    stp x6, x7, [x19, #216]
    stp x8, x9, [x19, #232]
    stp x10, x11, [x19, #248]
    stp x12, x13, [x19, #264]
    stp x14, x15, [x19, #280]
    stp x16, x17, [x19, #296]
    mrs x20, nzcv
    str x20, [x19, #312]
    ldp x21, x22, [sp, #16]
    ldp x23, x24, [sp, #32]
    ldp x25, x26, [sp, #48]
    ldp x27, x28, [sp, #64]
    ldp x29, x30, [sp, #80]
    ldp x19, x20, [sp], #96
    ret
.p2align 2
.global KH_SYMBOL(kh_selection_native_original)
KH_SYMBOL(kh_selection_native_original):
    mov x16, #0x3344
    mov x0, #0x4a11
    ret
#ifndef __APPLE__
.section .note.GNU-stack,"",%progbits
#endif

// SPDX-License-Identifier: Apache-2.0
// Copied into a private RX mapping before target publication. No relocations.
// Dart ABI tail, never callable from C. x1/x28 are the ORIGINAL live receiver
// and heap base. x2 is changed exactly by the replayed native instructions;
// only x0 is extra scratch. No heap writes, calls, flag changes or stack spill.
    .section .rodata.icon_highlight_bridge,"a",%progbits
    .balign 8
    .global kh_icon_highlight_template
    .hidden kh_icon_highlight_template
    .type kh_icon_highlight_template, %object
kh_icon_highlight_template:
    hint #38                       // BTI jc for the exact BR x0 entry
    nop                            // verified original ldur w2,[x1,#field]
    nop                            // verified original add x2,x2,x28,lsl #32
    ldr x0, .Lpolicy_address
    ldar w0, [x0]
    sub w0, w0, #1                 // deliberately does not set NZCV
    cbnz w0, .Lnative_result
    add x0, x22, #0x20
    b .Lfinish
.Lnative_result:
    mov x0, x2
.Lfinish:
    mov x15, x29
    ldp x29, x30, [x15], #16
    ret
    nop
.Lpolicy_address:
    .quad 0
    .size kh_icon_highlight_template, .-kh_icon_highlight_template
    .section .note.GNU-stack,"",%progbits

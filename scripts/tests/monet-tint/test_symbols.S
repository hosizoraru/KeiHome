// SPDX-License-Identifier: Apache-2.0
// Authored trampoline stops and primitive globals for the emulator.
    .text
    .global tint_original_stop, equals_original_stop
tint_original_stop:
    nop
    ret
equals_original_stop:
    nop
    ret
    .data
    .balign 8
    .global kh_monet_policy, kh_monet_readers, kh_tint_original
    .global kh_filter_equals_original, kh_tint_caller
    .global kh_render_equals_caller, kh_layer_equals_caller
kh_monet_policy: .quad 0
kh_monet_readers: .word 0
    .balign 8
kh_tint_original: .quad tint_original_stop
kh_filter_equals_original: .quad equals_original_stop
kh_tint_caller: .quad 0
kh_render_equals_caller: .quad 0
kh_layer_equals_caller: .quad 0
    .section .note.GNU-stack,"",%progbits

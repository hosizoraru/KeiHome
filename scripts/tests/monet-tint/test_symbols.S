// SPDX-License-Identifier: Apache-2.0
// Authored trampoline stops and primitive globals for the emulator.
    .text
    .global tint_original_stop, equals_original_stop, enabled_original_stop
tint_original_stop:
    nop
    ret
equals_original_stop:
    nop
    ret
enabled_original_stop:
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
    .global kh_monet_enabled_equals_original, kh_monet_enabled_equals_caller
    .global kh_monet_settings_setter_caller, kh_monet_selection_revision, kh_monet_selection_notified
    .global kh_monet_object_equals_entry
kh_monet_enabled_equals_original: .quad enabled_original_stop
kh_monet_enabled_equals_caller: .quad 0
kh_monet_settings_setter_caller: .quad 0
kh_monet_selection_revision: .quad 0
kh_monet_selection_notified: .quad 0
kh_monet_object_equals_entry: .quad 0
    .section .note.GNU-stack,"",%progbits

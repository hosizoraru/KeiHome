.data
.p2align 3
.global kh_monet_policy
kh_monet_policy: .quad 0
.global kh_monet_readers
kh_monet_readers: .word 0
.p2align 3
.global kh_selection_original
kh_selection_original: .quad 0x400000
.global kh_selection_factory_entry
kh_selection_factory_entry: .quad 0x19e8678
.global kh_selection_update_callback_entry
kh_selection_update_callback_entry: .quad 0xebf8dc
.global kh_selection_factory_pool_offset
kh_selection_factory_pool_offset: .quad 0x404f8
.global kh_selection_update_callback_pool_offset
kh_selection_update_callback_pool_offset: .quad 0x404e8
.global kh_selection_profile_ready
kh_selection_profile_ready: .word 1
.section .note.GNU-stack,"",%progbits

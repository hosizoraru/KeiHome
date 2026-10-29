// SPDX-License-Identifier: Apache-2.0
// Dart leaf entry veto. No C/Dart calls, safepoints, allocation, syscalls,
// retained Dart references, or cache writes. Unknown inputs run original.
// selection_profile.h specifies activation, exact policy layout and RCU order.
#include "selection_profile.h"

#if !defined(__aarch64__)
#error "The reviewed Dart selection ABI is AArch64 only"
#endif

    .text
    .p2align 2
    .global kh_monet_selection_bridge
    .hidden kh_monet_selection_bridge
    .type kh_monet_selection_bridge, %function
    .extern kh_monet_policy
    .extern kh_monet_readers
    .hidden kh_monet_policy
    .hidden kh_monet_readers
    .hidden kh_selection_original
    .hidden kh_selection_profile_ready
    .hidden kh_selection_factory_entry
    .hidden kh_selection_update_callback_entry
    .hidden kh_selection_factory_pool_offset
    .hidden kh_selection_update_callback_pool_offset

    .macro kh_save
    sub x15, x15, #0x60
    stp x0, x2, [x15, #0x00]
    stp x8, x9, [x15, #0x10]
    stp x10, x11, [x15, #0x20]
    stp x12, x13, [x15, #0x30]
    stp x14, x16, [x15, #0x40]
    str x17, [x15, #0x50]
    mrs x17, nzcv
    str x17, [x15, #0x58]
    .endm

    .macro kh_restore
    ldr x17, [x15, #0x58]
    msr nzcv, x17
    ldp x0, x2, [x15, #0x00]
    ldp x8, x9, [x15, #0x10]
    ldp x10, x11, [x15, #0x20]
    ldp x12, x13, [x15, #0x30]
    ldp x14, x16, [x15, #0x40]
    ldr x17, [x15, #0x50]
    add x15, x15, #0x60
    .endm

    // PP is the original live isolate root. Its offsets and Function entry
    // shape are validated by the resolver. No raw Dart reference is retained.
    .macro kh_pool_link offset_symbol, entry_symbol
    adrp x10, \offset_symbol
    ldr x10, [x10, :lo12:\offset_symbol]
    ldr x10, [x27, x10]
    tbz x10, #0, .Lkh_reject
    cmp w10, w22
    b.eq .Lkh_reject
    ldr x17, [x27, #0x40]
    cmp w10, w17
    b.eq .Lkh_reject
    ldur x10, [x10, #7]
    adrp x17, \entry_symbol
    ldr x17, [x17, :lo12:\entry_symbol]
    cmp x10, x17
    b.ne .Lkh_reject
    .endm

kh_monet_selection_bridge:
    bti jc
    kh_save
    adrp x16, kh_monet_readers
    add x16, x16, :lo12:kh_monet_readers
.Lkh_enter:
    ldaxr w13, [x16]
    cmn w13, #1
    b.eq .Lkh_counter_full
    add w13, w13, #1
    stlxr w12, w13, [x16]
    cbnz w12, .Lkh_enter
    dmb ish                     // publish reader before loading the RCU pointer
    adrp x9, kh_monet_policy
    add x9, x9, :lo12:kh_monet_policy
    ldar x9, [x9]
    cbz x9, .Lkh_reject
    ldr w12, [x9]
    tbz w12, #0, .Lkh_reject      // only flags & 1 enables selection veto
    ldr w12, [x9, #12]
    cbz w12, .Lkh_reject
    cmp w12, #KH_SELECTION_MAX_COUNT
    b.hi .Lkh_reject
    adrp x10, kh_selection_profile_ready
    add x10, x10, :lo12:kh_selection_profile_ready
    ldar w10, [x10]
    cmp w10, #1
    b.ne .Lkh_reject
    kh_pool_link kh_selection_factory_pool_offset, kh_selection_factory_entry
    kh_pool_link kh_selection_update_callback_pool_offset, kh_selection_update_callback_entry

    // x1 is the scoped, live argument, never a stored controller/reference.
    tbz x1, #0, .Lkh_reject
    cmp w1, w22
    b.eq .Lkh_reject
    ldur x10, [x1, #-1]
    ubfx x10, x10, #12, #20
    cmp x10, #KH_SELECTION_ONE_BYTE_CID
    b.ne .Lkh_reject             // including TwoByteString CID 95
    ldur x14, [x1, #7]
    tbnz x14, #0, .Lkh_reject
    cbz x14, .Lkh_reject
    cmp x14, #510                // full tagged length; rejects upper bits too
    b.hi .Lkh_reject
    lsr x14, x14, #1
    mov x11, #0
.Lkh_ascii:
    add x10, x1, x11
    ldrb w10, [x10, #15]
    cbz w10, .Lkh_reject
    tbnz w10, #7, .Lkh_reject
    add x11, x11, #1
    cmp x11, x14
    b.lo .Lkh_ascii

    add x13, x9, #KH_SELECTION_POLICY_ENTRIES
    mov x8, #0
.Lkh_row:
    ldr w10, [x13]
    cbz w10, .Lkh_reject
    cmp w10, #KH_SELECTION_MAX_LENGTH
    b.hi .Lkh_reject
    cmp x10, x14
    b.ne .Lkh_next
    mov x11, #0
.Lkh_byte:
    add x10, x1, x11
    ldrb w0, [x10, #15]
    add x10, x13, x11
    ldrb w2, [x10, #4]
    cmp w0, w2
    b.ne .Lkh_next
    add x11, x11, #1
    cmp x11, x14
    b.lo .Lkh_byte
    mov x8, #1                  // validate later row lengths before accepting
.Lkh_next:
    add x13, x13, #KH_SELECTION_ENTRY_STRIDE
    subs w12, w12, #1
    b.ne .Lkh_row
    b .Lkh_leave
.Lkh_reject:
    mov x8, #0
.Lkh_leave:
    // No policy/Dart loads occur after releasing this reader.
    adrp x16, kh_monet_readers
    add x16, x16, :lo12:kh_monet_readers
.Lkh_exit:
    ldaxr w13, [x16]
    cbz w13, .Lkh_counter_broken
    sub w13, w13, #1
    stlxr w12, w13, [x16]
    cbnz w12, .Lkh_exit
    cbz x8, .Lkh_original
    kh_restore
    add x0, x22, #0x30          // tagged Dart false; no Dart frame was entered
    ret
.Lkh_counter_broken:
    clrex                       // never underflow a corrupted owner counter
    b .Lkh_original
.Lkh_counter_full:
    clrex                       // no acquisition, so no release
.Lkh_original:
    kh_restore
    // x16 is killed before its first read by the proved original/stub path.
    // x30 and the original Dart frame/root lifetime are unchanged.
    adrp x16, kh_selection_original
    add x16, x16, :lo12:kh_selection_original
    ldar x16, [x16]
    br x16
    .size kh_monet_selection_bridge, .-kh_monet_selection_bridge

    .section .note.gnu.property, "a"
    .p2align 3
    .long 4
    .long 16
    .long 5
    .asciz "GNU"
    .p2align 3
    .long 0xc0000000
    .long 4
    .long 1
    .long 0
    .section .note.GNU-stack,"",%progbits

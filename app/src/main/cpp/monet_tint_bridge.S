// SPDX-License-Identifier: Apache-2.0
#include "monet_tint_profile.h"

#if !defined(__aarch64__)
#error "The reviewed Dart tint ABI is AArch64 only"
#endif

    .text
    .hidden kh_monet_policy
    .hidden kh_monet_readers
    .hidden kh_monet_tint_ever_enabled
    .hidden kh_tint_original
    .hidden kh_filter_equals_original
    .hidden kh_tint_caller
    .hidden kh_render_equals_caller
    .hidden kh_layer_equals_caller
    .hidden kh_monet_enabled_equals_original
    .hidden kh_monet_enabled_equals_caller
    .hidden kh_monet_settings_setter_caller
    .hidden kh_monet_filter_pool_offset
    .hidden kh_monet_controller_field_offset
    .hidden kh_monet_controller_cid
    .hidden kh_monet_rx_int_cid
    .hidden kh_monet_rx_bool_cid
    .hidden kh_monet_object_equals_entry
    .hidden kh_monet_selection_revision
    .hidden kh_monet_selection_notified

// Use Dart SP x15, never native CSP. No calls, allocations, safepoints, SIMD
// operations or Dart heap writes occur here. Save all used non-result GPRs
// and NZCV. x16 is dead at both authenticated original entries and is the
// sole scratch clobber on a tail transfer to the original trampoline.
    .macro KH_SAVE
    sub x15, x15, #64
    stp x9, x10, [x15]
    stp x11, x12, [x15, #16]
    stp x16, x17, [x15, #32]
    mrs x9, nzcv
    str x9, [x15, #48]
    .endm

    .macro KH_RESTORE
    ldr x9, [x15, #48]
    msr nzcv, x9
    ldp x16, x17, [x15, #32]
    ldp x11, x12, [x15, #16]
    ldp x9, x10, [x15]
    add x15, x15, #64
    .endm

    .macro KH_GLOBAL reg, name
    adrp \reg, \name
    add \reg, \reg, :lo12:\name
    .endm

// x11 = counter address, x12 = immutable policy. Both survive until UNPIN.
// Saturation must fail without wrapping the count or acquiring a pin.
    .macro KH_PIN unpinned_failure, pinned_failure
    KH_GLOBAL x11, kh_monet_readers
.Lpin_retry\@:
    ldaxr w9, [x11]
    adds w10, w9, #1
    b.eq .Lpin_saturated\@
    stlxr w9, w10, [x11]
    cbnz w9, .Lpin_retry\@
    dmb ish
    KH_GLOBAL x16, kh_monet_policy
    ldar x12, [x16]
    cbz x12, \pinned_failure
    ldr w9, [x12, #KH_TINT_POLICY_FLAGS]
    cmp w9, #1
    b.ne \pinned_failure
    b .Lpin_done\@
.Lpin_saturated\@:
    clrex
    b \unpinned_failure
.Lpin_done\@:
    .endm

    .macro KH_UNPIN
.Lunpin_retry\@:
    ldaxr w9, [x11]
    sub w9, w9, #1
    stlxr w10, w9, [x11]
    cbnz w10, .Lunpin_retry\@
    .endm

// x16 is a tagged reference, compressed or full. Reject uninitialized values
// before dereferencing; NULL_REG and PP+0x40 are isolate-local live roots.
    .macro KH_REFERENCE failure
    tbz w16, #0, \failure
    cmp w16, w22
    b.eq \failure
    ldr x17, [x27, #0x40]
    cmp w16, w17
    b.eq \failure
    .endm

// Resolved-image chain: Thread field table -> MonochromeController -> RxInt ->
// selected int. Do not invoke the Rx getter (its read-reporting has side
// effects). Class IDs come from the authenticated constructors/allocators.
    .macro KH_MATCH_BACKGROUND failure
    ldr x16, [x26, #0x78]
    cbz x16, \failure
    KH_GLOBAL x17, kh_monet_controller_field_offset
    ldr x17, [x17]
    ldr x16, [x16, x17]
    KH_REFERENCE \failure
    ldur w9, [x16, #-1]
    ubfx w9, w9, #12, #20
    KH_GLOBAL x17, kh_monet_controller_cid
    ldr w17, [x17]
    cmp w9, w17
    b.ne \failure
    ldur w16, [x16, #0x1f]
    KH_REFERENCE \failure
    add x16, x16, x28, lsl #32
    ldur w9, [x16, #-1]
    ubfx w9, w9, #12, #20
    KH_GLOBAL x17, kh_monet_rx_int_cid
    ldr w17, [x17]
    cmp w9, w17
    b.ne \failure
    ldur w16, [x16, #0x13]
    tbz w16, #0, .Lbackground_smi\@
    KH_REFERENCE \failure
    add x16, x16, x28, lsl #32
    ldur w9, [x16, #-1]
    ubfx w9, w9, #12, #20
    cmp w9, #KH_TINT_MINT_CID
    b.ne \failure
    ldur x16, [x16, #7]
    // Accept either signed Android ARGB32 or normalized unsigned ARGB32,
    // never a wider Mint that merely shares the low 32 bits.
    sxtw x17, w16
    cmp x16, x17
    b.eq .Lbackground_compare\@
    lsr x17, x16, #32
    cbnz x17, \failure
    b .Lbackground_compare\@
.Lbackground_smi\@:
    sbfx x16, x16, #1, #31
.Lbackground_compare\@:
    ldr w17, [x12, #KH_TINT_POLICY_BACKGROUND]
    cmp w16, w17
    b.ne \failure
    .endm

    .p2align 2
    .global kh_monet_tint_bridge
    .hidden kh_monet_tint_bridge
    .type kh_monet_tint_bridge, %function
kh_monet_tint_bridge:
    hint #38 // BTI jc; also a harmless hint on pre-BTI ARMv8.
    KH_SAVE
    KH_GLOBAL x16, kh_tint_caller
    ldar x16, [x16]
    cmp x30, x16
    b.ne .Ltint_original
    // Entry is BEFORE the resolved FFI prologue. x29 still belongs to _ColorFilter.mode.
    ldur x16, [x29, #-0x10]
    KH_GLOBAL x17, kh_monet_filter_pool_offset
    ldr x17, [x17]
    ldr x17, [x27, x17]
    cmp x16, x17
    b.ne .Ltint_original
    cmp x3, #5 // BlendMode.srcIn.index, unboxed.
    b.ne .Ltint_original
    mov w16, #0xff000000
    cmp w2, w16 // Existing black input; this is NOT a tagged Smi argument.
    b.ne .Ltint_original
    KH_PIN .Ltint_original, .Ltint_unpin_original
    KH_MATCH_BACKGROUND .Ltint_unpin_original
    ldr w2, [x12, #KH_TINT_POLICY_FOREGROUND]
    // The original FFI stub sign-extends w2 and keeps its original leaf call.
.Ltint_unpin_original:
    KH_UNPIN
.Ltint_original:
    KH_RESTORE
    KH_GLOBAL x16, kh_tint_original
    ldar x16, [x16]
    br x16
    .size kh_monet_tint_bridge, .-kh_monet_tint_bridge

    .p2align 2
    .global kh_monet_filter_equals_bridge
    .hidden kh_monet_filter_equals_bridge
    .type kh_monet_filter_equals_bridge, %function
kh_monet_filter_equals_bridge:
    hint #38 // BTI jc.
    KH_SAVE
    KH_GLOBAL x16, kh_render_equals_caller
    ldar x16, [x16]
    cmp x30, x16
    b.eq .Lequals_scoped
    KH_GLOBAL x16, kh_layer_equals_caller
    ldar x16, [x16]
    cmp x30, x16
    b.ne .Lequals_original
.Lequals_scoped:
    // No native tint can predate the first enabled publication. This history
    // is never consumed/reset, including across withdrawal and Java hot reload.
    KH_GLOBAL x16, kh_monet_tint_ever_enabled
    ldar w16, [x16]
    cbz w16, .Lequals_original
    KH_GLOBAL x17, kh_monet_filter_pool_offset
    ldr x17, [x17]
    ldr x17, [x27, x17]
    ldr x16, [x15, #64] // Original Dart SP[0]: other.
    cmp x16, x17
    b.ne .Lequals_original
    ldr x16, [x15, #72] // Original Dart SP[8]: this.
    cmp x16, x17
    b.ne .Lequals_original
    // Invalidate this exact constant at these two setters even after policy
    // withdrawal. The ordinary refresh then rebuilds the native filter and
    // the tint bridge falls through to stock black. No policy read/pin here.
    KH_RESTORE
    add x0, x22, #0x30 // Exact same-image Dart false object, not integer zero.
    ret
.Lequals_original:
    KH_RESTORE
    KH_GLOBAL x16, kh_filter_equals_original
    ldar x16, [x16]
    br x16
    .size kh_monet_filter_equals_bridge, .-kh_monet_filter_equals_bridge

// Re-emit the ORIGINAL enabled Rx value once when its selection policy changes.
// This is an equality result only: the existing setter/stream owns notifications
// and every controller's lifetime. No Dart call, heap write or saved reference.
    .p2align 2
    .global kh_monet_enabled_equals_bridge
    .hidden kh_monet_enabled_equals_bridge
    .type kh_monet_enabled_equals_bridge, %function
kh_monet_enabled_equals_bridge:
    hint #38
    // Object.== is shared by unrelated Dart code. Reject other callers with
    // one saved scratch register and no flag/policy/heap work on that hot path.
    str x16, [x15, #-16]!
    KH_GLOBAL x16, kh_monet_enabled_equals_caller
    ldar x16, [x16]
    eor x16, x16, x30
    cbnz x16, .Lenabled_unrelated
    ldr x16, [x15], #16
    KH_SAVE
    add x16, x15, #64
    sub x17, x29, #0x20
    cmp x16, x17
    b.ne .Lenabled_original
    // bool CID 63 dispatches Object.== in this image; verify the live GDT too.
    KH_GLOBAL x16, kh_monet_object_equals_entry
    ldar x16, [x16]
    ldr x17, [x21, #0x1f8]
    cmp x16, x17
    b.ne .Lenabled_original
    KH_GLOBAL x16, kh_monet_settings_setter_caller
    ldar x16, [x16]
    ldr x17, [x29, #8] // Rx setter's caller, not Object.=='s caller.
    cmp x16, x17
    b.ne .Lenabled_original
    add x17, x22, #0x20
    ldr x16, [x15, #64]
    cmp x16, x17
    b.ne .Lenabled_original
    ldr x16, [x15, #72]
    cmp x16, x17
    b.ne .Lenabled_original
    ldr x16, [x26, #0x78]
    cbz x16, .Lenabled_original
    KH_GLOBAL x17, kh_monet_controller_field_offset
    ldr x17, [x17]
    ldr x16, [x16, x17]
    KH_REFERENCE .Lenabled_original
    ldur w9, [x16, #-1]
    ubfx w9, w9, #12, #20
    KH_GLOBAL x17, kh_monet_controller_cid
    ldr w17, [x17]
    cmp w9, w17
    b.ne .Lenabled_original
    ldur w16, [x16, #0x23]
    KH_REFERENCE .Lenabled_original
    add x16, x16, x28, lsl #32
    ldur w9, [x16, #-1]
    ubfx w9, w9, #12, #20
    KH_GLOBAL x17, kh_monet_rx_bool_cid
    ldr w17, [x17]
    cmp w9, w17
    b.ne .Lenabled_original
    ldur x17, [x29, #-8]
    cmp x16, x17
    b.ne .Lenabled_original
    KH_GLOBAL x11, kh_monet_selection_revision
    ldar x10, [x11]
    cbz x10, .Lenabled_original
    KH_GLOBAL x11, kh_monet_selection_notified
.Lenabled_claim:
    ldaxr x9, [x11]
    cmp x9, x10
    b.hs .Lenabled_already_claimed
    stlxr w9, x10, [x11]
    cbnz w9, .Lenabled_claim
    KH_RESTORE
    add x0, x22, #0x30
    ret
.Lenabled_already_claimed:
    clrex
.Lenabled_original:
    KH_RESTORE
    b .Lenabled_tail
.Lenabled_unrelated:
    add x15, x15, #16
.Lenabled_tail:
    KH_GLOBAL x16, kh_monet_enabled_equals_original
    ldar x16, [x16]
    br x16
    .size kh_monet_enabled_equals_bridge, .-kh_monet_enabled_equals_bridge

    .section .note.GNU-stack,"",%progbits

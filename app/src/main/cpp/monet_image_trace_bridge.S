// SPDX-License-Identifier: Apache-2.0
#include "monet_image_trace.h"
#if !defined(__aarch64__)
#error "Image tracing requires the reviewed AArch64 Dart ABI"
#endif
    .text
    .hidden kh_image_trace_control
    .hidden kh_image_trace_records
    .hidden kh_image_trace_image_cid
    .hidden kh_image_trace_dispose_selector
    .hidden kh_image_trace_raw_update_return
    .hidden kh_image_trace_dispose_original
    .hidden kh_image_trace_clone_original
    .hidden kh_image_trace_build_original

    .macro GLOBAL reg, symbol
    adrp \reg, \symbol
    add \reg, \reg, :lo12:\symbol
    .endm

// No calls, syscalls, allocations, SIMD, heap writes, TLS access, or waits.
// CSP/sp, x18, FP/LR, PP, THR, HEAP_BITS and all live roots remain untouched.
// The 80-byte Dart-stack scratch exists only within this call-free leaf. It is
// gone before the original Dart frame can enter any allocation/safepoint.
// x0 is the ONLY tail-transfer clobber; the resolver proves an unconditional x0
// definition before any incoming x0 read in each original entry. x16 is saved.
    .macro SAVE
    sub x15, x15, #80
    stp x9, x10, [x15]
    stp x11, x12, [x15, #16]
    stp x13, x14, [x15, #32]
    stp x16, x17, [x15, #48]
    mrs x9, nzcv
    str x9, [x15, #64]
    .endm
    .macro RESTORE original
    ldr x9, [x15, #64]
    msr nzcv, x9
    ldp x16, x17, [x15, #48]
    ldp x13, x14, [x15, #32]
    ldp x11, x12, [x15, #16]
    ldp x9, x10, [x15]
    add x15, x15, #80
    GLOBAL x0, \original
    ldr x0, [x0]
    br x0
    .endm

// x14 = control, x17 = new immutable record. A single try-lock attempt avoids
// spinning in the mutator. Any contention sets an explicit sticky loss flag.
// Slots never wrap/reuse. Count is release-published only after all ten fields.
    .macro BEGIN kind, done
    SAVE
    GLOBAL x14, kh_image_trace_control
    ldar w9, [x14]
    cbz w9, \done
    add x13, x14, #4
    ldaxr w9, [x13]
    cbnz w9, .Lloss\@
    mov w10, #1
    stxr w9, w10, [x13]
    cbnz w9, .Lloss\@
    ldar w9, [x14]
    cbz w9, .Labandon\@
    ldr w9, [x14, #8]
    cmp w9, #KH_IMAGE_TRACE_CAPACITY
    b.hs .Labandon\@
    GLOBAL x17, kh_image_trace_records
    mov x10, #KH_IMAGE_TRACE_RECORD_SIZE
    madd x17, x9, x10, x17
    mov x10, #\kind
    stp x10, x26, [x17]
    stp x30, xzr, [x17, #16]
    stp xzr, xzr, [x17, #32]
    mov x10, #2
    stp x10, x10, [x17, #48]
    stp xzr, xzr, [x17, #64]
    b .Lbegin_done\@
.Lloss\@:
    clrex
    mov w9, #1
    add x13, x14, #12
    stlr w9, [x13]
    b \done
.Labandon\@:
    stlr wzr, [x13]
    b \done
.Lbegin_done\@:
    .endm

// x10 is a full, currently rooted Image candidate. Only decoded class metadata
// authorizes the field reads. Disposed: 0=false, 1=true, 2=absent, 3=unrecognized.
// The saved address is scalar evidence, not a reference retained for future use.
    .macro IMAGE address_slot, flag_slot, backing=0
    cmp w10, w22
    b.eq .Limage_done\@
    str x10, [x17, #\address_slot]
    mov x11, #3
    str x11, [x17, #\flag_slot]
    tbz w10, #0, .Limage_done\@
    ldur w11, [x10, #-1]
    ubfx w11, w11, #12, #20
    GLOBAL x12, kh_image_trace_image_cid
    ldr w12, [x12]
    cmp w11, w12
    b.ne .Limage_done\@
    ldur w11, [x10, #0x1f]
    add w12, w22, #0x20
    cmp w11, w12
    mov x0, #1
    b.eq .Lflag\@
    add w12, w22, #0x30
    cmp w11, w12
    mov x0, #0
    b.ne .Limage_done\@
.Lflag\@:
    str x0, [x17, #\flag_slot]
    .if \backing
    ldur w11, [x10, #7]
    add x11, x11, x28, lsl #32
    str x11, [x17, #72]
    .endif
.Limage_done\@:
    .endm

    .macro COMMIT
    ldr w9, [x14, #8]
    add w9, w9, #1
    add x13, x14, #8
    stlr w9, [x13]
    add x13, x14, #4
    stlr wzr, [x13]
    .endm

    .macro IMAGE_BRIDGE name, kind, original
    .p2align 2
    .global \name
    .hidden \name
    .type \name, %function
\name:
    bti c
    BEGIN \kind, .Ldone\@
    mov x10, x1
    IMAGE 32, 48, 1
    .if \kind == KH_IMAGE_TRACE_CLONE
    // Keep RawImage.updateRenderObject and any already-disposed clone input.
    // Other successful clone traffic cannot establish the reproduced failure.
    ldr x10, [x17, #48]
    cmp x10, #1
    b.eq .Lrecord\@
    GLOBAL x10, kh_image_trace_raw_update_return
    ldr x10, [x10]
    cmp x30, x10
    b.eq .Lrecord\@
    add x13, x14, #4
    stlr wzr, [x13]
    b .Ldone\@
.Lrecord\@:
    .endif
    COMMIT
.Ldone\@:
    RESTORE \original
    .size \name, .-\name
    .endm

    IMAGE_BRIDGE kh_image_trace_dispose_bridge, KH_IMAGE_TRACE_DISPOSE, kh_image_trace_dispose_original
    IMAGE_BRIDGE kh_image_trace_clone_bridge, KH_IMAGE_TRACE_CLONE, kh_image_trace_clone_original

    .p2align 2
    .global kh_image_trace_build_bridge
    .hidden kh_image_trace_build_bridge
    .type kh_image_trace_build_bridge, %function
kh_image_trace_build_bridge:
    bti c
    BEGIN KH_IMAGE_TRACE_BUILD, .Lbuild_done
    str x1, [x17, #24]
    cmp w1, w22
    b.eq .Lbuild_commit
    tbz w1, #0, .Lbuild_commit
    // State is the original build receiver. The complete build body proves
    // these two fields; no guessed cache/record/context layout is traversed.
    ldur w10, [x1, #0x13]
    add x10, x10, x28, lsl #32
    IMAGE 32, 48, 1
    ldur w10, [x1, #0x17]
    add x10, x10, x28, lsl #32
    IMAGE 40, 56
    // Record the actual disposal dispatch target for this State. Unmount's
    // validated dispatch computes this same CID+selector index in live GDT.
    // No call or code dereference follows; a host can symbolize the scalar PC.
    ldur w10, [x1, #-1]
    ubfx w10, w10, #12, #20
    GLOBAL x11, kh_image_trace_dispose_selector
    ldr w11, [x11]
    add x10, x10, x11
    ldr x10, [x21, x10, lsl #3]
    str x10, [x17, #64]
.Lbuild_commit:
    COMMIT
.Lbuild_done:
    RESTORE kh_image_trace_build_original
    .size kh_image_trace_build_bridge, .-kh_image_trace_build_bridge
    .section .note.GNU-stack,"",%progbits

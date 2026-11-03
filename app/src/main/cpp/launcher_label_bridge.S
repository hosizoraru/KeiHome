// SPDX-License-Identifier: Apache-2.0
// These bridges never call C or retain a Dart reference. The only Dart calls
// replace an existing allocation BL and return to its ORIGINAL mapped PC.
// The caller's live String/Style stack-map roots remain roots of tagged objects.
// Marquee construction (including write barriers, curves and its State) remains
// entirely in original Dart code with original safepoints/exception handling.
    .section .rodata.launcher_label_bridge,"a",%progbits
    .balign 8
    .global kh_label_template, kh_label_pre, kh_label_post, kh_label_lines
    .global kh_label_config, kh_label_end
    .hidden kh_label_template, kh_label_pre, kh_label_post, kh_label_lines
    .hidden kh_label_config, kh_label_end
kh_label_template:
kh_label_pre:
    hint #38
    adr x17, kh_label_config
    ldr x16, [x17]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Lallocate_text
    ldr x16, [x17, #128]
    ldr x1, [x29, x16]
    ldr x16, [x17, #136]
    ldr x2, [x29, x16]
    ldr x16, [x17, #16]
    br x16                         // original measurement; original allocation return PC
.Lallocate_text:
    ldr x16, [x17, #8]
    br x16

kh_label_post:
    hint #38
    adr x17, kh_label_config
    cmp x0, x22
    b.eq .Lconstructor_returned
    tbz x0, #0, .Lallocate_text
    ldur x9, [x0, #-1]
    ubfx x9, x9, #12, #20
    ldr x10, [x17, #48]
    cmp x9, x10
    b.eq .Lnative_text
    ldr x10, [x17, #56]
    cmp x9, x10
    b.ne .Lallocate_text
    // Measurement returns Size; native short labels keep their original Text.
    ldr x16, [x17]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Lallocate_text
    ldur d0, [x0, #7]
    fmov x10, d0
    ubfx x10, x10, #52, #11
    cmp x10, #0x7ff
    b.eq .Lallocate_text
    fcmp d0, #0.0
    b.le .Lallocate_text
    b.vs .Lallocate_text
    ldr x9, [x26, #0x78]
    ldr x10, [x17, #72]
    ldr x9, [x9, x10]
    cmp w9, w22
    b.eq .Lallocate_text
    ldr x10, [x17, #80]
    ldr w9, [x9, x10]
    add x9, x9, x28, lsl #32
    ldr x10, [x17, #88]
    ldr w9, [x9, x10]
    add x9, x9, x28, lsl #32
    ldr x10, [x17, #96]
    ldr d1, [x9, x10]
    ldr x10, [x17, #120]
    ldr x9, [x29, x10]
    ldr x10, [x17, #104]
    ldr w9, [x9, x10]
    add x9, x9, x28, lsl #32
    ldur x10, [x9, #-1]
    ubfx x10, x10, #12, #20
    cmp x10, #62                   // same Double ABI as the native builder's inline allocations
    b.ne .Lallocate_text
    ldur d2, [x9, #7]
    fmin d1, d1, d2
    fcmp d1, #0.0
    b.le .Lallocate_text
    b.vs .Lallocate_text
    fcmp d0, d1
    b.le .Lallocate_text
    b.vs .Lallocate_text
    // Same young-space fast path as the authenticated native allocation stub.
    // No safepoint occurs here; insufficient space falls back to native Text.
    ldp x9, x10, [x26, #0x60]
    add x11, x9, #0x70
    cmp x10, x11
    b.ls .Lallocate_text
    str x11, [x26, #0x60]
    ldr x10, [x17, #64]
    str x10, [x9]
    add x12, x9, #8
.Lnull_fields:
    stp w22, w22, [x12], #8
    cmp x12, x11
    b.lo .Lnull_fields
    add x1, x9, #1
    ldr x10, [x17, #128]
    ldr x5, [x29, x10]
    // This was a live String root at the original allocation call. Until the
    // constructor returns it roots Marquee instead, so relocating GC updates it.
    str x1, [x29, x10]
    ldr x10, [x17, #136]
    ldr x3, [x29, x10]
    mov x2, x22
    ldr x10, [x17, #112]
    ldr x6, [x27, x10]
    ldr x16, [x17, #24]
    br x16                         // native Marquee constructor; original return PC

.Lconstructor_returned:
    ldr x10, [x17, #128]
    ldr x0, [x29, x10]
    // Only our native constructor may return null to this allocation site.
    ldur w2, [x0, #0xb]
    add x2, x2, x28, lsl #32
    str x2, [x29, x10]              // restore String root before any later Dart call
    ldr x10, [x17, #144]
    str x0, [x29, x10]
    ldr x10, [x17, #152]
    ldr x1, [x29, x10]
    lsl x4, x1, #1
    ldr x16, [x17, #40]
    br x16

.Lnative_text:
    ldr x10, [x17, #128]
    ldr x2, [x29, x10]
    ldr x10, [x17, #144]
    str x0, [x29, x10]
    stur w2, [x0, #0xb]
    ldr x10, [x17, #136]
    ldr x3, [x29, x10]
    ldr x16, [x17, #32]
    br x16

kh_label_lines:
    hint #38
    adr x0, kh_label_config
    ldr x0, [x0]
    ldar w0, [x0]
    cbz w0, .Loriginal_lines
    cmp w0, #2
    b.eq .Ltwo_lines
    mov x0, #1
    b .Llines_return
.Ltwo_lines:
    mov x0, #2
    b .Llines_return
.Loriginal_lines:
    mov x0, x1
.Llines_return:
    mov x15, x29
    ldp x29, x30, [x15], #16
    ret

    .balign 8
kh_label_config:
    .zero 160
kh_label_end:
    .section .note.GNU-stack,"",%progbits

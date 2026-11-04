// SPDX-License-Identifier: Apache-2.0
// Relocatable copied template, not a C entry. Render/gate are leaf entries:
// no BL/BLR, managed/C call, SP/x15 spill, safepoint or persistent Dart root.
// Preserve x1 (including every fallback), x15, x18-x30 and v8-v15. Only
// x0,x2-x14,x16-x17 and v0-v7,v16-v31 are scratch. x30 is the original BL LR.
// Parent authenticates the native graph; no raw fixture PC is used here.
#include "dock_glass_bridge.h"

    .section .rodata.dock_glass_bridge,"a",%progbits
    .balign 8
    .global kh_dock_template, kh_dock_gate, kh_dock_background
    .global kh_dock_config, kh_dock_end
    .hidden kh_dock_template, kh_dock_gate, kh_dock_background
    .hidden kh_dock_config, kh_dock_end
kh_dock_template:

// Replaces TBNZ w1,#4,falseSkip (8593 oracle 117d288) with a B, NOT BL.
// Preserve NZCV too: original TBNZ does not change flags. Enabled forces only
// the native insertion path; it never changes the captured .1b Bool itself.
kh_dock_gate:
    hint #38
    adr x17, kh_dock_config
    ldr x16, [x17, #KH_DOCK_ENABLED]
    ldar w16, [x16]
    sub w16, w16, #1
    cbz w16, .Ldock_enter
    tbnz w1, #4, .Ldock_skip
.Ldock_enter:
    ldr x16, [x17, #KH_DOCK_GATE_ENTER]
    br x16
.Ldock_skip:
    ldr x16, [x17, #KH_DOCK_GATE_SKIP]
    br x16

// Reject only absent/uninitialized/Smi references. Class identities and field
// layouts are authenticated by the parent profile, not guessed at runtime.
    .macro dock_ref xreg, wreg
    tbz \xreg, #0, .Ldock_original
    cmp \wreg, w22
    b.eq .Ldock_original
    ldr x16, [x27, #0x40]
    cmp \wreg, w16
    b.eq .Ldock_original
    .endm
    .macro dock_finite dreg
    fmov x16, \dreg
    ubfx x16, x16, #52, #11
    cmp x16, #0x7ff
    b.eq .Ldock_original
    .endm

// Replaces ONLY BL _buildBackground (8593 oracle 117d298). Frame roots:
// [fp-8] outer Context: +f compressed Smi count, +1b captured native gate Bool;
// [fp-20] inner Context: +13 BoxConstraints, raw maxWidth+f/maxHeight+1f;
// [fp-28] native GrowableList: type args+7, data Array+f.
// All hex field offsets are relative to tagged object pointers.
kh_dock_background:
    hint #38
    adr x17, kh_dock_config
    ldr x16, [x17, #KH_DOCK_ENABLED]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Ldock_original
    ldr x16, [x17, #KH_DOCK_IGNORE_POINTER_TAG]
    cbz x16, .Ldock_original     // never construct an unauthenticated wrapper
    // Parent's native Obx mode-capture path publishes ONLY a primitive 0..3.
    // Read it once: never mix one mode's header with another mode's size.
    ldr x16, [x17, #KH_DOCK_MATERIAL_MODE_ADDRESS]
    cbz x16, .Ldock_original
    ldar w14, [x16]
    cmp w14, #KH_DOCK_MATERIAL_NORMAL
    b.hi .Ldock_original
    add x16, x17, #KH_DOCK_MATERIAL_TAGS
    ldr x12, [x16, x14, lsl #3]
    cbz x12, .Ldock_original
    fmov d24, x12                // selected native header, not a managed root
    mov x11, #32
    mov x16, #48
    cmp w14, #KH_DOCK_MATERIAL_GLASS_OS4
    csel x11, x16, x11, eq       // Glass48; other native primitives32

    ldur x3, [x29, #-8]
    dock_ref x3, w3
    ldur w2, [x3, #0xf]
    tbnz w2, #0, .Ldock_original
    asr w2, w2, #1
    sub w16, w2, #1
    cmp w16, #4
    b.hi .Ldock_original          // count 1..5; includes negative rejection
    ldur w4, [x3, #0x1b]
    add w16, w22, #0x20
    cmp w4, w16
    cset x8, eq                   // include ItemBackground only if exactly true
    b.eq .Ldock_gate_captured
    add w16, w22, #0x30
    cmp w4, w16
    b.ne .Ldock_original
.Ldock_gate_captured:
    ldur x3, [x29, #-0x20]
    dock_ref x3, w3
    ldur w3, [x3, #0x13]
    add x3, x3, x28, lsl #32
    dock_ref x3, w3
    ldur d0, [x3, #0xf]           // C
    ldur d1, [x3, #0x1f]          // H
    dock_finite d0
    dock_finite d1
    fmov d7, #16.0
    fcmp d0, d7
    b.le .Ldock_original
    fcmp d1, #0.0
    b.le .Ldock_original

    // Cached GridController -> Rx -> current GridConfig. Never invoke a getter.
    ldr x3, [x26, #0x78]
    ldr x16, [x17, #KH_DOCK_GRID_SLOT]
    ldr x3, [x3, x16]
    dock_ref x3, w3
    ldr x16, [x17, #KH_DOCK_GRID_RX_FIELD]
    ldr w3, [x3, x16]
    add x3, x3, x28, lsl #32
    dock_ref x3, w3
    ldr x16, [x17, #KH_DOCK_RX_VALUE_FIELD]
    ldr w3, [x3, x16]
    add x3, x3, x28, lsl #32
    dock_ref x3, w3
    ldr x4, [x26, #0x78]
    ldr x16, [x17, #KH_DOCK_DEVICE_SLOT]
    ldr x4, [x4, x16]
    dock_ref x4, w4
    ldr x16, [x17, #KH_DOCK_DEVICE_LAYOUT_FIELD]
    ldr w4, [x4, x16]
    add w16, w22, #0x20
    cmp w4, w16
    b.eq .Ldock_wide_cell
    add w16, w22, #0x30
    cmp w4, w16
    b.ne .Ldock_original
    mov w14, #0                  // retain native layout flag for cell height
    ldur d2, [x3, #0x2b]
    b .Ldock_cell
.Ldock_wide_cell:
    mov w14, #1
    ldur d2, [x3, #0x77]
    fmov d7, #20.0
    fadd d2, d2, d7               // native layout width path; NOT an elderly test
.Ldock_cell:
    dock_finite d2
    fcmp d2, #0.0
    b.le .Ldock_original

    // Native background layout centers the painted icon within its cell.
    // Use that ink width, not the whole cell, for the plate's edge clearance.
    ldr x16, [x17, #KH_DOCK_ICON_METRICS_FIELD]
    ldr w4, [x3, x16]
    add x4, x4, x28, lsl #32
    dock_ref x4, w4
    mov x6, x4                   // metrics root, read only until geometry ends
    ldr x16, [x17, #KH_DOCK_ICON_WIDTH_FIELD]
    ldr d19, [x4, x16]
    dock_finite d19
    fcmp d19, #0.0
    b.le .Ldock_original
    fcmp d19, d2
    b.gt .Ldock_original

    // Native hotSeatMaxCount -> LauncherModeController.isElderlyManMode:
    // cached mode controller -> mode Rx -> generic Rx.value, compared with the
    // native elderly enum. All slot/field/PP offsets come from the parent graph.
    // This decision is independent of the DeviceConfig layout flag and width.
    ldr x4, [x26, #0x78]
    ldr x16, [x17, #KH_DOCK_MODE_CONTROLLER_SLOT]
    ldr x4, [x4, x16]
    dock_ref x4, w4
    mov x13, x4                  // LauncherModeController for native no-word Rx
    ldr x16, [x17, #KH_DOCK_MODE_RX_FIELD]
    ldr w4, [x4, x16]
    add x4, x4, x28, lsl #32
    dock_ref x4, w4
    ldr x16, [x17, #KH_DOCK_RX_VALUE_FIELD]
    ldr w4, [x4, x16]
    add x4, x4, x28, lsl #32
    dock_ref x4, w4
    ldr x16, [x17, #KH_DOCK_ELDERLY_ENUM_PP]
    ldr x5, [x27, x16]
    dock_ref x5, w5
    mov w12, #5
    mov w16, #4
    cmp w4, w5
    csel w12, w16, w12, eq       // exact elderly enum -> 4, other mode -> 5
    mov x7, x4                   // mode enum; no call/safepoint before use
    ldr x16, [x17, #KH_DOCK_NO_WORD_SUPPRESS_RX_FIELD]
    cbnz x16, .Ldock_no_word_suppression
    cmp w12, #4
    b.eq .Ldock_metrics_top      // legacy isNoWordModel: exact elderly -> false
    b .Ldock_no_word_bool

.Ldock_no_word_suppression:
    ldr w4, [x13, x16]
    add x4, x4, x28, lsl #32
    dock_ref x4, w4
    ldr x16, [x17, #KH_DOCK_RX_VALUE_FIELD]
    ldr w4, [x4, x16]
    add w16, w22, #0x20
    cmp w4, w16
    b.eq .Ldock_metrics_top      // extended effective helper: suppressed -> false
    add w16, w22, #0x30
    cmp w4, w16
    b.ne .Ldock_original
    ldur x4, [x7, #7]           // native enum raw index, only after suppress=false
    cmp x4, #0
    b.lt .Ldock_original         // malformed enum index; no heap mutation
    cmp x4, #1
    b.hi .Ldock_metrics_top      // extended effective helper: enum index >1 -> false

.Ldock_no_word_bool:
    ldr x16, [x17, #KH_DOCK_NO_WORD_RX_FIELD]
    ldr w4, [x13, x16]
    add x4, x4, x28, lsl #32
    dock_ref x4, w4
    ldr x16, [x17, #KH_DOCK_RX_VALUE_FIELD]
    ldr w4, [x4, x16]
    add w16, w22, #0x30
    cmp w4, w16
    b.eq .Ldock_metrics_top
    add w16, w22, #0x20
    cmp w4, w16
    b.ne .Ldock_original
    cbnz w14, .Ldock_pad_height
    ldr x16, [x17, #KH_DOCK_CELL_HEIGHT_FIELD]
    ldr d21, [x3, x16]
    b .Ldock_centered_top
.Ldock_pad_height:
    ldur d21, [x3, #0x77]        // same layout flag as width; no +20 for height
.Ldock_centered_top:
    dock_finite d21
    fcmp d21, #0.0
    b.lt .Ldock_original
    fsub d21, d21, d19
    fmov d7, #2.0
    fdiv d21, d21, d7
    fmov d7, xzr
    fmax d21, d21, d7            // native max(0, (cellHeight-inkWidth)/2)
    b .Ldock_top_ready
.Ldock_metrics_top:
    ldr x16, [x17, #KH_DOCK_ICON_TOP_FIELD]
    ldr d21, [x6, x16]
.Ldock_top_ready:
    dock_finite d21
    fcmp d21, #0.0
    b.lt .Ldock_original

    // Mirror dock_glass_geometry.h, without moving/rescaling native icons.
    scvtf d16, w2
    scvtf d17, w12
    fmov d18, #2.0
    fsub d20, d2, d19
    fdiv d20, d20, d18            // native per-cell painted inset
    fmov d6, #8.0
    cmp w2, w12
    b.ge .Ldock_distributed
    fmul d7, d16, d2
    fsub d3, d0, d7
    fdiv d3, d3, d18              // x0 = (C-n*w)/2
    fadd d4, d3, d7               // lastRight = x0+n*w
    b .Ldock_envelope
.Ldock_distributed:
    fdiv d7, d0, d17              // slot = C/M
    fsub d3, d7, d2
    fdiv d3, d3, d18              // x0 = (slot-w)/2
    sub w16, w2, #1
    scvtf d16, w16
    fmul d16, d16, d7
    fadd d4, d7, d2
    fdiv d4, d4, d18
    fadd d4, d16, d4              // (n-1)*slot+(slot+w)/2
.Ldock_envelope:
    fadd d3, d3, d20              // first painted left, native cell unchanged
    fsub d4, d4, d20              // last painted right
    dock_finite d3
    dock_finite d4
    fcmp d3, d6
    b.lt .Ldock_original
    fsub d7, d0, d6
    fcmp d4, d7
    b.gt .Ldock_original
    fcmp d4, d3
    b.le .Ldock_original
    fmov d16, #10.0
    fsub d3, d3, d16
    fmax d3, d3, d6               // plate left
    fadd d4, d4, d16
    fmin d4, d4, d7               // plate right
    fsub d5, d4, d3
    fdiv d5, d5, d18              // width/2
    fsub d20, d21, d6
    fmov d7, xzr
    fmax d20, d20, d7            // top = max(0, nativeIconTop-8)
    fadd d21, d21, d19
    dock_finite d21
    fadd d21, d21, d6
    fmin d21, d1, d21            // bottom edge = min(H, iconTop+inkWidth+8)
    fsub d7, d21, d20
    fdiv d7, d7, d18              // actual painted plate height/2
    fmin d5, d5, d7
    fcmp d5, #0.0
    b.le .Ldock_original
    fmov d7, #20.0
    // Public Android rounded-corner radius, normalized to dp Q8 by the parent.
    // No Dart corner-list/units assumptions. Zero/missing/invalid uses native20.
    ldr x16, [x17, #KH_DOCK_CORNER_RADIUS_ADDRESS]
    cbz x16, .Ldock_radius_ready
    ldar w4, [x16]
    cmp w4, #0x10, lsl #12       // protocol range 0..65536 (256dp)
    b.hi .Ldock_radius_ready
    scvtf d7, w4
    mov w16, #256
    scvtf d16, w16
    fdiv d7, d7, d16
    fsub d16, d0, d4
    fmin d16, d3, d16            // nearer horizontal plate inset
    fsub d7, d7, d16
    fmov d16, #20.0
    fmax d7, d7, d16             // retain native minimum softness
.Ldock_radius_ready:
    fmin d5, d5, d7
    fsub d4, d0, d4               // Positioned needs RIGHT INSET, not right edge
    fsub d21, d1, d21             // Positioned also needs BOTTOM INSET

    // Derive the Array CID from the native list, keeping its own type args.
    ldur x3, [x29, #-0x28]
    dock_ref x3, w3
    ldur w6, [x3, #7]
    fmov d22, x6                 // primitive compressed bits; no safepoint here
    ldur w3, [x3, #0xf]
    add x3, x3, x28, lsl #32
    dock_ref x3, w3
    ldur x7, [x3, #-1]
    ubfx x7, x7, #12, #20
    lsl x7, x7, #12
    mov x16, #0x21c              // fresh 32-byte Array, native young-space flags
    orr x7, x7, x16
    fmov d23, x7

    // Reserve the WHOLE arena before any heap/thread/stack mutation. 288 bytes
    // plus material size: Doubles96 + IgnorePointer32 + Positioned48 + Array32 +
    // Stack32 + SizedBox32 + original ItemBackground16, reserved even when
    // omitted. Exact-end is pressure too, matching the native bump allocator.
    ldp x9, x10, [x26, #0x60]
    add x12, x11, #288
    adds x12, x9, x12
    b.cs .Ldock_original
    cmp x12, x10
    b.hs .Ldock_original
    // No conditional fallback, call or safepoint after this publication.
    str x12, [x26, #0x60]
    mov x13, x9
.Ldock_initialize:
    stp w22, w22, [x13], #8      // initialize EVERY field and padding word
    cmp x13, x12
    b.lo .Ldock_initialize

    ldr x16, [x17, #KH_DOCK_DOUBLE_TAG]
    str x16, [x9]
    str d0, [x9, #8]             // Double C
    str x16, [x9, #16]
    str d1, [x9, #24]            // Double H
    str x16, [x9, #32]
    str d3, [x9, #40]            // Double left
    str x16, [x9, #48]
    str d4, [x9, #56]            // Double right inset
    str x16, [x9, #64]
    str d20, [x9, #72]           // Double top inset
    str x16, [x9, #80]
    str d21, [x9, #88]           // separate Double bottom inset
    add x2, x9, #1
    add x3, x9, #17
    add x4, x9, #33
    add x5, x9, #49
    add x6, x9, #65
    add x7, x9, #97              // material tagged pointer
    add x9, x9, #81              // bottom inset tagged pointer
    add x12, x7, x11             // IgnorePointer
    add x12, x12, #32            // Positioned
    add x13, x12, #48            // Array
    add x14, x13, #32            // Stack
    add x0, x14, #32             // SizedBox = result
    add x10, x0, #32             // optional native ItemBackground

    // All four primitives share radius/config/opacity. Only Glass has +1f.
    // x11 retains this render's selected size even if the global mode changes.
    fmov x16, d24
    stur x16, [x7, #-1]
    stur d5, [x7, #0xb]          // raw radius
    ldr x16, [x17, #KH_DOCK_MATERIAL_CONFIG_PP]
    ldr x16, [x27, x16]
    stur w16, [x7, #0x13]
    fmov d7, #1.0
    stur d7, [x7, #0x17]         // raw opacity
    cmp x11, #48
    b.ne .Ldock_material_ready
    stur xzr, [x7, #0x1f]        // Glass48 ONLY; out of bounds for other modes
.Ldock_material_ready:
    // Only the decorative material ignores input. Original ItemBackground
    // stays a separate Stack child, preserving its native feedback behavior.
    sub x11, x12, #32            // material size is no longer live: wrapper ptr
    ldr x16, [x17, #KH_DOCK_IGNORE_POINTER_TAG]
    stur x16, [x11, #-1]
    stur w7, [x11, #0xb]         // native SingleChild child
    add w16, w22, #0x20
    stur w16, [x11, #0xf]        // canonical true; key and remaining slots null

    ldr x16, [x17, #KH_DOCK_POSITIONED_TAG]
    stur x16, [x12, #-1]
    stur w11, [x12, #0xb]        // IgnorePointer child; key+7/typeargs+f null
    stur w4, [x12, #0x13]
    stur w6, [x12, #0x17]
    stur w5, [x12, #0x1b]
    stur w9, [x12, #0x1f]        // width+23/height+27 remain null

    fmov x16, d23
    stur x16, [x13, #-1]
    fmov x16, d22
    stur w16, [x13, #7]          // native GrowableList's type args
    add w16, w8, #1
    lsl w16, w16, #1
    stur w16, [x13, #0xb]        // Smi length 1 or 2
    stur w12, [x13, #0xf]        // glass FIRST
    cbz x8, .Ldock_no_native_child
    stur w10, [x13, #0x13]       // key+7 remains null; original gate true ONLY
.Ldock_no_native_child:
    // Even the omitted reserved object has a valid initialized header.
    ldr x16, [x17, #KH_DOCK_BACKGROUND_TAG]
    stur x16, [x10, #-1]
    ldr x16, [x17, #KH_DOCK_STACK_TAG]
    stur x16, [x14, #-1]
    stur w13, [x14, #0xb]
    ldr x16, [x17, #KH_DOCK_ALIGNMENT_PP]
    ldr x16, [x27, x16]
    stur w16, [x14, #0xf]        // textDirection+13 remains null
    ldr x16, [x17, #KH_DOCK_FIT_PP]
    ldr x16, [x27, x16]
    stur w16, [x14, #0x17]
    ldr x16, [x17, #KH_DOCK_CLIP_PP]
    ldr x16, [x27, x16]
    stur w16, [x14, #0x1b]

    ldr x16, [x17, #KH_DOCK_SIZED_BOX_TAG]
    stur x16, [x0, #-1]
    stur w14, [x0, #0xb]
    stur w2, [x0, #0xf]          // native C
    stur w3, [x0, #0x13]         // native H
    ret                         // unchanged original 117d29c LR/stack map

.Ldock_original:
    // x1, x15, x18-x30 and v8-v15 are still EXACT original values.
    ldr x16, [x17, #KH_DOCK_ORIGINAL_BACKGROUND]
    br x16

    .purgem dock_ref
    .purgem dock_finite
    .balign 8
kh_dock_config:
    .zero KH_DOCK_CONFIG_SIZE

// Other component's independent config/entries stay within the copied range.
#include "dock_glass_refresh.inc"
kh_dock_end:
    .section .note.GNU-stack,"",%progbits

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

// Native parent Stack clip store. Its next instruction reloads x1 from the
// original frame; only that dead register is used. No flags, other registers,
// root slots or allocation state change. Keep its stock clip while disabled.
    .global kh_dock_parent_clip
    .global kh_dock_parent_cid
    .hidden kh_dock_parent_clip
    .hidden kh_dock_parent_cid
kh_dock_parent_clip:
    hint #38
    adr x1, kh_dock_config
    ldr x1, [x1, #KH_DOCK_ENABLED]
    ldar w1, [x1]
    sub w1, w1, #1
    cbnz w1, .Ldock_parent_stock_clip
    // The Stack has not stored children yet. Its proven fp-40 root owns the
    // completed GrowableList: only the module's painted plate is a SizedBox.
    ldur x1, [x29, #-40]
    ldur w1, [x1, #0xb]
    cbz w1, .Ldock_parent_stock_clip
    ldur x1, [x29, #-40]
    ldur w1, [x1, #0xf]
    add x1, x1, x28, lsl #32
    ldur w1, [x1, #0xf]
    add x1, x1, x28, lsl #32
    ldur w1, [x1, #-1]
    ubfx w1, w1, #12, #20
kh_dock_parent_cid:
    // Installer XORs the resolved CID with at most ten logical immediates.
    // EOR/CBNZ preserve NZCV and use the sole proven-dead register.
    .rept 10
    nop
    .endr
    cbnz w1, .Ldock_parent_stock_clip
    adr x1, kh_dock_config
    ldr x1, [x1, #KH_DOCK_CLIP_PP]
    b .Ldock_parent_write_clip
.Ldock_parent_stock_clip:
    adr x1, kh_dock_config
    ldr x1, [x1, #KH_DOCK_NATIVE_CLIP_PP]
.Ldock_parent_write_clip:
    ldr x1, [x27, x1]
    stur w1, [x0, #0x1b]
    adr x1, kh_dock_config
    ldr x1, [x1, #KH_DOCK_PARENT_CLIP_RESUME]
    br x1

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
    ldr x16, [x17, #KH_DOCK_CORNER_STYLE_ADDRESS]
    ldar w16, [x16]
    fmov d26, x16                // one immutable packed geometry snapshot
    and w16, w16, #7
    cmp w16, #4
    b.hi .Ldock_original
    fmov d25, x16                // snapshot style; primitive, no managed root
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
    b.ne .Ldock_material_selected
    fmov x16, d25
    cmp w16, #1
    b.ne .Ldock_material_selected
    // A genuine native ValueKey scopes clip adaptation to this owned glass.
    // Material class, filter parameters and opacity remain the original ones.
    mov x11, #64                // Glass48 + ValueKey<dynamic>16
.Ldock_material_selected:

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
    cmp w2, #5
    b.ne .Ldock_ink_edge_ready
    cmp w12, #5
    b.ne .Ldock_ink_edge_ready
    fmov d6, xzr                 // full five-slot plate: require ink containment
.Ldock_ink_edge_ready:
    fcmp d3, d6
    b.lt .Ldock_original
    fsub d7, d0, d6
    fcmp d4, d7
    b.gt .Ldock_original
    fcmp d4, d3
    b.le .Ldock_original
    cmp w2,#5
    b.ne .Ldock_plate_edges
    fmov d6,xzr                 // five items: use full native content width
    fmov d7,d0
.Ldock_plate_edges:
    fmov d28, d3
    fsub d29, d0, d4             // native painted side limits, before padding
    fmov d16, #18.0
    fsub d3, d3, d16
    fmax d3, d3, d6               // plate left
    fadd d4, d4, d16
    fmin d4, d4, d7               // plate right
    fsub d5, d4, d3
    fdiv d5, d5, d18              // width/2
    fmov x16, d26
    ubfx w16, w16, #3, #6
    cbz w16, .Ldock_default_vertical_padding
    sub w16, w16, #1
    scvtf d16, w16
    b .Ldock_vertical_padding_ready
.Ldock_default_vertical_padding:
    fmov d16, #18.0
.Ldock_vertical_padding_ready:
    fadd d22, d21, d19           // native painted bottom
    dock_finite d22
    fmov d7, #12.0
    fadd d20, d21, d7           // room above the painted icon
    fmin d16, d16, d20
    fadd d7, d1, d7
    fsub d7, d7, d22            // room below the painted icon
    fcmp d7, #0.0
    b.lt .Ldock_original        // do not draw a plate containing only part of an icon
    fmin d16, d16, d7           // use the tighter edge's padding on both sides
    fsub d20, d21, d16
    fadd d21, d22, d16
    fsub d7, d21, d20
    fdiv d7, d7, d18              // actual painted plate height/2
    fmin d5, d5, d7
    fcmp d5, #0.0
    b.le .Ldock_original
    fmov x16, d25
    cmp w16, #3
    b.ne .Ldock_concentric_edges_ready
    cmp w2, #5
    b.eq .Ldock_concentric_edges_ready // keep five-icon edge padding
    ldr x16, [x17, #KH_DOCK_CORNER_RADIUS_ADDRESS]
    ldar w4, [x16]
    cbz w4, .Ldock_concentric_edges_ready
    scvtf d27, w4
    mov w16, #256
    scvtf d7, w16
    fdiv d27, d27, d7
    fsub d7, d21, d20
    fmov d18, #2.0
    fdiv d7, d7, d18
    fsub d27, d27, d7
    fmov d16, xzr
    fmax d27, d27, d16
    fmin d27, d27, d28
    fmin d3, d27, d29
    fsub d4, d0, d3
    fsub d5, d4, d3
    fdiv d5, d5, d18
    fmin d5, d5, d7
.Ldock_concentric_edges_ready:
    // Adjust only the decoration. Apply the same delta on both sides; the
    // native content edges and the outermost painted icons bound the result.
    fmov x16, d26
    sbfx w16, w16, #24, #6
    cbz w16, .Ldock_horizontal_padding_ready
    scvtf d16, w16
    fsub d6, d28, d3             // available inner space at the left
    fsub d7, d0, d4
    fsub d18, d29, d7            // available inner space at the right
    fmin d6, d6, d18
    fmin d7, d3, d7              // available outer space
    fmov d18, xzr
    fmax d6, d6, d18
    fmax d7, d7, d18
    fneg d6, d6
    fmax d16, d16, d6
    fmin d16, d16, d7
    fsub d3, d3, d16
    fadd d4, d4, d16
    fsub d5, d4, d3
    fsub d7, d21, d20
    fmin d5, d5, d7
    fmov d18, #2.0
    fdiv d5, d5, d18
.Ldock_horizontal_padding_ready:
    fmov d7, #28.0
    fmov x16, d25
    cmp w16, #2
    b.eq .Ldock_pad_radius
    cmp w16, #4
    b.eq .Ldock_custom_radius
    cmp w16, #1
    b.eq .Ldock_recents_radius
    // Public Android rounded-corner radius, normalized to dp Q8 by the parent.
    // No Dart corner-list/units assumptions. Zero/missing/invalid uses bounded28.
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
    fmov x16, d25
    cmp w16, #3
    b.eq .Ldock_concentric_radius
    fmov d18,#8.0
    fmax d16,d16,d18            // preserve existing rounded-radius policy
    fsub d7, d7, d16
    fmov d16, #28.0
    fmax d7, d7, d16             // retain native minimum softness
    b .Ldock_radius_ready
.Ldock_concentric_radius:
    fsub d7, d7, d16
    fmov d16, xzr
    fmax d7, d7, d16
    b .Ldock_radius_ready
.Ldock_custom_radius:
    fmov x16, d26
    ubfx w16, w16, #9, #7
    scvtf d7, w16
    b .Ldock_radius_ready
.Ldock_pad_radius:
    fmov d16, #16.0
    mov w16, #60
    scvtf d18, w16
    fdiv d16, d16, d18
    fmul d7, d19, d16
    fsub d16, d21, d20
    fsub d16, d16, d19
    fmov d18, #2.0
    fdiv d16, d16, d18
    fadd d7, d7, d16
    fmov d16, xzr
    fmax d7, d7, d16
    b .Ldock_radius_ready
.Ldock_recents_radius:
    // Observe the real native task-card getter result; never substitute the
    // Dock's much wider aspect ratio into the task-card scaling formula.
    ldr d7, [x17, #KH_DOCK_RECENTS_DEFAULT_BITS]
    ldr x16, [x17, #KH_DOCK_RECENTS_RADIUS_ADDRESS]
    cbz x16, .Ldock_radius_ready
    ldar x16, [x16]
    fmov d16, x16
    ubfx x16, x16, #52, #11
    cmp x16, #0x7ff
    b.eq .Ldock_radius_ready
    fcmp d16, #0.0
    b.le .Ldock_radius_ready
    mov w16, #256
    scvtf d18, w16
    fcmp d16, d18
    b.gt .Ldock_radius_ready
    fmov d7, d16
.Ldock_radius_ready:
    cmp w2,#5
    b.ne .Ldock_radius_cap
    mov w16,#32
    scvtf d18,w16              // protect outermost icon corners in full Dock
    fmin d7,d7,d18
.Ldock_radius_cap:
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

    // Reserve the WHOLE arena before any heap/thread/stack mutation. 336 bytes
    // plus material size: Doubles96 + IgnorePointer32 + Unlock48 + Positioned48 + Array32 +
    // Stack32 + SizedBox32 + original ItemBackground16, reserved even when
    // omitted. Exact-end is pressure too, matching the native bump allocator.
    ldp x9, x10, [x26, #0x60]
    add x12, x11, #336
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
    add x12, x12, #80            // Unlock48 then Positioned
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
    cmp x11, #32
    b.eq .Ldock_material_ready
    stur xzr, [x7, #0x1f]        // Glass48 ONLY; out of bounds for other modes
    cmp x11, #64
    b.ne .Ldock_material_ready
    add x16, x7, #48
    stur w16, [x7, #7]          // Key belongs only to this injected material
    ldr x4, [x17, #KH_DOCK_VALUE_KEY_TAG]
    stur x4, [x16, #-1]
    mov w4, #(KH_DOCK_OWNED_KEY_VALUE & 0xffff)
    movk w4, #(KH_DOCK_OWNED_KEY_VALUE >> 16), lsl #16
    stur w4, [x16, #0xb]        // Smi marker; type arguments remain native null
    sub x4, x7, #64             // restore left Double reference
.Ldock_material_ready:
    // Only the decorative material ignores input. Original ItemBackground
    // stays a separate Stack child, preserving its native feedback behavior.
    sub x11, x12, #80            // material size is no longer live: wrapper ptr
    ldr x16, [x17, #KH_DOCK_IGNORE_POINTER_TAG]
    stur x16, [x11, #-1]
    stur w7, [x11, #0xb]         // native SingleChild child
    add w16, w22, #0x20
    stur w16, [x11, #0xf]        // canonical true; key and remaining slots null

    // One native UnlockAnimWidget, scoped to decoration. It joins the existing
    // unlock controller; no extra managed call, timer, frame or retained root.
    sub x11, x12, #48
    ldr x16, [x17, #KH_DOCK_UNLOCK_TAG]
    stur x16, [x11, #-1]
    sub x16, x12, #80
    stur w16, [x11, #0xb]        // original IgnorePointer
    ldr x16, [x17, #KH_DOCK_UNLOCK_ZERO_PP]
    ldr x16, [x27, x16]
    stur w16, [x11, #0xf]        // canonical Offset.zero seed
    mov x16, #3
    stur x16, [x11, #0x17]       // source-owned search animation category
    mov x16, #-1
    stur x16, [x11, #0x1f]       // source-owned global screen identity
    add w16, w22, #0x30
    stur w16, [x11, #0x27]       // native fallback resolves live center
    ldr x16, [x17, #KH_DOCK_UNLOCK_DEBUG_PP]
    ldr x16, [x27, x16]
    stur w16, [x11, #0x2b]

    ldr x16, [x17, #KH_DOCK_POSITIONED_TAG]
    stur x16, [x12, #-1]
    stur w11, [x12, #0xb]        // animated decoration; key+7/typeargs+f null
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

// Original native total-bottom-margin getter RET. Moving its result moves the
// actual Dock container, including icon transforms and hit testing.
    .global kh_dock_bottom_margin
    .hidden kh_dock_bottom_margin
kh_dock_bottom_margin:
    hint #38
    adr x17, kh_dock_config
    ldr x16, [x17, #KH_DOCK_ENABLED]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Ldock_bottom_return
    ldr x16, [x17, #KH_DOCK_CORNER_STYLE_ADDRESS]
    ldar w16, [x16]
    sbfx w16, w16, #16, #8
    cbz w16, .Ldock_bottom_return
    scvtf d16, w16
    fsub d0, d0, d16
    // Stay within the native parent's bottom boundary. Do not invent a
    // navigation-bar inset; stock device/inset policy stays upstream.
    fmov d16, xzr
    fmax d0, d0, d16
.Ldock_bottom_return:
    ret

.Ldock_original:
    // x1, x15, x18-x30 and v8-v15 are still EXACT original values.
    ldr x16, [x17, #KH_DOCK_ORIGINAL_BACKGROUND]
    br x16

// SPDX-License-Identifier: Apache-2.0
// Owned material scoping. No new managed/native call, VM frame or C++ root.
    .global kh_dock_glass_scope,kh_dock_glass_child_scope,kh_dock_glass_clip
    .hidden kh_dock_glass_scope,kh_dock_glass_child_scope,kh_dock_glass_clip
// x16 holds a Key reference. Retain it in d17 while checking its class/value.
// Flags and x16/x17 are dead at all three authenticated native boundaries.
    .macro dock_scope_count offset
    adr x16,kh_dock_config
    ldr x16,[x16,#KH_DOCK_SCOPE_COUNTS_ADDRESS]
    add x16,x16,#\offset
    ldar x17,[x16]
    add x17,x17,#1
    stlr x17,[x16]
    .endm
    .macro dock_owned_key fallback
    cmp w16,w22
    b.eq \fallback
    tbz x16,#0,\fallback
    fmov d17,x16
    ldur w16,[x16,#-1]
    ubfx w16,w16,#12,#20
    adr x17,kh_dock_config
    ldr x17,[x17,#KH_DOCK_VALUE_KEY_TAG]
    lsr x17,x17,#12
    cmp w16,w17
    b.ne \fallback
    fmov x16,d17
    ldur w16,[x16,#0xb]
    mov w17,#(KH_DOCK_OWNED_KEY_VALUE & 0xffff)
    movk w17,#(KH_DOCK_OWNED_KEY_VALUE >> 16),lsl #16
    cmp w16,w17
    b.ne \fallback
    .endm

// Capture ownership while the original receiver is live, immediately after
// its native radius load. Carry only a Smi in the future child-root slot; the
// original graph does not read this slot until its first child-root store.
kh_dock_glass_scope:
    hint #38
    adr x17,kh_dock_config
    ldr x16,[x17,#KH_DOCK_GLASS_SCOPE_SLOT]
    ldr x16,[x29,x16]
    ldur w16,[x16,#7]
    add x16,x16,x28,lsl #32
    dock_owned_key .Lglass_scope_stock
    dock_scope_count 0
    mov w16,#(KH_DOCK_OWNED_KEY_VALUE & 0xffff)
    movk w16,#(KH_DOCK_OWNED_KEY_VALUE >> 16),lsl #16
    b .Lglass_scope_store
.Lglass_scope_stock:
    mov x16,#0
.Lglass_scope_store:
    adr x17,kh_dock_config
    ldr x17,[x17,#KH_DOCK_GLASS_CHILD_SLOT]
    str x16,[x29,x17]
    adr x17,kh_dock_config
    ldr x17,[x17,#KH_DOCK_GLASS_RADIUS_SLOT]
    str d0,[x29,x17]
    adr x17,kh_dock_config
    cbz x16,.Lglass_scope_log_stock
    // Only owned Recents decoration: skip native diagnostic formatting.
    // Original raw radius/receiver/context roots and all visual work survive.
    ldr x17,[x17,#KH_DOCK_GLASS_VISUAL_RESUME]
    br x17
.Lglass_scope_log_stock:
    ldr x17,[x17,#KH_DOCK_GLASS_SCOPE_RESUME]
    br x17

// The native allocator returned a fresh/remembered DockGlassWidget in x0.
// Transfer only the Smi ownership token into a genuine native Key. All original
// native scope Bools and original glass-filter parameters stay untouched.
kh_dock_glass_child_scope:
    hint #38
    adr x17,kh_dock_config
    ldr x16,[x17,#KH_DOCK_GLASS_CHILD_SLOT]
    ldr x16,[x29,x16]
    mov w17,#(KH_DOCK_OWNED_KEY_VALUE & 0xffff)
    movk w17,#(KH_DOCK_OWNED_KEY_VALUE >> 16),lsl #16
    cmp x16,x17
    b.ne .Lglass_child_stock
    adr x17,kh_dock_config
    ldr x16,[x17,#KH_DOCK_CURVE_ENABLED_ADDRESS]
    ldar w16,[x16]
    cmp w16,#1
    b.ne .Lglass_child_stock
    ldr x16,[x17,#KH_DOCK_VALUE_KEY_TAG]
    fmov d18,x16
    ldp x16,x17,[x26,#0x60]
    adds x16,x16,#16
    b.cs .Lglass_child_stock
    cmp x16,x17
    b.hs .Lglass_child_stock
    str x16,[x26,#0x60]
    sub x16,x16,#16
    stp w22,w22,[x16]
    stp w22,w22,[x16,#8]
    fmov x17,d18
    str x17,[x16]
    mov w17,#(KH_DOCK_OWNED_KEY_VALUE & 0xffff)
    movk w17,#(KH_DOCK_OWNED_KEY_VALUE >> 16),lsl #16
    str w17,[x16,#12]
    add x16,x16,#1
    stur w16,[x0,#7]
    dock_scope_count 8
.Lglass_child_stock:
    adr x17,kh_dock_config
    ldr x16,[x17,#KH_DOCK_GLASS_CHILD_SLOT]
    str x0,[x29,x16]
    ldr x17,[x17,#KH_DOCK_CHILD_SCOPE_RESUME]
    br x17

// Preserve the exact original allocator return PC, registers and stack map.
// Native allocation handles its own pressure/GC paths for either widget class.
kh_dock_glass_clip:
    hint #38
    adr x17,kh_dock_config
    ldr x16,[x17,#KH_DOCK_CURVE_ENABLED_ADDRESS]
    ldar w16,[x16]
    cmp w16,#1
    b.ne .Lglass_clip_stock
    ldr x16,[x17,#KH_DOCK_GLASS_CHILD_SLOT]
    ldr x16,[x29,x16]
    ldur w16,[x16,#7]
    add x16,x16,x28,lsl #32
    dock_owned_key .Lglass_clip_stock
    dock_scope_count 16
    adr x17,kh_dock_config
    ldr x16,[x17,#KH_DOCK_GLASS_SUPER_ALLOC]
    br x16
.Lglass_clip_stock:
    adr x17,kh_dock_config
    ldr x16,[x17,#KH_DOCK_GLASS_RRECT_ALLOC]
    br x16
// Observe native task-radius returns without calling a controller or changing
// any native caller result/frame. Only unboxed numeric bits leave managed code.
    .global kh_dock_recents_radius
    .hidden kh_dock_recents_radius
kh_dock_recents_radius:
    hint #38
    adr x16,kh_dock_config
    ldr x16,[x16,#KH_DOCK_RECENTS_RADIUS_ADDRESS]
    fmov x17,d0
    stlr x17,[x16]
    ret
// Observe the inlined native task-radius join before any flight-size scaling.
// Replay the original frame load. D3, all other registers and flags survive.
    .global kh_dock_recents_flight_radius
    .hidden kh_dock_recents_flight_radius
kh_dock_recents_flight_radius:
    hint #38
    adr x16,kh_dock_config
    ldr x16,[x16,#KH_DOCK_RECENTS_RADIUS_ADDRESS]
    fmov x17,d3
    stlr x17,[x16]
    adr x16,kh_dock_config
    ldr x17,[x16,#KH_DOCK_FLIGHT_RADIUS_SLOT]
    ldr d0,[x29,x17]
    ldr x16,[x16,#KH_DOCK_FLIGHT_RADIUS_RESUME]
    br x16
    .purgem dock_scope_count
    .purgem dock_owned_key

    .purgem dock_ref
    .purgem dock_finite
    .balign 8
kh_dock_config:
    .zero KH_DOCK_CONFIG_SIZE

// Other component's independent config/entries stay within the copied range.
#include "dock_glass_refresh.inc"
kh_dock_end:
    .section .note.GNU-stack,"",%progbits

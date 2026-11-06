// SPDX-License-Identifier: Apache-2.0
// These bridges never call C or retain a Dart reference outside original roots.
// Additional Dart calls return to authenticated ORIGINAL mapped native PCs.
// Existing live stack-map roots retain tagged objects throughout the calls.
// Marquee construction (including write barriers, curves and its State) remains
// entirely in original Dart code with original safepoints/exception handling.
    .section .rodata.launcher_label_bridge,"a",%progbits
    .balign 8
    .global kh_label_template, kh_label_pre, kh_label_post, kh_label_lines
    .global kh_label_config, kh_label_end, kh_label_refresh, kh_label_page_post, kh_label_page_replay
    .global kh_label_fade, kh_label_cache_post, kh_label_factory_one, kh_label_factory_two
    .hidden kh_label_template, kh_label_pre, kh_label_post, kh_label_lines
    .hidden kh_label_config, kh_label_end, kh_label_refresh, kh_label_page_post, kh_label_page_replay
    .hidden kh_label_fade, kh_label_cache_post, kh_label_factory_one, kh_label_factory_two
kh_label_template:
kh_label_pre:
    hint #38
    adr x17, kh_label_config
    ldr x16, [x17]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Lallocate_text
.Lmeasure_text:
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
    add x9, x22, #0x20
    cmp x0, x9
    b.eq .Ltitle_editor_return
    add x9, x22, #0x30
    cmp x0, x9
    b.eq .Ltitle_editor_return
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
    b.ne .Lprefix_result
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
    // Native title/shadow layout uses both widget and grid widths. Text can use
    // the wider title envelope; the smaller icon box must not force scrolling.
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
    fmov x10, d2
    ubfx x10, x10, #52, #11
    cmp x10, #0x7ff
    b.eq .Lallocate_text
    fcmp d2, #0.0
    b.le .Lallocate_text
    b.vs .Lallocate_text
    ldr x9, [x26, #0x78]
    ldr x10, [x17, #72]
    ldr x9, [x9, x10]
    cmp w9, w22
    b.eq .Lallocate_text
    ldr x10, [x27, #0x40]
    cmp w9, w10
    b.eq .Lallocate_text
    ldr x10, [x17, #80]
    ldr w9, [x9, x10]
    add x9, x9, x28, lsl #32
    ldr x10, [x17, #88]
    ldr w9, [x9, x10]
    add x9, x9, x28, lsl #32
    ldr x10, [x17, #96]
    ldr d1, [x9, x10]
    fmov x10, d1
    ubfx x10, x10, #52, #11
    cmp x10, #0x7ff
    b.eq .Lallocate_text
    fcmp d1, #0.0
    b.le .Lallocate_text
    b.vs .Lallocate_text
    fmin d3, d1, d2
    fcmp d0, d3
    b.le .Lallocate_text
    b.vs .Lallocate_text
    fmax d3, d1, d2
    fcmp d0, d3
    cset x13, le                  // intermediate width: ask native prefix policy
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
    stur w5, [x1, #0xb]           // preserve String through an optional prefix call
    // This was a live String root at the original allocation call. Until the
    // constructor returns it roots Marquee instead, so relocating GC updates it.
    str x1, [x29, x10]
    cbz x13, .Lconstruct_marquee
    ldr x10, [x17, #248]
    ldr x1, [x29, x10]
    ldr x16, [x17, #256]
    br x16                        // original native prefix getter; same return PC
.Lprefix_result:
    ldr x10, [x17, #128]
    ldr x1, [x29, x10]
    ldur x9, [x1, #-1]
    ubfx x9, x9, #12, #20
    ldr x10, [x17, #64]
    lsr x10, x10, #12
    cmp x9, x10
    b.ne .Lallocate_text
    ldr x16, [x17]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Lprefix_static
    ldur w9, [x0, #7]             // same String-length ABI as the native consumer
    cbnz w9, .Lconstruct_marquee   // native badge consumes the narrower budget
.Lprefix_static:
    ldur w9, [x1, #0xb]
    add x9, x9, x28, lsl #32
    ldr x10, [x17, #128]
    str x9, [x29, x10]
    b .Lallocate_text
.Lconstruct_marquee:
    ldr x10, [x17, #128]
    ldr x1, [x29, x10]
    ldur w5, [x1, #0xb]
    add x5, x5, x28, lsl #32
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
    // Only the title is muted. Checkbox, icon and drag animations stay native.
    ldr x1, [x26, #0x78]
    ldr x16, [x17, #296]
    ldr x1, [x1, x16]
    cmp w1, w22
    b.eq .Lfinish_marquee
    ldr x16, [x27, #0x40]
    cmp w1, w16
    b.eq .Lfinish_marquee
    ldp x11, x12, [x26, #0x60]
    add x13, x11, #32
    cmp x12, x13
    b.ls .Lfinish_marquee
    str x13, [x26, #0x60]
    ldr x12, [x17, #192]
    str x12, [x11]
    stp w22, w22, [x11, #8]
    stp w22, w22, [x11, #16]
    stp w22, w22, [x11, #24]
    add x11, x11, #1
    stur w2, [x11, #7]       // String root, restored after the native query
    stur w0, [x11, #15]      // completed native Marquee retains its own fields
    ldr x10, [x17, #128]
    str x11, [x29, x10]
    ldr x16, [x17, #288]
    br x16                   // original Text allocation return PC is unchanged
.Ltitle_editor_return:
    ldr x10, [x17, #128]
    ldr x9, [x29, x10]
    ldur w2, [x9, #7]
    add x2, x2, x28, lsl #32
    str x2, [x29, x10]
    add x11, x22, #0x20
    cmp x0, x11
    add x11, x22, #0x30
    add x12, x22, #0x20
    csel x11, x11, x12, eq
    stur w11, [x9, #11]
    stur w22, [x9, #7]
    mov x0, x9
.Lfinish_marquee:
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

// Enter through an existing UI item-change log call. The native synchronous
// font refresh ignores incoming arguments, clears the widget cache and notifies
// the existing title IDs. Its own frame/GC metadata and original caller PC stay
// intact. Other isolates, unchanged heartbeats and ordinary events keep logging.
kh_label_refresh:
    hint #38
    adr x17, kh_label_config
    ldr x16, [x17, #176]
    ldar x16, [x16]
    ldr x17, [x17, #184]
    ldar x17, [x17]
    cmp x16, x17
    b.eq .Lrefresh_original
    adr x17, kh_label_config
    ldr x16, [x17, #72]
    ldr x17, [x26, #0x78]
    ldr x16, [x17, x16]
    cmp w16, w22
    b.eq .Lrefresh_original
    ldr x17, [x27, #0x40]
    cmp w16, w17
    b.eq .Lrefresh_original
    adr x17, kh_label_config
    ldr x16, [x17, #176]
    ldar x16, [x16]
    ldr x17, [x17, #184]
    stlr x16, [x17]
    adr x17, kh_label_config
    ldr x16, [x17, #168]
    br x16
.Lrefresh_original:
    adr x17, kh_label_config
    ldr x16, [x17, #160]
    br x16

// CellLayout's original final Stack allocator has returned. Add the resident
// TickerMode around the page using native grid visibility outside editing.
// Editing must keep source-page tickers alive: native drag completion can await
// an animation owned by that page after the pointer crosses to another page.
// Its return PC is the ORIGINAL allocation PC, retaining the original stack map.
// The live child-list root temporarily owns TickerMode; its temporary key roots
// the list and its child roots Stack. No reference leaves Dart.
kh_label_page_post:
    hint #38
    adr x17, kh_label_config
    ldr x10, [x17, #208]
    ldr x9, [x29, x10]
    ldur x11, [x9, #-1]
    ubfx x11, x11, #12, #20
    ldr x12, [x17, #192]
    lsr x12, x12, #12
    cmp x11, x12
    b.eq .Lpage_visible_return
    b kh_label_page_replay
kh_label_page_replay:
    .zero 44                     // original initialization, before ANY extra GC
    // Stack's original raw reference stores assume a fresh/remembered result.
    // Complete them before the visibility call, preserving that VM guarantee.
    ldp x11, x12, [x26, #0x60]
    add x13, x11, #0x20
    cmp x12, x13
    b.ls .Lpage_resume             // pressure: preserve the initialized Stack
    str x13, [x26, #0x60]
    ldr x12, [x17, #192]
    str x12, [x11]
    stp w22, w22, [x11, #8]
    stp w22, w22, [x11, #16]
    stp w22, w22, [x11, #24]
    add x11, x11, #1
    stur w9, [x11, #7]            // temporary key: original live child list
    stur w0, [x11, #15]           // child: original native build result
    str x11, [x29, x10]
    // The authenticated original list/Obx/closure construction retains CellLayout.
    ldur w1, [x9, #15]            // backing array
    add x1, x1, x28, lsl #32
    ldur w1, [x1, #15]            // first child: grid/background Obx
    add x1, x1, x28, lsl #32
    ldur w1, [x1, #11]            // builder closure
    add x1, x1, x28, lsl #32
    ldur w1, [x1, #23]            // captured context
    add x1, x1, x28, lsl #32
    ldur w1, [x1, #15]            // original CellLayout widget
    add x1, x1, x28, lsl #32
    ldr x10, [x17, #224]
    ldr w1, [x1, x10]            // native CellLayoutGetxController
    add x1, x1, x28, lsl #32
    ldr x16, [x17, #200]
    br x16
.Lpage_visible_return:
    ldur w11, [x9, #11]
    tbz w11, #0, .Lpage_publish   // unpublished Smi: editing query has returned
    add x11, x22, #0x20
    cmp x0, x11
    b.eq .Lpage_publish           // visible page needs no additional query
    ldr x1, [x26, #0x78]
    ldr x16, [x17, #296]
    ldr x1, [x1, x16]
    cmp w1, w22
    b.eq .Lpage_enable
    ldr x16, [x27, #0x40]
    cmp w1, w16
    b.eq .Lpage_enable
    stur wzr, [x9, #11]          // safe Smi phase marker, never published
    ldr x16, [x17, #288]
    br x16                       // same original return PC and live list root
.Lpage_enable:
    add x0, x22, #0x20           // unknown editing state: retain native tickers
.Lpage_publish:
    stur w0, [x9, #11]            // canonical boolean: enabled
    ldur w11, [x9, #7]
    add x11, x11, x28, lsl #32
    str x11, [x29, x10]           // restore original child-list root after GC
    stur w22, [x9, #7]            // publish a normal null key
    mov x0, x9
.Lpage_resume:
    ldr x16, [x17, #240]
    br x16

// The native Marquee wraps even zero-length fading in a ShaderMask plus scroll
// listeners. Return its existing ListView only when both fractions are zero.
// Nonzero/invalid fractions and other modes keep the original Dart callee/PC.
kh_label_fade:
    hint #38
    adr x17, kh_label_config
    ldr x16, [x17]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Lfade_original
    ldur w9, [x1, #0xb]
    add x9, x9, x28, lsl #32
    cmp w9, w22
    b.eq .Lfade_original
    ldr x10, [x17, #272]
    ldr d0, [x9, x10]
    fcmp d0, #0.0
    b.ne .Lfade_original
    ldr x10, [x17, #280]
    ldr d1, [x9, x10]
    fcmp d1, #0.0
    b.ne .Lfade_original
    mov x0, x2
    ret
.Lfade_original:
    ldr x16, [x17, #264]
    br x16

// Cached/cold returns root immutable native copies in an unpublished shell while
// the editing getter subscribes the reactive parent. Only the title publishes
// TickerMode; checkbox/icon/drag animations stay native. No new VM frame/map.
kh_label_factory_one:
    hint #38
    adr x17, kh_label_config
    ldr x8, [x17, #336]
    b .Lfactory_entry
kh_label_factory_two:
    hint #38
    adr x17, kh_label_config
    ldr x8, [x17, #344]
.Lfactory_entry:
    add x7, x8, #8
    ldr x10, [x17, #352]
    ldr x9, [x29, x10]
    b .Lcache_check_phase
kh_label_cache_post:
    hint #38
    adr x17, kh_label_config
    ldr x8, [x17, #304]
    add x7, x8, #16
    ldr x10, [x17, #312]
    ldr x9, [x29, x10]
.Lcache_check_phase:
    ldur x11, [x9, #-1]
    ubfx x11, x11, #12, #20
    ldr x12, [x17, #192]
    lsr x12, x12, #12
    cmp x11, x12
    b.eq .Lcache_editor_return
    ldr x12, [x17, #328]
    lsr x12, x12, #12
    cmp x11, x12
    mov x0, x9
    b.eq .Lcache_have_drag_root
    ldur w0, [x9, #11]
    add x0, x0, x28, lsl #32
.Lcache_have_drag_root:
    ldr x16, [x17]
    ldar w16, [x16]
    cmp w16, #1
    b.ne .Lcache_resume
    ldur x11, [x0, #-1]
    ubfx x11, x11, #12, #20
    ldr x12, [x17, #328]
    lsr x12, x12, #12
    cmp x11, x12
    b.ne .Lcache_resume
    // Preserve the direct ShortcutIconWidget class expected by native dragging.
    ldur w14, [x0, #19]
    add x14, x14, x28, lsl #32
    ldur x11, [x14, #-1]
    ubfx x11, x11, #12, #20
    ldr x12, [x17, #320]
    cmp x11, x12
    b.ne .Lcache_resume
    ldr x1, [x26, #0x78]
    ldr x16, [x17, #296]
    ldr x1, [x1, x16]
    cmp w1, w22
    b.eq .Lcache_resume
    ldr x16, [x27, #0x40]
    cmp w1, w16
    b.eq .Lcache_resume
    ldr x11, [x26, #0x60]
    add x12, x11, #160
    ldr x13, [x26, #0x68]
    cmp x12, x13
    b.hs .Lcache_resume
    str x12, [x26, #0x60]
    // Clone the authenticated immutable native drag widget into fresh space.
    // Its GlobalKey, controller, callbacks and flags remain byte-identical.
    mov x6, x14
    sub x14, x0, #1
    add x12, x11, #32
    ldr x13, [x17, #328]
    str x13, [x12]
    ldr x13, [x14, #8]
    str x13, [x12, #8]
    ldp x0, x13, [x14, #16]
    stp x0, x13, [x12, #16]
    ldp x0, x13, [x14, #32]
    stp x0, x13, [x12, #32]
    ldp x0, x13, [x14, #48]
    stp x0, x13, [x12, #48]
    ldp x0, x13, [x14, #64]
    stp x0, x13, [x12, #64]
    // Clone the ordinary shortcut too: native GetBuilder titles then rebuild
    // on the reactive parent update, retaining their original controller IDs.
    add x5, x11, #112
    sub x6, x6, #1
    ldr x13, [x17, #360]
    str x13, [x5]
    ldr x13, [x6, #8]
    str x13, [x5, #8]
    ldp x0, x13, [x6, #16]
    stp x0, x13, [x5, #16]
    ldp x0, x13, [x6, #32]
    stp x0, x13, [x5, #32]
    add x5, x5, #1
    stur w5, [x12, #20]      // raw drag clone + 20 is child at tagged +19
    ldr x13, [x17, #192]
    str x13, [x11]
    stp w22, w22, [x11, #24]
    add x11, x11, #1
    add x12, x12, #1
    stur w9, [x11, #7]        // temporary key: original cache entry
    stur w12, [x11, #11]      // temporary tagged root: native drag clone
    mov x0, x5               // temporary shell retains the cloned shortcut
    stur w0, [x11, #15]
    stur w22, [x11, #19]
    // The temporary shell is never published into the widget tree.
    str x11, [x29, x10]
    mov x30, x8              // original mapped return PC / real stack map
    ldr x16, [x17, #288]
    br x16
.Lcache_editor_return:
    ldur w0, [x9, #11]
    add x0, x0, x28, lsl #32
    ldur w11, [x9, #7]
    add x11, x11, x28, lsl #32
    str x11, [x29, x10]
.Lcache_resume:
    mov x15, x29
    br x7

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
    .zero 368
kh_label_end:
    .section .note.GNU-stack,"",%progbits

// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "icon_highlight_profile.h"
#include "launcher_label_profile.h"
struct DockGlassProfile {
    LauncherLabelProfile labels;
    size_t unlock_search, unlock_create, unlock_location, unlock_center;
    uint32_t unlock_tag, unlock_zero_pp, unlock_debug_pp;
    size_t phone, gate, skip, background_call, background, outer_call, outer_logger;
    size_t bottom_margin_return;
    size_t mode, mode_returns[4];
    size_t mode_post, mode_stackmap;
    // Mapped source proof sites; not part of the assembly/config ABI.
    size_t stack_paint, push_clip_rect, stack_create, stack_constructor, stack_clip_load;
    size_t parent_clip_store; // STUR w1,[x0,#1b]; next LDUR x1 kills scratch x1
    uint32_t mode_post_expected, ink_metrics_field, ink_width_field, ink_top_field, cell_height_field, no_word_rx_field, no_word_suppress_rx_field;
    uint32_t gate_expected, background_expected, outer_expected, mode_slot;
    uint32_t material_tags[4], material_config_pp, stack_tag, positioned_tag, sized_box_tag, ignore_pointer_tag;
    uint32_t background_tag, double_tag, alignment_pp, fit_pp;
    uint32_t clip_pp; // authenticated native Clip.none, not Hotseat's default
    uint32_t native_clip_pp; // original parent value, retained for disabled mode
    uint32_t device_slot, corner_field, device_layout_field, launcher_mode_slot, launcher_mode_rx, elderly_pp;
    // Native Recents corner source contract. Entry offsets are relative to the
    // mapped image; curve_getter is the complete synchronous Dart getter
    // (no receiver/arguments, tagged bool result in x0), including its native
    // lazy capability/cache behavior. Caller must provide a managed-call safe
    // boundary; resolving this address alone does not establish GC safety.
    size_t curve_getter, recents_adaptive_clip;
    // Native young-space object headers, authenticated through source callers
    // and the matching createRenderObject/update graph. Radius is 32 bytes with
    // raw doubles at +7/+f; BorderRadius is 32 bytes with refs +7/+b/+f/+13.
    // Both clip widgets are 32 bytes: borderRadius +f, clipper +13, behavior +17;
    // Initialize +1b to native false for both clip widgets: newer native
    // superellipse renderers consume isImage there; older ones ignore it.
    uint32_t recents_radius_tag, recents_border_radius_tag;
    uint32_t recents_superellipse_tag, recents_rrect_tag, recents_clip_pp;
    // IEEE-754 double bits derived from the validated native stacked-radius
    // function's FMOV instructions, not a production version/value table.
    uint64_t recents_default_radius_bits, recents_stacked_min_radius_bits;
    // Source proof sites for diagnostics and structural mutation tests only.
    size_t recents_enabled_for_radius, recents_enabled_for, recents_eligibility;
    size_t recents_superellipse_create, recents_rrect_create, recents_stacked_radius;
    size_t recents_superellipse_constructor, recents_rrect_constructor;
    size_t recents_radius_caller, recents_task_radius;
    size_t recents_task_radius_returns[2];
    size_t recents_flight_radius;
    uint32_t recents_flight_radius_expected;
    int32_t recents_flight_radius_slot;
    size_t recents_radius_alloc, recents_border_radius_alloc;
    size_t recents_superellipse_alloc, recents_rrect_alloc;
    size_t recents_dispatch_start, recents_dispatch_end; // read-only snapshot proof
    // Owned original GlassOs4 clip scope. Exact native replay instructions;
    // signed root slots are relative to the original Dart frame pointer.
    size_t glass_build, glass_scope_store, glass_child_scope_store, glass_clip_alloc_call;
    size_t glass_scope_resume, glass_child_scope_resume, glass_clip_alloc_resume;
    size_t glass_visual_resume, glass_visual_stackmap;
    uint32_t glass_scope_expected, glass_child_scope_expected, glass_clip_alloc_expected;
    int32_t glass_scope_root_slot, glass_radius_slot, glass_child_root_slot;
    // Genuine generic ValueKey: 16 bytes; ref32 typeArguments +7, value +b.
    // null typeArguments represents dynamic. The value is a tagged Smi marker.
    uint32_t value_key_tag;
    size_t value_key_alloc, value_key_caller, value_key_equals;
    // Before child store the future slot contains only a Smi marker. These
    // prove the allocation-return map and the subsequent clip child root.
    size_t glass_scope_stackmap, glass_child_stackmap;
};
// Resolve before any label/Dock patches. No symbols, image/version gates or
// fixed code addresses are consulted by the production resolver.
bool ResolveDockGlass(const IconHighlightImage&, DockGlassProfile*, const char** = nullptr);
namespace dock_glass_profile_detail {
// Bounded source proof shared with focused host ambiguity/boundary mutations.
bool ResolveRootListSave(const IconHighlightImage&, size_t phone, size_t bytes,
                         size_t* save, const char** = nullptr);
}

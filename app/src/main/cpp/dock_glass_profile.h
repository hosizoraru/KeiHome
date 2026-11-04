// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "icon_highlight_profile.h"
#include "launcher_label_profile.h"
struct DockGlassProfile {
    LauncherLabelProfile labels;
    size_t phone, gate, skip, background_call, background, outer_call, outer_logger;
    size_t mode, mode_returns[4];
    size_t mode_post;
    uint32_t mode_post_expected, ink_metrics_field, ink_width_field, ink_top_field, cell_height_field, no_word_rx_field, no_word_suppress_rx_field;
    uint32_t gate_expected, background_expected, outer_expected, mode_slot;
    uint32_t material_tags[4], material_config_pp, stack_tag, positioned_tag, sized_box_tag, ignore_pointer_tag;
    uint32_t background_tag, double_tag, alignment_pp, fit_pp, clip_pp;
    uint32_t device_slot, corner_field, device_layout_field, launcher_mode_slot, launcher_mode_rx, elderly_pp;
};
// Resolve before any label/Dock patches. No symbols, image/version gates or
// fixed code addresses are consulted by the production resolver.
bool ResolveDockGlass(const IconHighlightImage&, DockGlassProfile*, const char** = nullptr);

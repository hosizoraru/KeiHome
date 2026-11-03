// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "icon_highlight_profile.h"

struct LauncherLabelProfile {
    size_t builder, lines, config, value, measure, marquee_constructor;
    size_t allocation_call, text_allocator, marquee_allocator, lines_tail, after_text_init;
    size_t change_event, refresh_call, refresh_logger, font_refresh;
    size_t cell_call, cell_visibility;
    size_t prefix_helper;
    size_t fade_call, fade_wrap;
    uint32_t fade_start_field, fade_end_field;
    int32_t prefix_widget_slot;
    uint32_t ticker_tag, cell_controller_field, cell_screen_field, cell_post_expected;
    int32_t cell_root_slot;
    uint32_t cell_replay[11];
    uint32_t text_cid, size_cid, marquee_tag;
    uint32_t grid_slot, config_rx_field, rx_value_field, width_field, source_width_field;
    uint32_t ltr_slot;
    int32_t source_slot, text_slot, style_slot, widget_slot, lines_slot;
    uint32_t post_expected[4], lines_expected[4];
};

// Symbols/debugdata are test oracles only. Resolution reads executable code,
// follows calls and derives table slots, object tags and caller stack slots.
bool ResolveLauncherLabels(const IconHighlightImage& image, LauncherLabelProfile* out,
                           const char** rejection = nullptr);

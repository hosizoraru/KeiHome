// SPDX-License-Identifier: Apache-2.0
#pragma once
#include "icon_highlight_profile.h"

struct LauncherLabelProfile {
    size_t builder, lines, config, value, measure, marquee_constructor;
    size_t allocation_call, text_allocator, marquee_allocator, lines_tail, after_text_init;
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

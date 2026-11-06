// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>

// Command 8 keeps legacy 0/1 values. Extended geometry is one immutable word,
// so a render never combines the height/radius of two different settings.
constexpr bool ValidDockGlassOptions(uint32_t value) {
    const uint32_t style = value & 7u;
    const uint32_t padding = (value >> 3u) & 63u;
    const uint32_t radius = (value >> 9u) & 127u;
    const int offset = static_cast<int8_t>((value >> 16u) & 255u);
    return (value & 0xff000000u) == 0u && style <= 4u &&
            padding <= 33u && radius <= 64u &&
            (style == 4u || radius == 0u) && offset >= -48 && offset <= 96;
}

constexpr unsigned DockGlassPadding(uint32_t value) {
    const unsigned code = (value >> 3u) & 63u;
    return code ? code - 1u : 18u;
}

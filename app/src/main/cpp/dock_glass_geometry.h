// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <algorithm>
#include <cmath>
#include <optional>

struct DockGlassGeometry {
    bool visible;
    double left;
    double right;
    double top;
    double bottom;
    double radius;
};

// Pure reference math for the native bridge; C++17, no Android or runtime state.
// All lengths are dp in the native content/container coordinate system. Convert
// pixel measurements before calling. An absent native width/height is zero.
// max_count is 4 (elderly) or 5 (normal); count is the actual native icon count.
// icon_width is the centered painted width inside each native layout cell;
// absence preserves the cell-width envelope. No native icon positions change.
// icon_top is the native painted Y coordinate; absence retains the legacy
// container-height envelope. An explicit top bounds the plate around the ink.
[[nodiscard]] inline DockGlassGeometry ComputeDockGlassGeometry(
        double content_width, double cell_width, int count, int max_count,
        double container_height,
        std::optional<double> screen_bottom_radius = std::nullopt,
        std::optional<double> icon_width = std::nullopt,
        std::optional<double> icon_top = std::nullopt) noexcept {
    constexpr double kPadding = 10.0;
    constexpr double kEdge = 8.0;
    constexpr double kFallbackRadius = 20.0;
    const double ink_width = icon_width.value_or(cell_width);
    if (!std::isfinite(content_width) || !std::isfinite(cell_width) ||
        !std::isfinite(container_height) || content_width <= 2.0 * kEdge ||
        cell_width <= 0.0 || container_height <= 0.0 ||
        (!icon_top && container_height <= 2.0 * kEdge) ||
        count <= 0 || count > 5 || (max_count != 4 && max_count != 5) ||
        !std::isfinite(ink_width) || ink_width <= 0.0 || ink_width > cell_width ||
        (icon_top && (!std::isfinite(*icon_top) || *icon_top < 0.0))) {
        return {};
    }

    double first_left;
    double last_right;
    if (count < max_count) {
        first_left = (content_width - count * cell_width) / 2.0;
        last_right = first_left + count * cell_width;
    } else {
        const double slot = content_width / max_count;
        first_left = (slot - cell_width) / 2.0;
        last_right = (count - 1) * slot + (slot + cell_width) / 2.0;
    }
    // The native background layout centers the painted icon inside its cell.
    // Cells may extend beyond edge insets while their actual painted ink fits.
    const double ink_inset = (cell_width - ink_width) / 2.0;
    first_left += ink_inset;
    last_right -= ink_inset;
    // A plate cannot contain these painted bounds and maintain its edge inset.
    // In particular, do not recenter/squeeze a fifth icon in elderly mode.
    if (!std::isfinite(first_left) || !std::isfinite(last_right) ||
        first_left < kEdge || last_right > content_width - kEdge ||
        last_right <= first_left) {
        return {};
    }

    const double left = std::max(kEdge, first_left - kPadding);
    const double right = std::min(content_width - kEdge, last_right + kPadding);
    if (icon_top && !std::isfinite(*icon_top + ink_width)) return {};
    const double top = icon_top ? std::max(0.0, *icon_top - kEdge) : kEdge;
    const double bottom = icon_top
            ? std::min(container_height, *icon_top + ink_width + kEdge)
            : container_height - kEdge;
    const double radius_cap = std::min((right - left) / 2.0, (bottom - top) / 2.0);
    if (!(radius_cap > 0.0)) return {};

    // Keep the native 20dp softness, growing only when the concentric inner arc
    // (screen radius - nearer horizontal outer inset) exceeds it. Missing, zero
    // or invalid screen radii use 20dp too. Both dimensions still cap the radius.
    const double outer_inset = std::min(left, content_width - right);
    const double radius = screen_bottom_radius &&
                          std::isfinite(*screen_bottom_radius) &&
                          *screen_bottom_radius > 0.0
            ? std::max(kFallbackRadius, *screen_bottom_radius - outer_inset)
            : kFallbackRadius;
    return {true, left, right, top, bottom, std::min(radius, radius_cap)};
}

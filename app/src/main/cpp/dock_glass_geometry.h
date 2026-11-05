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
// container-height envelope. An explicit top surrounds the ink with 18dp
// padding, allowing at most 12dp of decorative paint beyond the native box.
[[nodiscard]] inline DockGlassGeometry ComputeDockGlassGeometry(
        double content_width, double cell_width, int count, int max_count,
        double container_height,
        std::optional<double> screen_bottom_radius = std::nullopt,
        std::optional<double> icon_width = std::nullopt,
        std::optional<double> icon_top = std::nullopt,
        bool recents_style = false,
        double recents_default_radius = 20.0,
        std::optional<double> native_recents_radius = std::nullopt) noexcept {
    constexpr double kPadding = 18.0;
    constexpr double kEdge = 8.0;
    constexpr double kFallbackRadius = 28.0;
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

    // Full five-item layout needs the whole native content width. Keep the
    // existing icon fit guard and all <=4 geometry unchanged.
    const double plate_edge = count == 5 ? 0.0 : kEdge;
    const double left = std::max(plate_edge, first_left - kPadding);
    const double right = std::min(content_width - plate_edge, last_right + kPadding);
    if (icon_top && !std::isfinite(*icon_top + ink_width)) return {};
    constexpr double kVerticalPadding = 18.0;
    constexpr double kPaintOverflow = 12.0;
    const double top = icon_top ? std::max(-kPaintOverflow, *icon_top - kVerticalPadding) : kEdge;
    const double bottom = icon_top
            ? std::min(container_height + kPaintOverflow, *icon_top + ink_width + kVerticalPadding)
            : container_height - kEdge;
    if (icon_top && bottom < *icon_top + ink_width) return {};
    const double radius_cap = std::min((right - left) / 2.0, (bottom - top) / 2.0);
    if (!(radius_cap > 0.0)) return {};

    // Keep a 28dp minimum softness, growing only when the concentric inner arc
    // (screen radius - nearer horizontal outer inset) exceeds it. Missing, zero
    // or invalid screen radii use 28dp too. Both dimensions still cap the radius.
    const double outer_inset = std::max(kEdge, std::min(left, content_width - right));
    double radius = screen_bottom_radius &&
                          std::isfinite(*screen_bottom_radius) &&
                          *screen_bottom_radius > 0.0
            ? std::max(kFallbackRadius, *screen_bottom_radius - outer_inset)
            : kFallbackRadius;
    if (recents_style) {
        // Observe the system's actual task-card getter. Before its first
        // normal call, use its source-authenticated ordinary radius fallback.
        if (!std::isfinite(recents_default_radius) || recents_default_radius <= 0) return {};
        radius = native_recents_radius && std::isfinite(*native_recents_radius) &&
                 *native_recents_radius > 0 && *native_recents_radius <= 256
                ? *native_recents_radius : recents_default_radius;
    }
    return {true, left, right, top, bottom, std::min({radius, radius_cap, count == 5 ? 32.0 : radius_cap})};
}

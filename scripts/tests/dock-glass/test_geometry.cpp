// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_geometry.h"

#include <cstdio>
#include <cstdlib>
#include <limits>
#include <type_traits>

namespace {
unsigned checks = 0;

void Check(bool condition, const char* expression, int line) {
    ++checks;
    if (!condition) {
        std::fprintf(stderr, "line %d: %s\n", line, expression);
        std::exit(1);
    }
}
#define CHECK(expression) Check((expression), #expression, __LINE__)

bool Near(double actual, double expected) {
    return std::isfinite(actual) && std::abs(actual - expected) < 1e-9;
}

void Hidden(const DockGlassGeometry& g) {
    CHECK(!g.visible);
    CHECK(g.left == 0.0 && g.right == 0.0 && g.top == 0.0 &&
          g.bottom == 0.0 && g.radius == 0.0);
}

void Rect(const DockGlassGeometry& g, double left, double right,
          double top, double bottom, double radius) {
    CHECK(g.visible);
    CHECK(std::isfinite(g.left) && std::isfinite(g.right) &&
          std::isfinite(g.top) && std::isfinite(g.bottom) &&
          std::isfinite(g.radius));
    if(!Near(g.left,left))std::fprintf(stderr,"rect left actual=%g expected=%g right=%g expected=%g\n",g.left,left,g.right,right);
    CHECK(Near(g.left, left));
    CHECK(Near(g.right, right));
    CHECK(Near(g.top, top));
    CHECK(Near(g.bottom, bottom));
    CHECK(Near(g.radius, radius));
}

void RecentsRadiusPolicy() {
    // Same ink/plate bounds as Rounded; only the source-backed corner policy
    // changes. Test observed native values and final size caps.
    for (int count = 1; count <= 5; ++count) {
        for (double screen : {0.0, 20.0, 30.0, 30.5, 60.0, 256.0}) {
            const auto rounded = ComputeDockGlassGeometry(400, 80, count, 5, 90,
                                                          screen, 48, 10);
            const auto recent = ComputeDockGlassGeometry(400, 80, count, 5, 90,
                                                         screen, 48, 10, true, 20, screen);
            CHECK(rounded.visible && recent.visible);
            CHECK(recent.left == rounded.left && recent.right == rounded.right &&
                  recent.top == rounded.top && recent.bottom == rounded.bottom);
            const double native_target = screen > 0 ? screen : 20.0;
            CHECK(Near(recent.radius, std::min({native_target,
                       (recent.right - recent.left) / 2,
                       (recent.bottom - recent.top) / 2, count==5?32.0:256.0})));
        }
    }
    Hidden(ComputeDockGlassGeometry(400, 80, 1, 5, 90, 60, 48, 10, true, 0));
    CHECK(ComputeDockGlassGeometry(400, 80, 1, 5, 90, 60, 48, 10, true, 20, -1).radius == 20);
}

void NativePositionFixtures() {
    // Independently tabulated native bounds, C=400, w=56, H=80.
    // The normal 4->5 transition switches from packed to distributed slots.
    struct Fixture { int count, max; double first, last, left, right; };
    constexpr Fixture fixtures[] = {
        {1, 5, 172, 228, 154, 246},
        {2, 5, 144, 256, 126, 274},
        {3, 5, 116, 284,  98, 302},
        {4, 5,  88, 312,  70, 330},
        {5, 5,  12, 388,   0, 400},
        {1, 4, 172, 228, 154, 246},
        {2, 4, 144, 256, 126, 274},
        {3, 4, 116, 284,  98, 302},
        {4, 4,  22, 378,   8, 392},
    };
    for (const auto& f : fixtures) {
        const auto g = ComputeDockGlassGeometry(400, 56, f.count, f.max, 80);
        Rect(g, f.left, f.right, 8, 72, 28);
        CHECK(g.left <= f.first && g.right >= f.last);
    }
    // Native elderly fifth-icon right bound is 478; never silently clamp count.
    Hidden(ComputeDockGlassGeometry(400, 56, 5, 4, 80));
    Hidden(ComputeDockGlassGeometry(400, 56, 0, 5, 80));
    Hidden(ComputeDockGlassGeometry(400, 56, 0, 4, 80));
    // Fractional coordinates must survive without integer/pixel rounding.
    Rect(ComputeDockGlassGeometry(401.5, 55.25, 3, 5, 80.5),
         99.875, 301.625, 8, 72.5, 28);
}

void NarrowAndMissingBounds() {
    // Full five-item plates use the content edges; 279 still has 7.9dp of ink clearance.
    Rect(ComputeDockGlassGeometry(300, 40, 5, 5, 80), 0, 300, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(280, 40, 5, 5, 80), 0, 280, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(279, 40, 5, 5, 80), 0, 279, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(200, 40, 5, 5, 80), 0, 200, 8, 72, 28);
    Hidden(ComputeDockGlassGeometry(199, 40, 5, 5, 80));
    // Packed-layout envelope exactly fits the permitted content bounds.
    Rect(ComputeDockGlassGeometry(96, 40, 2, 5, 80), 8, 88, 8, 72, 28);
    Hidden(ComputeDockGlassGeometry(95, 40, 2, 5, 80));
    Rect(ComputeDockGlassGeometry(36, 20, 1, 5, 100), 8, 28, 8, 92, 10);
    Hidden(ComputeDockGlassGeometry(35, 20, 1, 5, 100));
    Hidden(ComputeDockGlassGeometry(100, 101, 1, 5, 80));
    Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, 80));
    Hidden(ComputeDockGlassGeometry(0, 56, 1, 5, 80));
    Hidden(ComputeDockGlassGeometry(400, 0, 1, 5, 80));
    Hidden(ComputeDockGlassGeometry(400, 56, 1, 5, 0));
    Hidden(ComputeDockGlassGeometry(16, 1, 1, 5, 80));
    Hidden(ComputeDockGlassGeometry(400, 56, 1, 5, 16));
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 17), 154, 246, 8, 9, 0.5);
}

void FiveItemContentClearance() {
    // Authored distributed centers are 40,120,200,280,360 at C=400.
    // Each row gives the measured outer ink bounds independently of plate math.
    struct Fixture { double width, cell, ink, first, last; bool fits; };
    constexpr Fixture fixtures[] = {
        {400, 100, 80, 0, 400, true},
        {400, 100, 56, 12, 388, true},
        {400, 100, 64, 8, 392, true},
        {400, 100, 64.5, 7.75, 392.25, true},
        {400, 100, 65, 7.5, 392.5, true},
        {400, 100, 72, 4, 396, true},
        {400, 100, 79.5, 0.25, 399.75, true},
        {400, 100, 80.5, -0.25, 400.25, false},
        {400, 100, 100, -10, 410, false},
        {300, 40, 40, 10, 290, true},
        {280, 40, 40, 8, 272, true},
        {279, 40, 40, 7.9, 271.1, true},
        {200, 100, 40, 0, 200, true},
        {200, 40, 40, 0, 200, true},
        {199, 40, 40, -0.1, 199.1, false},
    };
    for (const auto& f : fixtures) {
        CHECK(f.fits == (f.first >= 0 && f.last <= f.width));
        const auto g = ComputeDockGlassGeometry(f.width, f.cell, 5, 5, 80,
                                               std::nullopt, f.ink);
        if (g.visible != f.fits) {
            std::fprintf(stderr, "five-item C=%g cell=%g ink=%g bounds=[%g,%g]: "
                         "visible=%d expected=%d\n", f.width, f.cell, f.ink,
                         f.first, f.last, g.visible, f.fits);
        }
        CHECK(g.visible == f.fits);
        if (f.fits) {
            Rect(g, 0, f.width, 8, 72, 28);
            CHECK(g.left <= f.first && g.right >= f.last);
        } else {
            Hidden(g);
        }
        // Elderly has four distributed slots. A fifth center is beyond C;
        // accepting 0dp clearance must never clamp or repack that fifth icon.
        Hidden(ComputeDockGlassGeometry(f.width, f.cell, 5, 4, 80,
                                        std::nullopt, f.ink));
    }
    // A one-to-four item packed envelope retains the exact inclusive 8dp rule.
    for (int maximum : {4, 5}) {
        for (int count = 1; count <= 4; ++count) {
            const double width = count < maximum ? count * 40 + 16 : 224;
            Rect(ComputeDockGlassGeometry(width, 40, count, maximum, 80),
                 8, width - 8, 8, 72, count == 1 ? 20 : 28);
            Hidden(ComputeDockGlassGeometry(width - 0.5, 40, count, maximum, 80));
            Hidden(ComputeDockGlassGeometry(count * 40, 40, count, maximum, 80));
        }
    }
    // The new horizontal admission must still run all malformed/vertical guards.
    const double nan = std::numeric_limits<double>::quiet_NaN();
    const double inf = std::numeric_limits<double>::infinity();
    for (double bad : {0.0, -1.0, nan, inf, -inf}) {
        Hidden(ComputeDockGlassGeometry(bad, 100, 5, 5, 120, {}, 80, 20));
        Hidden(ComputeDockGlassGeometry(400, bad, 5, 5, 120, {}, 80, 20));
        Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, bad, {}, 80, 20));
        Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, 120, {}, bad, 20));
    }
    Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, 120, {}, 101, 20));
    for (double bad : {-1.0, nan, inf, -inf, 52.001}) {
        Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, 120, {}, 80, bad));
    }
    for (int bad : {0, -1, 6, std::numeric_limits<int>::max()}) {
        Hidden(ComputeDockGlassGeometry(400, 100, bad, 5, 120, {}, 80, 20));
        Hidden(ComputeDockGlassGeometry(400, 100, 5, bad, 120, {}, 80, 20));
    }
    // Exercise the newly admitted zero-clearance case through every corner policy.
    for (unsigned style = 0; style <= 4; ++style) {
        for (double native_radius : {0.0, 44.0}) {
            const uint32_t options = style | (style == 4 ? 12u << 9u : 0);
            const double radius = style == 4 ? 12 :
                    style == 1 && native_radius == 0 ? 20 : 32;
            Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 120, 60, 80, 20,
                                          false, 20, native_radius, options),
                 0, 400, 2, 118, radius);
        }
    }
}

void RadiusRules() {
    // Full-five plate expands; preserve the old radius floor with a 32dp cap.
    for (double screen_radius : {0.0, 8.0, 10.0, 28.0, 35.0, 36.0}) {
        Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, screen_radius),
             0, 400, 8, 72, 28);
    }
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 38), 0, 400, 8, 72, 30);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 40), 0, 400, 8, 72, 32);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 1000), 0, 400, 8, 72, 32);
    // Wider padding now puts the full 56dp elderly dock at the same 8dp inset.
    Rect(ComputeDockGlassGeometry(400, 56, 4, 4, 80, 28), 8, 392, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 56, 4, 4, 80, 40), 8, 392, 8, 72, 32);
    // Smaller elderly ink preserves a 12dp inset in the concentric-radius rule.
    Rect(ComputeDockGlassGeometry(400, 40, 4, 4, 80, 40), 12, 388, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 40, 4, 4, 80, 42), 12, 388, 8, 72, 30);
    // Centered adaptive bars retain soft corners even when radius - inset < 0.
    for (int max_count : {4, 5}) {
        Rect(ComputeDockGlassGeometry(400, 56, 1, max_count, 80, 40),
             154, 246, 8, 72, 28);
        Rect(ComputeDockGlassGeometry(400, 56, 2, max_count, 80, 40),
             126, 274, 8, 72, 28);
    }
    // Exercise floor, concentric growth, and the height cap independently.
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, 180), 154, 246, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, 182), 154, 246, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, 184), 154, 246, 8, 72, 30);
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, 186), 154, 246, 8, 72, 32);
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, 200), 154, 246, 8, 72, 32);
    // With a tall legacy container, the width cap becomes the limiting factor.
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 200, 200), 154, 246, 8, 192, 46);
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 200, 1000), 154, 246, 8, 192, 46);
    Rect(ComputeDockGlassGeometry(36, 20, 1, 5, 100, 1000), 8, 28, 8, 92, 10);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 36), 0, 400, 8, 28, 10);

    const double nan = std::numeric_limits<double>::quiet_NaN();
    const double inf = std::numeric_limits<double>::infinity();
    for (const std::optional<double> fallback :
         {std::optional<double>{}, std::optional<double>{0.0},
          std::optional<double>{-1.0}, std::optional<double>{nan},
          std::optional<double>{inf}, std::optional<double>{-inf}}) {
        Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, fallback),
             154, 246, 8, 72, 28);
        Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, fallback),
             0, 400, 8, 72, 28);
        // The fallback floor still respects narrow/short geometry.
        Rect(ComputeDockGlassGeometry(36, 20, 1, 5, 100, fallback),
             8, 28, 8, 92, 10);
        Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 36, fallback),
             0, 400, 8, 28, 10);
        // Radius fallback cannot make missing native bounds drawable.
        Hidden(ComputeDockGlassGeometry(0, 56, 1, 5, 80, fallback));
    }
}

void PaintedIconEnvelope() {
    // Five 100dp cells in 80dp slots extend past the content edges, but their
    // centered 56dp painted icons occupy [12,388] and must retain a dock plate.
    Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, 80));
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 80, std::nullopt, 56),
         0, 400, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 80, 40, 56),
         0, 400, 8, 72, 32);
    Rect(ComputeDockGlassGeometry(400, 120, 4, 4, 80, std::nullopt, 56),
         8, 392, 8, 72, 28);
    Hidden(ComputeDockGlassGeometry(400, 120, 5, 4, 80, std::nullopt, 56));
    // Packed cells retain their original pitch; only the outer ink edges shrink.
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 80, std::nullopt, 56),
         154, 246, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 2, 5, 80, std::nullopt, 56),
         104, 296, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 3, 5, 80, std::nullopt, 56),
         54, 346, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 4, 5, 80, std::nullopt, 56),
         8, 392, 8, 72, 28);
    // Full-five ink may occupy the old 8dp inset, through the content edge.
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 80, std::nullopt, 64),
         0, 400, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 80, std::nullopt, 65),
         0, 400, 8, 72, 28);
    Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, 80, std::nullopt, 81));
    for (double bad : {0.0, -1.0, 101.0,
                       std::numeric_limits<double>::quiet_NaN(),
                       std::numeric_limits<double>::infinity(),
                       -std::numeric_limits<double>::infinity()}) {
        Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, 80, std::nullopt, bad));
    }
    // Independent per-icon center oracle across both native layout thresholds.
    for (int maximum : {4, 5}) {
        for (int count : {1, 2, 3, 4, 5, 4, 3, 2, 1}) {
            for (double cell : {56.0, 80.0, 100.0, 120.0}) {
                const auto g = ComputeDockGlassGeometry(400, cell, count, maximum,
                                                       80, std::nullopt, 56);
                bool fits = true;
                for (int i = 0; i < count; ++i) {
                    const double center = count < maximum
                            ? 200 + (i - (count - 1) / 2.0) * cell
                            : (i + 0.5) * (400.0 / maximum);
                    const double edge = count == 5 && maximum == 5 ? 0 : 8;
                    fits = fits && center - 28 >= edge && center + 28 <= 400 - edge;
                    if (g.visible) CHECK(g.left <= center - 28 && g.right >= center + 28);
                }
                CHECK(g.visible == fits);
                if (!fits) Hidden(g);
            }
        }
    }
}

void InvalidInputs() {
    const double nan = std::numeric_limits<double>::quiet_NaN();
    const double inf = std::numeric_limits<double>::infinity();
    for (double bad : {nan, inf, -inf, -1.0, 0.0}) {
        Hidden(ComputeDockGlassGeometry(bad, 56, 1, 5, 80));
        Hidden(ComputeDockGlassGeometry(400, bad, 1, 5, 80));
        Hidden(ComputeDockGlassGeometry(400, 56, 1, 5, bad));
        Hidden(ComputeDockGlassGeometry(400, 56, 1, 5, bad, std::nullopt, 56, 20));
    }
    for (int bad : {-1, 6, std::numeric_limits<int>::min(),
                    std::numeric_limits<int>::max()}) {
        Hidden(ComputeDockGlassGeometry(400, 56, bad, 5, 80));
    }
    for (int bad : {-1, 0, 1, 3, 6, std::numeric_limits<int>::max()}) {
        Hidden(ComputeDockGlassGeometry(400, 56, 1, bad, 80));
    }
    const double huge = std::numeric_limits<double>::max();
    Hidden(ComputeDockGlassGeometry(400, huge, 4, 5, 80));
    Hidden(ComputeDockGlassGeometry(huge, huge, 5, 5, 80));
    Hidden(ComputeDockGlassGeometry(400, std::numeric_limits<double>::denorm_min(),
                                    1, 5, 80)); // No representable native envelope.
    const auto g = ComputeDockGlassGeometry(huge, 56, 5, 5, huge, huge);
    CHECK(g.visible && std::isfinite(g.left) && std::isfinite(g.right) &&
          std::isfinite(g.top) && std::isfinite(g.bottom) && std::isfinite(g.radius));
    // Huge finite roots do not stretch a plate anchored to ordinary native ink.
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, huge, std::nullopt, 56, 20),
         154, 246, 2, 94, 28);
    // Finite inputs can overflow the ink-bottom sum; reject before clipping it.
    Hidden(ComputeDockGlassGeometry(huge, huge / 4, 1, 5, huge,
                                    std::nullopt, huge / 4, huge));
    // At this magnitude 56dp has no representable extent, so no plate is drawable.
    Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, huge,
                                    std::nullopt, 56, huge));
}

void NativeVerticalEnvelope() {
    // Explicit native ink gets 18dp padding, allowing 12dp beyond the root.
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 120, std::nullopt, 56, 20),
         0, 400, 2, 94, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 120, 40, 56, 20),
         0, 400, 2, 94, 32);
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 120, 256, 56, 20),
         0, 400, 2, 94, 32);
    // Near-top ink keeps its native Y; only the extra plate padding is clipped.
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt, 56, 0),
         154, 246, -12, 74, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt, 56, 4),
         154, 246, -12, 78, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 120, 256, 56, 5.5),
         154, 246, -12, 79.5, 45.75);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 120, 256, 56, 6),
         154, 246, -12, 80, 46);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 120, 256, 56, 6.5),
         154, 246, -11.5, 80.5, 46);
    // Near-bottom padding clips at H+12, including fractional boundary values.
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 100, std::nullopt, 56, 37.5),
         154, 246, 19.5, 111.5, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 100, std::nullopt, 56, 38),
         154, 246, 20, 112, 28);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 100, std::nullopt, 56, 38.5),
         154, 246, 20.5, 112, 28);
    Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 100, std::nullopt, 56, 80));
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 100, std::nullopt, 56, 56),
         154, 246, 38, 112, 28);
    Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 100, std::nullopt, 56, 56.001));
    // An explicit top remains useful even for roots too short for legacy insets.
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 16, std::nullopt, 8, 4),
         178, 222, -12, 28, 20);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 1, std::nullopt, 8, 0),
         178, 222, -12, 13, 12.5);
    // Off-root ink must never leave only part of an icon inside the plate.
    Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt, 56, 128));
    Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt, 56, 149));
    Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt, 56, 150));
    Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt, 56, 151));
    // Missing ink width uses the native cell width on both axes.
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt,
                                  std::nullopt, 20),
         132, 268, 2, 132, 28);
    // Omission retains the original container-based vertical bounds exactly.
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 120, std::nullopt, 56),
         0, 400, 8, 112, 28);
    Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 16, std::nullopt, 8));
    for (double bad : {-1.0, std::numeric_limits<double>::quiet_NaN(),
                       std::numeric_limits<double>::infinity(),
                       -std::numeric_limits<double>::infinity()}) {
        Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 120,
                                        std::nullopt, 56, bad));
    }
    for (double height : {1.0, 16.0, 64.0, 120.0, 1000.0}) {
        for (double ink : {8.0, 56.0, 100.0}) {
            for (double icon_top : {0.0, 4.0, 6.0, 6.5, 20.0, 80.0, 128.0,
                                    149.0, 150.0, 1000.0}) {
                const auto g = ComputeDockGlassGeometry(400, 100, 1, 5, height,
                                                       256, ink, icon_top);
                const double top = std::max(-12.0, icon_top - 18);
                const double bottom = std::min(height + 12, icon_top + ink + 18);
                CHECK(g.visible == (bottom > top && bottom >= icon_top + ink));
                if (!g.visible) { Hidden(g); continue; }
                const double left = 200 - ink / 2 - 18;
                const double right = 200 + ink / 2 + 18;
                Rect(g, left, right, top, bottom,
                     std::min((ink + 36) / 2, (bottom - top) / 2));
                // Ink stays centered in its original 100dp native cell.
                CHECK(Near((g.left + g.right) / 2, 200));
                CHECK(g.top >= -12 && g.bottom <= height + 12);
                CHECK(g.top <= icon_top);
                CHECK(g.bottom >= icon_top + ink);
                CHECK(g.radius > 0 && g.radius <= (g.right - g.left) / 2);
            }
        }
    }
}

void CountChangesAndEnvelopeSweep() {
    // Visit both directions, crossing the full-layout threshold and hidden state.
    constexpr int sequence[] = {0, 1, 2, 3, 4, 5, 4, 3, 2, 1, 0, 5, 1};
    unsigned visible = 0, hidden = 0;
    for (int max_count : {4, 5}) {
        for (double width : {36.0, 95.0, 96.0, 279.0, 280.0, 300.0, 400.0, 401.5}) {
            for (double cell : {20.0, 40.0, 55.25, 56.0, 100.0}) {
                for (double height : {16.0, 17.0, 36.0, 80.0, 120.0}) {
                    for (int count : sequence) {
                        const auto g = ComputeDockGlassGeometry(width, cell, count,
                                                               max_count, height, 60);
                        // Independent per-icon position oracle; no plate math.
                        bool fits = count > 0 && height > 16 && width > 16;
                        double first = 0, last = 0;
                        for (int i = 0; i < count; ++i) {
                            const double center = count < max_count
                                    ? width / 2 + (i - (count - 1) / 2.0) * cell
                                    : (i + 0.5) * width / max_count;
                            if (i == 0) first = center - cell / 2;
                            last = center + cell / 2;
                            const double edge = count == 5 && max_count == 5 ? 0 : 8;
                            fits = fits && center - cell / 2 >= edge && last <= width - edge;
                        }
                        CHECK(g.visible == fits);
                        if (!fits) { Hidden(g); ++hidden; continue; }
                        ++visible;
                        CHECK(g.left >= (count==5?0:8) && g.right <= width - (count==5?0:8));
                        CHECK(g.left <= first && g.right >= last);
                        CHECK(Near(g.left, std::max(count==5?0.0:8.0, first - 18)));
                        CHECK(Near(g.right, std::min(width - (count==5?0:8), last + 18)));
                        CHECK(g.top == 8 && g.bottom == height - 8);
                        CHECK(g.radius >= 0 && g.radius <= (g.right - g.left) / 2);
                        CHECK(g.radius <= (g.bottom - g.top) / 2);
                        CHECK(g.radius >= std::min(28.0,
                              std::min((g.right - g.left) / 2, (g.bottom - g.top) / 2)));
                        CHECK(std::isfinite(g.left) && std::isfinite(g.right) &&
                              std::isfinite(g.top) && std::isfinite(g.bottom) &&
                              std::isfinite(g.radius));
                    }
                }
            }
        }
    }
    std::printf("envelope/count-transition sweep: %u visible, %u hidden cases\n",
                visible, hidden);
}
} // namespace

int main() {
    FiveItemContentClearance();
    for(uint32_t style=0;style<=4;++style)
        for(uint32_t padding : {0u,6u,18u,32u})
            for(int count=1;count<=5;++count) {
                const uint32_t options=style|((padding+1u)<<3u)|
                        (style==4u ? 12u<<9u : 0u);
                CHECK(ValidDockGlassOptions(options));
                const auto shape=ComputeDockGlassGeometry(420,80,count,5,88,60.0,64.0,4.0,false,20.0,std::nullopt,options);
                CHECK(shape.visible&&shape.left>=0&&shape.right<=420);
                CHECK(shape.top<=4&&shape.bottom>=68&&shape.radius>=0);
                CHECK(shape.radius<=(shape.bottom-shape.top)*0.5);
                if(style==4)CHECK(shape.radius==12.0);
            }
    const auto pad=ComputeDockGlassGeometry(420,80,4,5,88,60.0,64.0,4.0,false,20.0,std::nullopt,2u);
    CHECK(std::abs(pad.radius-(64.0*16.0/60.0+17.0))<1e-10);
    static_assert(std::is_trivial<DockGlassGeometry>::value, "plain result");
    static_assert(noexcept(ComputeDockGlassGeometry(400, 56, 1, 5, 80)), "pure math");
    // Conservative RRect containment also covers the fuller native continuous
    // curve: every corner of all five unscaled icon rectangles stays inside.
    for(double width:{360.0,373.3333333333,400.0})for(double ink:{48.0,52.0,56.0})
      for(double y:{0.0,4.0,8.0,12.0})for(double native_radius:{20.0,44.210527795173505,60.0,256.0}) {
        auto g=ComputeDockGlassGeometry(width,100,5,5,80,60,ink,y,true,20,native_radius);
        CHECK(g.visible);CHECK(g.radius<=32);
        for(int i=0;i<5;++i)for(double x:{(i+.5)*width/5-ink/2,(i+.5)*width/5+ink/2})
          for(double cy:{y,y+ink}) {
            CHECK(x>=g.left&&x<=g.right&&cy>=g.top&&cy<=g.bottom);
            double dx=std::max({g.left+g.radius-x,x-(g.right-g.radius),0.0});
            double dy=std::max({g.top+g.radius-cy,cy-(g.bottom-g.radius),0.0});
            CHECK(dx*dx+dy*dy<=g.radius*g.radius+1e-8);
          }
      }
    NativePositionFixtures();
    NarrowAndMissingBounds();
    RadiusRules();
    RecentsRadiusPolicy();
    PaintedIconEnvelope();
    NativeVerticalEnvelope();
    InvalidInputs();
    CountChangesAndEnvelopeSweep();
    std::printf("PASS: dock glass geometry (%u checks)\n", checks);
}

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
    CHECK(Near(g.left, left));
    CHECK(Near(g.right, right));
    CHECK(Near(g.top, top));
    CHECK(Near(g.bottom, bottom));
    CHECK(Near(g.radius, radius));
}

void NativePositionFixtures() {
    // Independently tabulated native bounds, C=400, w=56, H=80.
    // The normal 4->5 transition switches from packed to distributed slots.
    struct Fixture { int count, max; double first, last, left, right; };
    constexpr Fixture fixtures[] = {
        {1, 5, 172, 228, 162, 238},
        {2, 5, 144, 256, 134, 266},
        {3, 5, 116, 284, 106, 294},
        {4, 5,  88, 312,  78, 322},
        {5, 5,  12, 388,   8, 392},
        {1, 4, 172, 228, 162, 238},
        {2, 4, 144, 256, 134, 266},
        {3, 4, 116, 284, 106, 294},
        {4, 4,  22, 378,  12, 388},
    };
    for (const auto& f : fixtures) {
        const auto g = ComputeDockGlassGeometry(400, 56, f.count, f.max, 80);
        Rect(g, f.left, f.right, 8, 72, 20);
        CHECK(g.left <= f.first && g.right >= f.last);
    }
    // Native elderly fifth-icon right bound is 478; never silently clamp count.
    Hidden(ComputeDockGlassGeometry(400, 56, 5, 4, 80));
    Hidden(ComputeDockGlassGeometry(400, 56, 0, 5, 80));
    Hidden(ComputeDockGlassGeometry(400, 56, 0, 4, 80));
    // Fractional coordinates must survive without integer/pixel rounding.
    Rect(ComputeDockGlassGeometry(401.5, 55.25, 3, 5, 80.5),
         107.875, 293.625, 8, 72.5, 20);
}

void NarrowAndMissingBounds() {
    // 10dp padding shrinks to 8dp edge clearance without moving any icon.
    Rect(ComputeDockGlassGeometry(300, 40, 5, 5, 80), 8, 292, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(280, 40, 5, 5, 80), 8, 272, 8, 72, 20);
    Hidden(ComputeDockGlassGeometry(279, 40, 5, 5, 80));
    // Packed-layout envelope exactly fits the permitted content bounds.
    Rect(ComputeDockGlassGeometry(96, 40, 2, 5, 80), 8, 88, 8, 72, 20);
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
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 17), 162, 238, 8, 9, 0.5);
}

void RadiusRules() {
    // Full normal dock has an 8dp outer inset, height cap 32dp.
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 28), 8, 392, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 10), 8, 392, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 8), 8, 392, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 0), 8, 392, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 36), 8, 392, 8, 72, 28);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 40), 8, 392, 8, 72, 32);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, 1000), 8, 392, 8, 72, 32);
    // Elderly full dock has a different inset (12dp).
    Rect(ComputeDockGlassGeometry(400, 56, 4, 4, 80, 28), 12, 388, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 56, 4, 4, 80, 40), 12, 388, 8, 72, 28);
    // Centered adaptive bars retain soft corners even when radius - inset < 0.
    for (int max_count : {4, 5}) {
        Rect(ComputeDockGlassGeometry(400, 56, 1, max_count, 80, 40),
             162, 238, 8, 72, 20);
        Rect(ComputeDockGlassGeometry(400, 56, 2, max_count, 80, 40),
             134, 266, 8, 72, 20);
    }
    // One icon may reach the height cap, but cannot exceed either dimension.
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, 200), 162, 238, 8, 72, 32);
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, 180), 162, 238, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, 186), 162, 238, 8, 72, 24);
    Rect(ComputeDockGlassGeometry(36, 20, 1, 5, 100, 1000), 8, 28, 8, 92, 10);
    Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 36), 8, 392, 8, 28, 10);

    const double nan = std::numeric_limits<double>::quiet_NaN();
    const double inf = std::numeric_limits<double>::infinity();
    for (const std::optional<double> fallback :
         {std::optional<double>{}, std::optional<double>{0.0},
          std::optional<double>{-1.0}, std::optional<double>{nan},
          std::optional<double>{inf}, std::optional<double>{-inf}}) {
        Rect(ComputeDockGlassGeometry(400, 56, 1, 5, 80, fallback),
             162, 238, 8, 72, 20);
        Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 80, fallback),
             8, 392, 8, 72, 20);
        // The fallback floor still respects narrow/short geometry.
        Rect(ComputeDockGlassGeometry(36, 20, 1, 5, 100, fallback),
             8, 28, 8, 92, 10);
        Rect(ComputeDockGlassGeometry(400, 56, 5, 5, 36, fallback),
             8, 392, 8, 28, 10);
        // Radius fallback cannot make missing native bounds drawable.
        Hidden(ComputeDockGlassGeometry(0, 56, 1, 5, 80, fallback));
    }
}

void PaintedIconEnvelope() {
    // Five 100dp cells in 80dp slots extend past the content edges, but their
    // centered 56dp painted icons occupy [12,388] and must retain a dock plate.
    Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, 80));
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 80, std::nullopt, 56),
         8, 392, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 80, 40, 56),
         8, 392, 8, 72, 32);
    Rect(ComputeDockGlassGeometry(400, 120, 4, 4, 80, std::nullopt, 56),
         12, 388, 8, 72, 20);
    Hidden(ComputeDockGlassGeometry(400, 120, 5, 4, 80, std::nullopt, 56));
    // Packed cells retain their original pitch; only the outer ink edges shrink.
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 80, std::nullopt, 56),
         162, 238, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 100, 2, 5, 80, std::nullopt, 56),
         112, 288, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 100, 3, 5, 80, std::nullopt, 56),
         62, 338, 8, 72, 20);
    Rect(ComputeDockGlassGeometry(400, 100, 4, 5, 80, std::nullopt, 56),
         12, 388, 8, 72, 20);
    // Ink at the 8dp edge is valid; one extra dp cannot be hidden by clipping.
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 80, std::nullopt, 64),
         8, 392, 8, 72, 20);
    Hidden(ComputeDockGlassGeometry(400, 100, 5, 5, 80, std::nullopt, 65));
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
                    fits = fits && center - 28 >= 8 && center + 28 <= 392;
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
    for (double bad : {nan, inf, -inf, -1.0}) {
        Hidden(ComputeDockGlassGeometry(bad, 56, 1, 5, 80));
        Hidden(ComputeDockGlassGeometry(400, bad, 1, 5, 80));
        Hidden(ComputeDockGlassGeometry(400, 56, 1, 5, bad));
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
    CHECK(g.visible && std::isfinite(g.radius) && std::isfinite(g.bottom));
}

void NativeVerticalEnvelope() {
    // The native root includes unused vertical space; explicit painted Y must
    // bound only the icon plus 8dp padding. Horizontal geometry stays identical.
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 120, std::nullopt, 56, 20),
         8, 392, 12, 84, 20);
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 120, 40, 56, 20),
         8, 392, 12, 84, 32);
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 120, 256, 56, 20),
         8, 392, 12, 84, 36);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt, 56, 4),
         162, 238, 0, 68, 20);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt, 56, 0),
         162, 238, 0, 64, 20);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 100, std::nullopt, 56, 80),
         162, 238, 72, 100, 14);
    Rect(ComputeDockGlassGeometry(400, 100, 1, 5, 16, std::nullopt, 8, 4),
         186, 214, 0, 16, 8);
    Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 120, std::nullopt, 56, 128));
    // Omission retains the original container-based behavior exactly.
    Rect(ComputeDockGlassGeometry(400, 100, 5, 5, 120, std::nullopt, 56),
         8, 392, 8, 112, 20);
    for (double bad : {-1.0, std::numeric_limits<double>::quiet_NaN(),
                       std::numeric_limits<double>::infinity(),
                       -std::numeric_limits<double>::infinity()}) {
        Hidden(ComputeDockGlassGeometry(400, 100, 1, 5, 120,
                                        std::nullopt, 56, bad));
    }
    for (double height : {16.0, 64.0, 120.0}) {
        for (double icon_top : {0.0, 4.0, 8.0, 20.0, 80.0, 128.0}) {
            const auto g = ComputeDockGlassGeometry(400, 100, 1, 5, height,
                                                   256, 56, icon_top);
            const double top = std::max(0.0, icon_top - 8);
            const double bottom = std::min(height, icon_top + 64);
            CHECK(g.visible == (bottom > top));
            if (!g.visible) { Hidden(g); continue; }
            CHECK(g.left == 162 && g.right == 238);
            CHECK(g.top == top && g.bottom == bottom);
            CHECK(g.radius > 0 && g.radius <= (bottom - top) / 2);
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
                            fits = fits && center - cell / 2 >= 8 && last <= width - 8;
                        }
                        CHECK(g.visible == fits);
                        if (!fits) { Hidden(g); ++hidden; continue; }
                        ++visible;
                        CHECK(g.left >= 8 && g.right <= width - 8);
                        CHECK(g.left <= first && g.right >= last);
                        CHECK(first - g.left <= 10 && g.right - last <= 10);
                        CHECK(g.top == 8 && g.bottom == height - 8);
                        CHECK(g.radius >= 0 && g.radius <= (g.right - g.left) / 2);
                        CHECK(g.radius <= (g.bottom - g.top) / 2);
                        CHECK(g.radius >= std::min(20.0,
                              std::min((g.right - g.left) / 2, (g.bottom - g.top) / 2)));
                        CHECK(std::isfinite(g.radius));
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
    static_assert(std::is_trivial<DockGlassGeometry>::value, "plain result");
    static_assert(noexcept(ComputeDockGlassGeometry(400, 56, 1, 5, 80)), "pure math");
    NativePositionFixtures();
    NarrowAndMissingBounds();
    RadiusRules();
    PaintedIconEnvelope();
    NativeVerticalEnvelope();
    InvalidInputs();
    CountChangesAndEnvelopeSweep();
    std::printf("PASS: dock glass geometry (%u checks)\n", checks);
}

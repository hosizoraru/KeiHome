// SPDX-License-Identifier: Apache-2.0
#include "../../main/cpp/launcher_glass_policy.h"
#include <assert.h>
#include <atomic>
#include <stdio.h>
#include <thread>
#include <vector>

int main() {
    assert(RequestLauncherGlassStyle(kLauncherGlassSystem));
    assert(kh_glass_material_revision == 0u);
    for (uint32_t style : {1u, 2u, 0u, 2u, 1u, 0u}) {
        const uint64_t before = kh_glass_material_revision;
        assert(RequestLauncherGlassStyle(style));
        assert(kh_launcher_glass_control == (style | (style << 2u)));
        assert(kh_glass_material_revision == before + 1u);
        for (unsigned renewal = 0; renewal < 1000u; ++renewal) {
            assert(RequestLauncherGlassStyle(style));
        }
        assert(kh_glass_material_revision == before + 1u);
    }
    for (uint32_t invalid : {3u, 4u, 255u, UINT32_MAX}) {
        const uint64_t before = kh_glass_material_revision;
        assert(!RequestLauncherGlassStyle(invalid));
        assert(kh_launcher_glass_control == 0u && kh_glass_material_revision == before);
    }
    for (uint32_t previous = 0u; previous < 16u; ++previous) {
        if (!ValidLauncherGlassPolicy(previous)) continue;
        assert(RequestLauncherGlassPolicy(previous));
        for (uint32_t policy = 0u; policy < 256u; ++policy) {
            assert(RequestLauncherGlassPolicy(previous));
            const uint64_t before = kh_glass_material_revision;
            const bool valid = policy < 16u && (policy & 3u) < 3u && (policy >> 2u) < 3u;
            assert(RequestLauncherGlassPolicy(policy) == valid);
            assert(kh_launcher_glass_control == (valid ? policy : previous));
            assert(kh_glass_material_revision == before + uint64_t{valid && policy != previous});
            if (valid) {
                assert(RequestLauncherGlassPolicy(policy));
                assert(kh_glass_material_revision == before + uint64_t{policy != previous});
            }
        }
    }
    for (uint32_t bit = 4u; bit < 32u; ++bit) {
        const uint64_t before = kh_glass_material_revision;
        assert(!RequestLauncherGlassPolicy(1u << bit));
        assert(kh_glass_material_revision == before);
    }
    assert(RequestLauncherGlassStyle(0u));
    std::atomic<bool> start{false};
    std::vector<std::thread> readers;
    for (unsigned i = 0; i < 8u; ++i) {
        readers.emplace_back([&] {
            while (!start.load(std::memory_order_acquire)) {}
            for (unsigned sample = 0; sample < 20000u; ++sample) {
                const uint64_t pair = __atomic_load_n(&kh_launcher_glass_control, __ATOMIC_ACQUIRE);
                assert(pair < 16u && ValidLauncherGlassPolicy(static_cast<uint32_t>(pair)));
            }
        });
    }
    const uint64_t before = kh_glass_material_revision;
    start.store(true, std::memory_order_release);
    for (unsigned setting = 1; setting <= 40000u; ++setting) {
        const uint32_t pair = setting % 3u | (((setting / 3u) % 3u) << 2u);
        assert(RequestLauncherGlassPolicy(pair));
    }
    for (auto& reader : readers) reader.join();
    assert(kh_glass_material_revision == before + 40000u);
    assert(kh_glass_material_notified == 0u);
    assert(RequestLauncherGlassStyle(kLauncherGlassSystem));
    puts("Material policy: legacy styles, nine pairs, all transitions, quiet renewals, invalid requests and 160000 concurrent reads passed");
}

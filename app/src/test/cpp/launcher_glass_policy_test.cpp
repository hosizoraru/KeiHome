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
        assert(kh_launcher_glass_control == style);
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
    std::atomic<bool> start{false};
    std::vector<std::thread> readers;
    for (unsigned i = 0; i < 8u; ++i) {
        readers.emplace_back([&] {
            while (!start.load(std::memory_order_acquire)) {}
            for (unsigned sample = 0; sample < 20000u; ++sample) {
                assert(__atomic_load_n(&kh_launcher_glass_control, __ATOMIC_ACQUIRE) <= 2u);
            }
        });
    }
    const uint64_t before = kh_glass_material_revision;
    start.store(true, std::memory_order_release);
    for (unsigned setting = 1; setting <= 40000u; ++setting) {
        assert(RequestLauncherGlassStyle(setting % 3u));
    }
    for (auto& reader : readers) reader.join();
    assert(kh_glass_material_revision == before + 40000u);
    assert(kh_glass_material_notified == 0u);
    assert(RequestLauncherGlassStyle(kLauncherGlassSystem));
    puts("Material policy: three modes, quiet renewals, invalid requests and 160000 concurrent reads passed");
}

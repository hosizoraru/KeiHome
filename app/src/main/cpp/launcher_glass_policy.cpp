// SPDX-License-Identifier: Apache-2.0
#include "launcher_glass_policy.h"

extern "C" {
alignas(8) uint64_t kh_launcher_glass_control = 0;
uint32_t kh_launcher_glass_installed = 0;
alignas(8) uint64_t kh_glass_material_revision = 0;
alignas(8) uint64_t kh_glass_material_notified = 0;
}

bool RequestLauncherGlassStyle(uint32_t style) {
    if (style > kLauncherGlassDark) return false;
    const uint64_t previous = __atomic_exchange_n(
            &kh_launcher_glass_control, uint64_t{style}, __ATOMIC_ACQ_REL);
    if (previous != style) {
        __atomic_add_fetch(&kh_glass_material_revision, uint64_t{1}, __ATOMIC_RELEASE);
    }
    return true;
}

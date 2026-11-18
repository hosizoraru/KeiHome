// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stdint.h>

constexpr uint32_t kLauncherGlassSystem = 0u;
constexpr uint32_t kLauncherGlassLight = 1u;
constexpr uint32_t kLauncherGlassDark = 2u;

extern "C" {
// Only admitted Dock/search preset branches read this atomic policy.
// Engine frames, wallpaper values and native theme brightness stay untouched.
extern uint64_t kh_launcher_glass_control;
extern uint32_t kh_launcher_glass_installed;
extern uint32_t kh_launcher_search_glass_installed;
extern uint64_t kh_glass_material_revision, kh_glass_material_notified;
}

// Dock/folder bits 0..1, effective search bits 2..3; each 0..2, no high bits.
constexpr bool ValidLauncherGlassPolicy(uint32_t policy) {
    return (policy & ~15u) == 0u && (policy & 3u) <= kLauncherGlassDark &&
            ((policy >> 2u) & 3u) <= kLauncherGlassDark;
}

// Unchanged lease renewals never request another managed refresh.
bool RequestLauncherGlassPolicy(uint32_t policy);
// Legacy callers select the same style for both roles.
bool RequestLauncherGlassStyle(uint32_t style);

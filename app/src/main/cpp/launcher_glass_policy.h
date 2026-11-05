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
extern uint64_t kh_glass_material_revision, kh_glass_material_notified;
}

// Unchanged lease renewals never request another managed refresh.
bool RequestLauncherGlassStyle(uint32_t style);

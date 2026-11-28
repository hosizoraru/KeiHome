// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "icon_highlight_profile.h"

// Resolved targets and verified struct offsets inside com.miui.home's
// libapp_launcher.so (mi_wallpaper_manager crate).
//
// All offsets and ABI details are extracted and cross-checked directly from the
// loaded ARM64 machine code. Any mismatch or ambiguity fails closed.
struct WallpaperReconnectProfile {
    uintptr_t do_bind_service;
    uintptr_t trigger_reconnect;
    uintptr_t get_service;
    size_t arc_header_size;        // ArcInner header -> MiuiWallpaperManagerInner
    size_t destroyed_offset;       // ArcInner + destroyed AtomicBool
    size_t is_reconnecting_offset; // ArcInner + is_reconnecting AtomicBool
    size_t is_bound_offset;        // ArcInner + ServiceState.is_bound bool
};

bool ResolveWallpaperReconnect(const IconHighlightImage& image,
                               WallpaperReconnectProfile* out,
                               const char** rejection = nullptr);

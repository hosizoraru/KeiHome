// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

// Installs self-healing wallpaper service reconnect hooks into com.miui.home's
// libapp_launcher.so (mi_wallpaper_manager crate). Safe to call on both Phone
// and Pad launchers; fails closed if the native image does not match.
void InstallWallpaperReconnect();

// Pure, host-testable retry & lazy-rebind policy used by the native hooks.
struct WallpaperServiceResult {
    void* data;
    void* second;
};

struct WallpaperReconnectOps {
    void* (*do_bind_service)(void** arc_ref, void** runtime_ref,
                             const char* reason, size_t reason_len);
    void (*trigger_reconnect)(void** arc_ref, const char* reason,
                              size_t reason_len);
    void (*get_service)(WallpaperServiceResult* out, void* inner);
    void (*drop_error)(void* err);
    bool (*is_destroyed)(const void* arc_inner);
    bool (*is_reconnecting)(const void* arc_inner);
    bool (*is_bound)(const void* arc_inner);
    void (*sleep_ms)(uint32_t ms);
    int64_t (*now_ms)();
    bool (*is_ui_thread)();
    void (*on_event)(const char* event, size_t arg);
};

struct WallpaperReconnectConfig {
    static constexpr size_t kMaxBackoffSteps = 6u;
    uint32_t backoff_ms[kMaxBackoffSteps];
    size_t backoff_count;
    uint32_t sleep_slice_ms;
    int64_t lazy_throttle_ms;
    uint32_t inline_wait_ms;
    uint32_t inline_poll_ms;
    size_t arc_header_size;
};

struct WallpaperReconnectState {
    int64_t last_lazy_trigger_ms;
};

WallpaperReconnectConfig DefaultWallpaperReconnectConfig(size_t arc_header_size);

void* RunWallpaperDoBindWithRetry(const WallpaperReconnectOps& ops,
                                  const WallpaperReconnectConfig& cfg,
                                  void** arc_ref, void** runtime_ref,
                                  const char* reason, size_t reason_len);

void RunWallpaperGetServiceWithRecovery(const WallpaperReconnectOps& ops,
                                        const WallpaperReconnectConfig& cfg,
                                        WallpaperReconnectState* state,
                                        WallpaperServiceResult* out,
                                        void* inner);

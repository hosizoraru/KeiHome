// SPDX-License-Identifier: Apache-2.0
#include "wallpaper_reconnect_hooks.h"

#include <string.h>

#if defined(__ANDROID__)
#include "lsposed_hook_backend.h"
#include "monet_policy_server.h"
#include "wallpaper_reconnect_profile.h"

#include <android/log.h>
#include <link.h>
#include <pthread.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>
#endif

namespace {

constexpr char kReconnectPrefix[] = "reconnect";
constexpr size_t kReconnectPrefixLen = sizeof(kReconnectPrefix) - 1u;
constexpr char kLazyReason[] = "reconnect_lazy";
constexpr size_t kLazyReasonLen = sizeof(kLazyReason) - 1u;

bool IsReconnectReason(const char* reason, size_t reason_len) {
    return reason != nullptr && reason_len >= kReconnectPrefixLen &&
           memcmp(reason, kReconnectPrefix, kReconnectPrefixLen) == 0;
}

bool SleepCancellable(const WallpaperReconnectOps& ops, const void* arc_inner,
                      uint32_t total_ms, uint32_t slice_ms) {
    const uint32_t step = slice_ms > 0u ? slice_ms : 250u;
    uint32_t remaining = total_ms;
    while (remaining > 0u) {
        if (ops.is_destroyed(arc_inner)) return false;
        const uint32_t chunk = remaining < step ? remaining : step;
        ops.sleep_ms(chunk);
        remaining -= chunk;
    }
    return !ops.is_destroyed(arc_inner);
}

thread_local bool g_in_get_service_recovery = false;

}  // namespace

WallpaperReconnectConfig DefaultWallpaperReconnectConfig(size_t arc_header_size) {
    return WallpaperReconnectConfig{
        {2000u, 4000u, 8000u, 15000u, 20000u, 30000u},
        6u,
        250u,
        3000,
        500u,
        15u,
        arc_header_size,
    };
}

void* RunWallpaperDoBindWithRetry(const WallpaperReconnectOps& ops,
                                  const WallpaperReconnectConfig& cfg,
                                  void** arc_ref, void** runtime_ref,
                                  const char* reason, size_t reason_len) {
    void* err = ops.do_bind_service(arc_ref, runtime_ref, reason, reason_len);
    if (err == nullptr || arc_ref == nullptr || *arc_ref == nullptr ||
            !IsReconnectReason(reason, reason_len)) {
        return err;
    }

    const void* arc_inner = *arc_ref;
    const size_t steps = cfg.backoff_count <= WallpaperReconnectConfig::kMaxBackoffSteps
        ? cfg.backoff_count
        : WallpaperReconnectConfig::kMaxBackoffSteps;

    for (size_t i = 0u; i < steps; ++i) {
        if (!SleepCancellable(ops, arc_inner, cfg.backoff_ms[i],
                              cfg.sleep_slice_ms)) {
            if (ops.on_event != nullptr) ops.on_event("retry_cancelled", i + 1u);
            return err;
        }
        ops.drop_error(err);
        err = ops.do_bind_service(arc_ref, runtime_ref, reason, reason_len);
        if (err == nullptr) {
            if (ops.on_event != nullptr) ops.on_event("retry_succeeded", i + 1u);
            return nullptr;
        }
    }
    if (ops.on_event != nullptr) ops.on_event("retry_exhausted", steps);
    return err;
}

void RunWallpaperGetServiceWithRecovery(const WallpaperReconnectOps& ops,
                                        const WallpaperReconnectConfig& cfg,
                                        WallpaperReconnectState* state,
                                        WallpaperServiceResult* out,
                                        void* inner) {
    ops.get_service(out, inner);
    if (out == nullptr || out->data != nullptr || inner == nullptr ||
            state == nullptr || g_in_get_service_recovery) {
        return;
    }

    const uintptr_t inner_addr = reinterpret_cast<uintptr_t>(inner);
    if (inner_addr <= cfg.arc_header_size) return;
    void* arc_inner =
        reinterpret_cast<void*>(inner_addr - cfg.arc_header_size);

    // Only recover genuine "Service is not bound" states; never touch poisoned
    // mutexes, destroyed managers, or class-association errors on bound services.
    if (ops.is_destroyed(arc_inner) || ops.is_bound(arc_inner)) return;

    g_in_get_service_recovery = true;

    if (!ops.is_reconnecting(arc_inner)) {
        const int64_t now = ops.now_ms();
        int64_t prev =
            __atomic_load_n(&state->last_lazy_trigger_ms, __ATOMIC_ACQUIRE);
        if (prev == 0 || now - prev >= cfg.lazy_throttle_ms) {
            if (__atomic_compare_exchange_n(&state->last_lazy_trigger_ms, &prev,
                                            now, false, __ATOMIC_ACQ_REL,
                                            __ATOMIC_ACQUIRE)) {
                void* arc_ptr = arc_inner;
                if (ops.on_event != nullptr) ops.on_event("lazy_trigger", 0u);
                ops.trigger_reconnect(&arc_ptr, kLazyReason, kLazyReasonLen);
            }
        }
    }

    // Worker threads (app-frb-pool / ContentProvider binder) wait briefly so an
    // already-running MiuiWallPaperControllerService recovers inline without
    // throwing into Dart WallpaperGetxController.adaptHomeToWallpaper().
    if (!ops.is_ui_thread() && cfg.inline_wait_ms > 0u) {
        const uint32_t poll = cfg.inline_poll_ms > 0u ? cfg.inline_poll_ms : 15u;
        uint32_t waited = 0u;
        while (waited < cfg.inline_wait_ms) {
            if (ops.is_destroyed(arc_inner)) break;
            if (ops.is_bound(arc_inner)) {
                void* stale_err = out->second;
                WallpaperServiceResult retry_out{nullptr, nullptr};
                ops.get_service(&retry_out, inner);
                if (retry_out.data != nullptr) {
                    if (stale_err != nullptr) ops.drop_error(stale_err);
                    *out = retry_out;
                    if (ops.on_event != nullptr) {
                        ops.on_event("inline_recovered", waited);
                    }
                    break;
                }
                if (retry_out.second != nullptr) ops.drop_error(retry_out.second);
                break;
            }
            const uint32_t chunk =
                (cfg.inline_wait_ms - waited) < poll
                    ? (cfg.inline_wait_ms - waited)
                    : poll;
            ops.sleep_ms(chunk);
            waited += chunk;
        }
    }

    g_in_get_service_recovery = false;
}

#if defined(__ANDROID__)
namespace {

constexpr char kLogTag[] = "KeiHomeWallpaperReconnect";
constexpr char kTargetLibrary[] = "libapp_launcher.so";
constexpr size_t kMaxImageSpan = 128u * 1024u * 1024u;
constexpr size_t kMaxSegments = 16u;

using DoBindServiceFn = void* (*)(void**, void**, const char*, size_t);
using TriggerReconnectFn = void (*)(void**, const char*, size_t);
using GetServiceFn = void (*)(WallpaperServiceResult*, void*);
using DropErrorFn = void (*)(void*);

uint32_t g_installed = 0u;
WallpaperReconnectProfile g_profile{};
WallpaperReconnectConfig g_config{};
WallpaperReconnectState g_state{0};
DoBindServiceFn g_orig_do_bind = nullptr;
GetServiceFn g_orig_get_service = nullptr;

pid_t g_cached_pid = 0;
bool g_cached_is_launcher = false;

bool IsLauncherProcessCached() {
    const pid_t pid = getpid();
    if (__atomic_load_n(&g_cached_pid, __ATOMIC_ACQUIRE) == pid) {
        return __atomic_load_n(&g_cached_is_launcher, __ATOMIC_RELAXED);
    }
    char cmdline[128]{};
    bool launcher = false;
    FILE* f = fopen("/proc/self/cmdline", "re");
    if (f != nullptr) {
        const size_t n = fread(cmdline, 1u, sizeof(cmdline) - 1u, f);
        fclose(f);
        cmdline[n] = '\0';
        launcher = strcmp(cmdline, "com.miui.home") == 0;
    }
    __atomic_store_n(&g_cached_is_launcher, launcher, __ATOMIC_RELAXED);
    __atomic_store_n(&g_cached_pid, pid, __ATOMIC_RELEASE);
    return launcher;
}

bool IsUiThread() {
    char name[16]{};
    if (pthread_getname_np(pthread_self(), name, sizeof(name)) != 0) {
        return false;
    }
    const size_t n = strlen(name);
    return n > 3u && strcmp(name + n - 3u, ".ui") == 0;
}

int64_t NowMonotonicMs() {
    struct timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

void SleepMs(uint32_t ms) {
    struct timespec req{};
    req.tv_sec = static_cast<time_t>(ms / 1000u);
    req.tv_nsec = static_cast<long>((ms % 1000u) * 1000000u);
    nanosleep(&req, nullptr);
}

void DropAnyhowError(void* err) {
    if (err == nullptr) return;
    auto** vtable = *reinterpret_cast<DropErrorFn**>(err);
    vtable[0](err);
}

bool LoadAtomicBoolAt(const void* arc_inner, size_t offset) {
    const auto* byte = static_cast<const uint8_t*>(arc_inner) + offset;
    return __atomic_load_n(byte, __ATOMIC_ACQUIRE) != 0u;
}

bool IsDestroyed(const void* arc_inner) {
    return LoadAtomicBoolAt(arc_inner, g_profile.destroyed_offset);
}

bool IsReconnecting(const void* arc_inner) {
    return LoadAtomicBoolAt(arc_inner, g_profile.is_reconnecting_offset);
}

bool IsBound(const void* arc_inner) {
    return LoadAtomicBoolAt(arc_inner, g_profile.is_bound_offset);
}

void LogReconnectEvent(const char* event, size_t arg) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "event=%s arg=%zu",
                        event, arg);
}

WallpaperReconnectOps LiveOps() {
    return WallpaperReconnectOps{
        g_orig_do_bind,
        reinterpret_cast<TriggerReconnectFn>(g_profile.trigger_reconnect),
        g_orig_get_service,
        DropAnyhowError,
        IsDestroyed,
        IsReconnecting,
        IsBound,
        SleepMs,
        NowMonotonicMs,
        IsUiThread,
        LogReconnectEvent,
    };
}

void* HookedDoBindService(void** arc_ref, void** runtime_ref,
                          const char* reason, size_t reason_len) {
    if (!IsLauncherProcessCached()) {
        return g_orig_do_bind(arc_ref, runtime_ref, reason, reason_len);
    }
    return RunWallpaperDoBindWithRetry(LiveOps(), g_config, arc_ref,
                                       runtime_ref, reason, reason_len);
}

void HookedGetService(WallpaperServiceResult* out, void* inner) {
    if (!IsLauncherProcessCached()) {
        g_orig_get_service(out, inner);
        return;
    }
    RunWallpaperGetServiceWithRecovery(LiveOps(), g_config, &g_state, out,
                                       inner);
}

struct MappedRange {
    uintptr_t begin;
    uintptr_t end;
    uintptr_t cursor;
    bool executable;
};

struct LauncherImage {
    IconHighlightRange ranges[kMaxSegments]{};
    MappedRange mapped[kMaxSegments]{};
    IconHighlightImage image{};
    size_t matches = 0u;
    bool overflow = false;
};

int FindLauncherImage(dl_phdr_info* info, size_t, void* opaque) {
    const char* path = info->dlpi_name;
    const char* slash = path != nullptr ? strrchr(path, '/') : nullptr;
    const char* base_name = slash != nullptr ? slash + 1 : path;
    if (base_name == nullptr || strcmp(base_name, kTargetLibrary) != 0) return 0;

    auto& out = *static_cast<LauncherImage*>(opaque);
    ++out.matches;
    if (out.matches != 1u) return 0;

    const uintptr_t base = static_cast<uintptr_t>(info->dlpi_addr);
    out.image.base = reinterpret_cast<const uint8_t*>(base);
    out.image.ranges = out.ranges;
    for (size_t i = 0u; i < info->dlpi_phnum; ++i) {
        const auto& p = info->dlpi_phdr[i];
        if (p.p_type != PT_LOAD || (p.p_flags & PF_R) == 0u || p.p_memsz == 0u) {
            continue;
        }
        if (out.image.range_count == kMaxSegments ||
                p.p_vaddr > kMaxImageSpan ||
                p.p_memsz > kMaxImageSpan - p.p_vaddr ||
                p.p_vaddr > UINTPTR_MAX - base ||
                p.p_memsz > UINTPTR_MAX - (base + p.p_vaddr)) {
            out.overflow = true;
            return 1;
        }
        const size_t end = static_cast<size_t>(p.p_vaddr + p.p_memsz);
        const bool exec = (p.p_flags & PF_X) != 0u;
        const size_t idx = out.image.range_count++;
        out.ranges[idx] = {static_cast<size_t>(p.p_vaddr), end, exec};
        out.mapped[idx] = {base + p.p_vaddr, base + end, base + p.p_vaddr, exec};
        if (end > out.image.size) out.image.size = end;
    }
    return 0;
}

bool VerifySegmentsMapped(MappedRange* segments, size_t count) {
    if (count == 0u) return false;
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return false;
    char line[768];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (strchr(line, '\n') == nullptr) {
            int ch;
            while ((ch = fgetc(maps)) != '\n' && ch != EOF) {}
        }
        unsigned long long begin = 0u;
        unsigned long long finish = 0u;
        char perms[5]{};
        if (sscanf(line, "%llx-%llx %4s", &begin, &finish, perms) != 3) {
            continue;
        }
        const bool readable = perms[0] == 'r';
        const bool executable = perms[2] == 'x';
        bool all_done = true;
        for (size_t i = 0u; i < count; ++i) {
            MappedRange& seg = segments[i];
            if (seg.cursor >= seg.end) continue;
            if (finish > seg.cursor && begin <= seg.cursor) {
                if (readable && (!seg.executable || executable)) {
                    seg.cursor = finish < seg.end
                        ? static_cast<uintptr_t>(finish)
                        : seg.end;
                }
            }
            if (seg.cursor < seg.end) all_done = false;
        }
        if (all_done) break;
    }
    fclose(maps);
    for (size_t i = 0u; i < count; ++i) {
        if (segments[i].cursor != segments[i].end) return false;
    }
    return true;
}

}  // namespace

void InstallWallpaperReconnect() {
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(&g_installed, &expected, uint32_t{1},
                                     false, __ATOMIC_ACQ_REL,
                                     __ATOMIC_ACQUIRE)) {
        return;
    }

    LauncherImage loaded{};
    dl_iterate_phdr(FindLauncherImage, &loaded);
    if (loaded.matches == 0u) {
        // Not loaded yet (e.g. during hyos_spawner backfill before launcher
        // loads libapp_launcher.so). Leave state 0 so OnLsposedLibraryLoaded
        // will install once it actually loads.
        __atomic_store_n(&g_installed, uint32_t{0}, __ATOMIC_RELEASE);
        return;
    }

    const char* why = "missing or duplicate libapp_launcher.so";
    WallpaperReconnectProfile resolved{};
    if (loaded.matches != 1u || loaded.overflow ||
            !VerifySegmentsMapped(loaded.mapped, loaded.image.range_count) ||
            !ResolveWallpaperReconnect(loaded.image, &resolved, &why)) {
        __atomic_store_n(&g_installed, uint32_t{3}, __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "wallpaper reconnect hook disabled: %s", why);
        return;
    }

    g_profile = resolved;
    g_config = DefaultWallpaperReconnectConfig(resolved.arc_header_size);

    if (InstallInlineHook(reinterpret_cast<void*>(resolved.do_bind_service),
                          reinterpret_cast<void*>(HookedDoBindService),
                          reinterpret_cast<void**>(&g_orig_do_bind)) !=
            kHookSuccess) {
        __atomic_store_n(&g_installed, uint32_t{3}, __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "do_bind_service hook installation failed");
        return;
    }

    if (InstallInlineHook(reinterpret_cast<void*>(resolved.get_service),
                          reinterpret_cast<void*>(HookedGetService),
                          reinterpret_cast<void**>(&g_orig_get_service)) !=
            kHookSuccess) {
        RemoveInlineHook(reinterpret_cast<void*>(resolved.do_bind_service));
        g_orig_do_bind = nullptr;
        __atomic_store_n(&g_installed, uint32_t{3}, __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "get_service hook installation failed; rolled back");
        return;
    }

    __atomic_store_n(&kh_wallpaper_reconnect_installed, uint32_t{1}, __ATOMIC_RELEASE);
    __atomic_store_n(&g_installed, uint32_t{2}, __ATOMIC_RELEASE);
    __android_log_print(
        ANDROID_LOG_INFO, kLogTag,
        "wallpaper reconnect self-healing installed: bind=%p trig=%p get=%p "
        "offsets(hdr=0x%zx dest=0x%zx reconn=0x%zx bound=0x%zx)",
        reinterpret_cast<void*>(resolved.do_bind_service),
        reinterpret_cast<void*>(resolved.trigger_reconnect),
        reinterpret_cast<void*>(resolved.get_service),
        resolved.arc_header_size, resolved.destroyed_offset,
        resolved.is_reconnecting_offset, resolved.is_bound_offset);
}
#endif

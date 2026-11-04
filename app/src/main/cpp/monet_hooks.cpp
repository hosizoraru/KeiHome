// SPDX-License-Identifier: Apache-2.0
#include "monet_hooks.h"
#include "lsposed_hook_backend.h"
#include "monet_image_identity.h"
#include "monet_image_recovery.h"
#include "monet_policy_server.h"
#include "monet_tint_profile.h"
#include "selection_profile.h"
#include "icon_highlight_hooks.h"
#include "launcher_label_hooks.h"
#include "dock_glass_hooks.h"
#if defined(KH_MONET_IMAGE_TRACE)
#include "monet_image_trace.h"
#endif

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <string.h>

extern "C" {
void* kh_tint_original = nullptr;
void* kh_filter_equals_original = nullptr;
void* kh_monet_enabled_equals_original = nullptr;
void* kh_selection_original = nullptr;
}

namespace {
uint32_t g_install_state = 0;
struct Image {
    uintptr_t base;
    MonetTintImageRange tint_ranges[16];
    MonetSelectionImageRange selection_ranges[16];
    size_t count;
    bool valid;
};
int FindImage(dl_phdr_info* info, size_t, void* opaque) {
    auto* image = static_cast<Image*>(opaque);
    if (info->dlpi_addr != image->base) return 0;
    if (info->dlpi_phnum > 64) return 1;
    for (size_t i = 0; i < info->dlpi_phnum; ++i) {
        const auto& segment = info->dlpi_phdr[i];
        if (segment.p_type != PT_LOAD || !(segment.p_flags & PF_R)) continue;
        if (image->count == 16 || segment.p_vaddr > UINTPTR_MAX - image->base ||
            segment.p_memsz > UINTPTR_MAX - (image->base + segment.p_vaddr)) return 1;
        if (!segment.p_memsz) continue;
        const uintptr_t begin = image->base + segment.p_vaddr;
        const bool executable = (segment.p_flags & PF_X) != 0;
        image->tint_ranges[image->count] = {begin, size_t(segment.p_memsz), executable};
        image->selection_ranges[image->count++] = {begin, size_t(segment.p_memsz), executable};
    }
    image->valid = image->count != 0;
    return 1;
}
bool Reject(const char* reason) {
    __atomic_store_n(&g_install_state, 3u, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_WARN, "KeiHomeMonet", "Two-tone hooks inactive: %s", reason);
    return false;
}
} // namespace

bool ResolveLoadedMonochromeGate(void* handle, MonochromeGatePatch* out,
                                const char** rejection) {
    auto* snapshot = dlsym(handle, "_kDartIsolateSnapshotInstructions");
    Dl_info info{};
    if (!snapshot || !dladdr(snapshot, &info) || !info.dli_fbase) {
        if (rejection) *rejection = "missing gate snapshot anchor";
        return false;
    }
    Image image{};
    image.base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(FindImage, &image);
    MonochromeGateExecutableRange ranges[16];
    size_t count = 0, span = 0;
    if (!image.valid) {
        if (rejection) *rejection = "missing gate mapped ranges";
        return false;
    }
    for (size_t i = 0; i < image.count; ++i) {
        const auto& r = image.tint_ranges[i];
        const uintptr_t begin = r.begin - image.base, end = begin + r.size;
        if (end > span) span = end;
        if (r.executable) ranges[count++] = {begin, end};
    }
    return ResolveMonochromeGate({reinterpret_cast<const uint8_t*>(image.base), span,
        ranges, count}, out, rejection);
}

static bool InstallMonetFeatureAtLibraryLoad(void* handle) {
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&g_install_state, &expected, 1u, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return expected == 2;
    auto* snapshot = dlsym(handle, "_kDartIsolateSnapshotInstructions");
    auto* build_id = dlsym(handle, "_kDartSnapshotBuildId");
    Dl_info info{};
    if (!snapshot || !build_id || !dladdr(snapshot, &info) || !info.dli_fbase || !info.dli_fname) {
        return Reject("missing live snapshot anchors");
    }
    Image image{};
    image.base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(FindImage, &image);
    uint8_t digest[32];
    if (!image.valid || !HashMonetLibrary(info.dli_fname, digest)) {
        return Reject("backing library identity unavailable");
    }
    MonetTintProfile tint{};
    MonetSelectionProfile selection{};
    const char* failure = nullptr;
    if (!ResolveMonetTintProfile({image.base, reinterpret_cast<uintptr_t>(snapshot),
            reinterpret_cast<uintptr_t>(build_id), image.tint_ranges, image.count, digest},
            &tint, &failure)) return Reject(failure ? failure : "tint ABI");
    if (!ResolveMonetSelection({image.base, image.selection_ranges, image.count},
            &selection, &failure)) return Reject(failure ? failure : "selection ABI");
    if (!PublishMonetSelectionProfile(selection, digest)) return Reject("selection identity");
    PublishMonetTintCallers(tint);
    // Called only by the framework's first libapp load notification. No Dart
    // isolate is running this newly loaded image yet. All trampolines are set
    // before the socket can publish a policy; partial installation stays inert.
    if (InstallInlineHook(tint.tint_entry, reinterpret_cast<void*>(kh_monet_tint_bridge),
            &kh_tint_original) != kHookSuccess ||
        InstallInlineHook(tint.filter_equals_entry, reinterpret_cast<void*>(kh_monet_filter_equals_bridge),
            &kh_filter_equals_original) != kHookSuccess ||
        InstallInlineHook(selection.entry, reinterpret_cast<void*>(kh_monet_selection_bridge),
            &kh_selection_original) != kHookSuccess ||
        InstallInlineHook(tint.enabled_equals_entry, reinterpret_cast<void*>(kh_monet_enabled_equals_bridge),
            &kh_monet_enabled_equals_original) != kHookSuccess) return Reject("framework hook installation");
    // Process-lifetime capability, not transport liveness. A resolver or partial
    // hook failure cannot advertise Monet; later socket failure cannot undo
    // hooks that are already installed. The server independently gates flags 0/1.
    __atomic_store_n(&kh_monet_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&g_install_state, 2u, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, "KeiHomeMonet",
            "Two-tone native profile ready: dynamic tint=%p selection=%p "
            "filter_pool=0x%zx controller_slot=0x%zx cids=%u/%u/%u",
            tint.tint_entry, selection.entry, size_t(tint.filter_pool_offset),
            size_t(tint.controller_field_offset), tint.controller_cid, tint.rx_int_cid, tint.rx_bool_cid);
#if defined(KH_MONET_IMAGE_TRACE)
    InstallMonetImageTraceAtLibraryLoad({image.base, image.selection_ranges, image.count});
#endif
    InstallMonetImageRecoveryAtLibraryLoad({image.base, image.selection_ranges, image.count});
    return true;
}

bool InstallMonetHooksAtLibraryLoad(void* handle) {
    const bool dock_ready = PrepareDockGlassAtLibraryLoad(handle);
    const bool labels = InstallLauncherLabelsAtLibraryLoad(handle);
    const bool highlight = InstallIconHighlightAtLibraryLoad(handle);
    const bool monet = InstallMonetFeatureAtLibraryLoad(handle);
    const bool dock = dock_ready && InstallPreparedDockGlass();
    // Both attempts and their capability stores finish before any greeting.
    // Request admission uses the server's matching capability bit, so partial
    // Monet hooks cannot receive policy through a highlight-only endpoint.
    if ((monet || highlight || labels || dock) && !StartNativeMonetPolicyServer()) {
        __android_log_print(ANDROID_LOG_WARN, "KeiHomeMonet",
                "Native features installed (Monet=%u highlight=%u), "
                "authenticated policy transport unavailable",
                monet ? 1u : 0u, highlight ? 1u : 0u);
    }
    return monet;
}

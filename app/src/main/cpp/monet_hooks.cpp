// SPDX-License-Identifier: Apache-2.0
#include "monet_hooks.h"
#include "lsposed_hook_backend.h"
#include "monet_image_recovery.h"
#include "monet_policy_server.h"
#include "monet_tint_profile.h"
#include "selection_profile.h"
#include "icon_highlight_hooks.h"
#include "launcher_label_hooks.h"
#include "launcher_glass_material_hooks.h"
#include "dock_glass_hooks.h"
#include "launcher_form_factor.h"
#include "verified_elf_image.h"
#if defined(KH_MONET_IMAGE_TRACE)
#include "monet_image_trace.h"
#endif

#include <android/log.h>
#include <string.h>

extern "C" {
void* kh_tint_original = nullptr;
void* kh_filter_equals_original = nullptr;
void* kh_monet_enabled_equals_original = nullptr;
void* kh_selection_original = nullptr;
}

namespace {
uint32_t g_install_state = 0;
bool Reject(const char* reason) {
    __atomic_store_n(&g_install_state, 3u, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_WARN, "KeiHomeMonet", "Two-tone hooks inactive: %s", reason);
    return false;
}
} // namespace

bool ResolveLoadedMonochromeGate(void* handle, MonochromeGatePatch* out,
                                const char** rejection) {
    VerifiedDartImage image{};
    if (!BuildVerifiedDartImage(handle, &image, /*compute_sha256=*/false)) {
        if (rejection) *rejection = "missing gate snapshot anchor or mapped ranges";
        return false;
    }
    MonochromeGateExecutableRange ranges[16];
    size_t count = 0;
    for (size_t i = 0; i < image.count; ++i) {
        const auto& r = image.relative_ranges[i];
        if (r.executable) ranges[count++] = {r.begin, r.end};
    }
    return ResolveMonochromeGate({reinterpret_cast<const uint8_t*>(image.base), image.span,
        ranges, count}, out, rejection);
}

static bool InstallMonetFeatureForVerifiedImage(const VerifiedDartImage& image) {
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&g_install_state, &expected, 1u, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return expected == 2;
    if (!image.valid || !image.snapshot_instructions || !image.snapshot_build_id) {
        return Reject("missing live snapshot anchors");
    }
    if (!image.has_sha256) {
        return Reject("backing library identity unavailable");
    }
    MonetTintProfile tint{};
    MonetSelectionProfile selection{};
    const char* failure = nullptr;
    if (!ResolveMonetTintProfile({image.base, image.snapshot_instructions,
            image.snapshot_build_id, image.absolute_tint_ranges, image.count, image.sha256},
            &tint, &failure)) return Reject(failure ? failure : "tint ABI");
    if (!ResolveMonetSelection({image.base, image.absolute_selection_ranges, image.count},
            &selection, &failure)) return Reject(failure ? failure : "selection ABI");
    if (!PublishMonetSelectionProfile(selection, image.sha256)) return Reject("selection identity");
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
    InstallMonetImageTraceAtLibraryLoad({image.base, image.absolute_selection_ranges, image.count});
#endif
    InstallMonetImageRecoveryAtLibraryLoad({image.base, image.absolute_selection_ranges, image.count});
    return true;
}

bool InstallMonetHooksAtLibraryLoad(void* handle) {
    if (IsTabletLauncherDevice()) {
        // Current detours authenticate phone factories only. Until a Pad
        // contract exists, avoid both modifying and repeatedly scanning them.
        __android_log_print(ANDROID_LOG_INFO, "KeiHomeMonet",
                "Tablet launcher: preserving native features; status-only transport");
        StartNativeMonetPolicyServer();
        return false;
    }
    VerifiedDartImage verified{};
    BuildVerifiedDartImage(handle, &verified, /*compute_sha256=*/true);
    const bool glass_ready = PrepareLauncherGlassMaterialForVerifiedImage(verified);
    const bool dock_ready = PrepareDockGlassForVerifiedImage(verified);
    const bool labels = InstallLauncherLabelsForVerifiedImage(verified);
    const bool highlight = InstallIconHighlightForVerifiedImage(verified);
    const bool monet = InstallMonetFeatureForVerifiedImage(verified);
    const bool dock = dock_ready && InstallPreparedDockGlass();
    const bool glass = glass_ready && InstallPreparedLauncherGlassMaterial();
    // Both attempts and their capability stores finish before any greeting.
    // Request admission uses the server's matching capability bit, so partial
    // Monet hooks cannot receive policy through a highlight-only endpoint.
    // A verified launcher with no admitted feature still needs to report its
    // zero capability mask. Command handlers independently reject absent hooks.
    if (!(monet || highlight || labels || dock || glass)) {
        __android_log_print(ANDROID_LOG_INFO, "KeiHomeMonet",
                "No native feature contract admitted; status-only transport requested");
    }
    if (!StartNativeMonetPolicyServer()) {
        __android_log_print(ANDROID_LOG_WARN, "KeiHomeMonet",
                "Authenticated status transport unavailable "
                "(Monet=%u highlight=%u labels=%u dock=%u glass=%u)",
                monet ? 1u : 0u, highlight ? 1u : 0u, labels ? 1u : 0u,
                dock ? 1u : 0u, glass ? 1u : 0u);
    }
    return monet;
}

// SPDX-License-Identifier: Apache-2.0
// Execute the actual installer orchestration with explicit mock boundaries.
// No library/image is hooked, no transport/thread is started.
#include <dlfcn.h>
#include <link.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <initializer_list>

void* TestDlsym(void*, const char*);
int TestDladdr(const void*, Dl_info*);
int TestIterate(int (*)(dl_phdr_info*, size_t, void*), void*);
#define dlsym TestDlsym
#define dladdr TestDladdr
#define dl_iterate_phdr TestIterate
#include "monet_hooks.cpp"
#undef dlsym
#undef dladdr
#undef dl_iterate_phdr

extern "C" {
uint32_t kh_monet_installed = 0;
uint32_t kh_theme_materials_installed = 0;
uint32_t kh_theme_materials_enabled = 0;
void kh_monet_tint_bridge() {}
void kh_monet_filter_equals_bridge() {}
void kh_monet_selection_bridge() {}
void kh_monet_enabled_equals_bridge() {}
int __android_log_print(int, const char*, const char*, ...) { return 0; }
}

namespace {
unsigned checks = 0, hook_calls = 0, server_calls = 0, last_capabilities = 0;
unsigned fail_hook = 0;
bool anchors = true, hash_ok = true, tint_ok = true, selection_ok = true;
bool publication_ok = true, highlight_ok = true, server_ok = true;
bool tablet = false;
unsigned other_profile_calls = 0;
uint8_t fake_image[256] = {};
void Check(bool success, const char* name) {
    ++checks;
    if (!success) { std::fprintf(stderr, "FAIL %s\n", name); std::exit(1); }
}
void Reset() {
    g_install_state = 0; // Test cases represent independent process lifetimes.
    kh_monet_installed = kh_theme_materials_installed = kh_theme_materials_enabled = 0;
    hook_calls = server_calls = last_capabilities = fail_hook = 0;
    anchors = hash_ok = tint_ok = selection_ok = publication_ok = true;
    highlight_ok = server_ok = true;
    tablet = false;
    other_profile_calls = 0;
}
}
void* TestDlsym(void*, const char*) { return anchors ? fake_image : nullptr; }
int TestDladdr(const void*, Dl_info* info) {
    info->dli_fbase = fake_image;
    info->dli_fname = "synthetic-host-fixture";
    return 1;
}
int TestIterate(int (*callback)(dl_phdr_info*, size_t, void*), void* data) {
    const TestProgramHeader ph{PT_LOAD, PF_R | PF_X, 0, 0, 0, 256, 256, 4096};
    dl_phdr_info info{reinterpret_cast<uintptr_t>(fake_image), "", &ph, 1};
    return callback(&info, sizeof(info), data);
}
bool HashMonetLibrary(const char*, uint8_t hash[32]) {
    std::memset(hash, 1, 32); return hash_ok;
}
bool ResolveMonetTintProfile(const MonetTintImageView&, MonetTintProfile* p, const char**) {
    *p = {}; return tint_ok;
}
bool ResolveMonetSelection(const MonetSelectionImageView&, MonetSelectionProfile* p, const char**) {
    *p = {}; return selection_ok;
}
bool PublishMonetSelectionProfile(const MonetSelectionProfile&, const uint8_t[32]) {
    return publication_ok;
}
void PublishMonetTintCallers(const MonetTintProfile&) {}
bool ResolveMonochromeGate(const MonochromeGateImageView&, MonochromeGatePatch*, const char**) {
    return false;
}
int InstallInlineHook(void*, void*, void** original) {
    Check(kh_monet_installed == 0, "not ready before the final hook returns");
    ++hook_calls;
    *original = fake_image;
    return hook_calls == fail_hook ? kHookFailed : kHookSuccess;
}
bool InstallIconHighlightAtLibraryLoad(void*) {
    ++other_profile_calls;
    kh_theme_materials_installed = highlight_ok ? 1u : 0u;
    return highlight_ok;
}
bool IsTabletLauncherDevice() { return tablet; }
bool PrepareDockGlassAtLibraryLoad(void*) { ++other_profile_calls; return false; }
bool InstallPreparedDockGlass() { return false; }
bool PrepareLauncherGlassMaterialAtLibraryLoad(void*) { ++other_profile_calls; return false; }
bool InstallPreparedLauncherGlassMaterial() { return false; }
bool InstallLauncherLabelsAtLibraryLoad(void*) { ++other_profile_calls; return false; }
bool StartNativeMonetPolicyServer() {
    ++server_calls;
    last_capabilities = (kh_monet_installed == 1 ? 1u : 0u) |
                        (kh_theme_materials_installed == 1 ? 2u : 0u);
    return server_ok;
}
void InstallMonetImageRecoveryAtLibraryLoad(const MonetSelectionImageView&) {}

int main() {
    void* handle = fake_image;
    for (const char* value : {"tablet", "tablet,nosdcard", "default, tablet ", "tablet,foldable"})
        Check(HasTabletCharacteristic(value), "exact tablet characteristic token");
    for (const char* value : {"", "phone", "default", "notablet", "tabletish", "TABLET"})
        Check(!HasTabletCharacteristic(value), "do not guess tablet from substrings or width");
    Reset(); tablet = true;
    Check(!InstallMonetHooksAtLibraryLoad(handle), "tablet overrides not admitted");
    Check(!hook_calls && !other_profile_calls && g_install_state == 0,
          "tablet startup performs no phone profile scans or feature writes");
    Check(server_calls == 1 && last_capabilities == 0, "tablet can report zero capabilities");
    for (unsigned failed = 0; failed <= 4; ++failed) {
        for (bool highlight : {false, true}) {
            Reset(); fail_hook = failed; highlight_ok = highlight;
            const bool success = InstallMonetHooksAtLibraryLoad(handle);
            Check(success == (failed == 0), "Monet result is actual four-hook installation");
            Check(kh_monet_installed == (failed == 0 ? 1u : 0u), "partial failure cannot advertise Monet");
            Check(server_calls == 1u, "one diagnostic-capable start after completed attempts");
            if (server_calls)
                Check(last_capabilities == ((failed == 0 ? 1u : 0u) | (highlight ? 2u : 0u)),
                      "greeting sees only installed capabilities");
        }
    }
    for (bool* dependency : {&anchors, &hash_ok, &tint_ok, &selection_ok, &publication_ok}) {
        Reset(); *dependency = false;
        Check(!InstallMonetHooksAtLibraryLoad(handle), "dependency failure");
        Check(!hook_calls && kh_monet_installed == 0 && last_capabilities == 2,
              "resolver failure preserves independent highlight-only endpoint");
    }
    Reset(); anchors = false; highlight_ok = false;
    Check(!InstallMonetHooksAtLibraryLoad(handle), "unsupported image stays unsupported");
    Check(server_calls == 1 && last_capabilities == 0 && !hook_calls,
          "zero-capability endpoint reports rejection without installing feature hooks");
    Reset(); server_ok = false;
    Check(InstallMonetHooksAtLibraryLoad(handle), "transport failure does not un-install hooks");
    Check(kh_monet_installed == 1 && hook_calls == 4 && server_calls == 1,
          "actual readiness published before failed transport");
    server_ok = true;
    Check(InstallMonetHooksAtLibraryLoad(handle), "idempotent retry");
    Check(kh_monet_installed == 1 && hook_calls == 4 && server_calls == 2,
          "readiness stays monotonic without duplicate hook installation");
    Reset(); fail_hook = 2;
    Check(!InstallMonetHooksAtLibraryLoad(handle), "partial failure first call");
    fail_hook = 0;
    Check(!InstallMonetHooksAtLibraryLoad(handle) && kh_monet_installed == 0 && hook_calls == 2,
          "failed partial image is not repatched or later declared ready");
    std::printf("%u actual Monet installer readiness assertions passed\n", checks);
}

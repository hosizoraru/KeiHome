// SPDX-License-Identifier: Apache-2.0
#include "icon_highlight_hooks.h"
#include "icon_highlight_profile.h"
#include "lsposed_hook_backend.h"
#include "monet_image_identity.h"

#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

extern "C" {
extern const uint8_t kh_icon_highlight_template[kIconHighlightBridgeSize];
}

namespace {
uint32_t g_install_state = 0; // 0 new, 1 resolving, 2 installed, 3 rejected
constexpr char kTag[] = "KeiHomeHighlight";
struct Loaded {
    uintptr_t base;
    IconHighlightRange ranges[16];
    size_t count, span;
    bool valid;
};
bool ReadableMapping(uintptr_t start, size_t size, bool executable) {
    FILE* maps = fopen("/proc/self/maps", "re");
    if (!maps) return false;
    const uintptr_t end = start + size;
    uintptr_t cursor = start;
    char line[768];
    while (cursor < end && fgets(line, sizeof(line), maps)) {
        unsigned long long begin = 0, finish = 0;
        char permissions[5] = {};
        if (sscanf(line, "%llx-%llx %4s", &begin, &finish, permissions) != 3 ||
                finish <= cursor) continue;
        if (begin > cursor || permissions[0] != 'r' ||
                (executable && permissions[2] != 'x')) break;
        cursor = finish < end ? uintptr_t(finish) : end;
    }
    fclose(maps);
    return cursor == end;
}
int FindImage(dl_phdr_info* info, size_t, void* opaque) {
    auto& image = *static_cast<Loaded*>(opaque);
    if (info->dlpi_addr != image.base) return 0;
    if (info->dlpi_phnum > 64) return 1;
    for (size_t i = 0; i < info->dlpi_phnum; ++i) {
        const auto& p = info->dlpi_phdr[i];
        if (p.p_type != PT_LOAD || !(p.p_flags & PF_R) || !p.p_memsz) continue;
        if (image.count == 16 || p.p_vaddr > UINTPTR_MAX - image.base ||
                p.p_memsz > UINTPTR_MAX - image.base - p.p_vaddr ||
                p.p_vaddr + p.p_memsz > 128u * 1024u * 1024u) return 1;
        const bool executable = (p.p_flags & PF_X) != 0;
        if (!ReadableMapping(image.base + p.p_vaddr, p.p_memsz, executable)) return 1;
        const size_t end = p.p_vaddr + p.p_memsz;
        image.ranges[image.count++] = {size_t(p.p_vaddr), end, executable};
        if (end > image.span) image.span = end;
    }
    image.valid = image.count != 0;
    return 1;
}
bool Reject(const char* why) {
    __atomic_store_n(&kh_theme_materials_enabled, 0u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_theme_materials_installed, 0u, __ATOMIC_RELEASE);
    __atomic_store_n(&g_install_state, 3u, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_WARN, kTag, "Icon highlight inactive: %s", why);
    return false;
}
} // namespace

bool IsIconHighlightInstalled() {
    return __atomic_load_n(&kh_theme_materials_installed, __ATOMIC_ACQUIRE) == 1;
}
bool SetIconHighlightEnabled(bool enabled) {
    const bool installed = IsIconHighlightInstalled();
    __atomic_store_n(&kh_theme_materials_enabled, enabled && installed ? 1u : 0u,
                     __ATOMIC_RELEASE);
    return installed;
}
bool IsIconHighlightEnabled() {
    return __atomic_load_n(&kh_theme_materials_enabled, __ATOMIC_ACQUIRE) == 1;
}

bool InstallIconHighlightAtLibraryLoad(void* handle) {
    uint32_t expected_state = 0;
    if (!__atomic_compare_exchange_n(&g_install_state, &expected_state, 1u, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return expected_state == 2;
    if (!handle) return Reject("missing fresh library");
    void* snapshot = dlsym(handle, "_kDartIsolateSnapshotInstructions");
    Dl_info info{};
    if (!snapshot || !dladdr(snapshot, &info) || !info.dli_fbase || !info.dli_fname)
        return Reject("missing live Dart image anchor");
    Loaded loaded{};
    loaded.base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(FindImage, &loaded);
    uint8_t digest[32] = {};
    if (!loaded.valid || !HashMonetLibrary(info.dli_fname, digest))
        return Reject("mapped/backing image unavailable");
    IconHighlightProfile profile{};
    const char* why = nullptr;
    const IconHighlightImage image{reinterpret_cast<const uint8_t*>(loaded.base),
        loaded.span, loaded.ranges, loaded.count};
    if (!ResolveIconHighlight(image, &profile, &why))
        return Reject(why ? why : "highlight contract");
    const long queried = sysconf(_SC_PAGESIZE);
    if (queried < 4096 || queried > 65536 || (queried & (queried - 1)))
        return Reject("unsupported page size");
    const size_t size = size_t(queried);
    void* bridge = mmap(nullptr, size, PROT_READ | PROT_WRITE,
                        MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (bridge == MAP_FAILED) return Reject("bridge allocation");
    if (!BuildIconHighlightBridge(profile, kh_icon_highlight_template,
            kIconHighlightBridgeSize, reinterpret_cast<uintptr_t>(&kh_theme_materials_enabled),
            static_cast<uint8_t*>(bridge))) {
        munmap(bridge, size);
        return Reject("bridge template ABI");
    }
    __builtin___clear_cache(static_cast<char*>(bridge),
                           static_cast<char*>(bridge) + kIconHighlightBridgeSize);
    if (mprotect(bridge, size, PROT_READ | PROT_EXEC)) {
        munmap(bridge, size);
        return Reject("bridge RX publication");
    }
    uint32_t replacement[4];
    if (!BuildIconHighlightDetour(reinterpret_cast<uintptr_t>(bridge), replacement)) {
        munmap(bridge, size);
        return Reject("absolute bridge transfer");
    }
    // Recheck the whole resolved graph immediately before publishing code.
    IconHighlightProfile current{};
    if (!ResolveIconHighlight(image, &current, &why) ||
            profile.entry != current.entry || profile.material_disabled != current.material_disabled ||
            profile.material_supported != current.material_supported ||
            profile.theme_writer != current.theme_writer || profile.stroke_consumer != current.stroke_consumer ||
            profile.patch != current.patch || profile.false_target != current.false_target ||
            profile.theme_field != current.theme_field || profile.device_slot != current.device_slot ||
            profile.support_slot != current.support_slot || profile.style_slot != current.style_slot ||
            profile.os_field != current.os_field || memcmp(profile.expected, current.expected, 16)) {
        munmap(bridge, size);
        return Reject("image changed before installation");
    }
    // From this point the bridge remains process-lifetime even on failure:
    // a protection-restore failure can leave the disabled detour reachable.
    if (PatchArm64HighlightTail16(reinterpret_cast<void*>(profile.patch),
            profile.expected, replacement, true) != kHookSuccess)
        return Reject("exact16 expected-byte/protection transaction");
    __atomic_store_n(&g_install_state, 2u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_theme_materials_installed, 1u, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, kTag,
            "Icon highlight installed, default off: tail=%p false=%p field=0x%x "
            "identity=%02x%02x%02x%02x; mounted refresh unverified",
            reinterpret_cast<void*>(profile.patch), reinterpret_cast<void*>(profile.false_target),
            profile.theme_field, digest[0], digest[1], digest[2], digest[3]);
    return true;
}

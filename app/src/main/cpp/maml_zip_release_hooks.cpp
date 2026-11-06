// SPDX-License-Identifier: Apache-2.0
#include "maml_zip_release_hooks.h"
#include "maml_zip_release_profile.h"
#include "deferred_zip_release.h"
#include "lsposed_hook_backend.h"

#include <android/log.h>
#include <link.h>
#include <pthread.h>
#include <string.h>
#include <unistd.h>

extern "C" {
// Private diagnostic gate permits paired profiling in one authenticated native
// process. No app-facing setting or unprivileged transport can write it.
uint32_t kh_maml_zip_release_enabled = 1;
}

namespace {
ZipMetadataRelease original = nullptr;
uint32_t installed = 0;
pid_t specialized_launcher_pid = 0;
struct Image {
    IconHighlightRange ranges[16]{};
    IconHighlightImage image{};
    size_t matches = 0;
};
int FindImage(dl_phdr_info* info, size_t, void* opaque) {
    const char* path = info->dlpi_name;
    const char* slash = path ? strrchr(path, '/') : nullptr;
    if (!path || strcmp(slash ? slash + 1 : path, "librust_maml_sdk.so")) return 0;
    auto& out = *static_cast<Image*>(opaque);
    ++out.matches;
    if (out.matches != 1) return 0;
    out.image.base = reinterpret_cast<const uint8_t*>(info->dlpi_addr);
    out.image.ranges = out.ranges;
    for (size_t i = 0; i < info->dlpi_phnum; ++i) {
        const auto& p = info->dlpi_phdr[i];
        if (p.p_type != PT_LOAD || !(p.p_flags & PF_R) || !p.p_memsz) continue;
        if (out.image.range_count == 16 || p.p_vaddr > 128u * 1024u * 1024u ||
                p.p_memsz > 128u * 1024u * 1024u - p.p_vaddr) {
            out.matches = 2; return 1;
        }
        const size_t end = p.p_vaddr + p.p_memsz;
        out.ranges[out.image.range_count++] = {p.p_vaddr, end, bool(p.p_flags & PF_X)};
        if (end > out.image.size) out.image.size = end;
    }
    return 0;
}
bool IsLauncherUi() {
    // Only the process-authenticated policy worker can prepare this owner.
    // Inherited spawner/other-app hooks keep synchronous cleanup.
    if (__atomic_load_n(&specialized_launcher_pid, __ATOMIC_ACQUIRE) != getpid())
        return false;
    char name[16]{};
    if (pthread_getname_np(pthread_self(), name, sizeof(name))) return false;
    const size_t n = strlen(name);
    return n > 3 && !strcmp(name + n - 3, ".ui");
}
void Release(void* allocation) {
    // The native MAML finish/destroy sequence stays synchronous. Only final ZIP
    // entry metadata destruction moves; there are no Dart/GPU objects here.
    if (!__atomic_load_n(&kh_maml_zip_release_enabled, __ATOMIC_RELAXED) ||
            !IsLauncherUi() || !TryDeferZipMetadata(allocation, original))
        original(allocation);
}
}

void InstallMamlZipRelease() {
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&installed, &expected, uint32_t{1}, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return;
    Image loaded{};
    dl_iterate_phdr(FindImage, &loaded);
    uintptr_t target = 0;
    const char* why = "missing or duplicate Rust MAML image";
    if (loaded.matches != 1 ||
            !ResolveMamlZipRelease(loaded.image, &target, &why)) {
        __atomic_store_n(&installed, uint32_t{3}, __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_INFO, "KeiHomeMamlRelease",
                           "native cleanup retained: %s", why);
        return;
    }
    if (InstallInlineHook(reinterpret_cast<void*>(target),
            reinterpret_cast<void*>(Release), reinterpret_cast<void**>(&original))
            != kHookSuccess) {
        __atomic_store_n(&installed, uint32_t{3}, __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_WARN, "KeiHomeMamlRelease",
                           "ZIP metadata release hook unavailable");
        return;
    }
    __atomic_store_n(&installed, uint32_t{2}, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, "KeiHomeMamlRelease",
                       "bounded ZIP metadata cleanup installed, capacity=64");
}

void PrepareMamlZipReleaseWorker() {
    if (__atomic_load_n(&installed, __ATOMIC_ACQUIRE) == 2) {
        __atomic_store_n(&specialized_launcher_pid, getpid(), __ATOMIC_RELEASE);
        StartDeferredZipRelease();
    }
}

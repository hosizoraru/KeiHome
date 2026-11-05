// SPDX-License-Identifier: Apache-2.0
#include "launcher_glass_patch.h"
#include <sys/mman.h>
#include <unistd.h>

bool LauncherGlassBranch(uintptr_t from, uintptr_t to, uint32_t* word) {
    const int64_t distance = int64_t(to) - int64_t(from);
    if (!word || ((from | to) & 3u) ||
            distance < -0x8000000LL || distance >= 0x8000000LL) return false;
    *word = 0x14000000u | (uint32_t(distance / 4) & 0x03ffffffu);
    return true;
}
void* AllocateLauncherGlassCode(const uintptr_t* sites, size_t count, size_t bytes) {
    const auto page = size_t(sysconf(_SC_PAGESIZE));
    if (!sites || !count || !bytes || page == 0 || (page & (page - 1)) ||
            (bytes & (page - 1))) return nullptr;
    const uintptr_t aligned = sites[0] & ~(uintptr_t(page) - 1);
    for (uintptr_t distance = 0x1000000; distance < 0x7000000; distance += 0x1000000) {
        for (int sign = -1; sign <= 1; sign += 2) {
            if ((sign < 0 && aligned < distance) ||
                    (sign > 0 && aligned > UINTPTR_MAX - distance)) continue;
            const auto hint = sign > 0 ? aligned + distance : aligned - distance;
            void* code = mmap(reinterpret_cast<void*>(hint), bytes, PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (code == MAP_FAILED) continue;
            bool near = true;
            for (size_t n = 0; n < count; ++n) {
                uint32_t ignored;
                if (!LauncherGlassBranch(sites[n], reinterpret_cast<uintptr_t>(code), &ignored) ||
                        !LauncherGlassBranch(sites[n],
                                reinterpret_cast<uintptr_t>(code) + bytes - 4, &ignored))
                    near = false;
            }
            if (near) return code;
            munmap(code, bytes);
        }
    }
    return nullptr;
}
bool SealLauncherGlassCode(void* code, size_t bytes) {
    if (!code || !bytes) return false;
    __builtin___clear_cache(static_cast<char*>(code), static_cast<char*>(code) + bytes);
    if (mprotect(code, bytes, PROT_READ | PROT_EXEC) != 0) return false;
    return true;
}

// SPDX-License-Identifier: Apache-2.0
#include "launcher_glass_material_hooks.h"
#include "launcher_glass_material_bridge.h"
#include "launcher_glass_material_profile.h"
#include "launcher_glass_patch.h"
#include "launcher_glass_policy.h"
#include "lsposed_hook_backend.h"
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

namespace {
uint32_t gPhase = 0;
struct Loaded {
    uintptr_t base = 0;
    size_t count = 0, span = 0;
    bool valid = false;
    IconHighlightRange ranges[16]{};
};
Loaded gImage;
LauncherGlassMaterialProfile gProfile{};
LauncherGlassMaterialChooseSites gExtra{};
bool Readable(uintptr_t start, size_t size, bool executable) {
    if (!size || size > UINTPTR_MAX - start) return false;
    FILE* file = fopen("/proc/self/maps", "re");
    if (!file) return false;
    uintptr_t cursor = start;
    const uintptr_t end = start + size;
    char line[768];
    while (cursor < end && fgets(line, sizeof(line), file)) {
        unsigned long long a, b;
        char access[5]{};
        if (sscanf(line, "%llx-%llx %4s", &a, &b, access) != 3 || b <= cursor) continue;
        if (a > cursor || access[0] != 'r' || (executable && access[2] != 'x')) break;
        cursor = b < end ? uintptr_t(b) : end;
    }
    fclose(file);
    return cursor == end;
}
int FindImage(dl_phdr_info* info, size_t, void*) {
    if (info->dlpi_addr != gImage.base) return 0;
    if (info->dlpi_phnum > 64) return 1;
    for (size_t n = 0; n < info->dlpi_phnum; ++n) {
        const auto& segment = info->dlpi_phdr[n];
        if (segment.p_type != PT_LOAD || !(segment.p_flags & PF_R) || !segment.p_memsz)
            continue;
        if (gImage.count == 16 || segment.p_vaddr > UINTPTR_MAX - gImage.base ||
                segment.p_memsz > 128u * 1024u * 1024u ||
                segment.p_vaddr > 128u * 1024u * 1024u - segment.p_memsz)
            return 1;
        const bool executable = segment.p_flags & PF_X;
        if (!Readable(gImage.base + segment.p_vaddr, segment.p_memsz, executable)) return 1;
        const size_t end = segment.p_vaddr + segment.p_memsz;
        gImage.ranges[gImage.count++] = {size_t(segment.p_vaddr), end, executable};
        if (gImage.span < end) gImage.span = end;
    }
    gImage.valid = gImage.count != 0;
    return 1;
}
bool Reject(const char* why) {
    __atomic_store_n(&gPhase, 4u, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_WARN, "KeiHomeGlass", "Material policy inactive: %s",
            why ? why : "source proof");
    return false;
}
uint32_t ReadWord(size_t relative) {
    uint32_t word;
    memcpy(&word, reinterpret_cast<const void*>(gImage.base + relative), 4);
    return word;
}
size_t Offset(const uint8_t* entry) {
    return size_t(entry - kh_glass_material_begin);
}
}
bool IsLauncherGlassInstalled() {
    return __atomic_load_n(&gPhase, __ATOMIC_ACQUIRE) == 3u &&
            __atomic_load_n(&kh_launcher_glass_installed, __ATOMIC_ACQUIRE) == 1u;
}
bool PrepareLauncherGlassMaterialAtLibraryLoad(void* handle) {
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&gPhase, &expected, 1u, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return expected == 2u || expected == 3u;
    void* snapshot = handle ? dlsym(handle, "_kDartIsolateSnapshotInstructions") : nullptr;
    Dl_info information{};
    if (!snapshot || !dladdr(snapshot, &information) || !information.dli_fbase)
        return Reject("Dart instructions identity");
    gImage.base = reinterpret_cast<uintptr_t>(information.dli_fbase);
    dl_iterate_phdr(FindImage, nullptr);
    const IconHighlightImage image{reinterpret_cast<const uint8_t*>(gImage.base),
        gImage.span, gImage.ranges, gImage.count};
    LauncherLabelProfile labels{};
    const char* why = nullptr;
    if (!gImage.valid || !ResolveLauncherLabelState(image, &labels, &why) ||
            !ResolveLauncherGlassMaterial(image, labels, &gProfile, &why))
        return Reject(why);
    if (!ResolveLauncherGlassSearchCapsule(image, gProfile, &gExtra, &why))
        return Reject(why);
    __atomic_store_n(&gPhase, 2u, __ATOMIC_RELEASE);
    return true;
}
bool InstallPreparedLauncherGlassMaterial() {
    uint32_t expected = 2u;
    if (!__atomic_compare_exchange_n(&gPhase, &expected, 5u, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return IsLauncherGlassInstalled();
    const auto& p = gProfile;
    struct Choice {
        size_t branch;
        uint32_t word;
        size_t lightScheme, darkScheme;
        uint64_t policyShift;
    };
    Choice choices[1 + kLauncherGlassMaterialExtraChooseCapacity]{};
    choices[0] = {p.branch, p.branchWord, p.darkTarget, p.lightTarget, 0u};
    const size_t count = 1 + gExtra.count;
    if (count > 1 + kLauncherGlassMaterialExtraChooseCapacity)
        return Reject("native material scope capacity");
    for (size_t i = 0; i < gExtra.count; ++i) {
        const auto& site = gExtra.sites[i];
        if (!site.lightSchemeTarget || !site.darkSchemeTarget)
            return Reject("native material scheme polarity");
        uint64_t shift;
        switch (site.kind) {
            case kLauncherGlassMaterialSearchCapsule: shift = 2u; break;
            case kLauncherGlassMaterialFolder:
            case kLauncherGlassMaterialFolderDefaultColors: shift = 0u; break;
            default: return Reject("native material policy role");
        }
        choices[i + 1] = {site.branch, site.word,
            site.lightSchemeTarget, site.darkSchemeTarget, shift};
    }
    const size_t length = size_t(kh_glass_material_end - kh_glass_material_begin);
    const long page = sysconf(_SC_PAGESIZE);
    if (page <= 0 || !length || (length & 7u) || length > size_t(page))
        return Reject("material template ABI");
    uintptr_t sites[4 + kLauncherGlassMaterialExtraChooseCapacity]{};
    for (size_t i = 0; i < count; ++i) sites[i] = gImage.base + choices[i].branch;
    sites[count] = gImage.base + p.returnPc;
    size_t siteCount = count + 1;
    const size_t bytes = (length * count + size_t(page) - 1u) & ~(size_t(page) - 1u);
    auto* arena = static_cast<uint8_t*>(AllocateLauncherGlassCode(sites, siteCount, bytes));
    if (!arena) return Reject("near executable space");
    const auto fail = [&](const char* why) {
        munmap(arena, bytes);
        return Reject(why);
    };
    uint32_t entryBranches[1 + kLauncherGlassMaterialExtraChooseCapacity]{};
    const auto local = int64_t(Offset(kh_glass_material_taken)) -
                       int64_t(Offset(kh_glass_material_original));
    LauncherGlassMaterialConfig config{
        reinterpret_cast<uintptr_t>(&kh_launcher_glass_installed),
        reinterpret_cast<uintptr_t>(&kh_launcher_glass_control),
        reinterpret_cast<uintptr_t>(&kh_glass_material_revision),
        reinterpret_cast<uintptr_t>(&kh_glass_material_notified),
        p.controllerSlot, uint64_t(p.wallpaperRxField), p.rxSubjectField, p.rxValueField,
        p.observerSlot, gImage.base + p.refreshEntry, gImage.base + p.returnPc,
        p.nativeTrueOffset, p.nativeFalseOffset,
        p.subjectListenersField, p.subjectBusyField, p.subjectListenerLengthField, 0u};
    for (size_t i = 0; i < count; ++i) {
        const auto& choice = choices[i];
        const bool testBit = (choice.word & 0x7e000000u) == 0x36000000u;
        const bool condition = (choice.word & 0xff000010u) == 0x54000000u;
        const bool compareZero = (choice.word & 0x7e000000u) == 0x34000000u;
        if (!testBit && !condition && !compareZero)
            return fail("native material conditional");
        const unsigned immediateBits = testBit ? 14u : 19u;
        const uint32_t immediateMask = (1u << immediateBits) - 1u;
        const int64_t limit = int64_t{1} << (immediateBits + 1u);
        if ((local & 3) || local < -limit || local >= limit)
            return fail("native conditional relocation");
        auto* code = arena + length * i;
        memcpy(code, kh_glass_material_begin, length);
        const auto patch = [&](const uint8_t* at, uint32_t word) {
            memcpy(code + Offset(at), &word, 4);
        };
        const auto jump = [&](const uint8_t* at, size_t native) {
            uint32_t word;
            if (!LauncherGlassBranch(reinterpret_cast<uintptr_t>(code + Offset(at)),
                    gImage.base + native, &word)) return false;
            patch(at, word);
            return true;
        };
        int64_t taken = int64_t((choice.word >> 5u) & immediateMask);
        if (taken & (1u << (immediateBits - 1u))) taken -= int64_t{1} << immediateBits;
        const size_t nativeTaken = size_t(int64_t(choice.branch) + taken * 4);
        patch(kh_glass_material_original, (choice.word & ~(immediateMask << 5u)) |
                ((uint32_t(local / 4) & immediateMask) << 5u));
        patch(kh_glass_material_event_original, p.replayWord);
        if (!jump(kh_glass_material_light, choice.lightScheme) ||
                !jump(kh_glass_material_dark, choice.darkScheme) ||
                !jump(kh_glass_material_taken, nativeTaken) ||
                !jump(kh_glass_material_fall, choice.branch + 4) ||
                !jump(kh_glass_material_event_resume, p.resume))
            return fail("native continuations");
        config.policyShift = choice.policyShift;
        memcpy(code + Offset(kh_glass_material_config), &config, sizeof(config));
        if (!LauncherGlassBranch(sites[i], reinterpret_cast<uintptr_t>(
                code + Offset(kh_glass_material_choose)), &entryBranches[i]) ||
                ReadWord(choice.branch) != choice.word)
            return fail("live native conditional");
    }
    uint32_t refresh;
    if (!LauncherGlassBranch(sites[count], reinterpret_cast<uintptr_t>(
            arena + Offset(kh_glass_material_refresh)), &refresh) ||
            ReadWord(p.returnPc) != p.replayWord || !SealLauncherGlassCode(arena, bytes))
        return fail("live checkpoint/RX publication");
    // Before installed=1 every leaf replays native. A partial publication must
    // retain its reachable RX arena; no live code is unmapped or rolled back.
    if (PatchArm64Instruction(reinterpret_cast<void*>(sites[count]), p.replayWord, refresh))
        return Reject("native material checkpoint");
    for (size_t i = 0; i < count; ++i) {
        if (PatchArm64Instruction(reinterpret_cast<void*>(sites[i]),
                choices[i].word, entryBranches[i]))
            return Reject("native material branch");
    }
    __atomic_store_n(&kh_launcher_search_glass_installed,
            uint32_t{(gExtra.admittedMask & kLauncherGlassMaterialSearchCapsule) != 0u},
            __ATOMIC_RELAXED);
    __atomic_store_n(&kh_launcher_glass_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&gPhase, 3u, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, "KeiHomeGlass",
            "Native material installed: sites=%zu extraScopes=%u ambiguous=%u",
            count, gExtra.admittedMask, gExtra.ambiguousMask);
    return true;
}

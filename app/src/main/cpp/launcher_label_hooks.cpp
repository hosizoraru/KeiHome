// SPDX-License-Identifier: Apache-2.0
#include "launcher_label_hooks.h"
#include "launcher_label_profile.h"
#include "lsposed_hook_backend.h"
#include "monet_policy_server.h"
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

extern "C" const uint8_t kh_label_template[], kh_label_pre[], kh_label_post[], kh_label_lines[],
        kh_label_config[], kh_label_end[], kh_label_refresh[], kh_label_page_post[], kh_label_page_replay[], kh_label_fade[];
namespace {
uint32_t installed = 0;
struct Loaded { uintptr_t base; IconHighlightRange ranges[16]; size_t count, span; bool valid; };
bool Readable(uintptr_t start, size_t size, bool exec) {
    FILE* file = fopen("/proc/self/maps", "re"); if (!file) return false;
    const uintptr_t end = start + size; uintptr_t cursor = start; char line[768];
    while (cursor < end && fgets(line, sizeof(line), file)) {
        unsigned long long a = 0, b = 0; char p[5] = {};
        if (sscanf(line, "%llx-%llx %4s", &a, &b, p) != 3 || b <= cursor) continue;
        if (a > cursor || p[0] != 'r' || (exec && p[2] != 'x')) break;
        cursor = b < end ? uintptr_t(b) : end;
    }
    fclose(file); return cursor == end;
}
int FindImage(dl_phdr_info* info, size_t, void* opaque) {
    auto& im = *static_cast<Loaded*>(opaque); if (info->dlpi_addr != im.base) return 0;
    if (info->dlpi_phnum > 64) return 1;
    for (size_t i = 0; i < info->dlpi_phnum; ++i) {
        const auto& p = info->dlpi_phdr[i];
        if (p.p_type != PT_LOAD || !(p.p_flags & PF_R) || !p.p_memsz) continue;
        if (im.count == 16 || p.p_vaddr > UINTPTR_MAX - im.base ||
                p.p_memsz > UINTPTR_MAX - im.base - p.p_vaddr ||
                p.p_vaddr + p.p_memsz > 128u*1024u*1024u) return 1;
        const bool exec = p.p_flags & PF_X;
        if (!Readable(im.base + p.p_vaddr, p.p_memsz, exec)) return 1;
        const size_t end = p.p_vaddr + p.p_memsz;
        im.ranges[im.count++] = {size_t(p.p_vaddr), end, exec};
        if (end > im.span) im.span = end;
    }
    im.valid = im.count != 0; return 1;
}
bool Reject(const char* why) {
    __android_log_print(ANDROID_LOG_WARN, "KeiHomeLabels", "Labels inactive: %s", why);
    return false;
}
void* AllocateNear(uintptr_t call, uintptr_t refresh_call, uintptr_t page_post, uintptr_t fade_call, size_t size) {
    const uintptr_t aligned = call & ~(uintptr_t(size) - 1);
    for (uintptr_t distance = 0x1000000; distance < 0x7000000; distance += 0x1000000) {
        for (int sign = -1; sign <= 1; sign += 2) {
            const uintptr_t hint = sign > 0 ? aligned + distance : aligned - distance;
            void* p = mmap(reinterpret_cast<void*>(hint), size, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            if (p == MAP_FAILED) continue;
            const int64_t delta = int64_t(reinterpret_cast<uintptr_t>(p)) - int64_t(call);
            const int64_t refresh_delta = int64_t(reinterpret_cast<uintptr_t>(p)) - int64_t(refresh_call);
            const int64_t page_delta = int64_t(reinterpret_cast<uintptr_t>(p)) - int64_t(page_post);
            const int64_t fade_delta = int64_t(reinterpret_cast<uintptr_t>(p)) - int64_t(fade_call);
            if (delta >= -0x8000000 && delta < 0x8000000 &&
                    refresh_delta >= -0x8000000 && refresh_delta < 0x8000000 &&
                    page_delta >= -0x8000000 && page_delta < 0x8000000 &&
                    fade_delta >= -0x8000000 && fade_delta < 0x8000000) return p;
            munmap(p, size);
        }
    }
    return nullptr;
}
void Detour(uintptr_t bridge, uint32_t words[4]) {
    words[0] = 0x58000050; words[1] = 0xd61f0200;
    words[2] = uint32_t(bridge); words[3] = uint32_t(bridge >> 32);
}
} // namespace

bool InstallLauncherLabelsAtLibraryLoad(void* handle) {
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&installed, &expected, 1u, false,
            __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return expected == 2;
    void* snapshot = handle ? dlsym(handle, "_kDartIsolateSnapshotInstructions") : nullptr;
    Dl_info info{};
    if (!snapshot || !dladdr(snapshot, &info) || !info.dli_fbase) return Reject("missing live image");
    Loaded loaded{}; loaded.base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(FindImage, &loaded);
    const IconHighlightImage image{reinterpret_cast<const uint8_t*>(loaded.base), loaded.span,
                                  loaded.ranges, loaded.count};
    LauncherLabelProfile p{}; const char* why = nullptr;
    if (!loaded.valid || !ResolveLauncherLabels(image, &p, &why)) return Reject(why ? why : "incomplete image");
    const long queried = sysconf(_SC_PAGESIZE);
    const size_t bytes = size_t(kh_label_end - kh_label_template);
    const size_t config = size_t(kh_label_config - kh_label_template);
    if (queried < 4096 || queried > 65536 || (queried & (queried-1)) || bytes > size_t(queried) ||
            config + 288 != bytes) return Reject("bridge ABI");
    auto* bridge = static_cast<uint8_t*>(AllocateNear(loaded.base + p.allocation_call,
            loaded.base + p.refresh_call, loaded.base + p.cell_call + 4, loaded.base + p.fade_call, size_t(queried)));
    if (!bridge) return Reject("near bridge unavailable");
    memcpy(bridge, kh_label_template, bytes);
    const uint64_t data[] = {
        reinterpret_cast<uintptr_t>(&kh_launcher_label_mode), loaded.base + p.text_allocator,
        loaded.base + p.measure, loaded.base + p.marquee_constructor,
        loaded.base + p.allocation_call + 20, loaded.base + p.after_text_init,
        p.text_cid, p.size_cid, p.marquee_tag, p.grid_slot, p.config_rx_field,
        p.rx_value_field, p.width_field, p.source_width_field, p.ltr_slot,
        uint64_t(int64_t(p.source_slot)), uint64_t(int64_t(p.text_slot)), uint64_t(int64_t(p.style_slot)),
        uint64_t(int64_t(p.widget_slot)), uint64_t(int64_t(p.lines_slot)),
        loaded.base + p.refresh_logger, loaded.base + p.font_refresh,
        reinterpret_cast<uintptr_t>(&kh_launcher_label_revision),
        reinterpret_cast<uintptr_t>(&kh_launcher_label_notified),
        p.ticker_tag, loaded.base + p.cell_visibility,
        uint64_t(int64_t(p.cell_root_slot)), 0,
        p.cell_controller_field, p.cell_screen_field, loaded.base + p.cell_call + 48,
        uint64_t(int64_t(p.prefix_widget_slot)), loaded.base + p.prefix_helper,
        loaded.base + p.fade_wrap, p.fade_start_field, p.fade_end_field,
    };
    static_assert(sizeof(data) == 288);
    memcpy(bridge + config, data, sizeof(data));
    memcpy(bridge + (kh_label_page_replay - kh_label_template), p.cell_replay, sizeof(p.cell_replay));
    __builtin___clear_cache(reinterpret_cast<char*>(bridge), reinterpret_cast<char*>(bridge) + bytes);
    if (mprotect(bridge, size_t(queried), PROT_READ | PROT_EXEC)) {
        munmap(bridge, size_t(queried)); return Reject("bridge RX publication");
    }
    LauncherLabelProfile again{};
    if (!ResolveLauncherLabels(image, &again, &why) || memcmp(&p, &again, sizeof(p))) {
        munmap(bridge, size_t(queried)); return Reject("image changed before publication");
    }
    // From here all bridge memory is process-lifetime, including rollback errors.
    // Mode stays zero unless every site and the authenticated transport succeed.
    uint32_t detour[4];
    Detour(reinterpret_cast<uintptr_t>(bridge + (kh_label_post - kh_label_template)), detour);
    if (PatchArm64Label16(reinterpret_cast<void*>(loaded.base + p.allocation_call + 4),
            p.post_expected, detour, false, true) != kHookSuccess) return Reject("Text return transaction");
    Detour(reinterpret_cast<uintptr_t>(bridge + (kh_label_lines - kh_label_template)), detour);
    if (PatchArm64Label16(reinterpret_cast<void*>(loaded.base + p.lines_tail),
            p.lines_expected, detour, true, true) != kHookSuccess) return Reject("line return transaction");
    const int64_t delta = int64_t(reinterpret_cast<uintptr_t>(bridge)) - int64_t(loaded.base + p.allocation_call);
    const uint32_t call = 0x94000000u | (uint32_t(delta / 4) & 0x3ffffffu);
    uint32_t original; memcpy(&original, image.base + p.allocation_call, 4);
    if (PatchArm64Instruction(reinterpret_cast<void*>(loaded.base + p.allocation_call), original, call) != kHookSuccess)
        return Reject("allocation call transaction");
    const uintptr_t refresh_bridge = reinterpret_cast<uintptr_t>(bridge + (kh_label_refresh - kh_label_template));
    const int64_t refresh_delta = int64_t(refresh_bridge) - int64_t(loaded.base + p.refresh_call);
    if (refresh_delta < -0x8000000 || refresh_delta >= 0x8000000) return Reject("refresh branch range");
    const uint32_t refresh_call = 0x94000000u | (uint32_t(refresh_delta / 4) & 0x3ffffffu);
    memcpy(&original, image.base + p.refresh_call, 4);
    if (PatchArm64Instruction(reinterpret_cast<void*>(loaded.base + p.refresh_call), original, refresh_call) != kHookSuccess)
        return Reject("refresh call transaction");
    const uintptr_t page_bridge = reinterpret_cast<uintptr_t>(bridge + (kh_label_page_post - kh_label_template));
    const int64_t page_delta = int64_t(page_bridge) - int64_t(loaded.base + p.cell_call + 4);
    if (page_delta < -0x8000000 || page_delta >= 0x8000000) return Reject("page branch range");
    const uint32_t page_jump = 0x14000000u | (uint32_t(page_delta / 4) & 0x3ffffffu);
    if (PatchArm64Instruction(reinterpret_cast<void*>(loaded.base + p.cell_call + 4),
            p.cell_post_expected, page_jump) != kHookSuccess) return Reject("page return transaction");
    const uintptr_t fade_bridge = reinterpret_cast<uintptr_t>(bridge + (kh_label_fade - kh_label_template));
    const int64_t fade_delta = int64_t(fade_bridge) - int64_t(loaded.base + p.fade_call);
    if (fade_delta < -0x8000000 || fade_delta >= 0x8000000) return Reject("fade branch range");
    const uint32_t fade_call = 0x94000000u | (uint32_t(fade_delta / 4) & 0x3ffffffu);
    memcpy(&original, image.base + p.fade_call, 4);
    if (PatchArm64Instruction(reinterpret_cast<void*>(loaded.base + p.fade_call), original, fade_call) != kHookSuccess)
        return Reject("fade call transaction");
    __atomic_store_n(&kh_launcher_labels_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&installed, 2u, __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, "KeiHomeLabels",
            "Dynamic labels ready: builder=0x%zx lines=0x%zx refresh=0x%zx page=0x%zx textCID=%u marqueeCID=%u; mode=native",
            p.builder, p.lines, p.refresh_call, p.cell_call, p.text_cid, p.marquee_tag >> 12);
    return true;
}

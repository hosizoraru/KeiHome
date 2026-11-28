// SPDX-License-Identifier: Apache-2.0
#include "wallpaper_reconnect_profile.h"

#include <string.h>

namespace {

constexpr char kStrServiceNotBound[] = "Service is not bound";
constexpr char kStrInterfaceDesc[] =
    "com.miui.miwallpaper.IMiuiWallpaperManagerService";
constexpr char kStrTriggeringReconnect[] =
    "MiuiWallpaperManager: Triggering reconnect, reason=";
constexpr char kStrBindingService[] =
    "MiuiWallpaperManager: Binding service, reason=";
constexpr char kStrReconnectFailed[] =
    "MiuiWallpaperManager: Reconnect failed: ";

constexpr size_t kMaxAdrpLookahead = 4u;
constexpr size_t kBindPrologueBack = 0x140u;
constexpr size_t kTriggerPrologueBack = 0x180u;
constexpr size_t kGetServicePrologueBack = 0x280u;
constexpr size_t kMaxFieldOffset = 0x400u;

bool Fail(const char** rejection, const char* why) {
    if (rejection != nullptr) *rejection = why;
    return false;
}

bool Contains(const IconHighlightImage& im, size_t offset, size_t length,
              bool executable = false) {
    if (im.base == nullptr || offset > im.size || length > im.size - offset) {
        return false;
    }
    for (size_t i = 0u; i < im.range_count; ++i) {
        const auto& r = im.ranges[i];
        if ((!executable || r.executable) && offset >= r.begin &&
                offset <= r.end && length <= r.end - offset) {
            return true;
        }
    }
    return false;
}

bool Executable(const IconHighlightImage& im, size_t offset, size_t length) {
    return Contains(im, offset, length, true);
}

uint32_t Word(const IconHighlightImage& im, size_t offset) {
    uint32_t value = 0u;
    memcpy(&value, im.base + offset, sizeof(value));
    return value;
}

bool FindUniqueString(const IconHighlightImage& im, const char* needle,
                      size_t needle_len, size_t* out_offset) {
    if (im.base == nullptr || needle_len == 0u || out_offset == nullptr) {
        return false;
    }
    size_t found = 0u;
    size_t count = 0u;
    for (size_t i = 0u; i < im.range_count; ++i) {
        const auto& r = im.ranges[i];
        if (r.executable || r.end <= r.begin || r.end > im.size) continue;
        const size_t seg_len = r.end - r.begin;
        if (seg_len < needle_len) continue;
        const uint8_t* seg = im.base + r.begin;
        size_t pos = 0u;
        while (pos <= seg_len - needle_len) {
            const void* match =
                memchr(seg + pos, needle[0], seg_len - needle_len - pos + 1u);
            if (match == nullptr) break;
            const size_t rel = static_cast<const uint8_t*>(match) - seg;
            if (memcmp(seg + rel, needle, needle_len) == 0) {
                found = r.begin + rel;
                ++count;
                if (count > 1u) return false;
            }
            pos = rel + 1u;
        }
    }
    if (count != 1u) return false;
    *out_offset = found;
    return true;
}

struct AdrpRefTracker {
    size_t target;
    bool allow_prev_byte;
    size_t first_pc;
    size_t count;

    bool Matches(size_t addr) const {
        return addr == target || (allow_prev_byte && target > 0u && addr == target - 1u);
    }

    void Record(size_t pc) {
        if (count == 0u) first_pc = pc;
        ++count;
    }
};

struct IfaceRefList {
    size_t target;
    size_t pcs[8];
    size_t count;

    void Record(size_t pc) {
        if (count < 8u) pcs[count] = pc;
        ++count;
    }
};

void ScanAdrpAddRefs(const IconHighlightImage& im, AdrpRefTracker* trackers,
                     size_t tracker_count, IfaceRefList* iface_refs) {
    for (size_t i = 0u; i < im.range_count; ++i) {
        const auto& r = im.ranges[i];
        if (!r.executable || r.end <= r.begin || r.end > im.size) continue;
        const size_t begin = (r.begin + 3u) & ~size_t{3u};
        const size_t end = r.end & ~size_t{3u};
        if (end < begin + 8u) continue;
        for (size_t pc = begin; pc + 8u <= end; pc += 4u) {
            const uint32_t w0 = Word(im, pc);
            if ((w0 & 0x9f000000u) != 0x90000000u) continue;  // ADRP Xd, #imm
            const uint32_t rd = w0 & 0x1fu;
            const uint32_t encoded =
                (((w0 >> 5) & 0x7ffffu) << 2) | ((w0 >> 29) & 3u);
            const int64_t pages = (encoded & 0x100000u) != 0u
                ? static_cast<int64_t>(encoded) - 0x200000
                : static_cast<int64_t>(encoded);
            const int64_t page =
                static_cast<int64_t>(pc & ~size_t{0xfffu}) + (pages << 12);
            if (page < 0) continue;
            for (size_t step = 1u;
                 step <= kMaxAdrpLookahead && pc + step * 4u + 4u <= end;
                 ++step) {
                const uint32_t w1 = Word(im, pc + step * 4u);
                if ((w1 & 0xffc00000u) == 0x91000000u &&
                        ((w1 >> 5) & 0x1fu) == rd) {
                    const size_t addr =
                        static_cast<size_t>(page) + ((w1 >> 10) & 0xfffu);
                    for (size_t t = 0u; t < tracker_count; ++t) {
                        if (trackers[t].Matches(addr)) trackers[t].Record(pc);
                    }
                    if (iface_refs != nullptr && addr == iface_refs->target) {
                        iface_refs->Record(pc);
                    }
                    break;
                }
            }
        }
    }
}

size_t FindFunctionPrologue(const IconHighlightImage& im, size_t ref_pc,
                            size_t max_back) {
    if (ref_pc < 8u) return 0u;
    const size_t limit = ref_pc > max_back ? ref_pc - max_back : 0u;
    for (size_t pc = ref_pc - 4u; pc >= limit; pc -= 4u) {
        if (!Executable(im, pc, 8u)) break;
        const uint32_t w0 = Word(im, pc);
        const uint32_t w1 = Word(im, pc + 4u);
        // sub sp, sp, #imm12 followed by stp x29, x30, [sp, #imm]
        if ((w0 & 0xffc003ffu) == 0xd10003ffu &&
                (w1 & 0xffc07fffu) == 0xa9007bfdu) {
            return pc;
        }
        if (pc < 4u) break;
    }
    return 0u;
}

bool ResolveBranchTarget(const IconHighlightImage& im, size_t pc,
                         size_t* target) {
    if (!Executable(im, pc, 4u)) return false;
    const uint32_t w = Word(im, pc);
    if ((w >> 26) != 0x25u) return false;  // BL
    const int32_t delta = static_cast<int32_t>(w << 6) >> 4;
    const int64_t dest = static_cast<int64_t>(pc) + delta;
    if (dest < 0 || !Executable(im, static_cast<size_t>(dest), 4u)) {
        return false;
    }
    *target = static_cast<size_t>(dest);
    return true;
}

bool VerifyDoBindService(const IconHighlightImage& im, size_t entry,
                         size_t* destroyed_off, size_t* service_mutex_off,
                         size_t* is_bound_rel) {
    if (!Executable(im, entry, 0x120u)) return false;
    uint32_t arc_reg = 32u;
    bool has_destroyed = false;
    bool has_svc_mutex = false;
    bool has_is_bound = false;
    for (size_t pc = entry; pc + 8u <= entry + 0x120u; pc += 4u) {
        const uint32_t w = Word(im, pc);
        if (arc_reg == 32u) {
            if (pc >= entry + 0x30u) return false;
            if ((w & 0xffffffe0u) == 0xf9400000u) {
                // ldr xR, [x0]
                arc_reg = w & 0x1fu;
            }
            continue;
        }
        if (!has_destroyed &&
                (w & 0xffc003e0u) == (0x91000000u | (arc_reg << 5))) {
            const uint32_t rd = w & 0x1fu;
            const uint32_t wn = Word(im, pc + 4u);
            // ldarb wD, [xD]
            if (wn == (0x08dffc00u | (rd << 5) | rd)) {
                *destroyed_off = (w >> 10) & 0xfffu;
                has_destroyed = true;
            }
        }
        if (!has_svc_mutex &&
                (w & 0xffc003ffu) == (0x91000001u | (arc_reg << 5)) &&
                (Word(im, pc + 4u) >> 26) == 0x25u) {
            // add x1, xArc, #service_mutex_off; bl Mutex::lock
            *service_mutex_off = (w >> 10) & 0xfffu;
            has_svc_mutex = true;
        }
        if (!has_is_bound && (w & 0xffc0001fu) == 0x3900001fu) {
            // strb wzr, [xGuard, #is_bound_rel]
            *is_bound_rel = (w >> 10) & 0xfffu;
            has_is_bound = true;
        }
    }
    return has_destroyed && has_svc_mutex && has_is_bound &&
           *destroyed_off < kMaxFieldOffset &&
           *service_mutex_off < kMaxFieldOffset &&
           *is_bound_rel > 0u && *is_bound_rel < 0x40u;
}

bool VerifyTriggerReconnect(const IconHighlightImage& im, size_t entry,
                            size_t expected_destroyed_off,
                            size_t* is_reconnecting_off) {
    if (!Executable(im, entry, 0x200u)) return false;
    uint32_t arc_reg = 32u;
    bool has_reconnecting = false;
    bool has_destroyed = false;
    for (size_t pc = entry; pc + 12u <= entry + 0x200u; pc += 4u) {
        const uint32_t w = Word(im, pc);
        if (arc_reg == 32u) {
            if (pc >= entry + 0x30u) return false;
            if ((w & 0xffffffe0u) == 0xf9400000u) {
                // ldr xR, [x0]
                arc_reg = w & 0x1fu;
            }
            continue;
        }
        if (!has_reconnecting &&
                (w & 0xffc003e0u) == (0x91000000u | (arc_reg << 5))) {
            const uint32_t rd = w & 0x1fu;
            const uint32_t wn = Word(im, pc + 4u);
            const uint32_t wn2 = Word(im, pc + 8u);
            // ldaxrb w?, [xRd]; stlxrb w?, w?, [xRd]
            if ((wn & 0xfffffbe0u) == (0x085ff800u | (rd << 5)) &&
                    (wn2 & 0xffe0fbe0u) == (0x0800f800u | (rd << 5))) {
                *is_reconnecting_off = (w >> 10) & 0xfffu;
                has_reconnecting = true;
            }
        }
        if (!has_destroyed &&
                (w & 0xffc003e0u) == (0x91000000u | (arc_reg << 5))) {
            const uint32_t rd = w & 0x1fu;
            const uint32_t wn = Word(im, pc + 4u);
            if (wn == (0x08dffc00u | (rd << 5) | rd) &&
                    ((w >> 10) & 0xfffu) == expected_destroyed_off) {
                has_destroyed = true;
            }
        }
    }
    return has_reconnecting && has_destroyed &&
           *is_reconnecting_off < kMaxFieldOffset;
}

bool VerifyGetService(const IconHighlightImage& im, size_t entry,
                      size_t unbound_ref, const IfaceRefList& iface_refs,
                      size_t* inner_mutex_off) {
    if (!Executable(im, entry, 0x30u) || !Executable(im, unbound_ref, 0x30u)) {
        return false;
    }
    bool has_mov_x19 = false;
    bool has_inner_mutex = false;
    for (size_t pc = entry; pc + 4u <= entry + 0x30u; pc += 4u) {
        const uint32_t w = Word(im, pc);
        if (w == 0xaa0003f3u) {  // mov x19, x0 (sret pointer)
            has_mov_x19 = true;
        }
        if (!has_inner_mutex && (w & 0xffc003ffu) == 0x91000021u) {
            // add x1, x1, #inner_mutex_off
            *inner_mutex_off = (w >> 10) & 0xfffu;
            has_inner_mutex = true;
        }
    }
    if (!has_mov_x19 || !has_inner_mutex) return false;

    bool has_iface_in_fn = false;
    const size_t iface_checked = iface_refs.count < 8u ? iface_refs.count : 8u;
    for (size_t i = 0u; i < iface_checked; ++i) {
        if (iface_refs.pcs[i] >= entry && iface_refs.pcs[i] < entry + 0x180u) {
            has_iface_in_fn = true;
            break;
        }
    }
    if (!has_iface_in_fn) return false;

    // Verify Result::Err sret write: stp xzr, xN, [x19]
    bool has_err_stp = false;
    for (size_t pc = unbound_ref; pc + 4u <= unbound_ref + 0x30u; pc += 4u) {
        if ((Word(im, pc) & 0xffff83ffu) == 0xa900027fu) {
            has_err_stp = true;
            break;
        }
    }
    return has_err_stp;
}

bool VerifyAnyhowErrorDrop(const IconHighlightImage& im, size_t fail_ref,
                           size_t do_bind_entry) {
    if (fail_ref < 0x60u || !Executable(im, fail_ref - 0x60u, 0xb0u)) {
        return false;
    }
    bool calls_do_bind = false;
    for (size_t pc = fail_ref - 0x60u; pc < fail_ref; pc += 4u) {
        size_t dest = 0u;
        if (ResolveBranchTarget(im, pc, &dest) && dest == do_bind_entry) {
            calls_do_bind = true;
            break;
        }
    }
    if (!calls_do_bind) return false;

    // Verify exact anyhow::Error destructor sequence:
    //   ldr x8, [x0]   (0xf9400008)
    //   ldr x8, [x8]   (0xf9400108)
    //   blr x8         (0xd63f0100)
    for (size_t pc = fail_ref; pc + 12u <= fail_ref + 0x50u; pc += 4u) {
        if (Word(im, pc) == 0xf9400008u &&
                Word(im, pc + 4u) == 0xf9400108u &&
                Word(im, pc + 8u) == 0xd63f0100u) {
            return true;
        }
    }
    return false;
}

}  // namespace

bool ResolveWallpaperReconnect(const IconHighlightImage& image,
                               WallpaperReconnectProfile* out,
                               const char** rejection) {
    if (out == nullptr) return Fail(rejection, "null output");
    *out = {};
    if (image.base == nullptr || image.ranges == nullptr ||
            image.range_count == 0u || image.size < 64u) {
        return Fail(rejection, "invalid libapp_launcher image");
    }

    size_t s_unbound = 0u;
    size_t s_iface = 0u;
    size_t s_trig = 0u;
    size_t s_bind = 0u;
    size_t s_fail = 0u;
    if (!FindUniqueString(image, kStrServiceNotBound,
                          sizeof(kStrServiceNotBound) - 1u, &s_unbound) ||
            !FindUniqueString(image, kStrInterfaceDesc,
                              sizeof(kStrInterfaceDesc) - 1u, &s_iface) ||
            !FindUniqueString(image, kStrTriggeringReconnect,
                              sizeof(kStrTriggeringReconnect) - 1u, &s_trig) ||
            !FindUniqueString(image, kStrBindingService,
                              sizeof(kStrBindingService) - 1u, &s_bind) ||
            !FindUniqueString(image, kStrReconnectFailed,
                              sizeof(kStrReconnectFailed) - 1u, &s_fail)) {
        return Fail(rejection, "missing or duplicate mi_wallpaper_manager strings");
    }

    AdrpRefTracker trackers[4] = {
        {s_bind, true, 0u, 0u},
        {s_trig, true, 0u, 0u},
        {s_unbound, false, 0u, 0u},
        {s_fail, true, 0u, 0u},
    };
    IfaceRefList iface_refs{s_iface, {}, 0u};
    ScanAdrpAddRefs(image, trackers, 4u, &iface_refs);

    if (trackers[0].count != 1u || trackers[1].count != 1u ||
            trackers[2].count != 1u || trackers[3].count != 1u) {
        return Fail(rejection, "ambiguous or missing mi_wallpaper_manager code refs");
    }

    const size_t bind_entry =
        FindFunctionPrologue(image, trackers[0].first_pc, kBindPrologueBack);
    const size_t trig_entry =
        FindFunctionPrologue(image, trackers[1].first_pc, kTriggerPrologueBack);
    const size_t gs_entry =
        FindFunctionPrologue(image, trackers[2].first_pc, kGetServicePrologueBack);
    if (bind_entry == 0u || trig_entry == 0u || gs_entry == 0u ||
            bind_entry == trig_entry || bind_entry == gs_entry ||
            trig_entry == gs_entry) {
        return Fail(rejection, "mi_wallpaper_manager prologue mismatch");
    }

    size_t destroyed_off = 0u;
    size_t service_mutex_off = 0u;
    size_t is_bound_rel = 0u;
    if (!VerifyDoBindService(image, bind_entry, &destroyed_off,
                             &service_mutex_off, &is_bound_rel)) {
        return Fail(rejection, "do_bind_service ABI mismatch");
    }

    size_t is_reconnecting_off = 0u;
    if (!VerifyTriggerReconnect(image, trig_entry, destroyed_off,
                                &is_reconnecting_off)) {
        return Fail(rejection, "trigger_reconnect ABI mismatch");
    }

    size_t inner_mutex_off = 0u;
    if (!VerifyGetService(image, gs_entry, trackers[2].first_pc, iface_refs,
                          &inner_mutex_off)) {
        return Fail(rejection, "get_service ABI mismatch");
    }

    if (service_mutex_off <= inner_mutex_off ||
            service_mutex_off - inner_mutex_off != 2u * sizeof(uintptr_t)) {
        return Fail(rejection, "ArcInner header size mismatch");
    }

    if (!VerifyAnyhowErrorDrop(image, trackers[3].first_pc, bind_entry)) {
        return Fail(rejection, "anyhow::Error drop ABI mismatch");
    }

    const uintptr_t base = reinterpret_cast<uintptr_t>(image.base);
    out->do_bind_service = base + bind_entry;
    out->trigger_reconnect = base + trig_entry;
    out->get_service = base + gs_entry;
    out->arc_header_size = service_mutex_off - inner_mutex_off;
    out->destroyed_offset = destroyed_off;
    out->is_reconnecting_offset = is_reconnecting_off;
    out->is_bound_offset = service_mutex_off + is_bound_rel;
    return true;
}

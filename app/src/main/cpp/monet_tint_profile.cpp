// SPDX-License-Identifier: Apache-2.0
// Mapped-code resolution follows MiuiBackGestureHook's runtime_profile_resolver:
// bounded executable scans, decoded ARM64 operands, unique structural matches,
// and verified call relationships. Add/LDR decoders are adapted from that project.
// Source: https://github.com/wxxsfxyzm/MiuiBackGestureHook (Apache-2.0).
#include "monet_tint_profile.h"
#include "monet_image_identity.h"

#include <string.h>

extern "C" {
uintptr_t kh_tint_caller = 0;
uintptr_t kh_render_equals_caller = 0;
uintptr_t kh_layer_equals_caller = 0;
uintptr_t kh_monet_enabled_equals_caller = 0;
uintptr_t kh_monet_settings_setter_caller = 0;
uintptr_t kh_monet_object_equals_entry = 0;
uintptr_t kh_monet_filter_pool_offset = 0;
uintptr_t kh_monet_controller_field_offset = 0;
uint32_t kh_monet_controller_cid = 0;
uint32_t kh_monet_rx_int_cid = 0;
uint32_t kh_monet_rx_bool_cid = 0;
}

namespace {
struct Pattern {
    const char* name;
    size_t count;
    uint32_t prefix[4];
    uint8_t digest[32];
};
#include "monet_tint_patterns.h"
struct Variant { Role role; Pattern pattern; };
#include "monet_tint_variants.h"
constexpr size_t kMaxContractWords = 320;

bool Reject(const char** reason, const char* message) {
    if (reason) *reason = message;
    return false;
}
bool Contains(const MonetTintImageView& image, uintptr_t address, size_t size,
              bool executable = true) {
    if (!size || size > UINTPTR_MAX - address) return false;
    for (size_t i = 0; i < image.readable_range_count; ++i) {
        const auto& r = image.readable_ranges[i];
        if ((!executable || r.executable) && address >= r.begin &&
            address - r.begin <= r.size && size <= r.size - (address - r.begin)) return true;
    }
    return false;
}
uint32_t Word(uintptr_t pc) {
    uint32_t word;
    memcpy(&word, reinterpret_cast<const void*>(pc), sizeof(word));
    return word;
}
uint32_t Normalized(uintptr_t start, size_t count, size_t i, Role role) {
    const uint32_t w = Word(start + i * 4);
    const uint32_t previous = i ? Word(start + (i - 1) * 4) : 0;
    const uint32_t next = i + 1 < count ? Word(start + (i + 1) * 4) : 0;
    if ((w & 0xfc000000u) == 0x94000000u) return w & 0xfc000000u;
    if ((w & 0xfc000000u) == 0x14000000u) {
        const int64_t target = static_cast<int64_t>(i) +
            ((int64_t(w & 0x3ffffffu) ^ 0x2000000) - 0x2000000);
        if (target < 0 || target >= static_cast<int64_t>(count)) return w & 0xfc000000u;
    }
    // Only PP and Thread-field-table indices move. Object fields, registers,
    // local branches, stack slots and the null/sentinel ABI remain exact.
    if ((w & 0xffc003e0u) == 0x91400360u) return w & 0xffc003ffu;
    if ((previous & 0xffc003e0u) == 0x91400360u &&
        ((w >> 5) & 31) == (previous & 31) && (w & 0x3b000000u) == 0x39000000u) {
        return w & 0xffc003ffu;
    }
    if ((w & 0xffc003e0u) == 0xf9400360u && ((w >> 10) & 4095) * 8 > 0x100) {
        return w & 0xffc003ffu;
    }
    if ((previous & 0xffffffe0u) == 0xf9403f40u &&
        ((w >> 5) & 31) == (previous & 31) && (w & 0xff800000u) == 0xf9000000u) {
        return w & 0xffc003ffu;
    }
    if ((w & 0xffc003ffu) == 0x9100001eu && next == 0xf87e7abeu) return w & 0xffc003ffu;
    // The original equality implementation's class number is relocatable.
    // The bridge compares the live constant identity, not this numeric CID.
    if (role == kFilterEquals && i == 31) return w & 0xffe0001fu;
    return w;
}
bool MatchesPattern(const MonetTintImageView& image, uintptr_t address, Role role, const Pattern& p) {
    if (!address || (address & 3) || p.count > kMaxContractWords ||
        !Contains(image, address, p.count * 4)) return false;
    for (size_t i = 0; i < 4; ++i) {
        if (Normalized(address, p.count, i, role) != p.prefix[i]) return false;
    }
    uint32_t normalized[kMaxContractWords];
    for (size_t i = 0; i < p.count; ++i) normalized[i] = Normalized(address, p.count, i, role);
    uint8_t digest[32];
    MonetSha256(normalized, p.count * 4, digest);
    return memcmp(digest, p.digest, sizeof(digest)) == 0;
}
bool Matches(const MonetTintImageView& image, uintptr_t address, Role role) {
    if (MatchesPattern(image, address, role, kPatterns[role])) return true;
    for (const auto& v : kTintVariants)
        if (v.role == role && MatchesPattern(image, address, role, v.pattern)) return true;
    return false;
}
bool Prefix(Role role, uint32_t word) {
    if (word == kPatterns[role].prefix[0]) return true;
    for (const auto& v : kTintVariants)
        if (v.role == role && word == v.pattern.prefix[0]) return true;
    return false;
}
size_t MatchedSize(const MonetTintImageView& image, uintptr_t address, Role role) {
    size_t count = MatchesPattern(image, address, role, kPatterns[role]) ? kPatterns[role].count : 0;
    for (const auto& v : kTintVariants) {
        if (v.role != role || !MatchesPattern(image, address, role, v.pattern)) continue;
        if (count && count != v.pattern.count) return 0;
        count = v.pattern.count;
    }
    return count * 4;
}
template<typename Predicate>
uintptr_t Find(const MonetTintImageView& image, Role role, Predicate predicate) {
    uintptr_t result = 0;
    for (size_t i = 0; i < image.readable_range_count; ++i) {
        const auto& range = image.readable_ranges[i];
        const size_t size = 16;
        if (!range.executable || range.size < size) continue;
        for (uintptr_t p = range.begin; p - range.begin <= range.size - size; p += 4) {
            if (!Prefix(role, Word(p)) || !Matches(image, p, role) || !predicate(p)) continue;
            if (result && result != p) return 0;
            result = p;
        }
    }
    return result;
}
uintptr_t Find(const MonetTintImageView& image, Role role) {
    return Find(image, role, [](uintptr_t) { return true; });
}
uintptr_t Call(const MonetTintImageView& image, uintptr_t pc, bool link = true) {
    if (!pc || !Contains(image, pc, 4)) return 0;
    const uint32_t w = Word(pc);
    if ((w & 0xfc000000u) != (link ? 0x94000000u : 0x14000000u)) return 0;
    const int64_t offset = ((int64_t(w & 0x03ffffffu) ^ 0x02000000) - 0x02000000) * 4;
    if ((offset < 0 && pc < uintptr_t(-offset)) ||
        (offset >= 0 && uintptr_t(offset) > UINTPTR_MAX - pc)) return 0;
    const uintptr_t target = offset < 0 ? pc - uintptr_t(-offset) : pc + uintptr_t(offset);
    return Contains(image, target, 4) ? target : 0;
}
// Adapted from MiuiBackGestureHook's DecodeAddImmediate/DecodeLdr64Immediate.
bool PoolPair(uintptr_t pc, uintptr_t* offset) {
    const uint32_t add = Word(pc), ldr = Word(pc + 4);
    const uint32_t reg = add & 31;
    if ((add & 0xffc003e0u) != 0x91400360u ||
        (ldr & 0xffc003ffu) != (0xf9400000u | (reg << 5) | reg)) return false;
    *offset = ((add >> 10) & 0xfffu) * 4096u + ((ldr >> 10) & 0xfffu) * 8u;
    return *offset != 0 && *offset < 0x100000;
}
uintptr_t TableSlot(uintptr_t pc) { return ((Word(pc) >> 10) & 4095u) * 8u; }
bool AllocatorCid(const MonetTintImageView& image, uintptr_t pc, uint32_t size_tag,
                  uint32_t* cid) {
    if (!Contains(image, pc, 12)) return false;
    const uint32_t low = Word(pc), high = Word(pc + 4), branch = Word(pc + 8);
    if ((low & 0xffe0001fu) != 0xd2800002u ||
        (high & 0xffe0001fu) != 0xf2a00002u ||
        (branch & 0xfc000000u) != 0x14000000u) return false;
    const uint32_t tag = ((low >> 5) & 0xffffu) | (((high >> 5) & 0xffffu) << 16);
    if ((tag & 0xfffu) != size_tag || !Matches(image, Call(image, pc + 8, false),
            size_tag == 0x41c ? kObjectAllocator : kTypedAllocator)) return false;
    *cid = tag >> 12;
    return *cid > 63 && *cid < 0x100000;
}
} // namespace

bool ResolveMonetTintProfile(const MonetTintImageView& image, MonetTintProfile* out,
                             const char** rejection) {
    if (rejection) *rejection = nullptr;
    if (!out) return Reject(rejection, "missing tint output");
    *out = {};
    if (!image.load_bias || !image.readable_ranges || !image.readable_range_count ||
        image.readable_range_count > 32 || !image.verified_file_sha256) {
        return Reject(rejection, "missing tint image/ranges/backing identity");
    }
    size_t executable_size = 0;
    for (size_t i = 0; i < image.readable_range_count; ++i) {
        const auto& r = image.readable_ranges[i];
        if (!r.size || r.size > UINTPTR_MAX - r.begin || (r.begin & 3)) return Reject(rejection, "invalid tint range");
        if (r.executable) {
            if (r.size > 0x8000000 - executable_size) return Reject(rejection, "oversized tint image");
            executable_size += r.size;
        }
    }
    constexpr uint8_t note_header[] = {4,0,0,0,16,0,0,0,3,0,0,0,'G','N','U',0};
    if (!Contains(image, image.load_bias, 64, false) ||
        (image.snapshot_instructions & 3) || !Contains(image, image.snapshot_instructions, 16) ||
        !Contains(image, image.snapshot_build_id, 32, false) ||
        memcmp(reinterpret_cast<void*>(image.snapshot_build_id), note_header, sizeof(note_header))) {
        return Reject(rejection, "invalid live Dart anchors");
    }
    const uintptr_t foreground = Find(image, kForegroundBuild);
    const uintptr_t background = Find(image, kBackgroundClosure);
    const uintptr_t mode = Find(image, kModeConstructor);
    const uintptr_t identity = Find(image, kIdentityEquals);
    if (!foreground || !background || !mode || !identity) return Reject(rejection, "ambiguous tint/widget instruction contracts");
    const uintptr_t ffi = Call(image, mode + 0xd0);
    const uintptr_t getter = Call(image, background + 0x7c);
    if (!Matches(image, ffi, kModeFfi) || !Matches(image, getter, kRxGetter)) {
        return Reject(rejection, "tint FFI or background getter contract changed");
    }
    uintptr_t filter_pool = 0, type_pool = 0;
    const size_t foreground_size = MatchedSize(image, foreground, kForegroundBuild);
    for (size_t off = 0; off + 24 <= foreground_size; off += 4) {
        const uintptr_t pc = foreground + off;
        uintptr_t pool = 0;
        if ((Word(pc) & 0xffc003ffu) != 0x91400360u || !PoolPair(pc, &pool)) continue;
        // The native closure captures the filter in its .f field and the image
        // in .b. Authenticate the destination register and rooted image load.
        const uint32_t store = Word(pc + 12), reg = (store >> 5) & 31u;
        if ((reg != 2 && reg != 3) || store != (0xb800f000u | reg << 5) ||
            (Word(pc + 8) & 0xffe00fffu) != (0xf80003a0u | reg) ||
            (Word(pc + 16) & 0xffe00fffu) != 0xf84003a0u ||
            Word(pc + 20) != (0xb800b000u | reg << 5)) continue;
        if (filter_pool) return Reject(rejection, "ambiguous native foreground filter capture");
        filter_pool = pool;
    }
    if (!filter_pool || !PoolPair(background + 0x38, &type_pool)) {
        return Reject(rejection, "tint pool operands changed");
    }
    const uintptr_t field_slot = TableSlot(background + 0x54);
    const auto same_type = [&](uintptr_t pc) {
        uintptr_t pool = 0; return PoolPair(pc, &pool) && pool == type_pool;
    };
    const uintptr_t factory = Find(image, kControllerFactory, [&](uintptr_t pc) {
        return same_type(pc + 0x34) && same_type(pc + 0x5c);
    });
    const uintptr_t accessor = Find(image, kControllerAccessor, [&](uintptr_t pc) {
        return TableSlot(pc + 0x10) == field_slot && same_type(pc + 0x38);
    });
    const uintptr_t on_init = Find(image, kControllerOnInit, [&](uintptr_t pc) {
        return TableSlot(pc + 0x20) == field_slot;
    });
    if (!factory || !accessor || !on_init || !same_type(background + 0x60)) {
        return Reject(rejection, "controller ownership graph changed");
    }
    const uintptr_t constructor = Call(image, factory + 0x58);
    const uintptr_t load_settings = Call(image, factory + 0x84);
    if (!Matches(image, constructor, kControllerConstructor) || !Matches(image, load_settings, kLoadSettingsPrefix)) {
        return Reject(rejection, "controller field or settings ABI changed");
    }
    const uintptr_t rx_int = Call(image, constructor + 0x20);
    const uintptr_t rx_bool = Call(image, constructor + 0x48);
    const uintptr_t setter = Call(image, load_settings + 0xac);
    if (!Matches(image, rx_int, kRxIntConstructor) || !Matches(image, rx_bool, kRxBoolConstructor) ||
        Call(image, constructor + 0x98) != rx_int || Call(image, constructor + 0x70) != rx_bool ||
        !Matches(image, Call(image, rx_int + 0x4c), kMintAllocator) ||
        !Matches(image, setter, kRxSetter) || Call(image, load_settings + 0x44) != setter) {
        return Reject(rejection, "Rx integer/boolean/notification ABI changed");
    }
    uint32_t controller_cid = 0, rx_int_cid = 0, rx_bool_cid = 0;
    if (!AllocatorCid(image, Call(image, factory + 0x4c), 0x41c, &controller_cid) ||
        !AllocatorCid(image, Call(image, rx_int + 0x18), 0x21c, &rx_int_cid) ||
        !AllocatorCid(image, Call(image, rx_bool + 0x18), 0x21c, &rx_bool_cid)) {
        return Reject(rejection, "controller/Rx allocation tags changed");
    }
    const auto filter_setter = [&](uintptr_t pc) { return Matches(image, Call(image, pc + 0x24), kFilterEquals); };
    const uintptr_t render = Find(image, kRenderSetter, filter_setter);
    const uintptr_t layer = Find(image, kLayerSetter, filter_setter);
    if (!render || !layer || Call(image, render + 0x24) != Call(image, layer + 0x24)) {
        return Reject(rejection, "filter setter/equality graph changed");
    }
    const uintptr_t demo = Find(image, kDemoBuild, [&](uintptr_t pc) {
        uintptr_t pool = 0; return PoolPair(pc + 0x1d4, &pool) && pool == filter_pool;
    });
    if (!demo) return Reject(rejection, "shared foreground constant relationship changed");
    *out = {image.load_bias, reinterpret_cast<void*>(ffi),
        reinterpret_cast<void*>(Call(image, render + 0x24)), mode + 0xd4,
        render + 0x28, layer + 0x28, reinterpret_cast<void*>(identity), setter + 0xb8,
        load_settings + 0xb0, filter_pool, field_slot, controller_cid, rx_int_cid, rx_bool_cid};
    return true;
}

void PublishMonetTintCallers(const MonetTintProfile& p) {
    __atomic_store_n(&kh_tint_caller, p.tint_caller, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_render_equals_caller, p.render_equals_caller, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_layer_equals_caller, p.layer_equals_caller, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_enabled_equals_caller, p.enabled_equals_caller, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_settings_setter_caller, p.settings_setter_caller, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_object_equals_entry, reinterpret_cast<uintptr_t>(p.enabled_equals_entry), __ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_filter_pool_offset, p.filter_pool_offset, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_controller_field_offset, p.controller_field_offset, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_controller_cid, p.controller_cid, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_rx_int_cid, p.rx_int_cid, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_rx_bool_cid, p.rx_bool_cid, __ATOMIC_RELEASE);
}

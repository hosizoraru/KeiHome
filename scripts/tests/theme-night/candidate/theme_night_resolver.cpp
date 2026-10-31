// SPDX-License-Identifier: Apache-2.0
// Bounded mapped scans and decoded operand/call relationships follow
// MiuiBackGestureHook's dart_runtime_resolver.cpp (Apache-2.0).
#include "theme_night_profile.h"
#include "monet_image_identity.h"
#include <initializer_list>
#include <string.h>

extern "C" {
ThemeNightConfig kh_theme_night_config{};
void* kh_theme_night_original = nullptr;
}

namespace {
struct Pattern { unsigned role, count; uint32_t prefix[4]; uint8_t digest[32]; };
#include "theme_night_patterns.h"
constexpr size_t kMaxSpan = 128u * 1024u * 1024u;
constexpr size_t kMaxWords = 512;
struct Match { uintptr_t pc; size_t words; };

bool Reject(const char** reason, const char* text) {
    if (reason) *reason = text;
    return false;
}
bool Contains(const MonetSelectionImageView& v, uintptr_t p, size_t n, bool exec = true) {
    if (!n || p > UINTPTR_MAX-n) return false;
    for (size_t i = 0; i < v.readable_range_count; ++i) {
        const auto& r = v.readable_ranges[i];
        if ((!exec || r.executable) && p >= r.begin && p-r.begin <= r.size &&
            n <= r.size-(p-r.begin)) return true;
    }
    return false;
}
uint32_t Word(uintptr_t p) {
    uint32_t w; memcpy(&w, reinterpret_cast<const void*>(p), 4); return w;
}
bool Target(const MonetSelectionImageView& v, uintptr_t pc, uint32_t w,
            unsigned bits, unsigned shift, uintptr_t* out) {
    const uint32_t raw = (w >> shift) & ((uint32_t{1} << bits)-1);
    const int64_t delta = int64_t(raw) -
        ((raw & (uint32_t{1} << (bits-1))) ? int64_t{1} << bits : 0);
    const int64_t relative = int64_t(pc-v.load_bias) + delta*4;
    if (relative < 0 || uint64_t(relative) >= kMaxSpan) return false;
    *out = v.load_bias + uintptr_t(relative);
    return Contains(v, *out, 4);
}
uintptr_t Call(const MonetSelectionImageView& v, uintptr_t pc, bool link = true) {
    if (!Contains(v, pc, 4)) return 0;
    const uint32_t w = Word(pc);
    uintptr_t target = 0;
    if ((w & 0xfc000000u) != (link ? 0x94000000u : 0x14000000u) ||
        !Target(v, pc, w, 26, 0, &target)) return 0;
    return target;
}
bool IsField(unsigned role, size_t offset) {
    return (role == kCreate && offset == 0x17c) ||
        (role == kNodeSetter && (offset == 8 || offset == 0x2c)) ||
        (role == kBitmapLocal && offset == 0xa4) ||
        (role == kStream && offset == 0x34) ||
        (role == kHasFile && offset == 0x30);
}
uint32_t Normalize(uintptr_t pc, size_t count, size_t i, unsigned role) {
    const uint32_t w = Word(pc+i*4);
    const uint32_t prev = i ? Word(pc+(i-1)*4) : 0;
    const uint32_t next = i+1 < count ? Word(pc+(i+1)*4) : 0;
    if ((w & 0xfc000000u) == 0x94000000u) return w & 0xfc000000u;
    if ((w & 0xfc000000u) == 0x14000000u) {
        const int64_t target = int64_t(i) +
            ((int64_t(w & 0x3ffffffu)^0x2000000)-0x2000000);
        if (target < 0 || uint64_t(target) >= count) return w & 0xfc000000u;
    }
    if ((w & 0xffc003e0u) == 0x91400360u &&
        (next & 0xffc003e0u) == (0xf9400000u | ((w & 31) << 5)))
        return w & ~0x003ffc00u;
    if ((prev & 0xffc003e0u) == 0x91400360u &&
        (w & 0xffc003e0u) == (0xf9400000u | ((prev & 31) << 5)))
        return w & ~0x003ffc00u;
    if ((w & 0xffc003e0u) == 0xf9400360u) return w & ~0x003ffc00u;
    if ((w & 0xffc003e0u) == 0xf9400340u &&
        (next & 0xff8003e0u) == (0xf9000000u | ((w & 31) << 5)))
        return w & ~0x003ffc00u;
    if ((prev & 0xffc003e0u) == 0xf9400340u &&
        (w & 0xff8003e0u) == (0xf9000000u | ((prev & 31) << 5)))
        return w & ~0x003ffc00u;
    if (IsField(role, i*4)) return w & ~0x001ff000u;
    if (role == kNodeSetter && i*4 == 0x24) return w & ~0x003ffc00u;
    return w;
}
bool Matches(const MonetSelectionImageView& v, uintptr_t pc, const Pattern& p) {
    if (!Contains(v, pc, p.count*4) || p.count > kMaxWords) return false;
    for (size_t i = 0; i < 4; ++i)
        if (Normalize(pc, p.count, i, p.role) != p.prefix[i]) return false;
    uint32_t normalized[kMaxWords];
    for (size_t i = 0; i < p.count; ++i) {
        const uint32_t w = Word(pc+i*4);
        if ((w & 0xfc000000u) == 0x94000000u && !Call(v, pc+i*4)) return false;
        if ((w & 0xfc000000u) == 0x14000000u && !Call(v, pc+i*4, false)) return false;
        normalized[i] = Normalize(pc, p.count, i, p.role);
    }
    uint8_t digest[32];
    MonetSha256(normalized, p.count*4, digest);
    return memcmp(digest, p.digest, 32) == 0;
}
bool Find(const MonetSelectionImageView& v, unsigned role, Match* out) {
    unsigned candidates = 0;
    for (size_t i = 0; i < v.readable_range_count; ++i) {
        const auto& r = v.readable_ranges[i];
        if (!r.executable || r.size < 16) continue;
        for (uintptr_t pc = r.begin; pc-r.begin <= r.size-16; pc += 4) {
            for (const auto& p : kPatterns) {
                if (p.role != role || Word(pc) != p.prefix[0] ||
                    Word(pc+4) != p.prefix[1]) continue;
                if (!Contains(v, pc, p.count*4)) continue;
                if (Normalize(pc, p.count, 2, role) != p.prefix[2] ||
                    Normalize(pc, p.count, 3, role) != p.prefix[3]) continue;
                if (++candidates > 8192) return false;
                if (!Matches(v, pc, p)) continue;
                if (out->pc && out->pc != pc) return false;
                *out = {pc, p.count};
            }
        }
    }
    return out->pc != 0;
}
uint32_t Immediate(uint32_t w) { return (w >> 10) & 4095u; }
int32_t Field(uint32_t w) {
    return int32_t((w >> 12) & 511u) - ((w & (1u << 20)) ? 512 : 0);
}
uint32_t Pool(uintptr_t pc) {
    const uint32_t a = Word(pc), b = Word(pc+4), reg = a & 31;
    if ((a & 0xffc003e0u) != 0x91400360u ||
        (b & 0xffc003ffu) != (0xf9400000u | (reg << 5) | reg)) return 0;
    const uint32_t off = Immediate(a)*4096 + Immediate(b)*8;
    return off && off < 0x100000 ? off : 0;
}
bool StaticPairs(const Match& m, bool stores, uint32_t table,
                 uint32_t* slots, size_t wanted) {
    size_t count = 0;
    for (size_t i = 0; i+1 < m.words; ++i) {
        const uint32_t a = Word(m.pc+i*4), b = Word(m.pc+(i+1)*4);
        const uint32_t rn = (a >> 5) & 31, rt = a & 31;
        if (rn != 26 || (a & 0xffc003e0u) != (0xf9400000u | (rn << 5)) ||
            Immediate(a)*8 != table) continue;
        const uint32_t next_rn = (b >> 5) & 31;
        if (next_rn != rt) continue;
        const bool is_store = (b & 0xffc003e0u) == (0xf9000000u | (rt << 5));
        const bool is_load = (b & 0xffc003e0u) == (0xf9400000u | (rt << 5));
        if ((!stores && !is_load) || (stores && !is_store)) continue;
        if (count >= wanted) return false;
        slots[count++] = Immediate(b)*8;
    }
    return count == wanted;
}
bool AllocatorTags(const MonetSelectionImageView& v, uintptr_t pc, uint32_t* cid,
                   uint32_t* bytes) {
    const uintptr_t target = Call(v, pc);
    if (!target || !Contains(v, target, 12)) return false;
    const uint32_t low = Word(target), high = Word(target+4);
    if ((low & 0xffe0001fu) != 0xd2800002u ||
        (high & 0xffe0001fu) != 0xf2a00002u) return false;
    const uint32_t tags = ((low >> 5) & 0xffffu) | (((high >> 5) & 0xffffu) << 16);
    const uintptr_t generic = Call(v, target+8, false);
    // These instructions decode size bits [11:8], multiply by 16, allocate
    // that many bytes, and store x2 as the new object's header.
    const uint32_t prefix[] = {
        0xd3482c44u,0xd37cec84u,0xa9461740u,0x8b040003u,
        0xeb0300bfu,0x54000169u,0xf9003343u,0xf9000002u,
        0x91002004u,0x92407ed0u,0xaa108210u,0xa8814090u,
        0xeb03009fu,0x54ffffc3u,0x91000400u,0xd65f03c0u,
    };
    if (!Contains(v, generic, sizeof(prefix)) ||
        memcmp(reinterpret_cast<const void*>(generic), prefix, sizeof(prefix))) return false;
    *cid = tags >> 12;
    *bytes = ((tags >> 8) & 15u)*16;
    return *cid > 255 && *cid < 4096 && *bytes != 0;
}
bool EntrySafe(const MonetSelectionImageView& v, uintptr_t entry) {
    for (size_t i = 0; i < v.readable_range_count; ++i) {
        const auto& r = v.readable_ranges[i];
        if (!r.executable) continue;
        for (uintptr_t pc = r.begin; pc-r.begin < r.size; pc += 4) {
            const uint32_t w = Word(pc);
            unsigned bits = 0, shift = 0;
            if ((w & 0x7c000000u) == 0x14000000u) bits = 26;
            else if ((w & 0xff000010u) == 0x54000000u ||
                     (w & 0x7e000000u) == 0x34000000u) { bits = 19; shift = 5; }
            else if ((w & 0x7e000000u) == 0x36000000u) { bits = 14; shift = 5; }
            uintptr_t target;
            if (bits && Target(v, pc, w, bits, shift, &target) &&
                target > entry && target < entry+32) return false;
        }
    }
    return true;
}
} // namespace

bool ResolveThemeNight(const MonetSelectionImageView& v, ThemeNightProfile* out,
                       const char** reason) {
    if (reason) *reason = nullptr;
    if (!out) return Reject(reason, "missing theme-night output");
    *out = {};
    if (!v.load_bias || v.load_bias > UINTPTR_MAX-kMaxSpan || !v.readable_ranges ||
        !v.readable_range_count || v.readable_range_count > 16)
        return Reject(reason, "invalid theme-night ranges");
    for (size_t i = 0; i < v.readable_range_count; ++i) {
        const auto& r = v.readable_ranges[i];
        if (!r.size || r.begin < v.load_bias || r.begin-v.load_bias > kMaxSpan ||
            r.size > kMaxSpan-(r.begin-v.load_bias) ||
            (r.executable && ((r.begin | r.size) & 3)))
            return Reject(reason, "invalid theme-night segment bounds");
        for (size_t j = 0; j < i; ++j) {
            const auto& s = v.readable_ranges[j];
            if (r.begin < s.begin+s.size && s.begin < r.begin+r.size)
                return Reject(reason, "overlapping theme-night ranges");
        }
    }
    if (!Contains(v, v.load_bias, 64, false) ||
        memcmp(reinterpret_cast<void*>(v.load_bias), "\177ELF\2\1", 6) ||
        Word(v.load_bias+16) != 0x00b70003u)
        return Reject(reason, "theme-night requires AArch64 ET_DYN");
    Match m[kRoleCount]{};
    for (unsigned r = 0; r < kRoleCount; ++r)
        if (!Find(v, r, &m[r])) return Reject(reason, "missing/ambiguous theme-night code family");
    const auto pc = [&](Role r) { return m[r].pc; };
    const auto edge = [&](Role from, size_t offset, Role to) {
        return Call(v, pc(from)+offset) == pc(to);
    };
    if (!edge(kInit, 0x10, kCreate) || !edge(kInit, 0x24, kCreate) ||
        !edge(kInit, 0x38, kCreate) || !edge(kInit, 0x58, kNodeSetter) ||
        !edge(kSeed, 0x2c, kReadMode) || !edge(kApply, 0x38, kSystemSetter) ||
        !edge(kSystemSetter, 0x50, kNodeSetter) ||
        !edge(kNodeBitmap, 0x2c, kBitmapLocal) ||
        !edge(kGetSystem, 0x64, kSeed) || !edge(kGetSystem, 0x68, kInit))
        return Reject(reason, "theme-night initialization/reader/setter edges");
    unsigned bitmap_calls = 0;
    for (size_t i = 0; i < m[kSystemBitmap].words; ++i)
        if (Call(v, pc(kSystemBitmap)+i*4) == pc(kNodeBitmap)) ++bitmap_calls;
    if (bitmap_calls != 1) return Reject(reason, "theme-night bitmap ownership");

    ThemeNightConfig c{};
    uint32_t init[3], seed[1], setter[1], reads[3], icons[1], init_read[1], apply[2];
    c.thread_table = Immediate(Word(pc(kInit)+0x14))*8;
    if (!c.thread_table ||
        !StaticPairs(m[kInit], true, c.thread_table, init, 3) ||
        !StaticPairs(m[kInit], false, c.thread_table, init_read, 1) ||
        !StaticPairs(m[kSeed], true, c.thread_table, seed, 1) ||
        !StaticPairs(m[kSystemSetter], true, c.thread_table, setter, 1) ||
        !StaticPairs(m[kSystemSetter], false, c.thread_table, reads, 3) ||
        !StaticPairs(m[kApply], false, c.thread_table, apply, 2) ||
        !StaticPairs(m[kSystemBitmap], false, c.thread_table, icons, 1))
        return Reject(reason, "theme-night static-table instructions");
    if (init[0] == init[1] || init[0] == init[2] || init[1] == init[2] ||
        icons[0] != init[0] || seed[0] != setter[0] || reads[0] != setter[0] ||
        reads[1] != init[2] || reads[2] == init[0] || reads[2] == init[2] ||
        reads[2] == setter[0] || init_read[0] != setter[0] ||
        apply[0] != setter[0] || setter[0] == init[0] ||
        setter[0] == init[1] || setter[0] == init[2])
        return Reject(reason, "theme-night chain/mode slot relationship");
    c.icons_slot = icons[0]; c.mode_slot = seed[0];
    const int32_t field = Field(Word(pc(kNodeSetter)+8));
    if (field < 7 || (field & 3) != 3 ||
        field != Field(Word(pc(kNodeSetter)+0x2c)) ||
        field != Field(Word(pc(kCreate)+0x17c)) ||
        field != Field(Word(pc(kBitmapLocal)+0xa4)) ||
        field != Field(Word(pc(kStream)+0x34)) ||
        field != Field(Word(pc(kHasFile)+0x30)))
        return Reject(reason, "theme-night field disagreement");
    c.night_field = uint32_t(field);
    const uintptr_t allocator = Call(v, pc(kCreate)+0x16c);
    uint32_t bytes;
    if (!AllocatorTags(v, pc(kCreate)+0x16c, &c.resource_cid, &bytes) ||
        c.resource_cid != Immediate(Word(pc(kNodeSetter)+0x24)) ||
        c.night_field+1+4 > bytes)
        return Reject(reason, "theme-night resource allocator/CID");
    // Do not let a relocated field alias the child, package or fallback state.
    for (size_t off : {size_t(0x180), size_t(0x188), size_t(0x190), size_t(0x1ac), size_t(0x1e0)}) {
        const int32_t other = Field(Word(pc(kCreate)+off));
        const int32_t width = off == 0x180 ? 8 : 4;
        if (field < other+width && other < field+4)
            return Reject(reason, "theme-night field aliases another owner");
    }
    c.true_offset = Immediate(Word(pc(kReadMode)+0x3c));
    c.false_offset = Immediate(Word(pc(kReadMode)+0x40));
    if (c.true_offset == c.false_offset ||
        c.false_offset != Immediate(Word(pc(kCreate)+0x174)) ||
        c.true_offset != Immediate(Word(pc(kStringEquals)+0x68)) ||
        c.false_offset != Immediate(Word(pc(kStringEquals)+0x60)))
        return Reject(reason, "theme-night canonical boolean contract");
    c.icons_pool = Pool(pc(kInit)+8);
    // TBNZ bit 4 takes the false (x22+0x30) branch to the empty prefix.
    // True (x22+0x20) falls through to the night prefix.
    c.night_pool = Pool(pc(kBitmapLocal)+0xb0);
    c.normal_pool = Immediate(Word(pc(kBitmapLocal)+0xbc))*8;
    if (!c.icons_pool || !c.night_pool || !c.normal_pool ||
        c.night_pool == c.normal_pool || c.icons_pool == c.night_pool ||
        c.icons_pool == c.normal_pool ||
        c.night_pool != Pool(pc(kStream)+0x40) ||
        c.night_pool != Pool(pc(kHasFile)+0x3c) ||
        c.normal_pool != Immediate(Word(pc(kStream)+0x4c))*8 ||
        c.normal_pool != Immediate(Word(pc(kHasFile)+0x48))*8)
        return Reject(reason, "theme-night prefix pool relationship");
    c.string_cid = Immediate(Word(pc(kStringEquals)+0x1c));
    c.string_length = uint32_t(Field(Word(pc(kStringEquals)+0x24)));
    c.string_data = Immediate(Word(pc(kStringEquals)+0x40));
    if (!EntrySafe(v, pc(kBitmapLocal)))
        return Reject(reason, "theme-night reader has interior entry");
    // BitmapLocal's full code contract proves an unconditional x0 definition
    // before any incoming x0 use. Its first four instructions need relocation
    // only by the host's existing inline-hook backend.
    *out = {pc(kBitmapLocal), pc(kSystemBitmap), pc(kNodeBitmap), pc(kInit),
        pc(kCreate), pc(kSeed), pc(kReadMode), pc(kApply), pc(kSystemSetter),
        pc(kNodeSetter), pc(kGetSystem), pc(kStream), pc(kHasFile),
        pc(kStringEquals), allocator, c, {}};
    memcpy(out->displaced, reinterpret_cast<void*>(out->bitmap_local), sizeof(out->displaced));
    return true;
}

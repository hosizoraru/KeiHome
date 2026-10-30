// SPDX-License-Identifier: Apache-2.0
// Bounded mapped-code scanning follows MiuiBackGestureHook's
// runtime_profile_resolver.cpp (Apache-2.0). This diagnostic contract is new.
#include "monet_image_trace.h"
#include "monet_image_identity.h"
#include <string.h>

namespace {
struct Pattern { unsigned role, count; uint32_t prefix[4]; uint8_t digest[32]; };
#include "monet_image_trace_patterns.h"
constexpr size_t kMaxSpan = 128u * 1024u * 1024u;
constexpr size_t kMaxWords = 512;
struct Match { uintptr_t pc; size_t words; };

bool Reject(const char** reason, const char* text) {
    if (reason) *reason = text;
    return false;
}
bool Contains(const MonetSelectionImageView& v, uintptr_t p, size_t n) {
    if (!n || p > UINTPTR_MAX - n) return false;
    for (size_t i = 0; i < v.readable_range_count; ++i) {
        const auto& r = v.readable_ranges[i];
        if (r.executable && p >= r.begin && p - r.begin <= r.size &&
            n <= r.size - (p - r.begin)) return true;
    }
    return false;
}
uint32_t Word(uintptr_t p) {
    uint32_t w; memcpy(&w, reinterpret_cast<const void*>(p), 4); return w;
}
bool Target(const MonetSelectionImageView& v, uintptr_t pc, uint32_t w,
            unsigned bits, unsigned shift, uintptr_t* out) {
    const uint32_t mask = (uint32_t{1} << bits) - 1;
    const uint32_t raw = (w >> shift) & mask;
    const int64_t delta = int64_t(raw) - ((raw & (uint32_t{1} << (bits-1))) ? int64_t{1} << bits : 0);
    const int64_t relative = int64_t(pc - v.load_bias) + delta * 4;
    if (relative < 0 || uint64_t(relative) >= kMaxSpan) return false;
    *out = v.load_bias + static_cast<uintptr_t>(relative);
    return Contains(v, *out, 4);
}
bool Direct(const MonetSelectionImageView& v, uintptr_t pc, uint32_t op, uintptr_t* out) {
    if (!Contains(v, pc, 4)) return false;
    const uint32_t w = Word(pc);
    return (w & 0xfc000000u) == op && Target(v, pc, w, 26, 0, out);
}
bool Matches(const MonetSelectionImageView& v, uintptr_t pc, const Pattern& p) {
    if (!Contains(v, pc, p.count * 4) || p.count > kMaxWords) return false;
    for (size_t i = 0; i < 4; ++i) if (Word(pc+i*4) != p.prefix[i]) return false;
    uint32_t normalized[kMaxWords];
    for (size_t i = 0; i < p.count; ++i) normalized[i] = Word(pc+i*4);
    for (size_t i = 0; i < p.count; ++i) {
        const uint32_t w = Word(pc+i*4);
        if ((w & 0xfc000000u) == 0x94000000u) {
            uintptr_t target;
            if (!Target(v, pc+i*4, w, 26, 0, &target)) return false;
            normalized[i] = w & 0xfc000000u;
        }
        if ((w & 0xffc003e0u) == 0x91400360u && i+1 < p.count) {
            const uint32_t next = Word(pc+(i+1)*4);
            if ((next & 0xffc003e0u) == (0xf9400000u | ((w & 31) << 5))) {
                normalized[i] = w & ~0x003ffc00u;
                normalized[i+1] = next & ~0x003ffc00u;
            }
        }
        if ((w & 0xffc003e0u) == 0xf9400360u && ((w >> 10) & 4095)*8 >= 256)
            normalized[i] = w & ~0x003ffc00u;
        if (p.role == kUnmount && i == 13) normalized[i] = w & 0xffe0001fu;
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
        for (uintptr_t pc = r.begin; pc <= r.begin+r.size-16; pc += 4) {
            for (const auto& p : kPatterns) {
                if (p.role != role || Word(pc) != p.prefix[0] || Word(pc+4) != p.prefix[1] ||
                    Word(pc+8) != p.prefix[2] || Word(pc+12) != p.prefix[3]) continue;
                if (++candidates > 4096) return false;
                if (!Matches(v, pc, p)) continue;
                if (out->pc && out->pc != pc) return false;
                *out = {pc, p.count};
            }
        }
    }
    return out->pc != 0;
}
unsigned CallsTo(const MonetSelectionImageView& v, const Match& m, uintptr_t target) {
    unsigned count = 0;
    for (size_t i = 0; i < m.words; ++i) {
        uintptr_t actual;
        if (Direct(v, m.pc+i*4, 0x94000000u, &actual) && actual == target) ++count;
    }
    return count;
}
bool Allocator(const MonetSelectionImageView& v, uintptr_t pc, uint32_t* cid) {
    if (!Contains(v, pc, 0x30)) return false;
    const uint32_t low = Word(pc), high = Word(pc+4);
    if ((low & 0xffe0001fu) != 0xd2800002u || (high & 0xffe0001fu) != 0xf2a00002u)
        return false;
    const uint32_t tags = ((low >> 5) & 0xffffu) | (((high >> 5) & 0xffffu) << 16);
    if ((tags & 0xfffu) != 0x31cu) return false; // 48-byte compressed-pointer Image
    *cid = tags >> 12;
    // Independently tie the class tag to the adjacent generated type-check stub.
    const uint32_t check[] = {0x360000e0u, 0xf85ff009u, 0xd34c7d29u};
    for (size_t i = 0; i < 3; ++i) if (Word(pc+12+i*4) != check[i]) return false;
    if ((Word(pc+24) & 0xffe0001fu) != 0xd2800011u ||
        ((Word(pc+24) >> 5) & 0xffffu) != *cid || Word(pc+28) != 0xeb11013fu) return false;
    uintptr_t generic;
    if (!Direct(v, pc+8, 0x14000000u, &generic) || !Contains(v, generic, 64)) return false;
    // Prove that the tags are stored at the untagged allocation address and
    // returned with tag +1. This is metadata validation, not an allocator call.
    const uint32_t body[] = {
        0xd3482c44u,0xd37cec84u,0xa9461740u,0x8b040003u,
        0xeb0300bfu,0x54000169u,0xf9003343u,0xf9000002u,
        0x91002004u,0x92407ed0u,0xaa108210u,0xa8814090u,
        0xeb03009fu,0x54ffffc3u,0x91000400u,0xd65f03c0u,
    };
    return memcmp(reinterpret_cast<const void*>(generic), body, sizeof(body)) == 0;
}
bool NoInteriorBranches(const MonetSelectionImageView& v, const Match* roles) {
    for (size_t i = 0; i < v.readable_range_count; ++i) {
        const auto& r = v.readable_ranges[i];
        if (!r.executable) continue;
        for (uintptr_t pc = r.begin; pc < r.begin+r.size; pc += 4) {
            const uint32_t w = Word(pc);
            unsigned bits = 0, shift = 0;
            if ((w & 0x7c000000u) == 0x14000000u) bits = 26;
            else if ((w & 0xff000010u) == 0x54000000u || (w & 0x7e000000u) == 0x34000000u)
                { bits = 19; shift = 5; }
            else if ((w & 0x7e000000u) == 0x36000000u) { bits = 14; shift = 5; }
            if (!bits) continue;
            uintptr_t target;
            if (!Target(v, pc, w, bits, shift, &target)) continue;
            for (unsigned role = 0; role < 3; ++role) {
                if (target > roles[role].pc && target < roles[role].pc+32) return false;
            }
        }
    }
    return true;
}
} // namespace

bool ResolveMonetImageTrace(const MonetSelectionImageView& v, MonetImageTraceProfile* out,
                           const char** reason) {
    if (reason) *reason = nullptr;
    if (!out) return Reject(reason, "missing image-trace output");
    *out = {};
    if (!v.load_bias || v.load_bias > UINTPTR_MAX-kMaxSpan || !v.readable_ranges ||
        !v.readable_range_count || v.readable_range_count > 16)
        return Reject(reason, "invalid image-trace ranges");
    for (size_t i = 0; i < v.readable_range_count; ++i) {
        const auto& r = v.readable_ranges[i];
        if (!r.size || r.begin < v.load_bias || r.begin-v.load_bias > kMaxSpan ||
            r.size > kMaxSpan-(r.begin-v.load_bias) || (r.executable && ((r.begin | r.size) & 3)))
            return Reject(reason, "invalid image-trace segment bounds");
        for (size_t j = 0; j < i; ++j) {
            const auto& other = v.readable_ranges[j];
            if (r.begin < other.begin+other.size && other.begin < r.begin+r.size)
                return Reject(reason, "overlapping image-trace segments");
        }
    }
    Match roles[kRoleCount]{};
    const char* failures[] = {"missing/ambiguous Image.dispose", "missing/ambiguous Image.clone",
        "missing/ambiguous monochrome build", "missing/ambiguous monochrome clear",
        "missing/ambiguous monochrome put", "missing/ambiguous RawImage update",
        "missing/ambiguous State unmount"};
    for (unsigned role = 0; role < kRoleCount; ++role)
        if (!Find(v, role, &roles[role])) return Reject(reason, failures[role]);
    uintptr_t allocator, clone;
    uint32_t cid;
    if (!Direct(v, roles[kClone].pc+0x34, 0x94000000u, &allocator) || !Allocator(v, allocator, &cid) ||
        !Direct(v, roles[kRawUpdate].pc+0x78, 0x94000000u, &clone) || clone != roles[kClone].pc ||
        CallsTo(v, roles[kClear], roles[kDispose].pc) != 2 ||
        CallsTo(v, roles[kPut], roles[kDispose].pc) != 2 || !NoInteriorBranches(v, roles))
        return Reject(reason, "image-trace call/layout/entry contract");
    // These x0 kills precede every branch/call/read of incoming x0. The leaf
    // bridges use x0 alone for their final trampoline jump; x16 is preserved.
    if (Word(roles[kDispose].pc+12) != 0x910082c0u ||
        Word(roles[kClone].pc+12) != 0xb841f020u ||
        Word(roles[kBuild].pc+16) != 0xb8413020u)
        return Reject(reason, "image-trace trampoline scratch is live");
    *out = {roles[kDispose].pc, roles[kClone].pc, roles[kBuild].pc, roles[kClear].pc,
            roles[kPut].pc, roles[kRawUpdate].pc, roles[kUnmount].pc, allocator, cid,
            (Word(roles[kUnmount].pc+0x34) >> 5) & 0xffffu};
    return true;
}

// SPDX-License-Identifier: Apache-2.0
// Bounded mapped-code scanning follows MiuiBackGestureHook's
// runtime_profile_resolver.cpp (Apache-2.0). The recovery contract is restricted to RawImage update ownership.
#include "monet_image_recovery.h"
#include "monet_image_identity.h"
#include <string.h>

namespace {
struct Pattern { unsigned role, count; uint32_t prefix[4]; uint8_t digest[32]; };
#include "monet_image_recovery_patterns.h"
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
        if (p.role == kRawUpdate && i == 15) normalized[i] = w & 0xffe0001fu;
        if (p.role == kGuardedClone && i == 0xb8/4) normalized[i] = w & 0xffe0001fu;
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
bool Allocator(const MonetSelectionImageView& v, uintptr_t pc, uint32_t size_tags,
               uint32_t* cid, uintptr_t* generic) {
    if (!Contains(v, pc, 12)) return false;
    const uint32_t low = Word(pc), high = Word(pc+4);
    if ((low & 0xffe0001fu) != 0xd2800002u || (high & 0xffe0001fu) != 0xf2a00002u)
        return false;
    const uint32_t tags = ((low >> 5) & 0xffffu) | (((high >> 5) & 0xffffu) << 16);
    if ((tags & 0xfffu) != size_tags || (tags >> 12) < 256u) return false;
    *cid = tags >> 12;
    if (!Direct(v, pc+8, 0x14000000u, generic) || !Contains(v, *generic, 64)) return false;
    // Generic allocator writes the provided tags to the header and returns +1.
    const uint32_t body[] = {
        0xd3482c44u,0xd37cec84u,0xa9461740u,0x8b040003u,
        0xeb0300bfu,0x54000169u,0xf9003343u,0xf9000002u,
        0x91002004u,0x92407ed0u,0xaa108210u,0xa8814090u,
        0xeb03009fu,0x54ffffc3u,0x91000400u,0xd65f03c0u,
    };
    return memcmp(reinterpret_cast<const void*>(*generic), body, sizeof(body)) == 0;
}
bool NoInteriorBranches(const MonetSelectionImageView& v, uintptr_t entry) {
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
            if (Target(v, pc, w, bits, shift, &target) && target > entry && target < entry+32)
                return false;
        }
    }
    return true;
}
int32_t Offset(uint32_t word) {
    return int32_t((word >> 12) & 511u) - ((word & (1u << 20)) ? 512 : 0);
}
} // namespace

bool ResolveMonetImageRecovery(const MonetSelectionImageView& v, MonetImageRecoveryProfile* out,
                              const char** reason) {
    if (reason) *reason = nullptr;
    if (!out) return Reject(reason, "missing image-recovery output");
    *out = {};
    if (!v.load_bias || v.load_bias > UINTPTR_MAX-kMaxSpan || !v.readable_ranges ||
        !v.readable_range_count || v.readable_range_count > 16)
        return Reject(reason, "invalid image-recovery ranges");
    for (size_t i = 0; i < v.readable_range_count; ++i) {
        const auto& r = v.readable_ranges[i];
        if (!r.size || r.begin < v.load_bias || r.begin-v.load_bias > kMaxSpan ||
            r.size > kMaxSpan-(r.begin-v.load_bias) || (r.executable && ((r.begin | r.size) & 3)))
            return Reject(reason, "invalid image-recovery segment bounds");
        for (size_t j = 0; j < i; ++j) {
            const auto& other = v.readable_ranges[j];
            if (r.begin < other.begin+other.size && other.begin < r.begin+r.size)
                return Reject(reason, "overlapping image-recovery segments");
        }
    }
    Match roles[kRoleCount]{};
    const char* failures[] = {"missing/ambiguous Image.clone", "missing/ambiguous Image.dispose",
        "missing/ambiguous RawImage update", "missing/ambiguous RawImage create",
        "missing/ambiguous RenderImage setter", "missing/ambiguous RenderImage dispose",
        "missing/ambiguous guarded Image clone"};
    for (unsigned role = 0; role < kRoleCount; ++role)
        if (!Find(v, role, &roles[role])) return Reject(reason, failures[role]);
    const uintptr_t clone = roles[kClone].pc, dispose = roles[kDispose].pc;
    const uintptr_t update = roles[kRawUpdate].pc, create = roles[kRawCreate].pc;
    const uintptr_t setter = roles[kRenderSet].pc, teardown = roles[kRenderDispose].pc;
    uintptr_t target, image_allocator, render_allocator, generic, render_generic;
    uint32_t image_cid, render_cid;
    // The complete setter/update/create bodies authenticate each reviewed
    // RenderImage layout. Bind its allocator size to that layout; accepting both
    // sizes independently would admit a corrupted allocator on the older one.
    const uint32_t render_image_field = uint32_t(Offset(Word(setter+0x18)));
    const uint32_t render_size_tag = render_image_field == 0x63 ? 0xe1c :
        render_image_field == 0x6f ? 0xf1c : 0;
    if (!render_size_tag) return Reject(reason, "unreviewed RenderImage layout");
    if (!Direct(v, update+0x78, 0x94000000u, &target) || target != clone ||
        !Direct(v, update+0x8c, 0x94000000u, &target) || target != setter ||
        !Direct(v, create+0x38, 0x94000000u, &target) || target != clone ||
        !Direct(v, clone+0x34, 0x94000000u, &image_allocator) ||
        !Direct(v, create+0x120, 0x94000000u, &render_allocator) ||
        !Allocator(v, image_allocator, 0x31c, &image_cid, &generic) ||
        !Allocator(v, render_allocator, render_size_tag, &render_cid, &render_generic) || generic != render_generic ||
        ((Word(update+0x3c) >> 5) & 0xffffu) != render_cid || image_cid == render_cid ||
        CallsTo(v, roles[kRenderSet], dispose) != 2 || CallsTo(v, roles[kRenderDispose], dispose) != 1 ||
        !NoInteriorBranches(v, clone))
        return Reject(reason, "image-recovery call/allocator/entry contract");
    // Type tests are no longer emitted beside their allocator in newer AOT
    // layouts. Follow an authenticated cast -> Image.clone caller instead;
    // its inline check and nullable cast must agree with the clone allocator.
    const uintptr_t guarded = roles[kGuardedClone].pc;
    uintptr_t cast = 0;
    if (!Direct(v, guarded+0xf4, 0x94000000u, &target) || target != clone ||
        !Direct(v, guarded+0xd4, 0x94000000u, &cast) || !Contains(v, cast, 48) ||
        ((Word(guarded+0xb8) >> 5) & 0xffffu) != image_cid)
        return Reject(reason, "Image cast/clone graph mismatch");
    const uint32_t check[] = {0x360000e0u, 0xf85ff009u, 0xd34c7d29u};
    for (size_t i = 0; i < 3; ++i)
        if (Word(cast+i*4) != check[i]) return Reject(reason, "Image type check");
    const uint32_t tail[] = {0xeb11013fu, 0x54000041u, 0xd65f03c0u,
        0x6b16001fu, 0x54000041u, 0xd65f03c0u, 0xf9413f50u, 0xd61f0200u};
    for (size_t i = 0; i < 8; ++i)
        if (Word(cast+16+i*4) != tail[i]) return Reject(reason, "Image cast return contract");
    if ((Word(cast+12) & 0xffe0001fu) != 0xd2800011u ||
        ((Word(cast+12) >> 5) & 0xffffu) != image_cid)
        return Reject(reason, "Image CID/type-check mismatch");

    // Full fingerprints preserve register/field/local-branch instructions; only
    // external BLs, PP loads and the independently checked render CID relocate.
    // Decode offsets from those authenticated instructions, never version RVAs.
    MonetImageRecoveryConfig c{};
    c.update_return = update+0x7c;
    c.image_cid = image_cid;
    c.render_cid = render_cid;
    c.receiver_spill = Offset(Word(update+0x20)); // stur x3, [original FP, #-0x18]
    c.render_image = uint32_t(Offset(Word(setter+0x18)));
    c.disposed = uint32_t(Offset(Word(clone+0xc)));
    c.backing = uint32_t(Offset(Word(clone+0x18)));
    c.frame_size = (Word(update+8) >> 10) & 4095u;
    if (c.receiver_spill >= 0 || uint32_t(-c.receiver_spill) > c.frame_size ||
        c.receiver_spill != Offset(Word(update+0x84)) ||
        c.render_image != uint32_t(Offset(Word(setter+0x1e4))) ||
        c.render_image != uint32_t(Offset(Word(teardown+0x14))) ||
        c.render_image != uint32_t(Offset(Word(teardown+0x34))) ||
        c.disposed != uint32_t(Offset(Word(dispose+0x14))) ||
        c.backing != uint32_t(Offset(Word(setter+0x48))) ||
        c.backing != uint32_t(Offset(Word(setter+0x50))) ||
        Word(clone+0xc) != 0xb841f020u) // unconditional x0 kill before any read/call
        return Reject(reason, "image-recovery field/frame contract");
    c.enabled = 1;
    *out = {clone, dispose, update, create, setter, teardown, image_allocator, render_allocator,
            guarded, cast, c};
    return true;
}

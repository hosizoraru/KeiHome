// SPDX-License-Identifier: Apache-2.0
#include "icon_highlight_profile.h"

#include <string.h>

namespace {
struct PatternWord { uint32_t word, mask; };
#include "icon_highlight_patterns.inc"
constexpr size_t kMaxSpan = 128u * 1024u * 1024u;

bool Reject(const char** reason, const char* text) {
    if (reason) *reason = text;
    return false;
}
bool Contains(const IconHighlightImage& image, size_t at, size_t size,
              bool executable = true) {
    if (at > image.size || size > image.size - at) return false;
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& r = image.ranges[i];
        if ((!executable || r.executable) && at >= r.begin && at <= r.end &&
                size <= r.end - at) return true;
    }
    return false;
}
uint16_t U16(const uint8_t* p) {
    return uint16_t(p[0]) | uint16_t(p[1]) << 8;
}
uint32_t U32(const uint8_t* p) {
    return uint32_t(U16(p)) | uint32_t(U16(p + 2)) << 16;
}
uint64_t U64(const uint8_t* p) {
    return uint64_t(U32(p)) | uint64_t(U32(p + 4)) << 32;
}
uint32_t Word(const IconHighlightImage& image, size_t pc) {
    return U32(image.base + pc); // Callers establish Contains first.
}
bool ValidateImage(const IconHighlightImage& image) {
    if (!image.base || !image.ranges || !image.range_count ||
            image.range_count > 32 || image.size > kMaxSpan ||
            image.size > UINTPTR_MAX - reinterpret_cast<uintptr_t>(image.base)) return false;
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& r = image.ranges[i];
        if (r.begin >= r.end || r.end > image.size || (r.begin & 3u)) return false;
        for (size_t j = 0; j < i; ++j) {
            if (r.begin < image.ranges[j].end && image.ranges[j].begin < r.end) return false;
        }
    }
    if (!Contains(image, 0, 64, false)) return false;
    const auto* h = image.base;
    if (memcmp(h, "\177ELF\2\1\1", 7) || U16(h + 16) != 3 ||
            U16(h + 18) != 183 || U32(h + 20) != 1 || U16(h + 54) != 56) return false;
    const uint64_t ph = U64(h + 32);
    const size_t count = U16(h + 56);
    if (!count || count > 64 || ph > image.size ||
            !Contains(image, size_t(ph), count * 56, false)) return false;
    size_t found = 0;
    for (size_t i = 0; i < count; ++i) {
        const uint8_t* p = h + ph + i * 56;
        // Reviewed Dart images have no GNU_PROPERTY segment. Do not silently
        // remove mapping-level BTI/PAC requirements in an unknown image.
        if (U32(p) == 0x6474e553u) return false;
        if (U32(p) != 1 || !U64(p + 40)) continue;
        const uint32_t flags = U32(p + 4);
        if (!(flags & 4u)) { if (flags & 1u) return false; else continue; }
        const uint64_t begin = U64(p + 16), size = U64(p + 40);
        if (begin > image.size || size > image.size - begin || U64(p + 32) > size) return false;
        bool matched = false;
        for (size_t j = 0; j < image.range_count; ++j) {
            const auto& r = image.ranges[j];
            if (r.begin == begin && r.end == begin + size &&
                    r.executable == bool(flags & 1u)) matched = true;
        }
        if (!matched) return false;
        ++found;
    }
    return found == image.range_count;
}
bool Matches(const IconHighlightImage& image, size_t pc,
             const PatternWord* words, size_t count) {
    if (!Contains(image, pc, count * 4)) return false;
    for (size_t i = 0; i < count; ++i) {
        if ((Word(image, pc + i * 4) & words[i].mask) != words[i].word) return false;
    }
    return true;
}
int32_t Field(uint32_t word) {
    const uint32_t imm = (word >> 12) & 511u;
    return int32_t(imm) - (imm & 256u ? 512 : 0);
}
uint32_t Slot(uint32_t word) { return ((word >> 10) & 4095u) * 8; }
template<size_t N>
size_t Find(const IconHighlightImage& image, const PatternWord (&pattern)[N],
            uint32_t writer_field = 0) {
    size_t found = 0;
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& r = image.ranges[i];
        if (!r.executable || r.end - r.begin < N * 4) continue;
        for (size_t pc = r.begin; pc <= r.end - N * 4; pc += 4) {
            if (Word(image, pc) != pattern[0].word ||
                    !Matches(image, pc, pattern, N)) continue;
            if (writer_field && (Field(Word(image, pc + 0x9c)) != int32_t(writer_field) ||
                    Field(Word(image, pc + 0xac)) != int32_t(writer_field) ||
                    Field(Word(image, pc + 0xc0)) != int32_t(writer_field))) continue;
            if (found) return 0;
            found = pc;
        }
    }
    return found;
}
int64_t Delta(uint32_t imm, unsigned bits) {
    return (int64_t(imm) - ((imm & (1u << (bits - 1))) ? (int64_t{1} << bits) : 0)) * 4;
}
bool Branch(uint32_t word, size_t pc, int64_t* target) {
    if ((word & 0x7c000000u) == 0x14000000u)
        *target = int64_t(pc) + Delta(word & 0x3ffffffu, 26);
    else if ((word & 0xff000010u) == 0x54000000u ||
             (word & 0x7e000000u) == 0x34000000u)
        *target = int64_t(pc) + Delta((word >> 5) & 0x7ffffu, 19);
    else if ((word & 0x7e000000u) == 0x36000000u)
        *target = int64_t(pc) + Delta((word >> 5) & 0x3fffu, 14);
    else return false;
    return true;
}
size_t Call(const IconHighlightImage& image, size_t pc) {
    if (!Contains(image, pc, 4)) return 0;
    const uint32_t w = Word(image, pc);
    if ((w & 0xfc000000u) != 0x94000000u) return 0;
    const int64_t target = int64_t(pc) + Delta(w & 0x3ffffffu, 26);
    return target > 0 && Contains(image, size_t(target), 4) ? size_t(target) : 0;
}
bool AllExternalCallsMapped(const IconHighlightImage& image, size_t pc, size_t bytes) {
    for (size_t i = 0; i < bytes; i += 4) {
        const uint32_t w = Word(image, pc + i);
        if ((w & 0xfc000000u) == 0x94000000u && !Call(image, pc + i)) return false;
        if ((w & 0xfc000000u) == 0x14000000u) {
            int64_t target = 0;
            if (!Branch(w, pc + i, &target) || target < 0 ||
                    !Contains(image, size_t(target), 4)) return false;
        }
    }
    return true;
}
bool NoUnexpectedIngress(const IconHighlightImage& image, size_t entry) {
    for (size_t i = 0; i < image.range_count; ++i) {
        const auto& r = image.ranges[i];
        if (!r.executable) continue;
        for (size_t pc = r.begin; pc + 4 <= r.end; pc += 4) {
            int64_t target = 0;
            if (!Branch(Word(image, pc), pc, &target)) continue;
            if (target >= int64_t(entry + 0x60) && target < int64_t(entry + 0x6c)) return false;
            if (target > int64_t(entry) && target < int64_t(entry + 0x7c) &&
                    (pc < entry || pc >= entry + 0x7c)) return false;
        }
    }
    return true;
}
} // namespace

bool ResolveIconHighlight(const IconHighlightImage& image, IconHighlightProfile* out,
                          const char** rejection) {
    if (rejection) *rejection = nullptr;
    if (!out) return Reject(rejection, "missing highlight output");
    *out = {};
    if (!ValidateImage(image)) return Reject(rejection, "invalid/incomplete highlight ELF ranges");
    const size_t h = Find(image, kHighlight), d = Find(image, kDisabled),
                 s = Find(image, kSupported), c = Find(image, kStroke);
    if (!h || !d || !s || !c) return Reject(rejection, "ambiguous highlight instruction roles");
    const int32_t field = Field(Word(image, h + 0x5c)), os = Field(Word(image, s + 0x60));
    if (field < 7 || (field & 3) != 3 || os < 7 || (os & 3) != 3 || field == os)
        return Reject(rejection, "invalid highlight field layout");
    const size_t w = Find(image, kWriter, uint32_t(field));
    if (!w || Call(image, h + 0x0c) != d || Call(image, d + 8) != s ||
            Call(image, c + 0x2c) != h)
        return Reject(rejection, "highlight guard/writer/consumer graph changed");
    const uint32_t device = Slot(Word(image, h + 0x18));
    if (!device || device != Slot(Word(image, s + 0x1c)) ||
            Slot(Word(image, h + 0x28)) != Slot(Word(image, s + 0x2c)) ||
            Slot(Word(image, h + 0x38)) != Slot(Word(image, s + 0x3c)) ||
            Slot(Word(image, h + 0x40)) != Slot(Word(image, s + 0x44)) ||
            Slot(Word(image, h + 0x40)) != Slot(Word(image, h + 0x38)) + 8 ||
            Call(image, h + 0x3c) != Call(image, s + 0x40) ||
            Call(image, h + 0x3c) != Call(image, w + 0x38) ||
            Call(image, h + 0x4c) != Call(image, s + 0x50))
        return Reject(rejection, "highlight original receiver/lookup linkage changed");
    if (!AllExternalCallsMapped(image, h, sizeof(kHighlight) / sizeof(PatternWord) * 4) ||
            !AllExternalCallsMapped(image, d, sizeof(kDisabled) / sizeof(PatternWord) * 4) ||
            !AllExternalCallsMapped(image, s, sizeof(kSupported) / sizeof(PatternWord) * 4) ||
            !AllExternalCallsMapped(image, w, sizeof(kWriter) / sizeof(PatternWord) * 4) ||
            !AllExternalCallsMapped(image, c, sizeof(kStroke) / sizeof(PatternWord) * 4) ||
            !NoUnexpectedIngress(image, h))
        return Reject(rejection, "highlight branch target/patch ingress changed");
    const uintptr_t base = reinterpret_cast<uintptr_t>(image.base);
    *out = {base + h, base + d, base + s, base + w, base + c, base + h + 0x5c,
            base + h + 0x6c, uint32_t(field), device, Slot(Word(image, s + 0x10)),
            Slot(Word(image, d + 0x1c)), uint32_t(os), {}};
    for (size_t i = 0; i < 4; ++i) out->expected[i] = Word(image, h + 0x5c + i * 4);
    return true;
}

bool BuildIconHighlightBridge(const IconHighlightProfile& p, const uint8_t* code,
                              size_t size, uintptr_t policy, uint8_t* out) {
    if (!out) return false;
    memset(out, 0, kIconHighlightBridgeSize);
    // This complete template is also assembled independently in host tests.
    constexpr uint32_t expected[] = {
        0xd50324df, 0xd503201f, 0xd503201f, 0x58000160, 0x88dffc00,
        0x51000400, 0x35000060, 0x910082c0, 0x14000002, 0xaa0203e0,
        0xaa1d03ef, 0xa8c179fd, 0xd65f03c0, 0xd503201f, 0, 0};
    if (!code || size != sizeof(expected) || !policy || (policy & 3u) ||
            memcmp(code, expected, sizeof(expected)) ||
            !p.entry || p.entry > UINTPTR_MAX - 0x7c ||
            p.patch != p.entry + 0x5c || p.false_target != p.entry + 0x6c ||
            p.theme_field < 7 || p.theme_field > 255 || (p.theme_field & 3u) != 3 ||
            p.expected[0] != (0xb8400022u | (p.theme_field << 12)) ||
            p.expected[1] != 0x8b1c8042 || p.expected[2] != 0xaa0203e0 ||
            p.expected[3] != 0x14000002) return false;
    memcpy(out, code, size);
    memcpy(out + 4, p.expected, 8);
    const uint64_t address = policy;
    memcpy(out + 56, &address, 8);
    return true;
}

bool BuildIconHighlightDetour(uintptr_t bridge, uint32_t out[4]) {
    if (!out) return false;
    memset(out, 0, 16);
    if (!bridge || (bridge & 3u)) return false;
    out[0] = 0x58000040; // ldr x0, PC+8
    out[1] = 0xd61f0000; // br x0; no x16/x17 clobber
    out[2] = uint32_t(bridge);
    out[3] = uint32_t(uint64_t(bridge) >> 32);
    return true;
}

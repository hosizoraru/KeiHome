// SPDX-License-Identifier: Apache-2.0
#include "maml_zip_release_profile.h"
#include <string.h>

namespace {
#include "maml_zip_release_patterns.inc"
constexpr size_t kZipNodeCount = sizeof(kZipNodes) / sizeof(kZipNodes[0]);
// Native Arc final-release -> Shared drop -> index-map, name-map and comment
// cleanup. Relative call locations are resolved independently below.
constexpr uint32_t words[] = {
    0xa9be7bfd, 0xa9014ff4, 0x910003fd, 0xaa0003f3, 0x91004000,
    0x94000000, 0x94000000, 0xa8c27bfd, 0x14000000, 0x94000000,
    0xa9be7bfd, 0xa9014ff4, 0x910003fd, 0xaa0003f3, 0x94000000,
    0x9100c260, 0x94000000, 0xa9418660, 0xa9414ff4, 0xa8c27bfd,
    0x14000000, 0xaa0003f4, 0x9100c260, 0x94000000, 0xa9418660,
    0x94000000,
};
bool Contains(const IconHighlightImage& im, size_t offset, size_t length,
              bool executable = false) {
    if (offset > im.size || length > im.size - offset) return false;
    for (size_t i = 0; i < im.range_count; ++i) {
        const auto& r = im.ranges[i];
        if ((!executable || r.executable) && offset >= r.begin && offset <= r.end &&
                length <= r.end - offset) return true;
    }
    return false;
}
bool Executable(const IconHighlightImage& im, size_t offset, size_t length) {
    return Contains(im, offset, length, true);
}
uint32_t Word(const IconHighlightImage& im, size_t offset) {
    uint32_t v; memcpy(&v, im.base + offset, sizeof(v)); return v;
}
size_t Branch(const IconHighlightImage& im, size_t pc) {
    const int32_t delta = static_cast<int32_t>(Word(im, pc) << 6) >> 4;
    const int64_t dest = static_cast<int64_t>(pc) + delta;
    return dest > 0 && Executable(im, static_cast<size_t>(dest), 4)
        ? static_cast<size_t>(dest) : 0;
}
uint64_t U64(const IconHighlightImage& im, size_t at) {
    uint64_t v; memcpy(&v, im.base + at, sizeof(v)); return v;
}
// Android dynamic entries are normally image-relative. Also admit a loader
// that has relocated them, while retaining the same readable-image boundary.
size_t Relative(const IconHighlightImage& im, uint64_t value) {
    const uintptr_t base = reinterpret_cast<uintptr_t>(im.base);
    if (value >= base && value - base < im.size) return size_t(value - base);
    return value < im.size ? size_t(value) : im.size;
}
bool StringIs(const IconHighlightImage& im, size_t table, size_t size,
              uint64_t offset, const char* expected) {
    const size_t length = strlen(expected) + 1;
    return offset < size && length <= size - offset &&
        Contains(im, table, size) &&
        !memcmp(im.base + table + size_t(offset), expected, length);
}
bool FreeImport(const IconHighlightImage& im, size_t* got) {
    // ValidateArm64DartImage has already authenticated the ELF/program headers.
    const size_t ph = size_t(U64(im, 32));
    uint16_t count; memcpy(&count, im.base + 56, 2);
    size_t dynamic = 0, length = 0;
    for (size_t i = 0; i < count; ++i) {
        const size_t p = ph + i * 56;
        if (Word(im, p) != 2) continue; // PT_DYNAMIC
        if (dynamic) return false;
        dynamic = Relative(im, U64(im, p + 16));
        const uint64_t bytes = U64(im, p + 40);
        if (!bytes || bytes > 4096 || bytes % 16) return false;
        length = size_t(bytes);
    }
    if (!dynamic || !Contains(im, dynamic, length)) return false;
    size_t strings = im.size, symbols = im.size, relas = im.size;
    uint64_t strsize = 0, relsize = 0, syment = 0, reltype = 0, relaent = 0;
    uint64_t seen = 0;
    bool terminated = false;
    for (size_t p = dynamic; p < dynamic + length; p += 16) {
        const uint64_t tag = U64(im, p), value = U64(im, p + 8);
        if (!tag) { terminated = true; break; }
        // Reject duplicate singleton tags rather than choose one.
        const uint64_t bit = tag < 64 ? uint64_t{1} << tag : 0;
        if (tag != 1 && bit && (seen & bit)) return false;
        seen |= bit;
        switch (tag) {
            case 5: strings = Relative(im, value); break;
            case 6: symbols = Relative(im, value); break;
            case 10: strsize = value; break;
            case 11: syment = value; break;
            case 23: relas = Relative(im, value); break;
            case 2: relsize = value; break;
            case 20: reltype = value; break;
            case 9: relaent = value; break;
            default: break;
        }
    }
    if (!terminated || syment != 24 || reltype != 7 || relaent != 24 ||
            !strsize || strsize > 1024 * 1024 || !relsize ||
            relsize > 1024 * 1024 || relsize % 24 ||
            !Contains(im, strings, size_t(strsize)) ||
            !Contains(im, relas, size_t(relsize))) return false;
    bool libc = false;
    for (size_t p = dynamic; U64(im, p); p += 16) {
        if (U64(im, p) == 1 &&
                StringIs(im, strings, size_t(strsize), U64(im, p + 8), "libc.so"))
            libc = true;
    }
    if (!libc) return false;
    *got = 0;
    for (size_t p = relas; p < relas + size_t(relsize); p += 24) {
        const uint64_t info = U64(im, p + 8);
        if (uint32_t(info) != 1026) continue; // R_AARCH64_JUMP_SLOT
        const uint64_t index = info >> 32;
        if (symbols > im.size || index > (im.size - symbols) / 24) return false;
        const size_t symbol = symbols + size_t(index) * 24;
        if (!Contains(im, symbol, 24)) return false;
        if (!StringIs(im, strings, size_t(strsize), Word(im, symbol), "free")) continue;
        uint16_t section; memcpy(&section, im.base + symbol + 6, 2);
        if (*got || section || im.base[symbol + 4] != 0x12 ||
                im.base[symbol + 5] || U64(im, symbol + 8) ||
                U64(im, symbol + 16) || U64(im, p + 16)) return false;
        *got = Relative(im, U64(im, p));
        if (!*got || !Contains(im, *got, 8) || Executable(im, *got, 8)) return false;
    }
    return *got != 0;
}
bool FreePlt(const IconHighlightImage& im, size_t pc, size_t got) {
    if (!Executable(im, pc, 16)) return false;
    const uint32_t a = Word(im, pc), l = Word(im, pc + 4), d = Word(im, pc + 8);
    if ((a & 0x9f00001fu) != 0x90000010u ||
            (l & 0xffc003ffu) != 0xf9400211u ||
            (d & 0xffc003ffu) != 0x91000210u ||
            Word(im, pc + 12) != 0xd61f0220u) return false;
    const uint32_t encoded = ((a >> 29) & 3u) | (((a >> 5) & 0x7ffffu) << 2);
    const int64_t pages = encoded & 0x100000u
        ? int64_t(encoded) - 0x200000 : int64_t(encoded);
    const size_t offset = ((l >> 10) & 0xfffu) * 8;
    const int64_t slot = int64_t(pc & ~size_t{4095}) + pages * 4096 + int64_t(offset);
    return offset == ((d >> 10) & 0xfffu) && slot >= 0 && uint64_t(slot) == got;
}
bool NormalGraph(const IconHighlightImage& im, size_t root, size_t free_got) {
    size_t resolved[kZipNodeCount]{};
    resolved[0] = root;
    // The generated discovery order guarantees a node's first incoming link
    // precedes it. All subsequent links must resolve to that exact same node.
    for (size_t id = 0; id < kZipNodeCount; ++id) {
        const auto& node = kZipNodes[id];
        const size_t pc = resolved[id];
        if (!pc || !Executable(im, pc, node.span)) return false;
        if (!node.count) {
            if (!FreePlt(im, pc, free_got)) return false;
            continue;
        }
        for (size_t j = node.first; j < size_t(node.first) + node.count; ++j) {
            const auto& instruction = kZipWords[j];
            const uint32_t mask = instruction.target < 0 ? 0xffffffffu : 0xfc000000u;
            if ((Word(im, pc + instruction.offset) & mask) != instruction.word) return false;
            if (instruction.target < 0) continue;
            const size_t target = Branch(im, pc + instruction.offset);
            const size_t index = size_t(instruction.target);
            if (!target || index >= kZipNodeCount) return false;
            if (resolved[index] && resolved[index] != target) return false;
            for (size_t other = 0; other < kZipNodeCount; ++other)
                if (other != index && resolved[other] == target) return false;
            resolved[index] = target;
        }
    }
    return true;
}
bool Match(const IconHighlightImage& im, size_t pc) {
    for (size_t j = 0; j < sizeof(words) / sizeof(words[0]); ++j) {
        const uint32_t mask = (words[j] & 0x7c000000) == 0x14000000
            ? 0xfc000000 : 0xffffffff;
        if ((Word(im, pc + 4 * j) & mask) != words[j]) return false;
        if (mask == 0xfc000000 && !Branch(im, pc + 4 * j)) return false;
    }
    // The owned pointer flows directly to Shared.data; no caller stack load.
    // Both normal and unwind cleanup call the same name-map destructor.
    return Branch(im, pc + 20) == pc + 40 &&
        Branch(im, pc + 64) == Branch(im, pc + 92);
}
bool Reject(const char** out, const char* why) {
    if (out) *out = why;
    return false;
}
}

bool ResolveMamlZipRelease(const IconHighlightImage& im, uintptr_t* address,
                           const char** rejection) {
    if (rejection) *rejection = nullptr;
    if (!address) return Reject(rejection, "missing ZIP release output");
    *address = 0;
    if (!ValidateArm64DartImage(im))
        return Reject(rejection, "invalid ZIP runtime ELF ranges");
    size_t free_got = 0;
    if (!FreeImport(im, &free_got))
        return Reject(rejection, "ZIP allocator import contract changed");
    size_t found = 0;
    for (size_t i = 0; i < im.range_count; ++i) {
        const auto& r = im.ranges[i];
        if (!r.executable || r.end - r.begin < sizeof(words)) continue;
        for (size_t pc = (r.begin + 3) & ~size_t{3};
             pc <= r.end - sizeof(words); pc += 4) {
            if (Word(im, pc) != words[0] || !Match(im, pc) ||
                    !NormalGraph(im, pc, free_got)) continue;
            if (found) return Reject(rejection, "ambiguous ZIP metadata release");
            found = pc;
        }
    }
    if (!found) return Reject(rejection, "ZIP metadata ownership contract changed");
    *address = reinterpret_cast<uintptr_t>(im.base) + found;
    return true;
}

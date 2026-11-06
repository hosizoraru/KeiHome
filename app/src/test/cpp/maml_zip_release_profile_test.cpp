// SPDX-License-Identifier: Apache-2.0
#include "maml_zip_release_profile.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fstream>
#include <iterator>
#include <vector>

namespace {
#include "maml_zip_release_patterns.inc"
uint32_t Read(const std::vector<uint8_t>& bytes, size_t at) {
    uint32_t value; memcpy(&value, bytes.data() + at, 4); return value;
}
void Write(std::vector<uint8_t>& bytes, size_t at, uint32_t value) {
    memcpy(bytes.data() + at, &value, 4);
}
size_t Target(const std::vector<uint8_t>& bytes, size_t at) {
    const int32_t delta = static_cast<int32_t>(Read(bytes, at) << 6) >> 4;
    return size_t(int64_t(at) + delta);
}
uint32_t Redirect(uint32_t original, size_t at, size_t target) {
    const int64_t delta = int64_t(target) - int64_t(at);
    assert(!(delta % 4) && delta >= -(int64_t{1} << 27) && delta < (int64_t{1} << 27));
    return (original & 0xfc000000u) | (uint32_t(delta / 4) & 0x03ffffffu);
}
}

int main(int argc, char** argv) {
    assert(argc == 3);
    std::ifstream file(argv[1], std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(file)), {});
    assert(bytes.size() >= 64);
    const auto u16 = [&](size_t at) { uint16_t v; memcpy(&v, bytes.data() + at, 2); return v; };
    const auto u64 = [&](size_t at) { uint64_t v; memcpy(&v, bytes.data() + at, 8); return v; };
    struct Segment {
        uint32_t type, flags;
        uint64_t offset, vaddr, paddr, filesz, memsz, align;
    };
    assert(sizeof(Segment) == 56 && u16(54) == sizeof(Segment));
    std::vector<IconHighlightRange> ranges;
    std::vector<Segment> loads;
    size_t size = 0;
    for (size_t i = 0; i < u16(56); ++i) {
        Segment p;
        assert(u64(32) + (i + 1) * sizeof(p) <= bytes.size());
        memcpy(&p, bytes.data() + u64(32) + i * sizeof(p), sizeof(p));
        if (p.type != 1 || !(p.flags & 4) || !p.memsz) continue;
        assert(p.offset + p.filesz <= bytes.size());
        assert(p.vaddr + p.memsz < 128u * 1024u * 1024u);
        if (size < p.vaddr + p.memsz) size = p.vaddr + p.memsz;
        ranges.push_back({p.vaddr, p.vaddr + p.memsz, bool(p.flags & 1)});
        loads.push_back(p);
    }
    std::vector<uint8_t> mapped(size);
    for (const auto& p : loads)
        memcpy(mapped.data() + p.vaddr, bytes.data() + p.offset, p.filesz);
    IconHighlightImage image{mapped.data(), mapped.size(), ranges.data(), ranges.size()};
    const size_t expected = strtoull(argv[2], nullptr, 0);
    uintptr_t address = 1;
    const char* why = nullptr;
    if (!ResolveMamlZipRelease(image, &address, &why)) {
        fprintf(stderr, "resolution failed: %s\n", why); return 1;
    }
    assert(address == reinterpret_cast<uintptr_t>(mapped.data()) + expected);
    size_t tests = 1;
    constexpr size_t node_count = sizeof(kZipNodes) / sizeof(kZipNodes[0]);
    size_t nodes[node_count]{};
    nodes[0] = expected;
    for (size_t id = 0; id < node_count; ++id) {
        assert(nodes[id] && nodes[id] + kZipNodes[id].span <= mapped.size());
        const auto& node = kZipNodes[id];
        for (size_t j = node.first; j < size_t(node.first) + node.count; ++j) {
            const auto& word = kZipWords[j];
            if (word.target < 0) continue;
            const size_t target = Target(mapped, nodes[id] + word.offset);
            assert(!nodes[word.target] || nodes[word.target] == target);
            nodes[word.target] = target;
        }
    }
    // Structural words and independently linked normal/unwind cleanup.
    for (size_t offset = 0; offset < 104; offset += 4) {
        uint32_t saved; memcpy(&saved, mapped.data() + expected + offset, 4);
        const uint32_t changed = saved ^ 0x80000000u;
        memcpy(mapped.data() + expected + offset, &changed, 4);
        assert(!ResolveMamlZipRelease(image, &address) && address == 0);
        memcpy(mapped.data() + expected + offset, &saved, 4);
        ++tests;
    }
    for (size_t offset : {size_t{20}, size_t{64}, size_t{92}}) {
        uint32_t saved; memcpy(&saved, mapped.data() + expected + offset, 4);
        const uint32_t changed = saved + 1;
        memcpy(mapped.data() + expected + offset, &changed, 4);
        assert(!ResolveMamlZipRelease(image, &address) && address == 0);
        memcpy(mapped.data() + expected + offset, &saved, 4);
        ++tests;
    }
    // Every reachable normal instruction is part of the contract. In particular
    // this covers ZipFileData's 176-byte slice stride and nested field drops.
    for (size_t id = 0; id < node_count; ++id) {
        const auto& node = kZipNodes[id];
        for (size_t j = node.first; j < size_t(node.first) + node.count; ++j) {
            const auto& word = kZipWords[j];
            const size_t pc = nodes[id] + word.offset;
            const uint32_t saved = Read(mapped, pc);
            Write(mapped, pc, saved ^ 0x80000000u);
            assert(!ResolveMamlZipRelease(image, &address) && address == 0);
            Write(mapped, pc, saved);
            ++tests;
            if (word.target < 0) continue;
            // Keep B/BL valid and its destination readable/executable, but send
            // it to the wrong typed role rather than just corrupt its opcode.
            const size_t wrong = nodes[word.target] == expected ? nodes[1] : expected;
            Write(mapped, pc, Redirect(saved, pc, wrong));
            assert(!ResolveMamlZipRelease(image, &address) && address == 0);
            Write(mapped, pc, saved);
            ++tests;
        }
        if (!node.count) {
            // PLT address materialization and the indirect BR are authenticated.
            for (size_t offset = 0; offset < 16; offset += 4) {
                const uint32_t saved = Read(mapped, nodes[id] + offset);
                Write(mapped, nodes[id] + offset, saved ^ 1u);
                assert(!ResolveMamlZipRelease(image, &address) && address == 0);
                Write(mapped, nodes[id] + offset, saved);
                ++tests;
            }
        }
    }
    // Import role must survive neither symbol substitution nor GOT redirection,
    // even though the PLT itself still has the expected instruction shape.
    const auto mapped64 = [&](size_t at) {
        uint64_t value; memcpy(&value, mapped.data() + at, 8); return value;
    };
    size_t dynamic = 0;
    for (size_t i = 0; i < u16(56); ++i) {
        Segment p; memcpy(&p, bytes.data() + u64(32) + i * sizeof(p), sizeof(p));
        if (p.type == 2) dynamic = p.vaddr;
    }
    assert(dynamic);
    size_t strings = 0, symbols = 0, relas = 0, relsize = 0;
    for (size_t p = dynamic; mapped64(p); p += 16) {
        switch (mapped64(p)) {
            case 5: strings = mapped64(p + 8); break;
            case 6: symbols = mapped64(p + 8); break;
            case 23: relas = mapped64(p + 8); break;
            case 2: relsize = mapped64(p + 8); break;
            default: break;
        }
    }
    assert(strings && symbols && relas && relsize);
    size_t import_tests = 0;
    for (size_t p = relas; p < relas + relsize; p += 24) {
        const size_t symbol = symbols + (mapped64(p + 8) >> 32) * 24;
        const size_t name = strings + Read(mapped, symbol);
        if (strcmp(reinterpret_cast<const char*>(mapped.data() + name), "free")) continue;
        // Name, binding/type, section, relocation type, GOT role and addend.
        for (size_t at : {name, symbol + 4, symbol + 6, p + 8, p, p + 16}) {
            const uint8_t saved_byte = mapped[at];
            mapped[at] ^= 1;
            assert(!ResolveMamlZipRelease(image, &address) && address == 0);
            mapped[at] = saved_byte;
            ++tests;
            ++import_tests;
        }
    }
    assert(import_tests == 6);
    bool tested_libc = false;
    std::vector<size_t> relocated_entries;
    for (size_t p = dynamic; mapped64(p); p += 16) {
        const uint64_t tag = mapped64(p), value = mapped64(p + 8);
        if (tag == 1 && !strcmp(reinterpret_cast<const char*>(
                mapped.data() + strings + value), "libc.so")) {
            const size_t at = strings + value;
            mapped[at] = 'm';
            assert(!ResolveMamlZipRelease(image, &address) && address == 0);
            mapped[at] = 'l';
            tested_libc = true;
            ++tests;
        }
        if (tag == 5 || tag == 6 || tag == 23) relocated_entries.push_back(p + 8);
    }
    assert(tested_libc && relocated_entries.size() == 3);
    // A loader's already-relocated dynamic pointers describe the same roles.
    for (size_t at : relocated_entries) {
        const uint64_t value = mapped64(at) + reinterpret_cast<uintptr_t>(mapped.data());
        memcpy(mapped.data() + at, &value, 8);
    }
    assert(ResolveMamlZipRelease(image, &address));
    assert(address == reinterpret_cast<uintptr_t>(mapped.data()) + expected);
    for (size_t at : relocated_entries) {
        const uint64_t value = mapped64(at) - reinterpret_cast<uintptr_t>(mapped.data());
        memcpy(mapped.data() + at, &value, 8);
    }
    ++tests;
    // Bounds must be checked before reading any callee. Remove a deep graph
    // node's segment to exercise the fail-closed image/range boundary.
    for (auto& range : ranges) {
        if (nodes[1] >= range.begin && nodes[1] < range.end) {
            const size_t saved_end = range.end;
            range.end = nodes[1];
            assert(!ResolveMamlZipRelease(image, &address) && address == 0);
            range.end = saved_end;
            ++tests;
            break;
        }
    }
    // Relocate the root and its adjacent Shared destructor while preserving all
    // real typed callees. Two independently valid graphs must remain ambiguous.
    const size_t duplicate = expected + 0x1000;
    std::vector<uint8_t> saved(104);
    memcpy(saved.data(), mapped.data() + duplicate, 104);
    memcpy(mapped.data() + duplicate, mapped.data() + expected, 104);
    for (size_t offset = 0; offset < 104; offset += 4) {
        const uint32_t word = Read(mapped, expected + offset);
        if ((word & 0x7c000000u) != 0x14000000u) continue;
        size_t target = Target(mapped, expected + offset);
        if (target >= expected && target < expected + 104) target += duplicate - expected;
        Write(mapped, duplicate + offset, Redirect(word, duplicate + offset, target));
    }
    assert(!ResolveMamlZipRelease(image, &address, &why));
    assert(address == 0);
    assert(!strcmp(why, "ambiguous ZIP metadata release"));
    memcpy(mapped.data() + duplicate, saved.data(), 104);
    ++tests;
    assert(ResolveMamlZipRelease(image, &address));
    assert(address == reinterpret_cast<uintptr_t>(mapped.data()) + expected);
    const auto complete = image;
    image.size = 4;
    assert(!ResolveMamlZipRelease(image, &address) && address == 0);
    image = complete;
    assert(!ResolveMamlZipRelease(image, nullptr));
    printf("%zu native ZIP ownership contract checks passed: %s\n", tests + 2, argv[1]);
}

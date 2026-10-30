// SPDX-License-Identifier: Apache-2.0
#include "monet_image_trace.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <algorithm>
#include <fstream>
#include <iterator>
#include <vector>

struct Header {
    uint8_t ident[16]; uint16_t type, machine; uint32_t version;
    uint64_t entry, phoff, shoff; uint32_t flags;
    uint16_t ehsize, phentsize, phnum, shentsize, shnum, shstrndx;
};
struct Program { uint32_t type, flags; uint64_t offset, va, pa, filesz, memsz, align; };

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream input(argv[1], std::ios::binary);
    std::vector<uint8_t> file((std::istreambuf_iterator<char>(input)), {});
    assert(file.size() >= sizeof(Header));
    Header header; memcpy(&header, file.data(), sizeof(header));
    assert(!memcmp(header.ident, "\177ELF\2\1", 6) && header.machine == 183);
    assert(header.phentsize == sizeof(Program) && header.phnum <= 16);
    assert(header.phoff <= file.size() && header.phnum*sizeof(Program) <= file.size()-header.phoff);
    std::vector<Program> programs(header.phnum);
    memcpy(programs.data(), file.data()+header.phoff, programs.size()*sizeof(Program));
    size_t size = 0;
    for (const auto& p : programs) if (p.type == 1) {
        assert(p.va < 0x8000000 && p.memsz < 0x8000000-p.va);
        size = std::max(size, size_t(p.va+p.memsz));
    }
    size = (size+4095)&~size_t(4095);
    std::vector<uint8_t> mapped(size+4096);
    const uintptr_t base = reinterpret_cast<uintptr_t>(mapped.data());
    std::vector<MonetSelectionImageRange> ranges;
    for (const auto& p : programs) if (p.type == 1) {
        assert(p.offset <= file.size() && p.filesz <= file.size()-p.offset && p.filesz <= p.memsz);
        memcpy(mapped.data()+p.va, file.data()+p.offset, p.filesz);
        if (p.flags & 4) ranges.push_back({base+p.va, size_t(p.memsz), bool(p.flags & 1)});
    }
    MonetSelectionImageView view{base, ranges.data(), ranges.size()};
    MonetImageTraceProfile profile{};
    const char* reason = nullptr;
    if (!ResolveMonetImageTrace(view, &profile, &reason)) {
        fprintf(stderr, "Rejected %s: %s\n", argv[1], reason); return 1;
    }
    const auto good = profile;
    unsigned rejects = 0, positives = 1;
    auto reject = [&](const MonetSelectionImageView& candidate) {
        memset(&profile, 0xa5, sizeof(profile));
        assert(!ResolveMonetImageTrace(candidate, &profile, &reason));
        const MonetImageTraceProfile empty{};
        assert(!memcmp(&profile, &empty, sizeof(empty)) && reason);
        ++rejects;
    };
    auto positive = [&] {
        assert(ResolveMonetImageTrace(view, &profile, &reason)); ++positives;
    };
    auto word = [](uintptr_t pc) -> uint32_t& { return *reinterpret_cast<uint32_t*>(pc); };
    auto mutate = [&](uintptr_t pc, uint32_t mask) {
        word(pc) ^= mask; reject(view); word(pc) ^= mask;
    };
    // Complete body fingerprints authenticate every read ABI and local CFG.
    for (uintptr_t entry : {good.dispose, good.clone, good.build, good.clear, good.put,
                            good.raw_update, good.unmount}) mutate(entry+4, 0x40);
    for (uintptr_t pc : {good.dispose+0x14, good.clone+0xc, good.clone+0x14,
                        good.build+0x10, good.build+0x234, good.unmount+0x38}) mutate(pc, 0x1000);
    mutate(good.image_allocator, 0x2000); // allocation size
    mutate(good.image_allocator+24, 0x20); // tag/type-check mismatch
    mutate(good.raw_update+0x78, 1); // correct opcode, wrong executable target
    mutate(good.clone+0x34, 1);
    for (uintptr_t function : {good.clear, good.put}) {
        unsigned changed = 0;
        for (uintptr_t pc = function; pc < function+0x150; pc += 4) {
            uint32_t w = word(pc);
            if ((w & 0xfc000000u) != 0x94000000u) continue;
            const int64_t delta = (int64_t(w & 0x3ffffffu)^0x2000000)-0x2000000;
            if (uintptr_t(int64_t(pc)+delta*4) == good.dispose) { mutate(pc, 1); ++changed; }
        }
        assert(changed == 2);
    }
    auto bad = view; bad.readable_ranges = nullptr; reject(bad);
    bad = view; bad.readable_range_count = 0; reject(bad);
    bad = view; bad.readable_range_count = 17; reject(bad);
    bad = view; bad.load_bias = UINTPTR_MAX-4; reject(bad);
    for (auto& r : ranges) if (r.executable) {
        const auto saved = r;
        r.executable = false; reject(view); r = saved;
        r.size -= 1; reject(view); r = saved;
        r.begin += 1; reject(view); r = saved;
    }
    // Metadata may move coherently; no image hash or numeric CID allowlist.
    const uint32_t low = word(good.image_allocator), check = word(good.image_allocator+24);
    word(good.image_allocator) += 1u << 17; // increment CID inside MOVZ's immediate
    word(good.image_allocator+24) += 1u << 5;
    positive(); assert(profile.image_cid == good.image_cid+1);
    word(good.image_allocator) = low; word(good.image_allocator+24) = check;
    word(good.unmount+0x34) ^= 1u << 5;
    positive(); assert(profile.dispose_selector == (good.dispose_selector^1u));
    word(good.unmount+0x34) ^= 1u << 5;
    mapped[9] ^= 1; positive(); mapped[9] ^= 1; // non-code ELF identity change

    // Duplicate and relocated entry at a different image address. Preserve all
    // decoded outgoing BLs and rewrite actual incoming direct calls for the move.
    const uintptr_t moved = base+size;
    memcpy(reinterpret_cast<void*>(moved), reinterpret_cast<const void*>(good.dispose), 0x6c);
    auto branch = [&](uintptr_t pc, uintptr_t target, uint32_t opcode) {
        const int64_t delta = int64_t(target)-int64_t(pc);
        assert(delta%4 == 0 && delta >= -(int64_t{1}<<27) && delta < (int64_t{1}<<27));
        return opcode | (uint32_t(delta/4)&0x3ffffffu);
    };
    for (size_t i = 0; i < 0x6c; i += 4) {
        const uint32_t w = word(good.dispose+i);
        if ((w & 0xfc000000u) == 0x94000000u) {
            const int64_t delta = (int64_t(w&0x3ffffffu)^0x2000000)-0x2000000;
            word(moved+i) = branch(moved+i, uintptr_t(int64_t(good.dispose+i)+delta*4), 0x94000000u);
        }
    }
    ranges.push_back({moved, 4096, true}); view.readable_ranges = ranges.data(); view.readable_range_count = ranges.size();
    reject(view); // two independently valid entries are ambiguous
    word(good.dispose) = 0xd503201f;
    std::vector<std::pair<uintptr_t, uint32_t>> redirected;
    for (const auto& r : ranges) if (r.executable) for (uintptr_t pc = r.begin; pc < r.begin+r.size; pc += 4) {
        const uint32_t w = word(pc);
        if ((w & 0x7c000000u) != 0x14000000u) continue;
        const int64_t delta = (int64_t(w&0x3ffffffu)^0x2000000)-0x2000000;
        if (uintptr_t(int64_t(pc)+delta*4) == good.dispose) {
            redirected.emplace_back(pc, w); word(pc) = branch(pc, moved, w&0xfc000000u);
        }
    }
    positive(); assert(profile.dispose == moved);
    word(moved+0x100) = branch(moved+0x100, moved+4, 0x14000000u);
    reject(view); // branch into the displaced prologue
    word(moved+0x100) = 0;
    for (const auto& p : redirected) word(p.first) = p.second;
    word(good.dispose) = 0xa9bf79fd;
    ranges.pop_back(); view.readable_range_count = ranges.size();
    positive();
    printf("{\"rejects\":%u,\"positives\":%u,\"dispose\":%zu,\"clone\":%zu,\"build\":%zu,"
           "\"clear\":%zu,\"put\":%zu,\"raw_update\":%zu,\"unmount\":%zu,\"image_cid\":%u,\"dispose_selector\":%u}\n",
           rejects, positives, size_t(good.dispose-base), size_t(good.clone-base), size_t(good.build-base),
           size_t(good.clear-base), size_t(good.put-base), size_t(good.raw_update-base), size_t(good.unmount-base),
           good.image_cid, good.dispose_selector);
}

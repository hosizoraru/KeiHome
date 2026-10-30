// SPDX-License-Identifier: Apache-2.0
#include "monet_image_recovery.h"
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
    MonetImageRecoveryProfile profile{};
    const char* reason = nullptr;
    if (!ResolveMonetImageRecovery(view, &profile, &reason)) {
        fprintf(stderr, "Rejected %s: %s\n", argv[1], reason); return 1;
    }
    const auto good = profile;
    unsigned rejects = 0, positives = 1;
    auto reject = [&](const MonetSelectionImageView& candidate) {
        memset(&profile, 0xa5, sizeof(profile));
        assert(!ResolveMonetImageRecovery(candidate, &profile, &reason));
        const MonetImageRecoveryProfile empty{};
        assert(!memcmp(&profile, &empty, sizeof(empty)) && reason);
        ++rejects;
    };
    auto positive = [&] {
        assert(ResolveMonetImageRecovery(view, &profile, &reason)); ++positives;
    };
    auto word = [](uintptr_t pc) -> uint32_t& { return *reinterpret_cast<uint32_t*>(pc); };
    auto mutate = [&](uintptr_t pc, uint32_t mask) {
        word(pc) ^= mask; reject(view); word(pc) ^= mask;
    };
    auto branch = [](uintptr_t pc, uintptr_t target, uint32_t op) {
        const int64_t delta = int64_t(target)-int64_t(pc);
        assert(delta%4 == 0 && delta >= -(int64_t{1}<<27) && delta < (int64_t{1}<<27));
        return op | (uint32_t(delta/4)&0x3ffffffu);
    };
    // Body mutations include the original frame spill/reload, bool check,
    // compressed heap reconstruction, backing identity and setter disposal.
    for (uintptr_t entry : {good.clone, good.dispose, good.raw_update, good.raw_create,
                           good.render_set, good.render_dispose}) mutate(entry+4, 0x40);
    for (uintptr_t pc : {good.raw_update+8, good.raw_update+0x20, good.raw_update+0x84,
                        good.raw_update+0x64, good.clone+0xc, good.clone+0x14,
                        good.clone+0x18, good.clone+0x3c, good.dispose+0xc,
                        good.render_set+0x18, good.render_set+0x48, good.render_set+0x50,
                        good.render_set+0x58, good.render_set+0x60, good.render_set+0x1e4,
                        good.render_dispose+0x34}) mutate(pc, 0x1000);
    // BL relocation is allowed only when the ownership edges still agree.
    for (uintptr_t pc : {good.clone+0x34, good.raw_update+0x78, good.raw_update+0x8c,
                        good.raw_create+0x38, good.raw_create+0x120, good.render_set+0x64,
                        good.render_set+0x1d4, good.render_dispose+0x2c}) mutate(pc, 1);
    for (uintptr_t allocator : {good.image_allocator, good.render_allocator}) {
        mutate(allocator, 0x2000); // wrong allocation size
        mutate(allocator+4, 0x20); // mismatched CID
        const uint32_t saved = word(allocator+8);
        word(allocator+8) = branch(allocator+8, base, 0x14000000u); // non-executable target
        reject(view); word(allocator+8) = saved;
    }
    mutate(good.image_allocator+24, 0x20);
    mutate(good.raw_update+0x3c, 0x20);
    const uint32_t w = word(good.image_allocator+8);
    const uintptr_t generic = good.image_allocator+8+(((int64_t(w&0x3ffffff)^0x2000000)-0x2000000)*4);
    mutate(generic+28, 0x20); // actual header write
    mutate(generic+56, 0x400); // return tag

    auto bad = view; bad.readable_ranges = nullptr; reject(bad);
    bad = view; bad.readable_range_count = 0; reject(bad);
    bad = view; bad.readable_range_count = 17; reject(bad);
    bad = view; bad.load_bias = UINTPTR_MAX-4; reject(bad);
    bad = view; bad.load_bias = 0; reject(bad);
    assert(!ResolveMonetImageRecovery(view, nullptr, &reason) && reason); ++rejects;
    for (auto& r : ranges) if (r.executable) {
        const auto saved = r;
        r.executable = false; reject(view); r = saved;
        r.size -= 1; reject(view); r = saved;
        r.begin += 1; reject(view); r = saved;
        r.size = 0x8000000; reject(view); r = saved;
    }
    ranges.push_back(ranges[0]); view.readable_ranges = ranges.data(); view.readable_range_count = ranges.size();
    reject(view); ranges.pop_back(); view.readable_range_count = ranges.size();

    // Positive metadata movement proves there is no numeric CID allowlist.
    for (auto pair : {std::pair{good.image_allocator, good.image_allocator+24},
                      std::pair{good.render_allocator, good.raw_update+0x3c}}) {
        const uint32_t low = word(pair.first), check = word(pair.second);
        word(pair.first) += 1u << 17; word(pair.second) += 1u << 5;
        positive();
        if (pair.first == good.image_allocator) assert(profile.config.image_cid == good.config.image_cid+1);
        else assert(profile.config.render_cid == good.config.render_cid+1);
        word(pair.first) = low; word(pair.second) = check;
    }
    mapped[9] ^= 1; positive(); mapped[9] ^= 1;

    // Duplicate then relocate clone and all direct incoming edges.
    const uintptr_t moved = base+size;
    memcpy(reinterpret_cast<void*>(moved), reinterpret_cast<const void*>(good.clone), 0xa0);
    for (size_t i = 0; i < 0xa0; i += 4) {
        const uint32_t op = word(good.clone+i);
        if ((op & 0xfc000000u) == 0x94000000u) {
            const int64_t delta = (int64_t(op&0x3ffffffu)^0x2000000)-0x2000000;
            word(moved+i) = branch(moved+i, good.clone+i+delta*4, 0x94000000u);
        }
    }
    ranges.push_back({moved, 4096, true}); view.readable_ranges = ranges.data(); view.readable_range_count = ranges.size();
    reject(view);
    word(good.clone) = 0xd503201f;
    std::vector<std::pair<uintptr_t, uint32_t>> redirected;
    for (const auto& r : ranges) if (r.executable) for (uintptr_t pc = r.begin; pc < r.begin+r.size; pc += 4) {
        const uint32_t op = word(pc);
        if ((op & 0x7c000000u) != 0x14000000u) continue;
        const int64_t delta = (int64_t(op&0x3ffffffu)^0x2000000)-0x2000000;
        if (uintptr_t(int64_t(pc)+delta*4) == good.clone) {
            redirected.emplace_back(pc, op); word(pc) = branch(pc, moved, op&0xfc000000u);
        }
    }
    positive(); assert(profile.clone == moved);
    // Every direct branch family must reject an entry into displaced bytes.
    const uintptr_t probe = moved+0x100;
    for (uint32_t op : {0x14000000u, 0x94000000u, 0x54000000u, 0x34000000u, 0x36000000u}) {
        if ((op&0x7c000000u) == 0x14000000u) word(probe) = branch(probe, moved+4, op);
        else {
            const uint32_t mask = (op == 0x36000000u) ? 0x3fffu : 0x7ffffu;
            word(probe) = op | ((uint32_t((int64_t(moved+4)-int64_t(probe))/4)&mask)<<5);
        }
        reject(view);
    }
    word(probe) = 0;
    for (const auto& p : redirected) word(p.first) = p.second;
    word(good.clone) = 0xa9bf79fd;
    ranges.pop_back(); view.readable_range_count = ranges.size();
    positive();
    // Independently move the update caller: the published LR must follow it.
    memcpy(reinterpret_cast<void*>(moved), reinterpret_cast<const void*>(good.raw_update), 0x284);
    for (size_t i = 0; i < 0x284; i += 4) {
        const uint32_t op = word(good.raw_update+i);
        if ((op & 0xfc000000u) == 0x94000000u) {
            const int64_t delta = (int64_t(op&0x3ffffffu)^0x2000000)-0x2000000;
            word(moved+i) = branch(moved+i, good.raw_update+i+delta*4, 0x94000000u);
        }
    }
    ranges.push_back({moved, 4096, true}); view.readable_ranges = ranges.data(); view.readable_range_count = ranges.size();
    reject(view);
    word(good.raw_update) = 0xd503201f;
    positive(); assert(profile.raw_update == moved && profile.config.update_return == moved+0x7c);
    word(good.raw_update) = 0xa9bf79fd;
    ranges.pop_back(); view.readable_range_count = ranges.size();
    positive();
    printf("{\"rejects\":%u,\"positives\":%u,\"clone\":%zu,\"dispose\":%zu,"
           "\"raw_update\":%zu,\"raw_create\":%zu,\"render_set\":%zu,\"render_dispose\":%zu,"
           "\"image_allocator\":%zu,\"render_allocator\":%zu,\"image_cid\":%u,\"render_cid\":%u,"
           "\"update_return\":%zu,\"receiver_spill\":%d,\"render_image\":%u,\"disposed\":%u,"
           "\"backing\":%u,\"frame_size\":%u}\n",
           rejects, positives, size_t(good.clone-base), size_t(good.dispose-base),
           size_t(good.raw_update-base), size_t(good.raw_create-base), size_t(good.render_set-base),
           size_t(good.render_dispose-base), size_t(good.image_allocator-base), size_t(good.render_allocator-base),
           good.config.image_cid, good.config.render_cid, size_t(good.config.update_return-base),
           good.config.receiver_spill, good.config.render_image, good.config.disposed, good.config.backing,
           good.config.frame_size);
}

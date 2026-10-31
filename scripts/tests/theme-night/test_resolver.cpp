// SPDX-License-Identifier: Apache-2.0
#include "theme_night_profile.h"
#include <algorithm>
#include <assert.h>
#include <fstream>
#include <iterator>
#include <stdio.h>
#include <string.h>
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
    Header h; memcpy(&h, file.data(), sizeof(h));
    assert(!memcmp(h.ident, "\177ELF\2\1", 6) && h.machine == 183);
    assert(h.phentsize == sizeof(Program) && h.phnum <= 16);
    assert(h.phoff <= file.size() && h.phnum*sizeof(Program) <= file.size()-h.phoff);
    std::vector<Program> programs(h.phnum);
    memcpy(programs.data(), file.data()+h.phoff, programs.size()*sizeof(Program));
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
    ThemeNightProfile profile{};
    const char* reason = nullptr;
    if (!ResolveThemeNight(view, &profile, &reason)) {
        fprintf(stderr, "Rejected %s: %s\n", argv[1], reason); return 1;
    }
    const auto good = profile;
    unsigned rejects = 0, positives = 1;
    auto refresh = [&] { view.readable_ranges = ranges.data(); view.readable_range_count = ranges.size(); };
    auto reject = [&](const MonetSelectionImageView& v) {
        memset(&profile, 0xa5, sizeof(profile));
        assert(!ResolveThemeNight(v, &profile, &reason));
        const ThemeNightProfile empty{};
        assert(!memcmp(&profile, &empty, sizeof(empty)) && reason);
        ++rejects;
    };
    auto positive = [&] {
        if (!ResolveThemeNight(view, &profile, &reason)) {
            fprintf(stderr, "Positive rejected: %s\n", reason); assert(false);
        }
        assert(profile.config.mode == 0); ++positives;
    };
    auto word = [](uintptr_t p) -> uint32_t& { return *reinterpret_cast<uint32_t*>(p); };
    auto mutate = [&](uintptr_t p, uint32_t mask) { word(p) ^= mask; reject(view); word(p) ^= mask; };
    auto branch = [](uintptr_t pc, uintptr_t target, uint32_t op) {
        const int64_t delta = int64_t(target)-int64_t(pc);
        assert(delta%4 == 0 && delta >= -(int64_t{1}<<27) && delta < (int64_t{1}<<27));
        return op | (uint32_t(delta/4)&0x3ffffffu);
    };
    // Prove rejection of missing anchors, incoming x0 reads, mismatched fields,
    // different boolean interpretation, pool relations and independent chains.
    for (uintptr_t p : {good.init+4, good.bitmap_local+0x10, good.create+0x7c,
                        good.create+0x174, good.node_setter+8, good.seed+0x4c,
                        good.system_setter+0x40, good.bitmap_local+0xb4,
                        good.string_equals+0x34, good.read_mode+0x34,
                        good.init+0x50, good.apply+0x18}) mutate(p, 0x400);
    for (uintptr_t p : {good.init+0x10, good.init+0x24, good.init+0x58,
                        good.node_bitmap+0x2c, good.apply+0x38, good.get_system+0x68})
        mutate(p, 1);
    mutate(good.allocator, 1u << 17); // CID must agree with node setter.
    mutate(good.allocator, 1u << 14); // 48 -> 16 bytes, with identical CID.
    mutate(good.allocator+8, 1u); // Generic allocator must authenticate tag layout.
    const auto alloc_branch = word(good.allocator+8);
    const intptr_t alloc_delta =
        ((intptr_t(alloc_branch&0x3ffffffu)^0x2000000)-0x2000000)*4;
    const uintptr_t generic = good.allocator+8+alloc_delta;
    mutate(generic, 1u<<10); // Changed size decoding is not an allocation proof.
    auto bad = view;
    bad.readable_ranges = nullptr; reject(bad);
    bad = view; bad.readable_range_count = 0; reject(bad);
    bad = view; bad.readable_range_count = 17; reject(bad);
    bad = view; bad.load_bias = UINTPTR_MAX-4; reject(bad);
    assert(!ResolveThemeNight(view, nullptr, &reason)); ++rejects;
    for (auto& r : ranges) if (r.executable) {
        const auto saved = r;
        r.executable = false; reject(view); r = saved;
        r.size -= 1; reject(view); r = saved;
    }
    ranges.push_back(ranges[0]); refresh(); reject(view);
    ranges.pop_back(); refresh();

    // Change every relevant table operand together: the output must follow
    // instructions, not 8593's or 7722's table addresses.
    std::vector<std::pair<uintptr_t,uint32_t>> edits;
    const auto replace = [&](uintptr_t p, uint32_t value) {
        edits.emplace_back(p, word(p)); word(p) = value;
    };
    const auto restore = [&] {
        for (auto it=edits.rbegin(); it!=edits.rend(); ++it) word(it->first)=it->second;
        edits.clear();
    };
    for (auto pair : {std::pair{good.init,size_t(0x7c)}, {good.seed,size_t(0x60)},
                      {good.system_setter,size_t(0xdc)}, {good.apply,size_t(0xc0)},
                      {good.system_bitmap,size_t(0x160)}}) {
        for (size_t off=0; off+4<pair.second; off+=4) {
            const auto a=word(pair.first+off), b=word(pair.first+off+4);
            if ((a&0xffc003e0u)==0xf9400340u &&
                (b&0xff8003e0u)==(0xf9000000u|((a&31)<<5))) {
                replace(pair.first+off, a+0x400);
                replace(pair.first+off+4, b+0x800);
            }
        }
    }
    positive();
    assert(profile.config.thread_table == good.config.thread_table+8);
    assert(profile.config.icons_slot == good.config.icons_slot+16);
    assert(profile.config.mode_slot == good.config.mode_slot+16);
    restore();
    // The node field may move only in agreement with all independent readers
    // and its constructor/setter, without aliasing package/child/fallback data.
    for (uintptr_t p : {good.create+0x17c, good.node_setter+8, good.node_setter+0x2c,
                        good.bitmap_local+0xa4, good.stream+0x34, good.has_file+0x30})
        replace(p, (word(p)&~0x1ff000u) | (0x27u<<12));
    positive(); assert(profile.config.night_field == 0x27); restore();
    replace(good.allocator, word(good.allocator)+(1u<<17));
    replace(good.node_setter+0x24, word(good.node_setter+0x24)+(1u<<10));
    positive(); assert(profile.config.resource_cid == good.config.resource_cid+1); restore();

    // Duplicate -> ambiguity; relocate the only body and retarget callers ->
    // accepted. This also verifies the supported BL relocation boundaries.
    const uintptr_t moved=base+size;
    constexpr size_t reader_size=0x3c4;
    memcpy(reinterpret_cast<void*>(moved), reinterpret_cast<void*>(good.bitmap_local), reader_size);
    for (size_t off=0; off<reader_size; off+=4) {
        const auto w=word(good.bitmap_local+off);
        if ((w&0x7c000000u)!=0x14000000u) continue;
        const intptr_t delta=((intptr_t(w&0x3ffffffu)^0x2000000)-0x2000000)*4;
        const uintptr_t target=good.bitmap_local+off+delta;
        if (target < good.bitmap_local || target >= good.bitmap_local+reader_size)
            word(moved+off)=branch(moved+off,target,w&0xfc000000u);
    }
    ranges.push_back({moved,4096,true}); refresh(); reject(view);
    replace(good.bitmap_local, 0xd503201fu);
    replace(good.node_bitmap+0x2c, branch(good.node_bitmap+0x2c,moved,0x94000000u));
    positive(); assert(profile.bitmap_local==moved);
    const uintptr_t probe=moved+0x800;
    word(probe)=branch(probe,moved+4,0x14000000u); reject(view); word(probe)=0;
    restore(); ranges.pop_back(); refresh(); positive();

    const auto& c=good.config;
    printf("{\"positives\":%u,\"rejects\":%u,\"bitmap_local\":%zu,\"system_bitmap\":%zu,"
           "\"node_bitmap\":%zu,\"init\":%zu,\"create\":%zu,\"seed\":%zu,"
           "\"read_mode\":%zu,\"apply\":%zu,\"system_setter\":%zu,\"node_setter\":%zu,"
           "\"get_system\":%zu,\"stream\":%zu,\"has_file\":%zu,\"string_equals\":%zu,"
           "\"allocator\":%zu,\"config\":[%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u,%u]}\n",
           positives,rejects,size_t(good.bitmap_local-base),size_t(good.system_bitmap-base),
           size_t(good.node_bitmap-base),size_t(good.init-base),size_t(good.create-base),
           size_t(good.seed-base),size_t(good.read_mode-base),size_t(good.apply-base),
           size_t(good.system_setter-base),size_t(good.node_setter-base),
           size_t(good.get_system-base),size_t(good.stream-base),size_t(good.has_file-base),
           size_t(good.string_equals-base),size_t(good.allocator-base),
           c.mode,c.thread_table,c.icons_slot,c.mode_slot,c.night_field,c.resource_cid,
           c.true_offset,c.false_offset,c.icons_pool,c.night_pool,c.normal_pool,
           c.string_cid,c.string_length,c.string_data);
}

// SPDX-License-Identifier: Apache-2.0
// External ELF fixtures only. The same resolver runs on every supplied build.
#include "monet_tint_profile.h"
#include "monet_image_identity.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <fstream>
#include <iterator>
#include <vector>

struct Header {
    uint8_t ident[16]; uint16_t type, machine; uint32_t version;
    uint64_t entry, phoff, shoff; uint32_t flags;
    uint16_t ehsize, phentsize, phnum, shentsize, shnum, shstrndx;
};
struct Program { uint32_t type, flags; uint64_t offset, va, pa, filesz, memsz, align; };
struct Section { uint32_t name, type; uint64_t flags, addr, offset, size; uint32_t link, info; uint64_t align, entry; };
struct Symbol { uint32_t name; uint8_t info, other; uint16_t index; uint64_t value, size; };

int main(int argc, char** argv) {
    if (argc != 3 || strcmp(argv[1], "--library")) return 2;
    uint8_t digest[32];
    assert(HashMonetLibrary(argv[2], digest));
    std::ifstream input(argv[2], std::ios::binary);
    std::vector<uint8_t> file((std::istreambuf_iterator<char>(input)), {});
    assert(file.size() > sizeof(Header));
    const auto& header = *reinterpret_cast<const Header*>(file.data());
    assert(header.phentsize == sizeof(Program) && header.shentsize == sizeof(Section));
    const auto* programs = reinterpret_cast<const Program*>(file.data() + header.phoff);
    size_t size = 0;
    for (size_t i = 0; i < header.phnum; ++i) if (programs[i].type == 1) size = std::max(size, size_t(programs[i].va + programs[i].memsz));
    assert(size < 0x8000000);
    std::vector<uint8_t> mapped(size);
    const uintptr_t base = reinterpret_cast<uintptr_t>(mapped.data());
    std::vector<MonetTintImageRange> ranges;
    for (size_t i = 0; i < header.phnum; ++i) {
        const auto& p = programs[i];
        if (p.type != 1) continue;
        assert(p.offset + p.filesz <= file.size());
        memcpy(mapped.data() + p.va, file.data() + p.offset, p.filesz);
        if (p.flags & 4) ranges.push_back({base + p.va, size_t(p.memsz), bool(p.flags & 1)});
    }
    const auto* sections = reinterpret_cast<const Section*>(file.data() + header.shoff);
    uintptr_t snapshot = 0, build_id = 0;
    for (size_t i = 0; i < header.shnum; ++i) {
        if (sections[i].type != 11) continue;
        const auto& s = sections[i];
        const auto* symbols = reinterpret_cast<const Symbol*>(file.data() + s.offset);
        const char* names = reinterpret_cast<const char*>(file.data() + sections[s.link].offset);
        for (size_t j = 0; j < s.size / sizeof(Symbol); ++j) {
            if (!strcmp(names + symbols[j].name, "_kDartIsolateSnapshotInstructions")) snapshot = base + symbols[j].value;
            if (!strcmp(names + symbols[j].name, "_kDartSnapshotBuildId")) build_id = base + symbols[j].value;
        }
    }
    MonetTintImageView image{base, snapshot, build_id, ranges.data(), ranges.size(), digest};
    MonetTintProfile profile{};
    const char* reason = nullptr;
    if (!ResolveMonetTintProfile(image, &profile, &reason)) {
        fprintf(stderr, "Resolver rejected: %s\n", reason); return 1;
    }
    const MonetTintProfile good = profile;
    PublishMonetTintCallers(good);
    assert(kh_monet_filter_pool_offset == good.filter_pool_offset && kh_monet_controller_cid == good.controller_cid);
    unsigned rejects = 0;
    auto reject = [&](const MonetTintImageView& candidate) {
        memset(&profile, 0x5a, sizeof(profile));
        assert(!ResolveMonetTintProfile(candidate, &profile, &reason));
        const MonetTintProfile empty{};
        assert(!memcmp(&profile, &empty, sizeof(empty)) && reason);
        assert(kh_tint_caller == good.tint_caller && kh_monet_filter_pool_offset == good.filter_pool_offset);
        ++rejects;
    };
    auto mutate = [&](uintptr_t address, uint32_t mask) {
        auto* word = reinterpret_cast<uint32_t*>(address); *word ^= mask; reject(image); *word ^= mask;
    };
    // Changes to actual entry ABI, callers, control flow and unrelated BL targets
    // cannot be admitted merely because a scan found the same four-word prefix.
    mutate(reinterpret_cast<uintptr_t>(good.tint_entry) + 4, 0x40);
    mutate(reinterpret_cast<uintptr_t>(good.filter_equals_entry) + 4, 0x40);
    mutate(reinterpret_cast<uintptr_t>(good.enabled_equals_entry) + 4, 0x40);
    mutate(good.tint_caller - 4, 1);
    mutate(good.render_equals_caller - 4, 1);
    mutate(good.layer_equals_caller - 4, 1);
    mutate(good.settings_setter_caller - 4, 1);
    mutate(good.enabled_equals_caller - 4, 1);
    auto bad = image; bad.verified_file_sha256 = nullptr; reject(bad);
    bad = image; bad.readable_ranges = nullptr; reject(bad);
    bad = image; bad.readable_range_count = 0; reject(bad);
    bad = image; bad.readable_range_count = 33; reject(bad);
    bad = image; ++bad.snapshot_instructions; reject(bad);
    bad = image; ++bad.snapshot_build_id; reject(bad);
    bad = image; bad.load_bias = 1; reject(bad);
    bad = image; bad.load_bias = UINTPTR_MAX - 16; reject(bad);
    for (auto& range : ranges) {
        const auto saved = range;
        if (range.executable) { range.executable = false; reject(image); range = saved; }
    }
    // Build IDs and whole-file digests do not act as a version allowlist.
    *reinterpret_cast<uint8_t*>(build_id + 16) ^= 1;
    digest[0] ^= 1;
    assert(ResolveMonetTintProfile(image, &profile, &reason));
    *reinterpret_cast<uint8_t*>(build_id + 16) ^= 1;
    digest[0] ^= 1;
    assert(ResolveMonetTintProfile(image, &profile, &reason));
    printf("{\"resolver_rejections\":%u,\"dynamic_image_accepted\":true,\"outputs_cleared\":true,"
        "\"globals_preserved\":true,\"tint_rva\":%zu,\"filter_pool\":%zu,\"controller_slot\":%zu,"
        "\"controller_cid\":%u,\"rx_int_cid\":%u,\"rx_bool_cid\":%u}\n",
        rejects, size_t(reinterpret_cast<uintptr_t>(profile.tint_entry) - base),
        size_t(profile.filter_pool_offset), size_t(profile.controller_field_offset),
        profile.controller_cid, profile.rx_int_cid, profile.rx_bool_cid);
}

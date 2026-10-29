// SPDX-License-Identifier: Apache-2.0
// Authored host harness; see README.md for evidence provenance.
#include "monet_tint_profile.h"
#include "monet_image_identity.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include <fstream>
#include <iterator>
#include <vector>

#ifdef NDEBUG
#error "Resolver test assertions must remain enabled"
#endif

int main(int argc, char** argv) {
    if (argc != 3 || strcmp(argv[1], "--library") != 0) {
        fprintf(stderr, "Usage: %s --library /path/to/external/libapp.so\n", argv[0]);
        return 2;
    }
    const char* path = argv[2];
    uint8_t digest[32];
    if (!HashMonetLibrary(path, digest) ||
        memcmp(digest, kMonetTintImageSha256, 32) != 0) {
        fprintf(stderr, "Resolver needs the external libapp.so matching the current monet_tint_profile SHA-256.\n");
        return 2;
    }
    std::ifstream f(path, std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(f)), {});
    if (!f || bytes.size() != 29382056) {
        fprintf(stderr, "Could not read the complete external libapp.so.\n");
        return 2;
    }
    uint8_t buffered_digest[32];
    MonetSha256(bytes.data(), bytes.size(), buffered_digest);
    assert(memcmp(buffered_digest, digest, 32) == 0);
    // Exact-ELF PT_LOAD metadata, not embedded code/data. File offsets equal
    // these RVAs in this pinned image; gaps are deliberately not readable.
    uintptr_t base = reinterpret_cast<uintptr_t>(bytes.data());
    MonetTintImageRange ranges[] = {
        {base, 0x84c412, false}, {base + 0x850000, 0x130cd50, true},
        {base + 0x1b60000, 0x90, false},
    };
    MonetTintImageView image{base, base + 0x866940, base + 0x1c8,
                             ranges, 3, digest};
    MonetTintProfile profile{};
    const char* error = nullptr;
    assert(ResolveMonetTintProfile(image, &profile, &error));
    assert(error == nullptr);
    assert(profile.tint_entry == reinterpret_cast<void*>(base + 0x8f3178));
    assert(profile.filter_equals_entry == reinterpret_cast<void*>(base + 0x174ac8c));
    assert(profile.tint_caller == base + 0x8f314c);
    assert(profile.render_equals_caller == base + 0x152a504);
    assert(profile.layer_equals_caller == base + 0xfb6690);
    PublishMonetTintCallers(profile);
    assert(kh_layer_equals_caller == profile.layer_equals_caller);
    const auto saved_callers = profile;
    unsigned rejects = 0;
    auto reject = [&](const MonetTintImageView& bad) {
        memset(&profile, 0x5a, sizeof(profile));
        assert(!ResolveMonetTintProfile(bad, &profile, &error));
        const MonetTintProfile zero{};
        assert(memcmp(&profile, &zero, sizeof(profile)) == 0);
        assert(error != nullptr);
        assert(kh_tint_caller == saved_callers.tint_caller);
        assert(kh_render_equals_caller == saved_callers.render_equals_caller);
        assert(kh_layer_equals_caller == saved_callers.layer_equals_caller);
        ++rejects;
    };
    const size_t starts[] = {
        0x8f3078, 0x8f3178, 0x174ac8c, 0x152a4dc, 0xfb6668,
        0x116c438, 0x116c844, 0x1215b50, 0xebf4a0, 0xe931ec,
        0x1b00400, 0x16348a8, 0x8b1928, 0x18b8cec, 0x17d148c, 0x1b58510,
    };
    for (size_t start : starts) {
        bytes[start + 4] ^= 0x40; reject(image); bytes[start + 4] ^= 0x40;
    }
    for (size_t at : {size_t{0x8f3148}, size_t{0x152a500}, size_t{0xfb668c},
                      size_t{0x17d14c8}, size_t{0x116c8b8}, size_t{0x174adb8}}) {
        bytes[at] ^= 1; reject(image); bytes[at] ^= 1;
    }
    bytes[0x1d8] ^= 1; reject(image); bytes[0x1d8] ^= 1;
    digest[1] ^= 1; reject(image); digest[1] ^= 1;
    auto bad=image; bad.verified_file_sha256=nullptr; reject(bad);
    bad=image; bad.readable_ranges=nullptr; reject(bad);
    bad=image; bad.readable_range_count=0; reject(bad);
    bad=image; bad.readable_range_count=33; reject(bad);
    bad=image; bad.snapshot_instructions++; reject(bad);
    bad=image; bad.snapshot_build_id++; reject(bad);
    ranges[1].executable=false; reject(image); ranges[1].executable=true;
    ranges[0].size=0x1c8; reject(image); ranges[0].size=0x84c412;
    ranges[1].size=0x8f3178-0x850000; reject(image); ranges[1].size=0x130cd50;
    ranges[2].size=UINTPTR_MAX; reject(image); ranges[2].size=0x90;
    // The intentionally invalid image addresses must be rejected before any
    // memory access. No fake contiguous-size claim is supplied for the holes.
    bad=image; bad.load_bias=1; bad.snapshot_instructions=0x866941;
    bad.snapshot_build_id=0x1c9; reject(bad);
    bad=image; bad.load_bias=UINTPTR_MAX-16; reject(bad);
    assert(!ResolveMonetTintProfile(image, nullptr, &error));
    assert(ResolveMonetTintProfile(image, &profile, &error));
    assert(rejects == 36);
    printf("resolver: exact backing-file hash and target accepted; %u invalid inputs rejected; callers decoded; outputs cleared; globals preserved\n", rejects);
    printf("{\"resolver_rejections\":%u,\"exact_image_accepted\":true,"
           "\"outputs_cleared\":true,\"globals_preserved\":true}\n", rejects);
}

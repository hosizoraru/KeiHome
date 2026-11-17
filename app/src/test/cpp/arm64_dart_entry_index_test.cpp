// SPDX-License-Identifier: Apache-2.0
#include "arm64_dart_entry_index.h"
#include <assert.h>
#include <stdio.h>
#include <vector>

struct PatternWord { uint32_t word, mask; };
constexpr PatternWord kEntry[]{{0xa9bf79fd, UINT32_MAX}, {0xaa0f03fd, UINT32_MAX}};

int main() {
    std::vector<uint32_t> code(40, 0);
    auto entry = [&](size_t at) { code[at / 4] = kEntry[0].word; code[at / 4 + 1] = kEntry[1].word; };
    entry(8); entry(40); entry(112);
    code[92 / 4] = kEntry[0].word;
    const IconHighlightRange ranges[]{{4, 96, true}, {112, 128, false}, {136, 140, true}};
    IconHighlightImage image{reinterpret_cast<const uint8_t*>(code.data()), code.size() * 4, ranges, 3};
    auto matches = [&](const IndexedArm64Image& index, bool indexed) {
        std::vector<size_t> result;
        index.VisitCandidates(indexed, [&](size_t at, uint32_t first) {
            assert(first == code[at / 4]);
            for (const auto& range : ranges) {
                if (!range.executable || at < range.begin || at > range.end ||
                    range.end - at < 2 * sizeof(uint32_t)) continue;
                if (code[at / 4] == kEntry[0].word && code[at / 4 + 1] == kEntry[1].word)
                    result.push_back(at);
            }
            return true;
        });
        return result;
    };
    const IndexedArm64Image index(image, 8);
    assert(index.Covers(kEntry, 2));
    assert(matches(index, true) == std::vector<size_t>({8, 40}));
    assert(matches(index, false) == matches(index, true));

    PatternWord masked[]{{kEntry[0].word, 0xffff0000}, kEntry[1]};
    assert(!index.Covers(masked, 2));
    assert(!index.Covers(kEntry, 1));
    assert(!index.Covers(static_cast<const PatternWord*>(nullptr), 2));
    masked[0] = {0xd503201f, UINT32_MAX};
    assert(!index.Covers(masked, 2));

    size_t visited = 0;
    index.VisitCandidates(true, [&](size_t, uint32_t) { ++visited; return false; });
    assert(visited == 1);

    for (size_t capacity : {size_t{0}, size_t{1}, IndexedArm64Image::kMaxEntries + 1}) {
        const IndexedArm64Image fallback(image, capacity);
        assert(!fallback.Covers(kEntry, 2));
        assert(matches(fallback, false) == matches(index, true));
        assert(matches(fallback, true) == matches(index, true));
    }

    // A new invocation indexes newly inserted entries; no cache crosses phases.
    entry(64);
    const IndexedArm64Image revalidated(image, 8);
    assert(matches(revalidated, true) == std::vector<size_t>({8, 40, 64}));

    const IconHighlightRange invalid[]{{80, 4, true}, {0, 1024, true}};
    image.ranges = invalid; image.range_count = 2;
    const IndexedArm64Image rejected(image, 8);
    visited = 0;
    rejected.VisitCandidates(false, [&](size_t, uint32_t) { ++visited; return true; });
    assert(visited == 0);
    image.size = size_t{UINT32_MAX} + 1;
    const IndexedArm64Image oversized(image, 8);
    oversized.VisitCandidates(false, [&](size_t, uint32_t) { ++visited; return true; });
    assert(visited == 0);

    puts("entry index: bounded candidates, fallback, masks, ranges and fresh phase passed");
}

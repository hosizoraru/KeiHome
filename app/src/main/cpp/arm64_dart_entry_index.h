// SPDX-License-Identifier: Apache-2.0
#pragma once

#include "icon_highlight_profile.h"
#include <stdlib.h>
#include <string.h>

// One validated image, one resolver invocation. Never retain this across code
// patches or reuse it for final publication revalidation.
class IndexedArm64Image : public IconHighlightImage {
public:
    static constexpr size_t kMaxEntries = 65536;

    explicit IndexedArm64Image(const IconHighlightImage& image,
                               size_t capacity = kMaxEntries)
        : IconHighlightImage(image) {
        if (!base || size > UINT32_MAX || !ranges || !capacity ||
            capacity > kMaxEntries) return;
        entries_ = static_cast<uint32_t*>(malloc(capacity * sizeof(uint32_t)));
        if (!entries_) return;
        for (size_t i = 0; i < range_count; ++i) {
            const auto& range = ranges[i];
            if (range.begin > range.end || range.end > size) {
                Clear();
                return;
            }
            if (!range.executable || range.end - range.begin < 8) continue;
            for (size_t at = range.begin; at <= range.end - 8; at += 4) {
                uint32_t words[2];
                memcpy(words, base + at, sizeof(words));
                if (words[0] != kFrame || words[1] != kFramePointer) continue;
                if (count_ == capacity) {
                    Clear();
                    return;
                }
                entries_[count_++] = static_cast<uint32_t>(at);
            }
        }
    }

    ~IndexedArm64Image() { Clear(); }
    IndexedArm64Image(const IndexedArm64Image&) = delete;
    IndexedArm64Image& operator=(const IndexedArm64Image&) = delete;

    template <typename Word>
    bool Covers(const Word* pattern, size_t count) const {
        return entries_ && pattern && count >= 2 &&
            pattern[0].mask == UINT32_MAX && pattern[0].word == kFrame &&
            pattern[1].mask == UINT32_MAX && pattern[1].word == kFramePointer;
    }

    // Each visit supplies a first word read inside a proved executable extent.
    // Consumers still check the full body, but need not repeat range lookup for
    // every rejected word in a fallback scan.
    template <typename Visitor>
    void VisitCandidates(bool indexed, Visitor visit) const {
        if (indexed && entries_) {
            for (size_t i = 0; i < count_; ++i)
                if (!Visit(entries_[i], visit)) return;
            return;
        }
        // Allocation failure, index saturation and nonframed/partially masked
        // patterns retain the original complete scan and acceptance rules.
        if (!base || !ranges || size > UINT32_MAX) return;
        for (size_t i = 0; i < range_count; ++i) {
            const auto& range = ranges[i];
            if (!range.executable || range.begin > range.end ||
                range.end > size || range.end - range.begin < 4) continue;
            for (size_t at = range.begin; at <= range.end - 4; at += 4)
                if (!Visit(at, visit)) return;
        }
    }

private:
    static constexpr uint32_t kFrame = 0xa9bf79fd;
    static constexpr uint32_t kFramePointer = 0xaa0f03fd;
    uint32_t* entries_ = nullptr;
    size_t count_ = 0;

    template <typename Visitor>
    bool Visit(size_t at, Visitor& visit) const {
        uint32_t first;
        memcpy(&first, base + at, sizeof(first));
        return visit(at, first);
    }

    void Clear() {
        free(entries_);
        entries_ = nullptr;
        count_ = 0;
    }
};

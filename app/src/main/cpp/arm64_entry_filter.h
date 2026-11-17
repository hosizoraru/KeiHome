// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

// A rejection-only prefilter. Callers must still validate the complete pattern,
// readable extent, digest and call graph before accepting an instruction entry.
template <size_t Capacity>
class Arm64EntryFilter {
public:
    template <typename Pattern>
    constexpr explicit Arm64EntryFilter(const Pattern (&patterns)[Capacity]) {
        for (const auto& pattern : patterns) {
            bool known = false;
            for (size_t i = 0; i < count_; ++i)
                known |= prefixes_[i][0] == pattern.prefix[0] &&
                         prefixes_[i][1] == pattern.prefix[1];
            if (!known) {
                prefixes_[count_][0] = pattern.prefix[0];
                prefixes_[count_++][1] = pattern.prefix[1];
            }
        }
    }

    // The caller has already proved that at least eight bytes are readable.
    bool MayMatch(uintptr_t pc) const {
        uint32_t first;
        memcpy(&first, reinterpret_cast<const void*>(pc), sizeof(first));
        for (size_t i = 0; i < count_; ++i) {
            if (first != prefixes_[i][0]) continue;
            uint32_t second;
            memcpy(&second, reinterpret_cast<const void*>(pc + 4), sizeof(second));
            if (second == prefixes_[i][1]) return true;
        }
        return false;
    }

private:
    uint32_t prefixes_[Capacity][2]{};
    size_t count_ = 0;
};

template <typename Pattern, size_t Count>
constexpr auto MakeArm64EntryFilter(const Pattern (&patterns)[Count]) {
    return Arm64EntryFilter<Count>(patterns);
}

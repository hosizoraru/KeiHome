// SPDX-License-Identifier: Apache-2.0
#include "icon_highlight_profile.h"

extern "C" {
bool kh_test_resolve(const uint8_t* base, size_t size,
                     const IconHighlightRange* ranges, size_t count,
                     IconHighlightProfile* out, const char** reason) {
    return ResolveIconHighlight({base, size, ranges, count}, out, reason);
}
bool kh_test_bridge(const IconHighlightProfile* p, const uint8_t* code, size_t size,
                    uintptr_t policy, uint8_t* out) {
    return BuildIconHighlightBridge(*p, code, size, policy, out);
}
bool kh_test_detour(uintptr_t bridge, uint32_t* out) {
    return BuildIconHighlightDetour(bridge, out);
}
}

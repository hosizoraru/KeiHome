// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

struct IconHighlightRange {
    size_t begin;
    size_t end;
    bool executable;
};

// Ranges are currently readable PT_LOAD mappings of one live AArch64 ELF.
// Never supply a span over unreadable holes as a single range.
struct IconHighlightImage {
    const uint8_t* base;
    size_t size;
    const IconHighlightRange* ranges;
    size_t range_count;
};

struct IconHighlightProfile {
    uintptr_t entry;
    uintptr_t material_disabled;
    uintptr_t material_supported;
    uintptr_t theme_writer;
    uintptr_t stroke_consumer;
    uintptr_t patch;
    uintptr_t false_target;
    uint32_t theme_field;
    uint32_t device_slot;
    uint32_t support_slot;
    uint32_t style_slot;
    uint32_t os_field;
    uint32_t expected[4];
};

constexpr size_t kIconHighlightBridgeSize = 64;

// Bounded, read-only resolution. Symbols/debugdata are independent test
// oracles; runtime support is unique instruction shapes and linked data flow,
// never an image/version/RVA allowlist. All failure paths clear output.
bool ResolveIconHighlight(const IconHighlightImage& image,
                          IconHighlightProfile* out,
                          const char** rejection = nullptr);

// Builds private bridge bytes only. The supplied template must be the exact
// no-call/no-spill AArch64 template. No target patch or executable allocation.
bool BuildIconHighlightBridge(const IconHighlightProfile& profile,
                              const uint8_t* bridge_template, size_t size,
                              uintptr_t policy_address,
                              uint8_t out[kIconHighlightBridgeSize]);

// Exact patch [entry+0x5c,entry+0x6c): x0 is the native result register.
// Disabled entry+0x6c and its epilogue are outside this patch.
bool BuildIconHighlightDetour(uintptr_t bridge, uint32_t out[4]);

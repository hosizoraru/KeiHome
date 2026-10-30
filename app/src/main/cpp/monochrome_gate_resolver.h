// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

// Offsets from base, [begin, end). The caller must supply only currently
// readable executable mappings belonging to this one ARM64 Dart image.
struct MonochromeGateExecutableRange {
    uintptr_t begin;
    uintptr_t end;
};

struct MonochromeGateImageView {
    const uint8_t* base;
    size_t size;  // Mapped image span, not the sum of the executable ranges.
    const MonochromeGateExecutableRange* executable_ranges;
    size_t executable_range_count;
};

struct MonochromeGatePatch {
    void* address;
    uint32_t expected_instruction;
    uint32_t replacement_instruction;
};

// Setup-time, read-only, bounded structural resolution. No symbols, build IDs,
// file hashes, Dart object reads/calls, global state or retained Dart references.
// Exactly one proven candidate is required. Failure clears *out and returns a
// static diagnostic in rejection; it disables only this optional extension.
// The caller owns image lifetime and synchronization with patch installation,
// and must compare expected_instruction again when applying the patch.
bool ResolveMonochromeGate(const MonochromeGateImageView& image,
                           MonochromeGatePatch* out,
                           const char** rejection = nullptr);

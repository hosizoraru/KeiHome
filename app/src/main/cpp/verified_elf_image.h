// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "icon_highlight_profile.h"
#include "monet_tint_profile.h"
#include "selection_profile.h"

struct VerifiedDartImage {
    uintptr_t base;
    uintptr_t snapshot_instructions;
    uintptr_t snapshot_build_id;
    size_t span;
    size_t count;
    IconHighlightRange relative_ranges[16];
    MonetTintImageRange absolute_tint_ranges[16];
    MonetSelectionImageRange absolute_selection_ranges[16];
    uint8_t sha256[32];
    bool has_sha256;
    bool valid;

    IconHighlightImage HighlightView() const {
        return {reinterpret_cast<const uint8_t*>(base), span, relative_ranges, count};
    }
};

// Single-pass verified snapshot of a loaded Dart AOT image: one dl_iterate_phdr,
// one /proc/self/maps verification (with full-line drain), and at most one
// HashMonetLibrary pass.
bool BuildVerifiedDartImage(void* handle, VerifiedDartImage* out,
                            bool compute_sha256 = true);

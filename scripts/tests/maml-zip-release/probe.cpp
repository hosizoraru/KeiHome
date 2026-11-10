// SPDX-License-Identifier: Apache-2.0
// Test adapter for negative external-library admission checks.
#include "maml_zip_release_profile.h"
extern "C" bool kh_test_zip(const uint8_t* base, size_t size,
                           const IconHighlightRange* ranges, size_t count,
                           uintptr_t* address, const char** why) {
    return ResolveMamlZipRelease({base, size, ranges, count}, address, why);
}

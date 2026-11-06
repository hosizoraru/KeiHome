// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

using ZipMetadataRelease = void (*)(void*);

// Call from a specialized launcher's existing background worker, never its
// spawner or UI. Until ready, TryDefer retains synchronous caller ownership.
bool StartDeferredZipRelease();

// Transfers an already final-owned ZIP metadata allocation, never a MAML view
// or a reference to the caller's stack. False leaves ownership with the caller.
bool TryDeferZipMetadata(void* allocation, ZipMetadataRelease release);

struct DeferredZipReleaseStats {
    uint64_t queued;
    uint64_t completed;
    uint64_t fallback;
    size_t pending;
};
DeferredZipReleaseStats ReadDeferredZipReleaseStats();

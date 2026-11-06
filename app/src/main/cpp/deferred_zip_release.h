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
// One reservation attempt, without blocking, retrying or allocating. At most
// 64 pending releases plus one active worker callback; worker recursion is sync.
bool TryDeferZipMetadata(void* allocation, ZipMetadataRelease release);

struct DeferredZipReleaseStats {
    uint64_t queued;
    uint64_t completed;
    uint64_t fallback;
    size_t pending;
    uint64_t fallback_capacity;
    uint64_t fallback_reservation_contention;
    uint64_t fallback_unavailable;
    uint64_t fallback_reentrant;
    uint64_t peak;
};
// Concurrent counters are observational, not a transactional snapshot. Once
// producers stop, pending == 0 && completed == queued also excludes active work.
DeferredZipReleaseStats ReadDeferredZipReleaseStats();

#ifndef __ANDROID__
// Host-only deterministic scheduling/failure injection. Hook objects must live
// for the process lifetime; callbacks may block only in the host test.
struct DeferredZipReleaseTestHooks {
    void (*before_reserve)(size_t) = nullptr;
    void (*after_reserve)(size_t) = nullptr;
    void (*before_wait)(size_t credits) = nullptr;
    size_t initial_position = 0;
    bool fail_semaphore_init = false;
    bool fail_worker_create = false;
};
void SetDeferredZipReleaseTestHooks(const DeferredZipReleaseTestHooks* hooks);
#endif

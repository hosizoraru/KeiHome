// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

using ZipMetadataRelease = void (*)(void*);

// Private root-reader diagnostic gate; use atomic loads/stores in process.
// Zero requests fixed nice 10 on the next active iteration, unless a previous
// priority failure has permanently disabled transitions. Never wakes an idle worker.
// Boosts count successful nice-0 transitions; failures count failed change/readback attempts.
extern "C" {
extern uint32_t kh_zip_release_adaptive_priority_enabled;
extern uint64_t kh_zip_release_priority_boosts;
extern uint64_t kh_zip_release_priority_failures;
extern int32_t kh_zip_release_requested_nice;
extern int32_t kh_zip_release_worker_nice; // INT32_MIN means unknown.
}

// Call from a specialized launcher's existing background worker, never its
// spawner or UI. Until ready, TryDefer retains synchronous caller ownership.
// Startup waits for the initial nice check; unknown/negative failure is unavailable.
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
    uint64_t priority_boosts;
    uint64_t priority_failures;
    int32_t requested_nice;
    int32_t observed_nice;
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
    // Captured once by the worker. Returns success and the observed nice even
    // on failure, or INT32_MIN if unknown. Never called on a producer.
    bool (*set_priority)(int requested, int* observed) = nullptr;
};
void SetDeferredZipReleaseTestHooks(const DeferredZipReleaseTestHooks* hooks);
#endif

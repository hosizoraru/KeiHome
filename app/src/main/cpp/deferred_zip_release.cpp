// SPDX-License-Identifier: Apache-2.0
#include "deferred_zip_release.h"

#include <atomic>
#include <errno.h>
#include <pthread.h>
#include <semaphore.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <unistd.h>
#ifdef __APPLE__
#include <fcntl.h>
#include <stdio.h>
#endif
#if defined(__linux__) || defined(__ANDROID__)
#include <sys/syscall.h>
#endif
#ifdef __ANDROID__
#include <android/trace.h>
#include <android/log.h>
#endif

extern "C" {
uint64_t kh_zip_release_queued = 0;
uint64_t kh_zip_release_completed = 0;
uint64_t kh_zip_release_fallback = 0;
uint64_t kh_zip_release_peak = 0;
uint64_t kh_zip_release_fallback_capacity = 0;
uint64_t kh_zip_release_fallback_reservation_contention = 0;
uint64_t kh_zip_release_fallback_unavailable = 0;
uint64_t kh_zip_release_fallback_reentrant = 0;
uint32_t kh_zip_release_adaptive_priority_enabled = 1;
uint64_t kh_zip_release_priority_boosts = 0;
uint64_t kh_zip_release_priority_failures = 0;
int32_t kh_zip_release_requested_nice = 10;
int32_t kh_zip_release_worker_nice = INT32_MIN;
}

namespace {
constexpr size_t kCapacity = 64;
static_assert(std::atomic<size_t>::is_always_lock_free);
static_assert(__atomic_always_lock_free(sizeof(uint64_t), nullptr));
struct Item { void* allocation; ZipMetadataRelease release; };
struct Slot {
    std::atomic<size_t> sequence{0};
    Item item{};
};
Slot slots[kCapacity];
std::atomic<size_t> tail{0};
std::atomic<size_t> pending{0};
size_t head = 0; // Only the consumer accesses head after startup.
std::atomic<int> started{0};
pthread_t worker{};
pthread_mutex_t startup_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t startup_condition = PTHREAD_COND_INITIALIZER;
bool worker_priority_ready = false; // Protected by startup_mutex.
bool worker_priority_safe = false;
#ifndef __APPLE__
sem_t semaphore_storage;
#endif
sem_t* notifications = nullptr;

#ifndef __ANDROID__
std::atomic<const DeferredZipReleaseTestHooks*> test_hooks{nullptr};
#endif

struct WorkerPriority {
    int current = 10;
    bool disabled = false;
#if defined(__linux__) || defined(__ANDROID__)
    id_t tid = static_cast<id_t>(syscall(SYS_gettid));
#endif
#ifndef __ANDROID__
    bool (*adapter)(int, int*) = nullptr;
    WorkerPriority() {
        const auto* hooks = test_hooks.load(std::memory_order_acquire);
        if (hooks) adapter = hooks->set_priority;
    }
#endif

    bool Set(int requested) {
        __atomic_store_n(&kh_zip_release_requested_nice, requested, __ATOMIC_RELAXED);
        int observed = INT32_MIN;
        bool success;
#ifndef __ANDROID__
        if (adapter) {
            success = adapter(requested, &observed);
        } else
#endif
        {
#if defined(__linux__) || defined(__ANDROID__)
            const bool set = setpriority(PRIO_PROCESS, tid, requested) == 0;
            errno = 0; // A legitimate getpriority result can itself be -1.
            const int actual = getpriority(PRIO_PROCESS, tid);
            if (errno == 0) observed = actual;
            success = set && observed == requested;
#else
            // Host model only: never change the macOS process's nice value.
            observed = requested;
            success = true;
#endif
        }
        __atomic_store_n(&kh_zip_release_worker_nice, observed, __ATOMIC_RELAXED);
        if (!success || observed != requested) {
            disabled = true;
            __atomic_add_fetch(&kh_zip_release_priority_failures, uint64_t{1}, __ATOMIC_RELAXED);
            return false;
        }
        current = requested;
        if (requested == 0)
            __atomic_add_fetch(&kh_zip_release_priority_boosts, uint64_t{1}, __ATOMIC_RELAXED);
        return true;
    }

    bool Initialize() {
        if (Set(10)) return true;
        const int observed = __atomic_load_n(&kh_zip_release_worker_nice, __ATOMIC_RELAXED);
        // Do not accept cleanup on an inherited negative or unknown priority
        // after an initial failure. A known nonnegative fallback remains usable.
        return observed >= 0;
    }

    void Update(size_t backlog) {
        if (disabled) return;
        const bool adaptive = __atomic_load_n(
                &kh_zip_release_adaptive_priority_enabled, __ATOMIC_RELAXED) != 0;
        const int requested = !adaptive || backlog <= 8 ? 10
                : backlog >= 32 ? 0 : current;
        if (requested != current) Set(requested);
    }
};

bool Fallback(uint64_t* reason) {
    __atomic_add_fetch(reason, uint64_t{1}, __ATOMIC_RELAXED);
    __atomic_add_fetch(&kh_zip_release_fallback, uint64_t{1}, __ATOMIC_RELAXED);
    return false;
}

bool InitSemaphore() {
#ifndef __ANDROID__
    const auto* hooks = test_hooks.load(std::memory_order_acquire);
    if (hooks && hooks->fail_semaphore_init) return false;
#endif
#ifdef __APPLE__
    // macOS does not implement unnamed POSIX semaphores. Initialization is
    // background-only; unlink immediately while retaining the worker's handle.
    char name[32];
    snprintf(name, sizeof(name), "/kh-zip-%ld", static_cast<long>(getpid()));
    notifications = sem_open(name, O_CREAT | O_EXCL, 0600, 0);
    if (notifications == SEM_FAILED) return false;
    if (sem_unlink(name) == 0) return true;
    sem_close(notifications);
    return false;
#else
    if (sem_init(&semaphore_storage, 0, 0) != 0) return false;
    notifications = &semaphore_storage;
    return true;
#endif
}

void DestroySemaphore() {
#ifdef __APPLE__
    sem_close(notifications);
#else
    sem_destroy(notifications);
#endif
    notifications = nullptr;
}

void* Run(void*) {
#ifdef __APPLE__
    pthread_setname_np("KeiZipRelease");
#else
    pthread_setname_np(pthread_self(), "KeiZipRelease");
#endif
    WorkerPriority priority;
    const bool priority_safe = priority.Initialize();
    pthread_mutex_lock(&startup_mutex);
    worker_priority_safe = priority_safe;
    worker_priority_ready = true;
    pthread_cond_broadcast(&startup_condition);
    pthread_mutex_unlock(&startup_mutex);
    if (!priority_safe) return nullptr;
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "KeiHomeMamlRelease",
                       "ZIP metadata worker ready");
#endif
    size_t credits = 0;
    for (;;) {
        Slot& slot = slots[head % kCapacity];
        // Notifications can arrive out of reservation order. Keep each credit
        // until a FIFO dequeue consumes it. If head is unpublished, sleep for
        // another publication even if later slots have supplied credits.
        if (credits == 0 ||
                slot.sequence.load(std::memory_order_acquire) != head + 1) {
#ifndef __ANDROID__
            const auto* hooks = test_hooks.load(std::memory_order_acquire);
            if (hooks && hooks->before_wait) hooks->before_wait(credits);
#endif
            while (sem_wait(notifications) != 0) {
                if (errno != EINTR) abort();
            }
            ++credits;
            continue;
        }
        const Item item = slot.item;
        slot.item = {};
        const size_t backlog = pending.fetch_sub(1, std::memory_order_relaxed) - 1;
        // Free the slot before invoking the callback: exactly 64 pending plus
        // one active release. Acquire/release protects the non-atomic Item.
        slot.sequence.store(head + kCapacity, std::memory_order_release);
        ++head;
        --credits;
        priority.Update(backlog);
#ifdef __ANDROID__
        ATrace_beginSection("KeiHome:zip-metadata-release");
#endif
        item.release(item.allocation);
#ifdef __ANDROID__
        ATrace_endSection();
#endif
        __atomic_add_fetch(&kh_zip_release_completed, uint64_t{1}, __ATOMIC_RELEASE);
    }
}
}

bool StartDeferredZipRelease() {
    const int state = started.load(std::memory_order_acquire);
    if (state == 1 || state == -1) return state == 1;
    pthread_mutex_lock(&startup_mutex);
    while (started.load(std::memory_order_relaxed) == 2)
        pthread_cond_wait(&startup_condition, &startup_mutex);
    if (started.load(std::memory_order_relaxed) == 0) {
        started.store(2, std::memory_order_relaxed);
        size_t initial = 0;
#ifndef __ANDROID__
        const auto* hooks = test_hooks.load(std::memory_order_acquire);
        if (hooks) initial = hooks->initial_position;
#endif
        head = initial;
        tail.store(initial, std::memory_order_relaxed);
        for (size_t i = 0; i < kCapacity; ++i) {
            const size_t position = initial + i;
            slots[position % kCapacity].sequence.store(position, std::memory_order_relaxed);
        }
        bool success = false;
        if (InitSemaphore()) {
            pthread_attr_t attributes;
            if (pthread_attr_init(&attributes) == 0) {
                if (pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED) == 0) {
                    bool create_worker = true;
#ifndef __ANDROID__
                    if (hooks && hooks->fail_worker_create) create_worker = false;
#endif
                    success = create_worker &&
                            pthread_create(&worker, &attributes, Run, nullptr) == 0;
                    if (success) {
                        while (!worker_priority_ready)
                            pthread_cond_wait(&startup_condition, &startup_mutex);
                        success = worker_priority_safe;
                    }
                }
                pthread_attr_destroy(&attributes);
            }
            if (!success) DestroySemaphore();
        }
        started.store(success ? 1 : -1, std::memory_order_release);
        pthread_cond_broadcast(&startup_condition);
    }
    const bool success = started.load(std::memory_order_relaxed) == 1;
    pthread_mutex_unlock(&startup_mutex);
    return success;
}

bool TryDeferZipMetadata(void* allocation, ZipMetadataRelease release) {
    if (!allocation || !release ||
            started.load(std::memory_order_acquire) != 1) {
        return Fallback(&kh_zip_release_fallback_unavailable);
    }
    if (pthread_equal(worker, pthread_self()))
        return Fallback(&kh_zip_release_fallback_reentrant);
    size_t position = tail.load(std::memory_order_relaxed);
    Slot& slot = slots[position % kCapacity];
    const size_t sequence = slot.sequence.load(std::memory_order_acquire);
    if (sequence != position) {
        // Unsigned modular distance also handles size_t ticket wraparound.
        const bool older = position - sequence <= SIZE_MAX / 2;
        return Fallback(older ? &kh_zip_release_fallback_capacity
                              : &kh_zip_release_fallback_reservation_contention);
    }
#ifndef __ANDROID__
    const auto* hooks = test_hooks.load(std::memory_order_acquire);
    if (hooks && hooks->before_reserve) hooks->before_reserve(position);
#endif
    if (!tail.compare_exchange_strong(position, position + 1,
                                     std::memory_order_relaxed,
                                     std::memory_order_relaxed)) {
        return Fallback(&kh_zip_release_fallback_reservation_contention);
    }
    const size_t depth = pending.fetch_add(1, std::memory_order_relaxed) + 1;
    __atomic_add_fetch(&kh_zip_release_queued, uint64_t{1}, __ATOMIC_RELAXED);
    // Clang's atomic max avoids a producer-side compare/exchange retry loop.
    __atomic_fetch_max(&kh_zip_release_peak, static_cast<uint64_t>(depth), __ATOMIC_RELAXED);
#ifndef __ANDROID__
    if (hooks && hooks->after_reserve) hooks->after_reserve(position);
#endif
    slot.item = {allocation, release};
    slot.sequence.store(position + 1, std::memory_order_release);
    // A published item cannot be returned to caller ownership. With at most
    // 64 unconsumed notifications EOVERFLOW is impossible; other runtime
    // semaphore errors indicate a broken process invariant, so fail closed.
    if (sem_post(notifications) != 0) abort();
    return true;
}

DeferredZipReleaseStats ReadDeferredZipReleaseStats() {
    return {__atomic_load_n(&kh_zip_release_queued, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_completed, __ATOMIC_ACQUIRE),
            __atomic_load_n(&kh_zip_release_fallback, __ATOMIC_RELAXED),
            pending.load(std::memory_order_relaxed),
            __atomic_load_n(&kh_zip_release_fallback_capacity, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_fallback_reservation_contention, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_fallback_unavailable, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_fallback_reentrant, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_peak, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_priority_boosts, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_priority_failures, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_requested_nice, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_worker_nice, __ATOMIC_RELAXED)};
}

#ifndef __ANDROID__
void SetDeferredZipReleaseTestHooks(const DeferredZipReleaseTestHooks* hooks) {
    test_hooks.store(hooks, std::memory_order_release);
}
#endif

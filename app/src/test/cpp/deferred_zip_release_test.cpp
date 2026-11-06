// SPDX-License-Identifier: Apache-2.0
#include "deferred_zip_release.h"
#include <atomic>
#include <assert.h>
#include <chrono>
#include <stdio.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
constexpr size_t kCapacity = 64;
constexpr size_t kWrapBase = 67;
constexpr size_t kWrapCount = 1024;
constexpr size_t kPublishBase = kWrapBase + kWrapCount;
constexpr size_t kContentionBase = kPublishBase + 2;
constexpr size_t kStressBase = kContentionBase + 2;
constexpr size_t kProducers = 8;
constexpr size_t kPerProducer = 1000;
constexpr size_t kTotal = kStressBase + kProducers * kPerProducer;
std::atomic<unsigned> seen[kTotal]{};
std::atomic<size_t> freed{0};
std::atomic<bool> entered{false}, unblock{false};
std::atomic<bool> head_reserved{false}, publish_head{false}, waiting_for_head{false};
std::atomic<unsigned> reservation_arrivals{0};
std::atomic<bool> reserve_now{false};
std::atomic<size_t> fifo_size{0};
std::atomic<size_t> fifo[kWrapCount + 2]{};
std::atomic<int> stress_last[kProducers];
std::atomic<size_t> stress_queued{0};

using Clock = std::chrono::steady_clock;
template <typename Predicate>
void Await(Predicate predicate) {
    const auto until = Clock::now() + std::chrono::seconds(10);
    while (!predicate()) {
        assert(Clock::now() < until);
        std::this_thread::yield();
    }
}
void AwaitDrained() {
    Await([] {
        const auto state = ReadDeferredZipReleaseStats();
        return state.pending == 0 && state.completed == state.queued;
    });
}
void CheckReasons(const DeferredZipReleaseStats& state) {
    assert(state.fallback == state.fallback_capacity +
           state.fallback_reservation_contention + state.fallback_unavailable +
           state.fallback_reentrant);
    assert(state.pending <= kCapacity);
    assert(state.peak <= kCapacity);
}

struct Allocation {
    size_t id;
    ~Allocation() {
        assert(id < kTotal);
        assert(seen[id].fetch_add(1) == 0);
        freed.fetch_add(1);
    }
};
void Release(void* raw) {
    auto* allocation = static_cast<Allocation*>(raw);
    if (allocation->id == 0) {
        entered = true;
        Await([] { return unblock.load(); });
        auto* child = new Allocation{66};
        // This remains reentrant even with all 64 pending slots occupied.
        assert(!TryDeferZipMetadata(child, Release));
        Release(child);
    }
    if (allocation->id >= kWrapBase && allocation->id < kContentionBase) {
        const size_t index = fifo_size.fetch_add(1);
        assert(index < kWrapCount + 2);
        fifo[index] = allocation->id;
    }
    delete allocation;
}
void StressRelease(void* raw) {
    const auto* allocation = static_cast<Allocation*>(raw);
    const size_t offset = allocation->id - kStressBase;
    const size_t producer = offset / kPerProducer;
    const int ordinal = static_cast<int>(offset % kPerProducer);
    // Only accepted callbacks use this function; sync fallbacks use Release.
    assert(ordinal > stress_last[producer].load());
    stress_last[producer] = ordinal;
    stress_queued.fetch_add(1);
    Release(raw);
}

void PauseHead(size_t) {
    if (!head_reserved.exchange(true)) {
        Await([] { return publish_head.load(); });
    }
}
void ObserveWait(size_t credits) {
    if (credits == 1) waiting_for_head = true;
}
void RaceReservation(size_t) {
    reservation_arrivals.fetch_add(1);
    Await([] { return reserve_now.load(); });
}
const DeferredZipReleaseTestHooks kPublicationHooks{
    nullptr, PauseHead, ObserveWait};
const DeferredZipReleaseTestHooks kContentionHooks{RaceReservation};
// Cross the size_t boundary during the initial saturation test, then reuse the
// ring many more times. Ticket arithmetic must work independently of slot zero.
const DeferredZipReleaseTestHooks kWrapHooks{
    nullptr, nullptr, nullptr, SIZE_MAX - 31};
const DeferredZipReleaseTestHooks kSemaphoreFailure{
    nullptr, nullptr, nullptr, 0, true, false};
const DeferredZipReleaseTestHooks kWorkerFailure{
    nullptr, nullptr, nullptr, 0, false, true};

void CheckInitializationFailure(const DeferredZipReleaseTestHooks& hooks) {
    // Fork before starting any threads so each failure gets a fresh queue.
    const pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        SetDeferredZipReleaseTestHooks(&hooks);
        for (size_t i = 0; i < 2; ++i) {
            assert(!StartDeferredZipRelease());
            auto* allocation = new Allocation{kTotal - 1 - i};
            assert(!TryDeferZipMetadata(allocation, Release));
            Release(allocation);
        }
        const auto state = ReadDeferredZipReleaseStats();
        assert(state.queued == 0 && state.completed == 0 && state.pending == 0);
        assert(state.fallback == 2 && state.fallback_unavailable == 2);
        assert(freed == 2);
        CheckReasons(state);
        _exit(0);
    }
    int status = 0;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

void CheckSaturation() {
    assert(TryDeferZipMetadata(new Allocation{0}, Release));
    Await([] { return entered.load(); });
    const auto active = ReadDeferredZipReleaseStats();
    assert(active.queued == 1 && active.pending == 0 && active.completed == 0);
    for (size_t id = 1; id <= kCapacity; ++id)
        assert(TryDeferZipMetadata(new Allocation{id}, Release));
    auto* full = new Allocation{65};
    assert(!TryDeferZipMetadata(full, Release));
    Release(full);
    const auto saturated = ReadDeferredZipReleaseStats();
    assert(saturated.pending == kCapacity && saturated.peak == kCapacity);
    assert(saturated.queued == kCapacity + 1 && saturated.completed == 0);
    assert(saturated.fallback_capacity == 1);
    assert(saturated.fallback_reservation_contention == 0);
    CheckReasons(saturated);
    unblock = true;
    AwaitDrained();
    const auto drained = ReadDeferredZipReleaseStats();
    assert(drained.queued == 65 && drained.completed == 65);
    assert(drained.fallback_reentrant == 1);
    assert(drained.fallback_capacity == 1);
    assert(freed == kWrapBase);
}

void CheckWraparound() {
    const auto before = ReadDeferredZipReleaseStats();
    for (size_t batch = 0; batch < kWrapCount; batch += kCapacity) {
        for (size_t i = 0; i < kCapacity; ++i) {
            assert(TryDeferZipMetadata(new Allocation{kWrapBase + batch + i}, Release));
        }
        AwaitDrained();
    }
    const auto after = ReadDeferredZipReleaseStats();
    assert(after.queued - before.queued == kWrapCount);
    assert(after.fallback == before.fallback);
    assert(fifo_size == kWrapCount);
    for (size_t i = 0; i < kWrapCount; ++i) assert(fifo[i] == kWrapBase + i);
}

void CheckOutOfOrderPublication() {
    SetDeferredZipReleaseTestHooks(&kPublicationHooks);
    const auto before = ReadDeferredZipReleaseStats();
    std::thread first([] {
        assert(TryDeferZipMetadata(new Allocation{kPublishBase}, Release));
    });
    Await([] { return head_reserved.load(); });
    assert(TryDeferZipMetadata(new Allocation{kPublishBase + 1}, Release));
    // Later publication supplies a credit while head is still unpublished.
    // The worker must wait again without discarding that credit or skipping head.
    Await([] { return waiting_for_head.load(); });
    const auto blocked = ReadDeferredZipReleaseStats();
    assert(blocked.pending == 2 && blocked.completed == before.completed);
    assert(seen[kPublishBase] == 0 && seen[kPublishBase + 1] == 0);
    publish_head = true;
    first.join();
    AwaitDrained();
    SetDeferredZipReleaseTestHooks(nullptr);
    const auto after = ReadDeferredZipReleaseStats();
    assert(after.queued == before.queued + 2);
    assert(after.completed == before.completed + 2);
    assert(after.fallback == before.fallback);
    assert(fifo[kWrapCount] == kPublishBase);
    assert(fifo[kWrapCount + 1] == kPublishBase + 1);
}

void CheckReservationContention() {
    SetDeferredZipReleaseTestHooks(&kContentionHooks);
    const auto before = ReadDeferredZipReleaseStats();
    std::thread racers[2];
    for (size_t i = 0; i < 2; ++i) {
        racers[i] = std::thread([i] {
            auto* allocation = new Allocation{kContentionBase + i};
            if (!TryDeferZipMetadata(allocation, Release)) Release(allocation);
        });
    }
    // Both have observed the same free slot before either reservation CAS.
    Await([] { return reservation_arrivals.load() == 2; });
    reserve_now = true;
    for (auto& racer : racers) racer.join();
    AwaitDrained();
    SetDeferredZipReleaseTestHooks(nullptr);
    const auto after = ReadDeferredZipReleaseStats();
    assert(after.queued == before.queued + 1);
    assert(after.fallback == before.fallback + 1);
    assert(after.fallback_reservation_contention ==
           before.fallback_reservation_contention + 1);
    assert(after.fallback_capacity == before.fallback_capacity);
}

void CheckConcurrentProducers() {
    const auto before = ReadDeferredZipReleaseStats();
    for (auto& last : stress_last) last = -1;
    std::atomic<size_t> ready{0};
    std::atomic<bool> go{false};
    std::vector<std::thread> producers;
    for (size_t t = 0; t < kProducers; ++t) producers.emplace_back([&, t] {
        ready.fetch_add(1);
        Await([&] { return go.load(); });
        for (size_t i = 0; i < kPerProducer; ++i) {
            auto* allocation = new Allocation{kStressBase + kPerProducer * t + i};
            if (!TryDeferZipMetadata(allocation, StressRelease)) Release(allocation);
        }
    });
    Await([&] { return ready.load() == kProducers; });
    go = true;
    for (auto& producer : producers) producer.join();
    AwaitDrained();
    const auto after = ReadDeferredZipReleaseStats();
    assert(after.queued - before.queued + after.fallback - before.fallback ==
           kProducers * kPerProducer);
    assert(stress_queued == after.queued - before.queued);
    assert(after.fallback_unavailable == before.fallback_unavailable);
    assert(after.fallback_reentrant == before.fallback_reentrant);
    CheckReasons(after);
}
}

int main() {
    CheckInitializationFailure(kSemaphoreFailure);
    CheckInitializationFailure(kWorkerFailure);
    assert(!TryDeferZipMetadata(nullptr, Release));
    int unused = 0;
    assert(!TryDeferZipMetadata(&unused, nullptr));
    assert(!TryDeferZipMetadata(&unused, Release));
    const auto unavailable = ReadDeferredZipReleaseStats();
    assert(unavailable.fallback == 3 && unavailable.fallback_unavailable == 3);
    CheckReasons(unavailable);
    SetDeferredZipReleaseTestHooks(&kWrapHooks);
    std::vector<std::thread> starters;
    for (size_t i = 0; i < kProducers; ++i)
        starters.emplace_back([] { assert(StartDeferredZipRelease()); });
    for (auto& starter : starters) starter.join();
    SetDeferredZipReleaseTestHooks(nullptr);
    assert(StartDeferredZipRelease());
    CheckSaturation();
    CheckWraparound();
    CheckOutOfOrderPublication();
    CheckReservationContention();
    CheckConcurrentProducers();
    for (const auto& value : seen) assert(value == 1);
    assert(freed == kTotal);
    const auto stats = ReadDeferredZipReleaseStats();
    assert(stats.pending == 0 && stats.completed == stats.queued);
    assert(stats.peak == kCapacity);
    assert(stats.fallback_unavailable == 3 && stats.fallback_reentrant == 1);
    CheckReasons(stats);
    printf("%zu allocations released exactly once; initialization failures, "
           "64 pending + 1 active, ticket wraparound, FIFO publication credits, "
           "reservation contention and eight concurrent producers covered\n", kTotal);
}

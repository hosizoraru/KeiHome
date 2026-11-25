// SPDX-License-Identifier: Apache-2.0
#include "deferred_zip_release.h"
#include <atomic>
#include <assert.h>
#include <chrono>
#include <pthread.h>
#include <stdio.h>
#include <sys/wait.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
constexpr size_t kCapacity = kDeferredZipReleaseCapacity;
constexpr size_t kWrapBase = kCapacity + 3;
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
        auto* child = new Allocation{kCapacity + 2};
        // This remains reentrant even with every pending slot occupied.
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

enum class PriorityScenario {
    Hysteresis, GateOff, ToggleGate, DeniedBoost, DeniedRestore, ReadbackFailure,
    InitialDeniedSafe, InitialDeniedNegative, InitialDeniedUnknown,
};
PriorityScenario priority_scenario{};
std::atomic<unsigned> priority_calls{0};
std::atomic<int> priority_requests[16]{};
std::atomic<int> simulated_nice{-10};
pthread_t priority_caller{}, priority_worker{};
std::atomic<size_t> pressure_step{0}, allowed_step{0}, idle_waits{0};
std::atomic<bool> idle_after_drain{false};
std::atomic<unsigned> pressure_seen[65]{};

bool SetTestPriority(int requested, int* observed) {
    assert(requested == 10 || requested == 0);
    assert(!pthread_equal(priority_caller, pthread_self()));
    const unsigned call = priority_calls.fetch_add(1);
    assert(call < 16);
    if (call == 0) priority_worker = pthread_self();
    assert(pthread_equal(priority_worker, pthread_self()));
    priority_requests[call] = requested;
    const bool initial_failure = call == 0 && (
            priority_scenario == PriorityScenario::InitialDeniedSafe ||
            priority_scenario == PriorityScenario::InitialDeniedNegative ||
            priority_scenario == PriorityScenario::InitialDeniedUnknown);
    const bool denied = initial_failure ||
            (requested == 0 && priority_scenario == PriorityScenario::DeniedBoost) ||
            (call > 0 && requested == 10 &&
             priority_scenario == PriorityScenario::DeniedRestore);
    const bool unknown_boost = requested == 0 &&
            priority_scenario == PriorityScenario::ReadbackFailure;
    if (!denied) simulated_nice = requested;
    *observed = priority_scenario == PriorityScenario::InitialDeniedUnknown || unknown_boost
            ? INT32_MIN : simulated_nice.load();
    return !denied && !unknown_boost;
}
void PressureRelease(void* raw) {
    const size_t id = *static_cast<size_t*>(raw);
    assert(id < 65 && pressure_seen[id].fetch_add(1) == 0);
    const size_t step = pressure_step.fetch_add(1) + 1;
    Await([step] { return allowed_step.load() >= step; });
}
void ObserveIdle(size_t credits) {
    if (credits == 0) {
        idle_waits.fetch_add(1);
        if (ReadDeferredZipReleaseStats().completed == 65) idle_after_drain = true;
    }
}
const DeferredZipReleaseTestHooks kPriorityHooks{
    nullptr, nullptr, ObserveIdle, 0, false, false, SetTestPriority};

void CheckPriorityChild(PriorityScenario scenario) {
    priority_scenario = scenario;
    priority_caller = pthread_self();
    if (scenario == PriorityScenario::InitialDeniedSafe) simulated_nice = 10;
    const bool gate_off = scenario == PriorityScenario::GateOff;
    __atomic_store_n(&kh_zip_release_adaptive_priority_enabled,
                     gate_off ? 0u : 1u, __ATOMIC_RELAXED);
    SetDeferredZipReleaseTestHooks(&kPriorityHooks);
    if (scenario == PriorityScenario::InitialDeniedNegative ||
            scenario == PriorityScenario::InitialDeniedUnknown) {
        // The failed initial request must not leave a usable inherited -10
        // worker. Concurrent starters also wait for this startup decision.
        std::thread starters[4];
        for (auto& starter : starters)
            starter = std::thread([] { assert(!StartDeferredZipRelease()); });
        for (auto& starter : starters) starter.join();
        const auto state = ReadDeferredZipReleaseStats();
        assert(priority_calls == 1 && state.priority_failures == 1);
        assert(state.requested_nice == 10);
        assert(state.observed_nice == (scenario == PriorityScenario::InitialDeniedNegative
                ? -10 : INT32_MIN));
        assert(!TryDeferZipMetadata(&scenario, PressureRelease));
        assert(state.queued == 0 && state.completed == 0);
        return;
    }
    assert(StartDeferredZipRelease());
    assert(priority_calls == 1 && priority_requests[0] == 10);
    assert(ReadDeferredZipReleaseStats().observed_nice == 10);
    Await([] { return idle_waits.load() != 0; });
    const size_t idle_before = idle_waits.load();
    // With no active iteration, changing the gate cannot trigger a syscall or
    // wake the semaphore. No timer or elapsed-time assertion is involved.
    for (size_t i = 0; i < 100; ++i) {
        __atomic_store_n(&kh_zip_release_adaptive_priority_enabled, 0u, __ATOMIC_RELAXED);
        __atomic_store_n(&kh_zip_release_adaptive_priority_enabled,
                         gate_off ? 0u : 1u, __ATOMIC_RELAXED);
    }
    assert(priority_calls == 1 && idle_waits == idle_before);
    assert(ReadDeferredZipReleaseStats().completed == 0);

    size_t items[65];
    for (size_t i = 0; i < 65; ++i) items[i] = i;
    assert(TryDeferZipMetadata(&items[0], PressureRelease));
    Await([] { return pressure_step.load() == 1; });
    // Freeze the active release and establish a deterministic queued backlog.
    for (size_t i = 1; i <= 33; ++i)
        assert(TryDeferZipMetadata(&items[i], PressureRelease));
    assert(ReadDeferredZipReleaseStats().pending == 33);
    allowed_step = 1;
    Await([] { return pressure_step.load() == 2; });
    const bool stays_fixed = gate_off || scenario == PriorityScenario::InitialDeniedSafe;
    if (stays_fixed) {
        assert(priority_calls == 1 && simulated_nice == 10);
    } else {
        assert(priority_calls == 2 && priority_requests[1] == 0);
        assert(simulated_nice == (scenario == PriorityScenario::DeniedBoost ? 10 : 0));
    }
    assert(ReadDeferredZipReleaseStats().pending == 32);
    if (scenario == PriorityScenario::ToggleGate) {
        __atomic_store_n(&kh_zip_release_adaptive_priority_enabled, 0u, __ATOMIC_RELAXED);
        allowed_step = 2;
        Await([] { return pressure_step.load() == 3; });
        // Restore on the next active iteration despite still having 31 queued.
        assert(ReadDeferredZipReleaseStats().pending == 31);
        assert(priority_calls == 3 && priority_requests[2] == 10 && simulated_nice == 10);
    }
    allowed_step = 24;
    Await([] { return pressure_step.load() == 25; });
    assert(ReadDeferredZipReleaseStats().pending == 9);
    if (scenario == PriorityScenario::Hysteresis ||
            scenario == PriorityScenario::DeniedRestore)
        assert(priority_calls == 2 && simulated_nice == 0);
    allowed_step = 25;
    Await([] { return pressure_step.load() == 26; });
    assert(ReadDeferredZipReleaseStats().pending == 8);
    if (scenario == PriorityScenario::Hysteresis ||
            scenario == PriorityScenario::DeniedRestore) {
        assert(priority_calls == 3 && priority_requests[2] == 10);
        assert(simulated_nice == (scenario == PriorityScenario::DeniedRestore ? 0 : 10));
    }

    // Refill above the high watermark while the next callback is blocked.
    // A failed adapter must never be retried, even under renewed pressure.
    for (size_t i = 34; i < 65; ++i)
        assert(TryDeferZipMetadata(&items[i], PressureRelease));
    assert(ReadDeferredZipReleaseStats().pending == 39);
    const unsigned before_refill = priority_calls.load();
    allowed_step = 26;
    Await([] { return pressure_step.load() == 27; });
    if (scenario == PriorityScenario::Hysteresis) {
        assert(priority_calls == before_refill + 1 && priority_requests[3] == 0);
    } else {
        assert(priority_calls == before_refill);
    }
    allowed_step = 65;
    AwaitDrained();
    Await([] { return idle_after_drain.load(); });
    for (const auto& count : pressure_seen) assert(count == 1);
    const auto final = ReadDeferredZipReleaseStats();
    assert(final.queued == 65 && final.completed == 65 && final.fallback == 0);
    assert(final.priority_failures == (
            scenario == PriorityScenario::DeniedBoost ||
            scenario == PriorityScenario::DeniedRestore ||
            scenario == PriorityScenario::ReadbackFailure ||
            scenario == PriorityScenario::InitialDeniedSafe ? 1u : 0u));
    assert(final.priority_boosts == (scenario == PriorityScenario::Hysteresis ? 2u :
            scenario == PriorityScenario::ToggleGate ||
            scenario == PriorityScenario::DeniedRestore ? 1u : 0u));
    const unsigned expected_calls = scenario == PriorityScenario::Hysteresis ? 5 :
            scenario == PriorityScenario::ToggleGate ||
            scenario == PriorityScenario::DeniedRestore ? 3 :
            scenario == PriorityScenario::DeniedBoost ||
            scenario == PriorityScenario::ReadbackFailure ? 2 : 1;
    assert(priority_calls == expected_calls);
    assert(final.requested_nice == (scenario == PriorityScenario::DeniedBoost ||
            scenario == PriorityScenario::ReadbackFailure ? 0 : 10));
    assert(final.observed_nice == (scenario == PriorityScenario::ReadbackFailure ? INT32_MIN :
            scenario == PriorityScenario::DeniedRestore ? 0 : 10));
    CheckReasons(final);
}

void CheckPriority(PriorityScenario scenario) {
    const pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        CheckPriorityChild(scenario);
        _exit(0);
    }
    int status = 0;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

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
    auto* full = new Allocation{kCapacity + 1};
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
    assert(drained.queued == kCapacity + 1 && drained.completed == kCapacity + 1);
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
    CheckPriority(PriorityScenario::Hysteresis);
    CheckPriority(PriorityScenario::GateOff);
    CheckPriority(PriorityScenario::ToggleGate);
    CheckPriority(PriorityScenario::DeniedBoost);
    CheckPriority(PriorityScenario::DeniedRestore);
    CheckPriority(PriorityScenario::ReadbackFailure);
    CheckPriority(PriorityScenario::InitialDeniedSafe);
    CheckPriority(PriorityScenario::InitialDeniedNegative);
    CheckPriority(PriorityScenario::InitialDeniedUnknown);
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
           "%zu pending + 1 active, ticket wraparound, FIFO publication credits, "
           "reservation contention and eight concurrent producers covered; "
           "adaptive priority thresholds, gate, failure latch and idle behavior covered\n",
           kTotal, kCapacity);
}

// SPDX-License-Identifier: Apache-2.0
#include "deferred_zip_release.h"
#include <atomic>
#include <assert.h>
#include <chrono>
#include <stdio.h>
#include <thread>
#include <vector>

namespace {
constexpr size_t kTotal = 8067;
std::atomic<unsigned> seen[kTotal]{};
std::atomic<bool> entered{false}, unblock{false};
struct Allocation { size_t id; };
void Release(void* raw) {
    auto* a = static_cast<Allocation*>(raw);
    if (a->id == 0) {
        entered = true;
        while (!unblock) std::this_thread::yield();
        Allocation child{66};
        assert(!TryDeferZipMetadata(&child, Release));
        assert(seen[66].fetch_add(1) == 0);
    }
    assert(seen[a->id].fetch_add(1) == 0);
    delete a;
}
void Await(const std::atomic<bool>& flag) {
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!flag && std::chrono::steady_clock::now() < until) std::this_thread::yield();
    assert(flag);
}
}

int main() {
    assert(!TryDeferZipMetadata(nullptr, Release));
    Allocation unused{66};
    assert(!TryDeferZipMetadata(&unused, nullptr));
    assert(!TryDeferZipMetadata(&unused, Release));
    assert(StartDeferredZipRelease());
    assert(TryDeferZipMetadata(new Allocation{0}, Release));
    Await(entered);
    for (size_t id = 1; id <= 64; ++id)
        assert(TryDeferZipMetadata(new Allocation{id}, Release));
    auto* full = new Allocation{65};
    assert(!TryDeferZipMetadata(full, Release));
    Release(full);
    assert(ReadDeferredZipReleaseStats().pending == 64);
    unblock = true;
    std::vector<std::thread> producers;
    for (size_t t = 0; t < 8; ++t) producers.emplace_back([t] {
        for (size_t i = 0; i < 1000; ++i) {
            auto* p = new Allocation{67 + 1000 * t + i};
            if (!TryDeferZipMetadata(p, Release)) Release(p);
        }
    });
    for (auto& t : producers) t.join();
    const auto until = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    for (;;) {
        size_t total = 0;
        for (const auto& value : seen) total += value.load();
        if (total == kTotal) break;
        assert(std::chrono::steady_clock::now() < until);
        std::this_thread::yield();
    }
    for (const auto& value : seen) assert(value == 1);
    for (;;) {
        const auto state = ReadDeferredZipReleaseStats();
        if (state.pending == 0 && state.completed == state.queued) break;
        assert(std::chrono::steady_clock::now() < until);
        std::this_thread::yield();
    }
    const auto stats = ReadDeferredZipReleaseStats();
    assert(stats.pending == 0 && stats.queued >= 65);
    assert(stats.fallback >= 4);
    printf("8067 allocations released exactly once; bounded saturation, "
           "recursion and concurrent ownership passed\n");
}

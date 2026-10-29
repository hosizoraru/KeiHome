// SPDX-License-Identifier: Apache-2.0
// Standalone host test TU: include the implementation to exercise its private
// streaming parser and reclamation without exporting a test API or bypassing
// peer authentication. No socket is opened by these tests.
// Run with: bash scripts/tests/test-monet-policy-server.sh
#include "../../main/cpp/monet_policy_server.cpp"

#include <assert.h>
#include <stdio.h>

#include <atomic>
#include <string>
#include <thread>
#include <type_traits>
#include <vector>

static_assert(std::is_same_v<decltype(kh_monet_readers), uint32_t>,
              "The shared reader ABI must be nonvolatile uint32_t");

namespace {

using Bytes = std::vector<uint8_t>;

void AppendWord(Bytes* bytes, uint32_t value) {
    const size_t offset = bytes->size();
    bytes->resize(offset + 4u);
    WriteLe32(bytes->data() + offset, value);
}

Bytes Request(uint32_t flags, uint32_t foreground, uint32_t background,
              const std::vector<std::string>& names) {
    Bytes result;
    for (uint32_t value : {kNativeMonetMagic, kNativeMonetProtocolVersion, flags,
                          foreground, background, static_cast<uint32_t>(names.size())}) {
        AppendWord(&result, value);
    }
    for (const std::string& name : names) {
        AppendWord(&result, static_cast<uint32_t>(name.size()));
        result.insert(result.end(), name.begin(), name.end());
    }
    return result;
}

bool Decode(const Bytes& bytes, NativeMonetPolicy* policy) {
    size_t offset = 0u;
    return ReadPolicyRequest([&](void* target, size_t length) {
        if (length > bytes.size() - offset) return false;
        memcpy(target, bytes.data() + offset, length);
        offset += length;
        return true;
    }, policy);
}

PolicyAllocation* Policy(uint32_t color, uint32_t count = 1u) {
    PolicyAllocation* allocation = AllocatePolicy();
    assert(allocation != nullptr);
    allocation->policy.flags = 1u;
    allocation->policy.foreground = color;
    allocation->policy.background = color ^ 0x00ffffffu;
    allocation->policy.count = count;
    if (count != 0u) {
        allocation->policy.entries[0].len = 11u;
        memcpy(allocation->policy.entries[0].name, "com.example", 12u);
    }
    return allocation;
}

void Clear(PolicyState* state) {
    free(state->expiration);
    state->expiration = nullptr;
    state->expires_at = 0u;
    PublishPolicy(state, nullptr);
    assert(state->retired == nullptr);
    assert(__atomic_load_n(&kh_monet_readers, __ATOMIC_ACQUIRE) == 0u);
}

void TestPeerIdentityPredicate() {
    const char exact[] = "u:r:system_server:s0";
    assert(IsSystemServerPeer(1000u, exact, sizeof(exact) - 1u));
    assert(IsSystemServerPeer(1000u, exact, sizeof(exact)));
    for (uint32_t uid : {0u, 1001u, 2000u, 101000u}) {
        assert(!IsSystemServerPeer(uid, exact, sizeof(exact)));
    }
    for (const std::string& context : std::vector<std::string>{
            "", "u:r:system_server:s", "u:r:system_server:s0:c1",
            "u:r:system_server:s00", "u:r:system_app:s0", "u:r:su:s0",
            "u:r:system_server:s0 "}) {
        assert(!IsSystemServerPeer(1000u, context.data(), context.size()));
    }
    assert(!IsSystemServerPeer(1000u, nullptr, 0u));
    char embedded[sizeof(exact)];
    memcpy(embedded, exact, sizeof(exact));
    embedded[5] = '\0';
    assert(!IsSystemServerPeer(1000u, embedded, sizeof(embedded)));
}

void TestIdleWithoutServiceStaysNull() {
    PolicyState state{};
    for (uint64_t elapsed_ms : {uint64_t{0}, uint64_t{15000}, uint64_t{45000},
                                uint64_t{45001}, uint64_t{300000}}) {
        MaintainPolicy(&state, elapsed_ms * kNanosecondsPerMs);
        assert(AcquireNativeMonetPolicy() == nullptr);
        ReleaseNativeMonetPolicy();
        assert(state.expiration == nullptr && state.expires_at == 0u);
        assert(state.retired == nullptr && state.retired_count == 0u);
    }
}

void TestWireAndLimits() {
    const uint8_t magic_bytes[] = {0x4b, 0x48, 0x4d, 0x33};
    assert(ReadLe32(magic_bytes) == 0x334d484bu);
    uint8_t encoded[4];
    WriteLe32(encoded, 0x334d484bu);
    assert(memcmp(encoded, magic_bytes, sizeof(encoded)) == 0);

    PolicyAllocation* output = AllocatePolicy();
    assert(output != nullptr);
    const Bytes valid = Request(1u, 0xff123456u, 0xffabcdefu,
                                {"com.example_42.App9", "android"});
    assert(Decode(valid, &output->policy));
    assert(output->policy.foreground == 0xff123456u);
    assert(output->policy.background == 0xffabcdefu);
    assert(output->policy.flags == 1u && output->policy.count == 2u);
    assert(output->policy.entries[0].len == 19u);
    assert(strcmp(output->policy.entries[0].name, "com.example_42.App9") == 0);
    assert(output->policy.entries[1].len == 7u);

    // Every partial frame fails, including truncated headers, lengths and names.
    for (size_t length = 0u; length < valid.size(); ++length) {
        assert(!Decode(Bytes(valid.begin(), valid.begin() + length), &output->policy));
    }
    for (size_t offset : {size_t{0}, size_t{4}, size_t{8}}) {
        Bytes invalid = valid;
        WriteLe32(invalid.data() + offset, 2u);
        assert(!Decode(invalid, &output->policy));
    }
    assert(!Decode(Request(1u, 0xfe123456u, 0xffabcdefu, {}), &output->policy));
    assert(!Decode(Request(1u, 0xff123456u, 0x00abcdefu, {}), &output->policy));
    assert(!Decode(Request(0u, 0u, 0u, {"com.example"}), &output->policy));
    assert(Decode(Request(0u, 0u, 0u, {}), &output->policy));
    assert(output->policy.flags == 0u && output->policy.count == 0u);
    assert(Decode(Request(1u, 0xff000000u, 0xffffffffu, {}), &output->policy));

    for (const std::string& name : std::vector<std::string>{
            "", ".com", "com.", "com..app", "9com.app", "com.9app", "com._app",
            "com/app", "com\\app", "com-app", "com app", "com\napp",
            std::string("com.\0app", 8u), std::string("com.\xc3\xa9"),
            std::string(256u, 'a')}) {
        assert(!Decode(Request(1u, 0xff000000u, 0xffffffffu, {name}), &output->policy));
    }
    for (uint32_t length : {0u, 256u, UINT32_MAX}) {
        Bytes invalid = valid;
        WriteLe32(invalid.data() + 24u, length);
        assert(!Decode(invalid, &output->policy));
    }
    Bytes invalid_count = valid;
    WriteLe32(invalid_count.data() + 20u, 513u);
    assert(!Decode(invalid_count, &output->policy));
    const std::string longest(255u, 'a');
    assert(Decode(Request(1u, 0xff000000u, 0xffffffffu,
                          std::vector<std::string>(512u, longest)), &output->policy));
    assert(output->policy.count == 512u);
    assert(output->policy.entries[511].len == 255u);
    assert(output->policy.entries[511].name[255] == '\0');
    free(output);
}

void TestLeaseAndRetirement() {
    PolicyState state{};
    const uint64_t start = 100u * kNanosecondsPerMs;
    const uint64_t lease = uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs;
    PolicyAllocation* first = Policy(0xff112233u);
    assert(CommitPolicy(&state, first, start));
    const NativeMonetPolicy* pinned = AcquireNativeMonetPolicy();
    assert(pinned == &first->policy);

    PolicyAllocation* second = Policy(0xff445566u);
    assert(CommitPolicy(&state, second, start + 15u * 1000u * kNanosecondsPerMs));
    assert(state.retired_count == 1u);
    ReclaimDrainedPolicies(&state);
    assert(state.retired_count == 1u && pinned->foreground == 0xff112233u);
    const NativeMonetPolicy* live = AcquireNativeMonetPolicy();
    assert(live == &second->policy && live->foreground == 0xff445566u);
    ReleaseNativeMonetPolicy();
    ReleaseNativeMonetPolicy();
    ReclaimDrainedPolicies(&state);
    assert(state.retired_count == 0u);

    // Rejected input cannot change the publication or extend its expiry.
    PolicyAllocation* invalid = AllocatePolicy();
    assert(invalid != nullptr);
    assert(!Decode(Request(3u, 0u, 0u, {}), &invalid->policy));
    free(invalid);
    const uint64_t renewed_expiry = start + 15u * 1000u * kNanosecondsPerMs + lease;
    assert(state.expires_at == renewed_expiry);
    MaintainPolicy(&state, start + lease);
    live = AcquireNativeMonetPolicy();
    assert(live->count == 1u);
    ReleaseNativeMonetPolicy();
    MaintainPolicy(&state, renewed_expiry - 1u);
    live = AcquireNativeMonetPolicy();
    assert(live->count == 1u);
    ReleaseNativeMonetPolicy();
    MaintainPolicy(&state, renewed_expiry);
    live = AcquireNativeMonetPolicy();
    assert(live->count == 0u && live->flags == 1u);
    assert(live->foreground == 0xff445566u);
    assert(live->background == (0xff445566u ^ 0x00ffffffu));
    ReleaseNativeMonetPolicy();
    assert(state.expiration == nullptr);

    PolicyAllocation* disabled = AllocatePolicy();
    assert(disabled != nullptr);
    assert(Decode(Request(0u, 0u, 0u, {}), &disabled->policy));
    assert(CommitPolicy(&state, disabled, renewed_expiry + 1u));
    live = AcquireNativeMonetPolicy();
    assert(live->flags == 0u && live->count == 0u);
    ReleaseNativeMonetPolicy();
    Clear(&state);
    assert(AcquireNativeMonetPolicy() == nullptr);
    ReleaseNativeMonetPolicy();  // Null acquisitions also owe a decrement.
}

void TestBoundedRetirementStillExpires() {
    PolicyState state{};
    assert(CommitPolicy(&state, Policy(0xff000001u), 1u));
    const NativeMonetPolicy* pinned = AcquireNativeMonetPolicy();
    for (uint32_t i = 0u; i < kMaxRetiredPolicies; ++i) {
        assert(CommitPolicy(&state, Policy(0xff000010u + i), 2u + i));
    }
    assert(state.retired_count == kMaxRetiredPolicies);
    const uint64_t expiry = state.expires_at;
    PolicyAllocation* rejected = Policy(0xffdead00u);
    assert(!CommitPolicy(&state, rejected, 100u));
    free(rejected);
    assert(state.expires_at == expiry);
    MaintainPolicy(&state, expiry);
    assert(state.retired_count == kMaxRetiredPolicies + 1u);
    assert(pinned->foreground == 0xff000001u && pinned->count == 1u);
    const NativeMonetPolicy* live = AcquireNativeMonetPolicy();
    assert(live->count == 0u && live->foreground == 0xff000013u);
    ReleaseNativeMonetPolicy();
    ReleaseNativeMonetPolicy();
    ReclaimDrainedPolicies(&state);
    assert(state.retired_count == 0u);
    Clear(&state);
}

void TestSelectionNotificationRevisions() {
    PolicyState state{};
    const uint64_t initial = __atomic_load_n(&kh_monet_selection_revision, __ATOMIC_ACQUIRE);
    assert(CommitPolicy(&state, Policy(0xff000001u), 1u));
    assert(kh_monet_selection_revision == initial + 1u);
    // Lease renewals and palette-only updates must not rebuild icon selection.
    assert(CommitPolicy(&state, Policy(0xff000002u), 2u));
    assert(kh_monet_selection_revision == initial + 1u);
    auto* replacement = Policy(0xff000002u);
    replacement->policy.entries[0].name[4] = 'E';
    assert(CommitPolicy(&state, replacement, 3u));
    assert(kh_monet_selection_revision == initial + 2u);
    assert(CommitPolicy(&state, Policy(0xff000002u, 0u), 4u));
    assert(kh_monet_selection_revision == initial + 3u);
    Clear(&state);
    assert(kh_monet_selection_revision == initial + 3u);
}

void TestConcurrentReaders() {
    PolicyState state{};
    std::atomic<bool> stop{false};
    std::atomic<uint32_t> observations{0u};
    std::atomic<uint32_t> readers_ready{0u};
    assert(CommitPolicy(&state, Policy(0xff000001u), 1u));
    const auto read = [&] {
        bool first_observation = true;
        while (!stop.load(std::memory_order_acquire)) {
            const NativeMonetPolicy* policy = AcquireNativeMonetPolicy();
            if (policy != nullptr) {
                assert(policy->flags == 1u && policy->count == 1u);
                assert(policy->background == (policy->foreground ^ 0x00ffffffu));
                assert(policy->entries[0].len == 11u);
                assert(memcmp(policy->entries[0].name, "com.example", 12u) == 0);
                observations.fetch_add(1u, std::memory_order_relaxed);
            }
            ReleaseNativeMonetPolicy();
            if (first_observation) {
                readers_ready.fetch_add(1u, std::memory_order_release);
                first_observation = false;
            }
            std::this_thread::yield();
        }
    };
    std::thread readers[] = {std::thread(read), std::thread(read),
                             std::thread(read), std::thread(read)};
    // All four readers must have acquired/released the first publication before
    // writer stress starts, independent of host thread-start scheduling.
    while (readers_ready.load(std::memory_order_acquire) != 4u) {
        std::this_thread::yield();
    }
    uint32_t committed = 0u;
    for (uint32_t i = 0u; i < 1500u; ++i) {
        PolicyAllocation* candidate = Policy(0xff000100u + i);
        if (CommitPolicy(&state, candidate, 2u + i)) ++committed;
        else free(candidate);
        std::this_thread::yield();
    }
    stop.store(true, std::memory_order_release);
    for (std::thread& reader : readers) reader.join();
    assert(committed != 0u && observations.load() != 0u);
    ReclaimDrainedPolicies(&state);
    Clear(&state);
    printf("concurrent publications=%u, reader observations=%u\n",
           committed, observations.load());
}

}  // namespace

int main() {
    assert(!StartNativeMonetPolicyServer());  // This host is not com.miui.home.
    assert(__atomic_load_n(&kh_monet_policy, __ATOMIC_ACQUIRE) == nullptr);
    TestPeerIdentityPredicate();
    TestIdleWithoutServiceStaysNull();
    TestWireAndLimits();
    TestLeaseAndRetirement();
    TestBoundedRetirementStillExpires();
    TestSelectionNotificationRevisions();
    TestConcurrentReaders();
    puts("monet peer identity, parser, lease and reader lifetime tests passed");
}

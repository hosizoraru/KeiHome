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
static_assert(std::is_same_v<decltype(kh_monet_tint_ever_enabled), uint32_t>,
              "The assembly tint history ABI must be nonvolatile uint32_t");
static_assert(std::is_same_v<decltype(kh_theme_materials_enabled), uint32_t>);
static_assert(std::is_same_v<decltype(kh_theme_materials_installed), uint32_t>);
static_assert(std::is_same_v<decltype(kh_monet_installed), uint32_t>);
static_assert(std::is_same_v<decltype(kh_dock_glass_installed), uint32_t>);
static_assert(std::is_same_v<decltype(kh_dock_glass_enabled), uint32_t>);
static_assert(std::is_same_v<decltype(kh_dock_glass_corner_q8), uint32_t>);
static_assert(std::is_same_v<decltype(kh_dock_glass_corner_style), uint32_t>);
static_assert(std::is_same_v<decltype(kh_dock_glass_revision), uint64_t>);
static_assert(std::is_same_v<decltype(kh_dock_glass_notified), uint64_t>);
static_assert(kNativeDockGlassCapability == 8u && kNativeDockGlassCommand == 8u);
static_assert(kNativeDockGlassMaxCornerQ8 == 65536u);
static_assert(kNativeDockGlassRoundedStyle == 0u && kNativeDockGlassRecentsStyle == 1u);
static_assert(kNativeLauncherGlassCapability == 16u && kNativeLauncherGlassCommand == 9u);
static_assert(kNativeLauncherGlassPairCapability == 64u && kNativeLauncherGlassPairCommand == 10u);
static_assert(kNativeMonetExpiryMs == 45000u);

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

// Dock word 5 is a style, with no package body even when it is 1.
Bytes DockRequest(uint32_t enabled, uint32_t corner, uint32_t style) {
    Bytes result;
    for (uint32_t value : {kNativeMonetMagic, kNativeMonetProtocolVersion, 8u,
                          enabled, corner, style})
        AppendWord(&result, value);
    assert(result.size() == 24u);
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

bool ApplyRequest(PolicyState* state, const Bytes& bytes, uint64_t now) {
    size_t offset = 0u;
    return ReadAndCommitRequest(state, [&](void* target, size_t length) {
        if (length > bytes.size() - offset) return false;
        memcpy(target, bytes.data() + offset, length);
        offset += length;
        return true;
    }, [=] { return now; });
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
    const uint32_t tint_history = __atomic_load_n(&kh_monet_tint_ever_enabled, __ATOMIC_ACQUIRE);
    free(state->expiration);
    state->expiration = nullptr;
    state->expires_at = 0u;
    PublishPolicy(state, nullptr);
    assert(state->retired == nullptr);
    assert(__atomic_load_n(&kh_monet_readers, __ATOMIC_ACQUIRE) == 0u);
    assert(__atomic_load_n(&kh_monet_tint_ever_enabled, __ATOMIC_ACQUIRE) == tint_history);
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
        assert(__atomic_load_n(&kh_monet_tint_ever_enabled, __ATOMIC_ACQUIRE) == 0u);
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

void TestTintEnableHistory() {
    PolicyState state{};
    const auto history = [] {
        return __atomic_load_n(&kh_monet_tint_ever_enabled, __ATOMIC_ACQUIRE);
    };
    assert(history() == 0u);
    assert(CommitPolicy(&state, AllocatePolicy(), 1u));  // Disabled publication.
    assert(history() == 0u);

    // A rejected first enable must not arm invalidation. Fill retirement with
    // disabled snapshots while a reader holds the original disabled policy.
    const NativeMonetPolicy* pinned = AcquireNativeMonetPolicy();
    assert(pinned != nullptr && pinned->flags == 0u);
    for (uint32_t i = 0u; i < kMaxRetiredPolicies; ++i) {
        assert(CommitPolicy(&state, AllocatePolicy(), 2u + i));
    }
    PolicyAllocation* rejected = Policy(0xff123456u, 0u);
    assert(!CommitPolicy(&state, rejected, 10u));
    free(rejected);
    assert(history() == 0u);
    ReleaseNativeMonetPolicy();
    Clear(&state);
    assert(history() == 0u);

    std::atomic<bool> reader_ready{false};
    std::thread reader([&] {
        reader_ready.store(true, std::memory_order_release);
        for (;;) {
            const NativeMonetPolicy* live = AcquireNativeMonetPolicy();
            const bool enabled = live != nullptr && live->flags == 1u;
            if (enabled) assert(history() == 1u);
            ReleaseNativeMonetPolicy();
            if (enabled) break;
            std::this_thread::yield();
        }
    });
    while (!reader_ready.load(std::memory_order_acquire)) std::this_thread::yield();
    // No deny entries are needed to enable tint; this is independent of the
    // selection-notification revision and of whether a layer has painted yet.
    assert(CommitPolicy(&state, Policy(0xff123456u, 0u), 11u));
    reader.join();
    assert(history() == 1u);
    assert(CommitPolicy(&state, AllocatePolicy(), 12u));
    assert(history() == 1u);
    Clear(&state);
    MaintainPolicy(&state, UINT64_MAX);
    assert(history() == 1u);

    // Replacing policy ownership cannot reset native process history, as when
    // an API-102 Java owner reconnects to the resident native hooks.
    PolicyState replacement{};
    assert(CommitPolicy(&replacement, AllocatePolicy(), 13u));
    Clear(&replacement);
    assert(history() == 1u);
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
    assert(__atomic_load_n(&kh_monet_tint_ever_enabled, __ATOMIC_ACQUIRE) == 1u);

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

void TestMaterialCommandAdmissionAndPolicyIsolation() {
    PolicyState state{};
    assert(CommitPolicy(&state, Policy(0xff123456u), 1u));
    const NativeMonetPolicy* pinned = AcquireNativeMonetPolicy();
    const uint64_t policy_expiry = state.expires_at;
    const uint64_t selection = kh_monet_selection_revision;
    const uint32_t history = kh_monet_tint_ever_enabled;
    const Bytes enabled = Request(kNativeThemeMaterialsCommand, 1u, 0u, {});
    const Bytes disabled = Request(kNativeThemeMaterialsCommand, 0u, 0u, {});
    assert(enabled.size() == 24u);

    // Missing/failed highlight installation cannot ACK either setting.
    assert(!ApplyRequest(&state, enabled, 2u));
    assert(!ApplyRequest(&state, disabled, 2u));
    assert(kh_theme_materials_enabled == 0u && state.materials_expires_at == 0u);
    __atomic_store_n(&kh_theme_materials_installed, uint32_t{1}, __ATOMIC_RELEASE);
    assert(ApplyRequest(&state, enabled, 3u));
    assert(kh_theme_materials_enabled == 1u);
    const uint64_t expiry = state.materials_expires_at;
    assert(expiry == 3u + uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs);
    assert(NextMaintenanceWake(&state, UINT64_MAX) == policy_expiry);

    for (size_t length = 0u; length < enabled.size(); ++length) {
        assert(!ApplyRequest(&state, Bytes(enabled.begin(), enabled.begin() + length), 4u));
    }
    for (const Bytes& bad : std::vector<Bytes>{
            Request(2u, 2u, 0u, {}), Request(2u, UINT32_MAX, 0u, {}),
            Request(2u, 1u, 1u, {}), Request(2u, 1u, 0u, {"com.example"}),
            Request(3u, 1u, 0u, {})}) {
        assert(!ApplyRequest(&state, bad, 4u));
    }
    for (size_t offset : {size_t{0}, size_t{4}}) {
        Bytes bad = enabled;
        WriteLe32(bad.data() + offset, 0u);
        assert(!ApplyRequest(&state, bad, 4u));
    }
    assert(!ApplyRequest(&state, disabled, 0u));  // Deadline/clock rejected before commit.
    auto* invalid_policy = AllocatePolicy();
    invalid_policy->policy.flags = kNativeThemeMaterialsCommand;
    assert(!Decode(enabled, &invalid_policy->policy));
    assert(!CommitPolicy(&state, invalid_policy, 4u));
    free(invalid_policy);
    assert(state.materials_expires_at == expiry && kh_theme_materials_enabled == 1u);
    assert(kh_monet_policy == pinned && pinned->count == 1u);
    assert(pinned->foreground == 0xff123456u);
    assert(state.expires_at == policy_expiry && state.retired_count == 0u);
    assert(kh_monet_selection_revision == selection && kh_monet_tint_ever_enabled == history);

    assert(ApplyRequest(&state, disabled, 5u));
    assert(kh_theme_materials_enabled == 0u && state.materials_expires_at == 0u);
    assert(kh_monet_policy == pinned && kh_monet_selection_revision == selection);
    ReleaseNativeMonetPolicy();
    Clear(&state);
    __atomic_store_n(&kh_theme_materials_installed, uint32_t{0}, __ATOMIC_RELEASE);
}

void TestMaterialLeaseRenewalAndMonetOff() {
    PolicyState state{};
    const uint64_t lease = uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs;
    const uint64_t heartbeat = uint64_t{kNativeMonetKeepaliveMs} * kNanosecondsPerMs;
    const Bytes enabled = Request(2u, 1u, 0u, {});
    __atomic_store_n(&kh_theme_materials_installed, uint32_t{1}, __ATOMIC_RELEASE);
    assert(kh_monet_policy == nullptr);
    const uint64_t selection = kh_monet_selection_revision;
    assert(ApplyRequest(&state, enabled, 1u));
    assert(kh_monet_policy == nullptr && kh_monet_selection_revision == selection);
    assert(NextMaintenanceWake(&state, UINT64_MAX) == 1u + lease);
    assert(ApplyRequest(&state, enabled, 1u + heartbeat));
    assert(state.materials_expires_at == 1u + heartbeat + lease);

    // Ordinary Monet enable and disable remain usable and do not renew this lease.
    assert(ApplyRequest(&state, Request(1u, 0xff000000u, 0xffffffffu, {"com.example"}), 2u + heartbeat));
    assert(ApplyRequest(&state, Request(0u, 0u, 0u, {}), 3u + heartbeat));
    assert(kh_monet_policy->flags == 0u && kh_theme_materials_enabled == 1u);
    assert(state.materials_expires_at == 1u + heartbeat + lease);
    MaintainPolicy(&state, 1u + lease);
    assert(kh_theme_materials_enabled == 1u);
    MaintainPolicy(&state, heartbeat + lease);
    assert(kh_theme_materials_enabled == 1u);
    const NativeMonetPolicy* disabled = kh_monet_policy;
    MaintainPolicy(&state, 1u + heartbeat + lease);
    assert(kh_theme_materials_enabled == 0u && state.materials_expires_at == 0u);
    assert(kh_monet_policy == disabled && disabled->flags == 0u);

    assert(ApplyRequest(&state, enabled, 2u + heartbeat + lease));
    __atomic_store_n(&kh_theme_materials_installed, uint32_t{0}, __ATOMIC_RELEASE);
    assert(!ApplyRequest(&state, enabled, 3u + heartbeat + lease));
    assert(kh_theme_materials_enabled == 0u && state.materials_expires_at == 0u);
    // A rejected extension on this process must not reject an ordinary policy.
    assert(ApplyRequest(&state, Request(1u, 0xff000000u, 0xffffffffu, {}), 4u + heartbeat + lease));
    Clear(&state);
}

void TestMaterialCommandDoesNotConsumePolicyRetirementCapacity() {
    PolicyState state{};
    assert(CommitPolicy(&state, Policy(0xff123456u), 1u));
    const NativeMonetPolicy* pinned = AcquireNativeMonetPolicy();
    for (uint32_t i = 0u; i < kMaxRetiredPolicies; ++i) {
        assert(CommitPolicy(&state, Policy(0xff000010u + i), 2u + i));
    }
    __atomic_store_n(&kh_theme_materials_installed, uint32_t{1}, __ATOMIC_RELEASE);
    assert(ApplyRequest(&state, Request(2u, 1u, 0u, {}), 100u));
    assert(state.retired_count == kMaxRetiredPolicies && pinned->foreground == 0xff123456u);
    assert(ApplyRequest(&state, Request(2u, 0u, 0u, {}), 101u));
    assert(state.retired_count == kMaxRetiredPolicies);
    ReleaseNativeMonetPolicy();
    Clear(&state);
    __atomic_store_n(&kh_theme_materials_installed, uint32_t{0}, __ATOMIC_RELEASE);
}

void TestInstalledCapabilitiesAndCommandAdmission() {
    const Bytes enable = Request(1u, 0xff123456u, 0xffabcdefu, {"com.example"});
    const Bytes disable = Request(0u, 0u, 0u, {});
    const Bytes material_enable = Request(2u, 1u, 0u, {});
    const Bytes material_disable = Request(2u, 0u, 0u, {});
    for (uint32_t capabilities = 0u; capabilities <= 3u; ++capabilities) {
        PolicyState state{};
        const bool monet = (capabilities & kNativeMonetCapability) != 0u;
        const bool materials = (capabilities & kNativeThemeMaterialsCapability) != 0u;
        __atomic_store_n(&kh_monet_installed, monet ? 1u : 0u, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_theme_materials_installed, materials ? 1u : 0u, __ATOMIC_RELEASE);
        assert(InstalledCapabilities() == capabilities);
        assert(ApplyRequest(&state, enable, 1u) == monet);
        assert((kh_monet_policy != nullptr) == monet);
        if (monet) assert(kh_monet_policy->flags == 1u && kh_monet_policy->count == 1u);
        const NativeMonetPolicy* before_materials = kh_monet_policy;
        const uint64_t expiry = state.expires_at;
        const uint64_t revision = kh_monet_selection_revision;
        assert(ApplyRequest(&state, material_enable, 2u) == materials);
        assert(kh_theme_materials_enabled == (materials ? 1u : 0u));
        assert(kh_monet_policy == before_materials && state.expires_at == expiry);
        assert(kh_monet_selection_revision == revision);
        const uint64_t material_expiry = state.materials_expires_at;
        assert(ApplyRequest(&state, disable, 3u) == monet);
        assert(state.materials_expires_at == material_expiry);
        assert(kh_theme_materials_enabled == (materials ? 1u : 0u));
        assert(ApplyRequest(&state, material_disable, 4u) == materials);
        assert(kh_theme_materials_enabled == 0u);
        Clear(&state);
    }

    // Only the installer's exact success value grants capability, never an
    // in-progress or failed state borrowed from its internal state machine.
    for (uint32_t uninstalled : {0u, 2u, 3u, UINT32_MAX}) {
        __atomic_store_n(&kh_monet_installed, uninstalled, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_theme_materials_installed, uninstalled, __ATOMIC_RELEASE);
        assert(InstalledCapabilities() == 0u);
    }

    // Recheck at commit as well as in the greeting/dispatcher. No valid policy
    // command can reach an incomplete Monet installation through highlight.
    PolicyState state{};
    __atomic_store_n(&kh_monet_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_theme_materials_installed, 1u, __ATOMIC_RELEASE);
    assert(ApplyRequest(&state, enable, 1u));
    const NativeMonetPolicy* before_rejection = kh_monet_policy;
    const uint64_t expiry = state.expires_at;
    __atomic_store_n(&kh_monet_installed, 0u, __ATOMIC_RELEASE);
    assert(InstalledCapabilities() == kNativeThemeMaterialsCapability);
    assert(!ApplyRequest(&state, enable, 2u));
    assert(!ApplyRequest(&state, disable, 2u));
    PolicyAllocation* rejected = Policy(0xff000001u);
    assert(!CommitPolicy(&state, rejected, 2u));
    free(rejected);
    assert(kh_monet_policy == before_rejection && state.expires_at == expiry);
    assert(ApplyRequest(&state, material_enable, 3u));
    assert(ApplyRequest(&state, material_disable, 4u));
    __atomic_store_n(&kh_monet_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_theme_materials_installed, 0u, __ATOMIC_RELEASE);
    assert(ApplyRequest(&state, enable, 5u));
    Clear(&state);
}

struct InstalledFlagSnapshot {
    const uint32_t monet = kh_monet_installed;
    const uint32_t materials = kh_theme_materials_installed;
    const uint32_t labels = kh_launcher_labels_installed;
    const uint32_t dock = kh_dock_glass_installed;
    const uint32_t glass = kh_launcher_glass_installed;
    const uint32_t search_glass = kh_launcher_search_glass_installed;
    ~InstalledFlagSnapshot() {
        __atomic_store_n(&kh_monet_installed, monet, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_theme_materials_installed, materials, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_launcher_labels_installed, labels, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_dock_glass_installed, dock, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_launcher_search_glass_installed, search_glass, __ATOMIC_RELAXED);
        __atomic_store_n(&kh_launcher_glass_installed, glass, __ATOMIC_RELEASE);
    }
};

void TestDockGlassCapabilitiesAndCommandAdmission() {
    const InstalledFlagSnapshot installed;
    const Bytes enable = DockRequest(1u, 0u, 0u);
    const Bytes disable = DockRequest(0u, 0u, 0u);
    for (uint32_t caps = 0u; caps < 16u; ++caps) {
        for (uint32_t style : {0u, 1u}) {
            PolicyState state{};
            __atomic_store_n(&kh_monet_installed, uint32_t{bool(caps & 1u)}, __ATOMIC_RELEASE);
            __atomic_store_n(&kh_theme_materials_installed, uint32_t{bool(caps & 2u)}, __ATOMIC_RELEASE);
            __atomic_store_n(&kh_launcher_labels_installed, uint32_t{bool(caps & 4u)}, __ATOMIC_RELEASE);
            __atomic_store_n(&kh_dock_glass_installed, uint32_t{bool(caps & 8u)}, __ATOMIC_RELEASE);
            const uint32_t dock_extensions = (caps & 8u)
                    ? kNativeDockLayoutCapability | kNativeDockHorizontalPaddingCapability : 0u;
            assert(InstalledCapabilities() == (caps | dock_extensions));
            assert(kh_monet_policy == nullptr && kh_dock_glass_enabled == 0u);
            const uint64_t revision = kh_dock_glass_revision;
            const uint64_t notified = kh_dock_glass_notified;
            const uint32_t old_style = kh_dock_glass_corner_style;
            const bool dock = (caps & 8u) != 0u;
            assert(ApplyRequest(&state, DockRequest(1u, 0u, style), 1u) == dock);
            assert(kh_dock_glass_enabled == uint32_t{dock});
            assert(kh_dock_glass_corner_style == (dock ? style : old_style));
            // Enabling and changing style together still publish only ONE revision.
            assert(kh_dock_glass_revision == revision + uint64_t{dock});
            assert(state.dock_glass_expires_at ==
                    (dock ? 1u + uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs : 0u));
            assert(ApplyRequest(&state, DockRequest(0u, 0u, style), 2u) == dock);
            assert(kh_dock_glass_enabled == 0u && state.dock_glass_expires_at == 0u);
            assert(kh_dock_glass_corner_q8 == 0u);
            assert(kh_dock_glass_corner_style == (dock ? style : old_style));
            assert(kh_dock_glass_revision == revision + 2u * uint64_t{dock});
            assert(kh_dock_glass_notified == notified && kh_monet_policy == nullptr);
        }
    }
    for (uint32_t not_ready : {2u, UINT32_MAX}) {
        PolicyState state{};
        __atomic_store_n(&kh_dock_glass_installed, not_ready, __ATOMIC_RELEASE);
        const uint64_t revision = kh_dock_glass_revision;
        assert((InstalledCapabilities() & 8u) == 0u);
        assert(!ApplyRequest(&state, enable, 3u) && !ApplyRequest(&state, disable, 3u));
        assert(kh_dock_glass_revision == revision && state.dock_glass_expires_at == 0u);
    }
    puts("Dock capability 8: both corner styles, all 16 combinations and uninstalled/partial admission passed");
}

void TestDockGlassMalformedAndPolicyIsolation() {
    const InstalledFlagSnapshot installed;
    PolicyState state{};
    __atomic_store_n(&kh_monet_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_theme_materials_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_launcher_labels_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_dock_glass_installed, 1u, __ATOMIC_RELEASE);
    assert(CommitPolicy(&state, Policy(0xff123456u), 1u));
    const NativeMonetPolicy* pinned = AcquireNativeMonetPolicy();
    const NativeMonetPolicy policy = *pinned;
    assert(ApplyRequest(&state, Request(2u, 1u, 0u, {}), 2u));
    assert(ApplyRequest(&state, Request(3u, 2u, 0u, {}), 3u));
    const Bytes enable = Request(8u, 1u, 0u, {});
    const Bytes disable = Request(8u, 0u, 0u, {});
    const uint8_t wire[] = {
        0x4b, 0x48, 0x4d, 0x33, 1, 0, 0, 0, 8, 0, 0, 0,
        1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    };
    assert(enable.size() == sizeof(wire) && memcmp(enable.data(), wire, sizeof(wire)) == 0);
    const uint64_t selection = kh_monet_selection_revision;
    const uint32_t history = kh_monet_tint_ever_enabled;
    const uint64_t label_revision = kh_launcher_label_revision;
    const PolicyState before_dock = state;
    assert(ApplyRequest(&state, enable, 4u));
    const uint64_t revision = kh_dock_glass_revision, notified = kh_dock_glass_notified;
    const uint64_t expiry = state.dock_glass_expires_at;
    const uint32_t corner = kh_dock_glass_corner_q8;
    const uint32_t style = kh_dock_glass_corner_style;
    const auto other_features_unchanged = [&] {
        assert(kh_monet_policy == pinned && memcmp(pinned, &policy, sizeof(policy)) == 0);
        assert(state.expiration == before_dock.expiration && state.expires_at == before_dock.expires_at);
        assert(state.retired == before_dock.retired && state.retired_count == before_dock.retired_count);
        assert(state.materials_expires_at == before_dock.materials_expires_at);
        assert(state.labels_expires_at == before_dock.labels_expires_at);
        assert(kh_theme_materials_enabled == 1u && kh_launcher_label_mode == 2u);
        assert(kh_launcher_label_revision == label_revision);
        assert(kh_monet_selection_revision == selection && kh_monet_tint_ever_enabled == history);
        assert(kh_dock_glass_notified == notified);
    };
    const auto rejected_unchanged = [&](const Bytes& bad, uint64_t now = 5u) {
        assert(!ApplyRequest(&state, bad, now));
        assert(kh_dock_glass_enabled == 1u && state.dock_glass_expires_at == expiry);
        assert(kh_dock_glass_corner_q8 == corner && kh_dock_glass_corner_style == style);
        assert(kh_dock_glass_revision == revision);
        other_features_unchanged();
    };
    for (uint32_t corner_style : {0u, 1u}) {
        const Bytes command = DockRequest(1u, 10240u, corner_style);
        for (size_t n = 0u; n < command.size(); ++n)
            rejected_unchanged(Bytes(command.begin(), command.begin() + n));
    }
    for (uint32_t bad_style : {5u, 7u, 512u, 513u, UINT32_MAX}) {
        for (uint32_t enabled : {0u, 1u})
            rejected_unchanged(DockRequest(enabled, 10240u, bad_style));
    }
    for (const Bytes& bad : std::vector<Bytes>{
            Request(8u, 2u, 0u, {}), Request(8u, UINT32_MAX, 0u, {}),
            Request(8u, 1u, 65537u, {}), Request(8u, 1u, UINT32_MAX, {}),
            Request(8u, 1u, 0u, {"com.a", "com.b", "com.c", "com.d", "com.e"})})
        rejected_unchanged(bad);
    for (uint32_t command : {4u, 5u, 6u, 7u, 9u, UINT32_MAX})
        rejected_unchanged(Request(command, 1u, 0u, {}));
    for (size_t offset : {size_t{0}, size_t{4}, size_t{20}}) {
        Bytes bad = enable;
        WriteLe32(bad.data() + offset, offset == 20u ? kNativeMonetMaxPackages + 1u : 0u);
        rejected_unchanged(bad);
    }
    rejected_unchanged(disable, 0u); // Failed clock/deadline cannot commit.
    PolicyAllocation* invalid = AllocatePolicy();
    assert(invalid != nullptr);
    invalid->policy.flags = 8u;
    assert(!Decode(enable, &invalid->policy));
    assert(!CommitPolicy(&state, invalid, 5u));
    free(invalid);
    other_features_unchanged();
    assert(ApplyRequest(&state, disable, 6u));
    assert(kh_dock_glass_enabled == 0u && state.dock_glass_expires_at == 0u);
    assert(kh_dock_glass_revision == revision + 1u);
    other_features_unchanged();
    ReleaseNativeMonetPolicy();
    Clear(&state);
    MaintainPolicy(&state, UINT64_MAX);
    puts("Dock command 8: wire, truncation, corner/style bounds, reserved commands and isolation passed");
}

void TestDockGlassLeaseAndNotificationRevisions() {
    const InstalledFlagSnapshot installed;
    PolicyState state{};
    __atomic_store_n(&kh_dock_glass_installed, 1u, __ATOMIC_RELEASE);
    const uint64_t lease = uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs;
    const uint64_t heartbeat = uint64_t{kNativeMonetKeepaliveMs} * kNanosecondsPerMs;
    const Bytes enable = Request(8u, 1u, 0u, {}), disable = Request(8u, 0u, 0u, {});
    assert(ApplyRequest(&state, disable, 1u));
    const uint64_t revision = kh_dock_glass_revision, saved_notified = kh_dock_glass_notified;
    assert(ApplyRequest(&state, disable, 1u));
    assert(kh_dock_glass_revision == revision && kh_dock_glass_notified == saved_notified);
    assert(ApplyRequest(&state, enable, 2u));
    assert(kh_dock_glass_revision == revision + 1u && state.dock_glass_expires_at == 2u + lease);
    assert(kh_dock_glass_notified == saved_notified);
    // Simulate the UI bridge consuming this generation. The worker must never
    // consume/overwrite it, including on renewal, expiry and capability loss.
    __atomic_store_n(&kh_dock_glass_notified, revision + 1u, __ATOMIC_RELEASE);
    assert(ApplyRequest(&state, enable, 2u + heartbeat));
    const uint64_t expiry = 2u + heartbeat + lease;
    assert(state.dock_glass_expires_at == expiry && kh_dock_glass_revision == revision + 1u);
    MaintainPolicy(&state, 2u + lease);
    MaintainPolicy(&state, expiry - 1u);
    assert(kh_dock_glass_enabled == 1u && kh_dock_glass_revision == revision + 1u);
    assert(NextMaintenanceWake(&state, UINT64_MAX) == expiry);
    MaintainPolicy(&state, expiry);
    assert(kh_dock_glass_enabled == 0u && state.dock_glass_expires_at == 0u);
    assert(kh_dock_glass_revision == revision + 2u);
    MaintainPolicy(&state, expiry + 1u);
    assert(ApplyRequest(&state, disable, expiry + 2u));
    assert(kh_dock_glass_revision == revision + 2u); // Repeated off is not a notification.
    assert(ApplyRequest(&state, enable, expiry + 3u));
    assert(kh_dock_glass_revision == revision + 3u);
    const uint64_t late = state.dock_glass_expires_at + 1u;
    assert(ApplyRequest(&state, enable, late));
    assert(kh_dock_glass_revision == revision + 5u); // Expired off, then newly enabled.
    assert(state.dock_glass_expires_at == late + lease);
    assert(ApplyRequest(&state, disable, late + 1u));
    assert(ApplyRequest(&state, disable, late + 2u));
    assert(kh_dock_glass_revision == revision + 6u);
    assert(ApplyRequest(&state, enable, late + 3u));
    __atomic_store_n(&kh_dock_glass_installed, 0u, __ATOMIC_RELEASE);
    assert(!ApplyRequest(&state, enable, late + 4u));
    MaintainPolicy(&state, late + 4u);
    assert(kh_dock_glass_enabled == 0u && state.dock_glass_expires_at == 0u);
    assert(kh_dock_glass_revision == revision + 8u);
    MaintainPolicy(&state, late + 5u);
    assert(kh_dock_glass_revision == revision + 8u);
    assert(kh_dock_glass_notified == revision + 1u);
    assert(kh_monet_policy == nullptr);
    __atomic_store_n(&kh_dock_glass_notified, saved_notified, __ATOMIC_RELEASE);
    puts("Dock lease: heartbeat/repeated off stable, boundary/late renewal and uninstall revisions passed");
}

void TestDockGlassIndependentLeaseExpiry() {
    const InstalledFlagSnapshot installed;
    PolicyState state{};
    for (uint32_t* flag : {&kh_monet_installed, &kh_theme_materials_installed,
                           &kh_launcher_labels_installed, &kh_dock_glass_installed})
        __atomic_store_n(flag, 1u, __ATOMIC_RELEASE);
    const uint64_t lease = uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs;
    const uint64_t heartbeat = uint64_t{kNativeMonetKeepaliveMs} * kNanosecondsPerMs;
    const uint64_t revision = kh_dock_glass_revision, notified = kh_dock_glass_notified;
    assert(ApplyRequest(&state, Request(8u, 1u, 0u, {}), 1u));
    assert(ApplyRequest(&state, Request(2u, 1u, 0u, {}), 1u + heartbeat));
    assert(ApplyRequest(&state, Request(3u, 2u, 0u, {}), 2u + heartbeat));
    assert(ApplyRequest(&state, Request(1u, 0xff123456u, 0xffabcdefu, {"com.example"}),
                        3u + heartbeat));
    const NativeMonetPolicy* policy = kh_monet_policy;
    const uint64_t selection = kh_monet_selection_revision;
    assert(state.dock_glass_expires_at == 1u + lease); // Other commands cannot renew Dock.
    assert(NextMaintenanceWake(&state, UINT64_MAX) == 1u + lease);
    assert(NextMaintenanceWake(&state, 1u) == 1u);
    MaintainPolicy(&state, lease);
    assert(kh_dock_glass_enabled == 1u);
    MaintainPolicy(&state, 1u + lease);
    assert(kh_dock_glass_enabled == 0u && kh_dock_glass_revision == revision + 2u);
    assert(kh_theme_materials_enabled == 1u && kh_launcher_label_mode == 2u);
    assert(kh_monet_policy == policy && policy->count == 1u && kh_monet_selection_revision == selection);
    const uint64_t materials_expiry = state.materials_expires_at;
    const uint64_t labels_expiry = state.labels_expires_at, policy_expiry = state.expires_at;
    assert(ApplyRequest(&state, Request(8u, 1u, 0u, {}), 2u + lease));
    const uint64_t dock_expiry = state.dock_glass_expires_at;
    assert(state.materials_expires_at == materials_expiry && state.labels_expires_at == labels_expiry);
    assert(state.expires_at == policy_expiry && kh_monet_policy == policy);
    assert(NextMaintenanceWake(&state, UINT64_MAX) == materials_expiry);
    MaintainPolicy(&state, materials_expiry);
    assert(kh_theme_materials_enabled == 0u && kh_launcher_label_mode == 2u);
    assert(kh_dock_glass_enabled == 1u && kh_dock_glass_revision == revision + 3u);
    MaintainPolicy(&state, labels_expiry);
    assert(kh_launcher_label_mode == 0u && kh_monet_policy == policy && policy->count == 1u);
    assert(kh_dock_glass_enabled == 1u && kh_dock_glass_revision == revision + 3u);
    MaintainPolicy(&state, policy_expiry);
    assert(kh_monet_policy->count == 0u && kh_monet_policy->flags == 1u);
    assert(kh_dock_glass_enabled == 1u && state.dock_glass_expires_at == dock_expiry);
    assert(kh_dock_glass_revision == revision + 3u);
    assert(NextMaintenanceWake(&state, UINT64_MAX) == dock_expiry);
    MaintainPolicy(&state, dock_expiry);
    MaintainPolicy(&state, dock_expiry + 1u);
    assert(kh_dock_glass_enabled == 0u && kh_dock_glass_revision == revision + 4u);
    assert(kh_dock_glass_notified == notified);
    Clear(&state);
    puts("Dock/Monet/material/label leases: independent renewal, expiry and wake ordering passed");
}

void TestDockGlassCornerUpdates() {
    const InstalledFlagSnapshot installed;
    PolicyState state{};
    __atomic_store_n(&kh_dock_glass_installed, 1u, __ATOMIC_RELEASE);
    const uint64_t lease = uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs;
    uint64_t revision = kh_dock_glass_revision;
    const uint64_t notified = kh_dock_glass_notified;
    struct Change { uint32_t enabled, corner; uint64_t revisions; };
    uint64_t now = 1u;
    for (const Change& change : {
            Change{0u, 1u, 0u}, Change{0u, 65536u, 0u}, Change{0u, 0u, 0u},
            Change{1u, 32768u, 1u}, // Enable and corner change publish ONE revision.
            Change{1u, 32768u, 0u}, // Unchanged heartbeat renews only the lease.
            Change{1u, 65536u, 1u}, // Largest allowed logical corner.
            Change{1u, 0u, 1u},     // Native fallback while enabled is a change.
            Change{1u, 1u, 1u}, Change{1u, 1u, 0u},
            Change{0u, 65536u, 1u}, // Disable and corner change still publish ONE.
            Change{0u, 0u, 0u}}) {
        assert(ApplyRequest(&state, Request(8u, change.enabled, change.corner, {}), now));
        revision += change.revisions;
        assert(kh_dock_glass_enabled == change.enabled);
        assert(kh_dock_glass_corner_q8 == change.corner);
        assert(kh_dock_glass_revision == revision && kh_dock_glass_notified == notified);
        assert(state.dock_glass_expires_at == (change.enabled ? now + lease : 0u));
        ++now;
    }
    assert(ApplyRequest(&state, Request(8u, 1u, 4096u, {}), now));
    ++revision;
    assert(kh_dock_glass_revision == revision && kh_dock_glass_corner_q8 == 4096u);
    const uint64_t expiry = state.dock_glass_expires_at;
    MaintainPolicy(&state, expiry - 1u);
    assert(kh_dock_glass_corner_q8 == 4096u && kh_dock_glass_revision == revision);
    MaintainPolicy(&state, expiry);
    assert(kh_dock_glass_enabled == 0u && kh_dock_glass_corner_q8 == 4096u);
    assert(kh_dock_glass_corner_style == 0u);
    assert(kh_dock_glass_revision == ++revision && state.dock_glass_expires_at == 0u);
    MaintainPolicy(&state, expiry + 1u);
    assert(kh_dock_glass_revision == revision);
    assert(ApplyRequest(&state, Request(8u, 1u, 65536u, {}), expiry + 2u));
    assert(kh_dock_glass_revision == ++revision && kh_dock_glass_corner_q8 == 65536u);
    __atomic_store_n(&kh_dock_glass_installed, 0u, __ATOMIC_RELEASE);
    MaintainPolicy(&state, expiry + 3u);
    assert(kh_dock_glass_enabled == 0u && kh_dock_glass_corner_q8 == 65536u);
    assert(kh_dock_glass_corner_style == 0u);
    assert(kh_dock_glass_revision == ++revision && state.dock_glass_expires_at == 0u);
    assert(kh_dock_glass_notified == notified);
    puts("Dock corner Q8: endpoints, off updates, one revision per visible change and expiry retention passed");
}

void TestDockGlassCornerStylesAndExpiryRetention() {
    const InstalledFlagSnapshot installed;
    PolicyState state{};
    __atomic_store_n(&kh_dock_glass_installed, 1u, __ATOMIC_RELEASE);
    const uint64_t lease = uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs;
    const uint64_t heartbeat = uint64_t{kNativeMonetKeepaliveMs} * kNanosecondsPerMs;
    const uint64_t notified = kh_dock_glass_notified;
    assert(ApplyRequest(&state, DockRequest(0u, 0u, 0u), 1u));
    uint64_t revision = kh_dock_glass_revision;
    struct Change { uint32_t enabled, corner, style; uint64_t revisions; };
    uint64_t now = 2u;
    for (const Change& change : {
            Change{1u, 10240u, 1u, 1u}, // Enable, radius and style: one revision.
            Change{1u, 10240u, 1u, 0u}, // Identical renewal.
            Change{1u, 10240u, 0u, 1u}, // Style only, Recents -> Rounded.
            Change{1u, 10240u, 0u, 0u},
            Change{1u, 10240u, 1u, 1u}, // Style only, Rounded -> Recents.
            Change{1u, 12800u, 0u, 1u}, // Radius and style together: one revision.
            Change{0u, 12800u, 1u, 1u}, // Disable and style together: one revision.
            Change{0u, 12800u, 1u, 0u},
            Change{0u, 12800u, 0u, 1u}, // Style-only off change is still a revision.
            Change{0u, 16384u, 0u, 0u}, // Off radius change needs no repaint.
            Change{0u, 16384u, 1u, 1u},
            Change{0u, 16384u, 1u, 0u}}) {
        assert(ApplyRequest(&state, DockRequest(change.enabled, change.corner, change.style), now));
        revision += change.revisions;
        assert(kh_dock_glass_enabled == change.enabled);
        assert(kh_dock_glass_corner_q8 == change.corner);
        assert(kh_dock_glass_corner_style == change.style);
        assert(kh_dock_glass_revision == revision && kh_dock_glass_notified == notified);
        assert(state.dock_glass_expires_at == (change.enabled ? now + lease : 0u));
        ++now;
    }
    for (uint32_t style : {0u, 1u}) {
        const uint32_t radius = style == 0u ? 10240u : 12800u;
        const Bytes enable = DockRequest(1u, radius, style);
        assert(ApplyRequest(&state, enable, now));
        assert(kh_dock_glass_revision == ++revision);
        now += heartbeat;
        assert(ApplyRequest(&state, enable, now));
        const uint64_t expiry = now + lease;
        assert(state.dock_glass_expires_at == expiry);
        assert(kh_dock_glass_revision == revision); // Renewal moves only the deadline.
        MaintainPolicy(&state, expiry - 1u);
        assert(kh_dock_glass_enabled == 1u && kh_dock_glass_revision == revision);
        MaintainPolicy(&state, expiry);
        assert(kh_dock_glass_enabled == 0u && state.dock_glass_expires_at == 0u);
        assert(kh_dock_glass_corner_q8 == radius && kh_dock_glass_corner_style == style);
        assert(kh_dock_glass_revision == ++revision);
        MaintainPolicy(&state, expiry + 1u);
        assert(kh_dock_glass_revision == revision);
        assert(ApplyRequest(&state, DockRequest(0u, radius, style), expiry + 2u));
        assert(kh_dock_glass_revision == revision); // Off with retained geometry is quiet.
        assert(kh_dock_glass_notified == notified && kh_monet_policy == nullptr);
        now = expiry + 3u;
    }
    puts("Dock styles 0/1: single revisions, quiet renewals and radius/style retention at expiry passed");
}

void TestDockGlassWithFullRetirementQueue() {
    const InstalledFlagSnapshot installed;
    PolicyState state{};
    __atomic_store_n(&kh_monet_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_dock_glass_installed, 1u, __ATOMIC_RELEASE);
    assert(CommitPolicy(&state, Policy(0xff123456u), 1u));
    const NativeMonetPolicy* pinned = AcquireNativeMonetPolicy();
    for (uint32_t i = 0u; i < kMaxRetiredPolicies; ++i)
        assert(CommitPolicy(&state, Policy(0xff000010u + i), 2u + i));
    const NativeMonetPolicy* current = kh_monet_policy;
    PolicyAllocation* expiration = state.expiration;
    const uint64_t policy_expiry = state.expires_at, selection = kh_monet_selection_revision;
    assert(ApplyRequest(&state, Request(8u, 1u, 0u, {}), 100u));
    assert(ApplyRequest(&state, Request(8u, 0u, 0u, {}), 101u));
    assert(state.retired_count == kMaxRetiredPolicies && kh_monet_policy == current);
    assert(state.expiration == expiration && state.expires_at == policy_expiry);
    assert(kh_monet_selection_revision == selection && pinned->foreground == 0xff123456u);
    ReleaseNativeMonetPolicy();
    Clear(&state);
    puts("Dock commands remain independent of full policy retirement capacity");
}

void TestLauncherGlassAdmissionAndRefresh() {
    const InstalledFlagSnapshot installed;
    __atomic_store_n(&kh_launcher_search_glass_installed, 0u, __ATOMIC_RELAXED);
    assert(RequestLauncherGlassStyle(0u));
    for (uint32_t caps = 0u; caps < 32u; ++caps) {
        __atomic_store_n(&kh_monet_installed, uint32_t{bool(caps & 1u)}, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_theme_materials_installed, uint32_t{bool(caps & 2u)}, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_launcher_labels_installed, uint32_t{bool(caps & 4u)}, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_dock_glass_installed, uint32_t{bool(caps & 8u)}, __ATOMIC_RELEASE);
        __atomic_store_n(&kh_launcher_glass_installed, uint32_t{bool(caps & 16u)}, __ATOMIC_RELEASE);
        const uint32_t dock_extensions = (caps & 8u)
                ? kNativeDockLayoutCapability | kNativeDockHorizontalPaddingCapability : 0u;
        assert(InstalledCapabilities() == (caps | dock_extensions));
        for (uint32_t mode = 0u; mode <= 2u; ++mode) {
            PolicyState state{};
            const bool available = (caps & 16u) != 0u;
            const auto before = __atomic_load_n(&kh_launcher_glass_control, __ATOMIC_ACQUIRE);
            const Bytes request = Request(9u, mode, 0u, {});
            assert(request.size() == 24u);
            assert(ApplyRequest(&state, request, 1u) == available);
            const auto after = __atomic_load_n(&kh_launcher_glass_control, __ATOMIC_ACQUIRE);
            assert(after == (available ? uint64_t{mode | (mode << 2u)} : before));
            assert(state.launcher_glass_expires_at ==
                    (available && mode ? 1u + uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs : 0u));
            const auto expiry = state.launcher_glass_expires_at;
            const auto revision = kh_glass_material_revision;
            assert(!ApplyRequest(&state, Request(10u, 9u, 0u, {}), 2u));
            assert(kh_launcher_glass_control == after && kh_glass_material_revision == revision);
            assert(state.launcher_glass_expires_at == expiry);
            assert(kh_monet_policy == nullptr);
            assert(RequestLauncherGlassStyle(0u));
        }
    }
    __atomic_store_n(&kh_launcher_glass_installed, 1u, __ATOMIC_RELEASE);
    PolicyState state{};
    assert(ApplyRequest(&state, Request(9u, 1u, 0u, {}), 1u));
    assert(ApplyRequest(&state, Request(9u, 2u, 0u, {}), 2u));
    assert((kh_launcher_glass_control & 3u) == 2u);
    const uint64_t control = kh_launcher_glass_control;
    const uint64_t expiry = state.launcher_glass_expires_at;
    const auto rejects = [&](const Bytes& bad) {
        assert(!ApplyRequest(&state, bad, 3u));
        assert(kh_launcher_glass_control == control);
        assert(state.launcher_glass_expires_at == expiry);
    };
    const Bytes valid = Request(9u, 1u, 0u, {});
    for (size_t length = 0u; length < valid.size(); ++length)
        rejects(Bytes(valid.begin(), valid.begin() + length));
    for (uint32_t invalid : {3u, 255u, UINT32_MAX}) rejects(Request(9u, invalid, 0u, {}));
    rejects(Request(9u, 1u, 1u, {}));
    rejects(Request(9u, 1u, 0u, {"com.example"}));
    assert(ApplyRequest(&state, Request(9u, 2u, 0u, {}), 4u));
    assert(kh_launcher_glass_control == control);
    const uint64_t renewed = state.launcher_glass_expires_at;
    assert(renewed > expiry);
    MaintainPolicy(&state, renewed);
    assert(state.launcher_glass_expires_at == 0u);
    assert(kh_launcher_glass_control == 0u);
    puts("Glass capability 16: all 32 masks, three modes, wire rejection and expiry passed");
}

void TestLauncherGlassLeaseIsolation(uint32_t command) {
    const InstalledFlagSnapshot installed;
    PolicyState state{};
    __atomic_store_n(&kh_launcher_search_glass_installed, 1u, __ATOMIC_RELAXED);
    __atomic_store_n(&kh_launcher_glass_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_theme_materials_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_launcher_labels_installed, 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&kh_dock_glass_installed, 1u, __ATOMIC_RELEASE);
    assert(CommitPolicy(&state, Policy(0xff123456u), 1u));
    assert(ApplyRequest(&state, Request(2u, 1u, 0u, {}), 2u));
    assert(ApplyRequest(&state, Request(3u, 2u, 0u, {}), 3u));
    assert(ApplyRequest(&state, DockRequest(1u, 1024u, 1u), 4u));
    const auto policy = kh_monet_policy;
    const PolicyState before = state;
    const auto dock_revision = kh_dock_glass_revision;
    const auto label_revision = kh_launcher_label_revision;
    assert(ApplyRequest(&state, Request(command, command == 10u ? 6u : 1u, 0u, {}), 5u));
    assert(kh_monet_policy == policy);
    assert(state.expiration == before.expiration && state.expires_at == before.expires_at);
    assert(state.materials_expires_at == before.materials_expires_at);
    assert(state.labels_expires_at == before.labels_expires_at);
    assert(state.dock_glass_expires_at == before.dock_glass_expires_at);
    assert(kh_dock_glass_revision == dock_revision && kh_launcher_label_revision == label_revision);
    assert(NextMaintenanceWake(&state, UINT64_MAX) == before.expires_at);
    const auto glass_expiry = state.launcher_glass_expires_at;
    assert(ApplyRequest(&state, Request(2u, 1u, 0u, {}), 6u));
    assert(state.launcher_glass_expires_at == glass_expiry);
    __atomic_store_n(&kh_launcher_glass_installed, 0u, __ATOMIC_RELEASE);
    MaintainPolicy(&state, 7u);
    assert(kh_launcher_glass_control == 0u && state.launcher_glass_expires_at == 0u);
    assert(kh_monet_policy == policy && kh_dock_glass_enabled == 1u);
    DisableDockGlass();
    SetLauncherLabelMode(0u);
    __atomic_store_n(&kh_theme_materials_enabled, 0u, __ATOMIC_RELEASE);
    Clear(&state);
    puts("Glass lease remains independent of Monet, highlight, labels and Dock geometry");
}

void TestLauncherGlassPairCommands() {
    const InstalledFlagSnapshot installed;
    __atomic_store_n(&kh_launcher_search_glass_installed, 1u, __ATOMIC_RELAXED);
    assert(RequestLauncherGlassStyle(0u));
    const uint64_t lease = uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs;
    const uint64_t notified = kh_glass_material_notified;
    for (uint32_t ready : {0u, 1u, 2u, UINT32_MAX}) {
        __atomic_store_n(&kh_launcher_glass_installed, ready, __ATOMIC_RELEASE);
        assert(bool(InstalledCapabilities() & kNativeLauncherGlassPairCapability) == (ready == 1u));
        assert(bool(InstalledCapabilities() & kNativeLauncherGlassCapability) == (ready == 1u));
        for (uint32_t dock = 0u; dock <= 2u; ++dock) {
            for (uint32_t search = 0u; search <= 2u; ++search) {
                PolicyState state{};
                const uint32_t pair = dock | (search << 2u);
                const uint64_t revision = kh_glass_material_revision;
                const Bytes request = Request(10u, pair, 0u, {});
                assert(request.size() == 24u);
                assert(ApplyRequest(&state, request, 10u) == (ready == 1u));
                const bool changed = ready == 1u && pair != 0u;
                assert(kh_launcher_glass_control == (ready == 1u ? pair : 0u));
                assert(kh_glass_material_revision == revision + uint64_t{changed});
                assert(state.launcher_glass_expires_at == (changed ? 10u + lease : 0u));
                assert(ApplyRequest(&state, request, 20u) == (ready == 1u));
                assert(kh_glass_material_revision == revision + uint64_t{changed});
                const uint64_t expiry = changed ? 20u + lease : 0u;
                assert(state.launcher_glass_expires_at == expiry);
                assert(kh_monet_policy == nullptr && kh_glass_material_notified == notified);
                if (changed) {
                    assert(NextMaintenanceWake(&state, UINT64_MAX) == expiry);
                    MaintainPolicy(&state, expiry - 1u);
                    assert(kh_launcher_glass_control == pair);
                    MaintainPolicy(&state, expiry);
                    assert(kh_launcher_glass_control == 0u);
                    assert(kh_glass_material_revision == revision + 2u);
                    MaintainPolicy(&state, expiry + 1u);
                    assert(kh_glass_material_revision == revision + 2u);
                }
                assert(state.launcher_glass_expires_at == 0u);
            }
        }
    }
    __atomic_store_n(&kh_launcher_glass_installed, 1u, __ATOMIC_RELEASE);
    PolicyState state{};
    assert(ApplyRequest(&state, Request(10u, 9u, 0u, {}), 100u)); // Dock light/search dark.
    uint64_t revision = kh_glass_material_revision;
    const uint64_t expiry = state.launcher_glass_expires_at;
    const auto rejects = [&](const Bytes& bad, uint64_t now = 101u) {
        assert(!ApplyRequest(&state, bad, now));
        assert(kh_launcher_glass_control == 9u);
        assert(kh_glass_material_revision == revision);
        assert(state.launcher_glass_expires_at == expiry);
    };
    const Bytes valid = Request(10u, 6u, 0u, {});
    for (size_t length = 0u; length < valid.size(); ++length)
        rejects(Bytes(valid.begin(), valid.begin() + length));
    for (uint32_t pair = 0u; pair < 256u; ++pair) {
        const bool expected = pair < 16u && (pair & 3u) < 3u && (pair >> 2u) < 3u;
        assert(ValidLauncherGlassPolicy(pair) == expected);
        if (!expected) {
            assert(!RequestLauncherGlassPolicy(pair));
            rejects(Request(10u, pair, 0u, {}));
        }
    }
    for (uint32_t bit = 4u; bit < 32u; ++bit) rejects(Request(10u, 1u << bit, 0u, {}));
    assert(!RequestLauncherGlassPolicy(UINT32_MAX));
    rejects(Request(10u, UINT32_MAX, 0u, {}));
    rejects(Request(10u, 6u, 1u, {}));
    rejects(Request(10u, 6u, 0u, {"com.example"}));
    rejects(valid, 0u); // Commit deadline expired.
    assert(ApplyRequest(&state, Request(9u, 1u, 0u, {}), 102u));
    assert(kh_launcher_glass_control == 5u && kh_glass_material_revision == ++revision);
    assert(ApplyRequest(&state, Request(10u, 5u, 0u, {}), 103u));
    assert(kh_glass_material_revision == revision); // Same pair through either API.
    assert(ApplyRequest(&state, Request(10u, 4u, 0u, {}), 104u)); // Search only.
    assert(kh_glass_material_revision == ++revision);
    assert(state.launcher_glass_expires_at == 104u + lease);
    __atomic_store_n(&kh_launcher_glass_installed, 0u, __ATOMIC_RELEASE);
    MaintainPolicy(&state, 105u);
    assert(kh_launcher_glass_control == 0u && state.launcher_glass_expires_at == 0u);
    assert(kh_glass_material_revision == ++revision);
    assert(kh_glass_material_notified == notified);
    __atomic_store_n(&kh_launcher_glass_installed, 1u, __ATOMIC_RELEASE);
    assert(ApplyRequest(&state, Request(10u, 6u, 0u, {}), 106u));
    assert(kh_glass_material_revision == ++revision);
    assert(ApplyRequest(&state, Request(10u, 0u, 0u, {}), 107u));
    assert(kh_glass_material_revision == ++revision);
    assert(kh_launcher_glass_control == 0u && state.launcher_glass_expires_at == 0u);
    assert(ApplyRequest(&state, Request(9u, 0u, 0u, {}), 108u));
    assert(kh_glass_material_revision == revision);
    puts("Glass pair: nine policies, capability gating, validation, renewals, shared legacy lease passed");
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

void TestLauncherLabelCommands() {
    PolicyState state{};
    const uint32_t old_monet = kh_monet_installed, old_materials = kh_theme_materials_installed;
    for (uint32_t caps = 0; caps < 8; ++caps) {
        kh_monet_installed = (caps & 1) != 0;
        kh_theme_materials_installed = (caps & 2) != 0;
        kh_launcher_labels_installed = (caps & 4) != 0;
        assert(InstalledCapabilities() == caps);
        for (uint32_t mode = 0; mode < 3; ++mode) {
            kh_launcher_label_mode = 0;
            const uint64_t revision = kh_launcher_label_revision;
            const auto* policy = kh_monet_policy;
            assert(ApplyRequest(&state, Request(3u, mode, 0, {}), 1u) == bool(caps & 4));
            assert(kh_launcher_label_mode == ((caps & 4) ? mode : 0));
            const uint64_t changed = bool(caps & 4) && mode != 0;
            assert(kh_launcher_label_revision == revision + changed);
            assert(ApplyRequest(&state, Request(3u, mode, 0, {}), 1u) == bool(caps & 4));
            assert(kh_launcher_label_revision == revision + changed); // heartbeat
            assert(kh_monet_policy == policy);
            assert(!ApplyRequest(&state, Request(3u, 3u, 0, {}), 2u));
            assert(!ApplyRequest(&state, Request(3u, mode, 1u, {}), 2u));
            MaintainPolicy(&state, uint64_t(kNativeMonetExpiryMs)*1000000u + 1);
            assert(kh_launcher_label_mode == 0);
            assert(kh_launcher_label_revision == revision + 2*changed); // expiry
        }
    }
    kh_launcher_labels_installed = 0;
    kh_monet_installed = old_monet; kh_theme_materials_installed = old_materials;
}

int main() {
    assert(!StartNativeMonetPolicyServer());  // This host is not com.miui.home.
    assert(__atomic_load_n(&kh_monet_policy, __ATOMIC_ACQUIRE) == nullptr);
    assert(InstalledCapabilities() == 0u);
    assert(kh_dock_glass_corner_style == 1u); // New-process default is Recents.
    TestPeerIdentityPredicate();
    TestIdleWithoutServiceStaysNull();
    TestWireAndLimits();
    __atomic_store_n(&kh_monet_installed, 1u, __ATOMIC_RELEASE);
    TestTintEnableHistory();
    TestLeaseAndRetirement();
    TestBoundedRetirementStillExpires();
    TestSelectionNotificationRevisions();
    TestMaterialCommandAdmissionAndPolicyIsolation();
    TestMaterialLeaseRenewalAndMonetOff();
    TestMaterialCommandDoesNotConsumePolicyRetirementCapacity();
    TestInstalledCapabilitiesAndCommandAdmission();
    TestLauncherLabelCommands();
    TestDockGlassCapabilitiesAndCommandAdmission();
    TestDockGlassMalformedAndPolicyIsolation();
    TestDockGlassLeaseAndNotificationRevisions();
    TestDockGlassIndependentLeaseExpiry();
    TestDockGlassCornerUpdates();
    TestDockGlassCornerStylesAndExpiryRetention();
    TestDockGlassWithFullRetirementQueue();
    {
        InstalledFlagSnapshot flags;
        __atomic_store_n(&kh_dock_glass_installed,1u,__ATOMIC_RELEASE);
        assert((InstalledCapabilities() & kNativeDockHorizontalPaddingCapability) != 0u);
        PolicyState extended{};
        for(uint32_t style=0;style<=4;++style)
            for(uint32_t padding : {0u,6u,18u,32u})
                for(int offset : {-48,0,24,96}) {
                    const uint32_t options=style | ((padding+1u)<<3u) |
                            (style==4u ? 24u<<9u : 0u) |
                            (uint32_t(uint8_t(offset))<<16u);
                    assert(ApplyRequest(&extended,DockRequest(1u,15360u,options),1u));
                    assert(kh_dock_glass_corner_style==options);
                    const uint64_t revision=kh_dock_glass_revision;
                    assert(ApplyRequest(&extended,DockRequest(1u,15360u,options),2u));
                    assert(kh_dock_glass_revision==revision);
                }
        for (int delta = -16; delta <= 24; ++delta) {
            const uint32_t options = 1u | ((uint32_t(delta) & 63u) << 24u);
            assert(ApplyRequest(&extended,DockRequest(1u,15360u,options),3u));
            assert(kh_dock_glass_corner_style==options);
        }
        for(uint32_t invalid : {25u<<24u,47u<<24u,1u<<30u,1u<<31u,
                               5u,34u<<3u,65u<<9u,1u<<9u,97u<<16u,207u<<16u}) {
            const uint32_t prior=kh_dock_glass_corner_style;
            assert(!ApplyRequest(&extended,DockRequest(1u,15360u,invalid),3u));
            assert(kh_dock_glass_corner_style==prior);
        }
        puts("Dock extended geometry: 80 existing choices, 41 horizontal deltas, quiet renewals and invalid words rejected");
    }
    TestLauncherGlassAdmissionAndRefresh();
    TestLauncherGlassLeaseIsolation(9u);
    TestLauncherGlassLeaseIsolation(10u);
    TestLauncherGlassPairCommands();
    TestConcurrentReaders();
    puts("monet identity, capabilities, command isolation, leases and reader lifetime tests passed");
}

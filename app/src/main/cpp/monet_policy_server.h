// SPDX-License-Identifier: Apache-2.0
// Independently authored for KeiHome, following MiuiBackGestureHook's exact
// process/sender authentication and explicit native lifetime ownership principles.
#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint32_t kNativeMonetMagic = 0x334d484bu;
constexpr uint32_t kNativeMonetProtocolVersion = 1u;
constexpr uint32_t kNativeMonetProfileVersion = 1u;
constexpr uint32_t kNativeMonetMaxPackages = 512u;
constexpr uint32_t kNativeMonetMaxPackageLength = 255u;
constexpr uint32_t kNativeMonetIoTimeoutMs = 1000u;
constexpr uint32_t kNativeMonetKeepaliveMs = 15000u;
constexpr uint32_t kNativeMonetExpiryMs = 45000u;

struct NativeMonetPolicyEntry {
    uint32_t len;
    char name[256];
};

// Published snapshots are immutable. Entries contain only owned ASCII bytes,
// never Dart pointers/handles. flags == 0 bypasses policy; flags == 1 enables it.
// count is a deny-list length. After expiry count becomes zero while flags and
// accepted colors remain: the conversion bridge MUST also compare background
// with the live selected background before using the retained colors.
struct NativeMonetPolicy {
    uint32_t flags;
    uint32_t foreground;
    uint32_t background;
    uint32_t count;
    NativeMonetPolicyEntry entries[kNativeMonetMaxPackages];
};

static_assert(offsetof(NativeMonetPolicy, flags) == 0u);
static_assert(offsetof(NativeMonetPolicy, foreground) == 4u);
static_assert(offsetof(NativeMonetPolicy, background) == 8u);
static_assert(offsetof(NativeMonetPolicy, count) == 12u);
static_assert(offsetof(NativeMonetPolicy, entries) == 16u);
static_assert(offsetof(NativeMonetPolicyEntry, name) == 4u);
static_assert(sizeof(NativeMonetPolicyEntry) == 260u);
static_assert(sizeof(NativeMonetPolicy) == 16u + 512u * 260u);

extern "C" {
extern NativeMonetPolicy* kh_monet_policy;
extern uint32_t kh_monet_readers;
}

// Reader ABI, including the null-pointer path:
//   1. Atomically increment kh_monet_readers (acquire/release RMW), then DMB ISH.
//   2. LDAR kh_monet_policy; read only this snapshot while counted.
//   3. Release-decrement kh_monet_readers after the LAST snapshot access.
// The increment must precede the pointer load; an LDAR pointer load alone is
// insufficient. Never keep a snapshot/entry pointer after decrementing. These
// helpers express the same contract for C++ callers and host lifetime tests.
inline const NativeMonetPolicy* AcquireNativeMonetPolicy() {
    __atomic_fetch_add(&kh_monet_readers, uint32_t{1}, __ATOMIC_ACQ_REL);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    return __atomic_load_n(&kh_monet_policy, __ATOMIC_ACQUIRE);
}

inline void ReleaseNativeMonetPolicy() {
    __atomic_fetch_sub(&kh_monet_readers, uint32_t{1}, __ATOMIC_RELEASE);
}

// Call only from the target libapp child AFTER the caller has validated and
// installed its native hooks. This function additionally requires argv[0] to
// equal com.miui.home; it never starts in hyos_spawner or injects/loads code.
// Idempotent for this process. Failure leaves an initially null policy null,
// so hooks must preserve original behavior whenever kh_monet_policy is null.
// The detached worker owns its descriptors for the process lifetime; no signal,
// restart, refresh command, unload, or standalone deployment path is provided.
//
// Abstract AF_UNIX/SOCK_STREAM name: os.kei.hyper.home.monet.<getuid()>.
// Only uid 1000 AND SELinux peer context u:r:system_server:s0 are accepted.
// One greeting/request/ACK per connection, all uint32 words little-endian:
//   greeting: magic, protocolVersion, getuid(), profileVersion
//   request:  magic, protocolVersion, flags, fgARGB, bgARGB, count
//             then count repetitions of length + ASCII package bytes
//   ACK:      magic, protocolVersion, status (0 committed, 1 rejected)
// flags 0 requires count 0. flags 1 requires opaque colors. Other flags fail.
// An authenticated complete policy every 15 s renews the 45 s deny-list lease;
// no separate heartbeat/refresh opcode exists. Bad/partial requests never renew.
bool StartNativeMonetPolicyServer();

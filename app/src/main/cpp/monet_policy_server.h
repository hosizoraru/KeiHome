// SPDX-License-Identifier: Apache-2.0
// Independently authored for KeiHome, following MiuiBackGestureHook's exact
// process/sender authentication and explicit native lifetime ownership principles.
#pragma once

#include <stddef.h>
#include <stdint.h>

constexpr uint32_t kNativeMonetMagic = 0x334d484bu;
constexpr uint32_t kNativeMonetProtocolVersion = 1u;
// Binder channel-acquisition ABI only. Socket greeting word 4 is a capability mask.
constexpr uint32_t kNativeMonetProfileVersion = 1u;
constexpr uint32_t kNativeMonetCapability = 1u;
constexpr uint32_t kNativeThemeMaterialsCapability = 2u;
constexpr uint32_t kNativeLauncherLabelsCapability = 4u;
constexpr uint32_t kNativeDockGlassCapability = 8u;
constexpr uint32_t kNativeDockLayoutCapability = 32u;
constexpr uint32_t kNativeLauncherGlassCapability = 16u;
constexpr uint32_t kNativeLauncherGlassPairCapability = 64u;
constexpr uint32_t kNativeDockHorizontalPaddingCapability = 128u;
constexpr uint32_t kNativeWallpaperReconnectCapability = 256u;
constexpr uint32_t kNativeMonetMaxPackages = 512u;
constexpr uint32_t kNativeMonetMaxPackageLength = 255u;
constexpr uint32_t kNativeMonetIoTimeoutMs = 2000u;
constexpr uint32_t kNativeMonetKeepaliveMs = 15000u;
constexpr uint32_t kNativeMonetExpiryMs = 45000u;
constexpr uint32_t kNativeThemeMaterialsCommand = 2u;
constexpr uint32_t kNativeLauncherLabelsCommand = 3u;
constexpr uint32_t kNativeDockGlassCommand = 8u;
constexpr uint32_t kNativeLauncherGlassCommand = 9u;
constexpr uint32_t kNativeLauncherGlassPairCommand = 10u;
// Command 8 word 4: logical dp * 256; zero selects the native fallback.
constexpr uint32_t kNativeDockGlassMaxCornerQ8 = 65536u;
// Command 8 word 5 is a style, never a trailing package count.
constexpr uint32_t kNativeDockGlassRoundedStyle = 0u;
constexpr uint32_t kNativeDockGlassRecentsStyle = 1u;

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
// Native process-lifetime history, not a consumed notification generation.
// Atomically set before the first enabled publication; never reset on policy
// withdrawal, expiry, disconnect or API-102 Java hot reload. Equality may use
// stock behavior only while this remains zero. No Dart references are stored.
extern uint32_t kh_monet_tint_ever_enabled;
extern uint64_t kh_monet_selection_revision;
extern uint64_t kh_monet_selection_notified;
// Owned here, written by the Monet installer: release-store 1 only AFTER all
// required Monet hooks succeed and BEFORE starting the policy server. A failed
// or partial install leaves this zero even when highlight starts the transport.
extern uint32_t kh_monet_installed;
extern uint32_t kh_launcher_labels_installed;
extern uint32_t kh_launcher_label_mode;
extern uint64_t kh_launcher_label_revision;
extern uint64_t kh_launcher_label_notified;
// Installer publishes installed only after rendering AND native UI invalidation
// are ready. The server owns enabled/revision; the UI bridge owns notified.
// Primitive process-lifetime state only; no Java or Dart references.
extern uint32_t kh_dock_glass_installed;
extern uint32_t kh_dock_glass_enabled;
extern uint32_t kh_dock_glass_corner_q8;
extern uint32_t kh_dock_glass_corner_style;
extern uint64_t kh_dock_glass_revision;
extern uint64_t kh_dock_glass_notified;
// Independent of NativeMonetPolicy and its Dart ABI. The highlight installer
// release-stores installed=1 ONLY after its validated bridge is active. The
// server release-stores enabled; the bridge acquire-loads it on use. Both start
// disabled, and only an authenticated material command renews the enable lease.
extern uint32_t kh_theme_materials_installed;
extern uint32_t kh_theme_materials_enabled;
extern uint32_t kh_wallpaper_reconnect_installed;
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
// The worker obtains a socketpair endpoint through a narrowly scoped private
// IPackageManager Binder transaction. Java authenticates the launcher caller
// before transferring it. There is no listening socket or SELinux policy edit.
// Only a pair created by uid 1000 AND SELinux context u:r:system_server:s0 is
// accepted here. Closing the Java owner reconnects to the new hot-reload owner.
// One greeting/request/ACK per connection, all uint32 words little-endian:
//   greeting: magic, protocolVersion, getuid(), capabilities
//             bits: 1=Monet, 2=highlight, 4=labels, 8=Dock, 16=glass style,
//                   32=Dock layout, 64=independent Dock/search glass
//             0 is unavailable; unknown bits are rejected by the client
//   request:  magic, protocolVersion, flags, fgARGB, bgARGB, count
//             then count repetitions of length + ASCII package bytes
//   ACK:      magic, protocolVersion, status (0 committed, 1 rejected)
// flags 0 requires count 0. flags 1 requires opaque colors. Both require an
// installed Monet feature, rechecked at commit. Readiness requires capability 1.
// flags 2 is a separate material command: fg=0/1, bg=count=0; it never publishes
// a NativeMonetPolicy. It is ACKed only with the highlight bridge installed.
// An authenticated complete policy every 15 s renews the 45 s deny-list lease;
// an enabled material command independently renews a 45 s material lease, whose
// expiry clears enabled. Bad/partial requests never renew either lease. Legacy
// servers reject flags 2 without withdrawing or changing their Monet policy.
// flags 8 independently sets Dock glass: fg=0/1, bg=logical radius dp*256,
// word 5=corner style (0=rounded, 1=Recents/default); values >1 are rejected.
// This is exactly 24 bytes, with no body. An installed Dock bridge is required.
// Enabled commands renew its own 45 s lease. Enabled or style changes and enabled
// radius changes advance revision. Expiry/worker failure only disables, retaining
// the last radius and style; unchanged renewals do not advance revision;
// the native UI event bridge consumes that revision at a safe Dart boundary.
// flags 9 selects the launcher's native glass pipeline: fg=0 system, 1 classic,
// 2 modern; bg=count=0. Its independent lease restores system selection on
// expiry. A request is accepted before painting; a mode changes only between
// complete admitted render invocations, never halfway through a glass pass.
// Legacy flags 9 sets BOTH Dock/folder and search styles.
// flags 10 packs effective Dock style in fg bits 0..1 and effective search style
// in bits 2..3, each 0..2. All high bits and bg/count must be zero. Requires
// capability 64, advertised with 16 only after the full glass install (including
// resolved search). It shares command 9's lease/ownership/ACK; expiry restores
// both roles to system. Java resolves any follow-Dock preference before sending.
bool StartNativeMonetPolicyServer();

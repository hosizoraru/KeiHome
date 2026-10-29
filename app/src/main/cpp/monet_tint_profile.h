// SPDX-License-Identifier: Apache-2.0
#pragma once

// Constants proven against libapp.so build id 4f1bdaed62b77ed2de7d3ebc34e453ca.
// Shared with the preprocessed assembler; these are NOT public Dart VM ABI.
#define KH_TINT_POLICY_FLAGS 0
#define KH_TINT_POLICY_FOREGROUND 4
#define KH_TINT_POLICY_BACKGROUND 8
#define KH_TINT_CONTROLLER_CID 0x0fa4
#define KH_TINT_RX_INT_CID 0x0ef2
#define KH_TINT_MINT_CID 0x003d

#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>
#include "monet_policy_server.h"

// Layout checks for the prefix consumed by the bridges, without defining the
// publisher's variable-length NativeMonetPolicy or its allocation strategy.
struct MonetTintPolicyPrefix {
    uint32_t flags;
    uint32_t foreground;
    uint32_t background;
    uint32_t count;
};
struct MonetTintPolicyEntryLayout {
    uint32_t length;
    char package_name[256];
};
static_assert(offsetof(MonetTintPolicyPrefix, foreground) == 4);
static_assert(offsetof(MonetTintPolicyPrefix, background) == 8);
static_assert(offsetof(MonetTintPolicyPrefix, count) == 12);
static_assert(sizeof(MonetTintPolicyPrefix) == 16);
static_assert(sizeof(MonetTintPolicyEntryLayout) == 260);

extern "C" {
// Integrator-owned symbols. All accesses, including those in the publisher,
// must be atomic. Pointer: 8-byte
// alignment; readers: 4-byte alignment. Foreground requires flags == 1.
// Scoped equality invalidation is independent of policy and readers so a
// refresh after withdrawal can replace a cached tint with stock black.
// count == 0 deliberately does NOT disable the last accepted tint colors.
// kh_monet_policy/readers are declared by monet_policy_server.h.
extern void* kh_tint_original;
extern void* kh_filter_equals_original;

// Defined by monet_tint_profile.cpp. Publish before either hook becomes live.
extern uintptr_t kh_tint_caller;
extern uintptr_t kh_render_equals_caller;
extern uintptr_t kh_layer_equals_caller;

// Dart ABI entry points. NEVER call these as C functions.
void kh_monet_tint_bridge();
void kh_monet_filter_equals_bridge();
}

// Only readable, live mapped ranges may be supplied (normally PT_LOAD ranges
// collected for this image). No address is dereferenced before range checking.
struct MonetTintImageRange {
    uintptr_t begin;
    size_t size;
    bool executable;
};
struct MonetTintImageView {
    uintptr_t load_bias;
    uintptr_t snapshot_instructions;  // dlsym(_kDartIsolateSnapshotInstructions)
    uintptr_t snapshot_build_id;      // dlsym(_kDartSnapshotBuildId), OBJECT
    const MonetTintImageRange* readable_ranges;
    size_t readable_range_count;
    // SHA-256 computed by the integrator from this mapping's exact backing
    // libapp.so BEFORE patching. Required, 32 bytes. The resolver does not
    // perform file I/O or claim that a supplied digest is a newly computed one.
    const uint8_t* verified_file_sha256;
};
struct MonetTintProfile {
    uintptr_t load_bias;
    void* tint_entry;
    void* filter_equals_entry;
    uintptr_t tint_caller;
    uintptr_t render_equals_caller;
    uintptr_t layer_equals_caller;
};

extern const uint8_t kMonetTintImageSha256[32];

// Setup-thread functions, not functions callable from either Dart bridge.
// Resolve before installing either hook; a patched function fails its hash.
// Failure clears *out and leaves all global caller addresses unchanged.
bool ResolveMonetTintProfile(const MonetTintImageView& image,
                             MonetTintProfile* out,
                             const char** rejection = nullptr);
void PublishMonetTintCallers(const MonetTintProfile& profile);

// Lifetime contract: readers pin BEFORE loading the immutable policy. The
// bridge has a full DMB between the successful increment and LDAR policy.
// The serialized publisher must exchange policy atomically, issue a full
// fence, then wait for an acquire observation of readers == 0 before freeing
// retired policies. A release store + relaxed counter poll is insufficient.
// Retaining retired policies until process exit is also valid. Never wait for
// readers while holding a lock required by a reader (the bridges take no locks).
// Publish valid original trampolines before any thread can enter a bridge;
// keep trampolines, caller globals and this code alive until hooks are quiescent.
#endif

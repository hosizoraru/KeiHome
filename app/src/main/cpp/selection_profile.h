// SPDX-License-Identifier: Apache-2.0
#pragma once

// Structurally verified constants shared with the unchanged assembler. These
// are a bounded Dart AOT contract, not an assumed public Dart VM ABI.
#define KH_SELECTION_ONE_BYTE_CID 94
#define KH_SELECTION_MAX_COUNT 512
#define KH_SELECTION_MAX_LENGTH 255
#define KH_SELECTION_POLICY_ENTRIES 16
#define KH_SELECTION_ENTRY_STRIDE 260

#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>
#include "monet_policy_server.h"

static_assert(offsetof(NativeMonetPolicy, flags) == 0);
static_assert(offsetof(NativeMonetPolicy, count) == 12);
static_assert(offsetof(NativeMonetPolicy, entries) == KH_SELECTION_POLICY_ENTRIES);
static_assert(offsetof(NativeMonetPolicyEntry, name) == 4);
static_assert(sizeof(NativeMonetPolicyEntry) == KH_SELECTION_ENTRY_STRIDE);
static_assert(kNativeMonetMaxPackages == KH_SELECTION_MAX_COUNT);
static_assert(kNativeMonetMaxPackageLength == KH_SELECTION_MAX_LENGTH);

extern "C" {
// Main/framework owns the original trampoline and publishes it before detour
// activation. It stays executable for the lifetime of this bridge.
extern void* kh_selection_original;
void kh_monet_selection_bridge();  // Dart ABI: never invoke as a C function.

// Defined here, published once BEFORE installation, then immutable. These
// contain only code addresses, offsets and a native ready flag; no Dart refs.
extern uintptr_t kh_selection_factory_entry;
extern uintptr_t kh_selection_update_callback_entry;
extern uintptr_t kh_selection_factory_pool_offset;
extern uintptr_t kh_selection_update_callback_pool_offset;
extern uint32_t kh_selection_profile_ready;
}

struct MonetSelectionImageRange {
    uintptr_t begin;
    size_t size;
    bool executable;
};
struct MonetSelectionImageView {
    uintptr_t load_bias;
    const MonetSelectionImageRange* readable_ranges;
    size_t readable_range_count;
};
struct MonetSelectionProfile {
    uintptr_t load_bias;
    void* entry;
    uintptr_t resume;
    uintptr_t resource_entry;
    uintptr_t resource_probe_entry;
    uintptr_t update_entry;
    uintptr_t update_callback_entry;
    uintptr_t should_entry;
    uintptr_t string_equals_entry;
    uintptr_t string_allocator_entry;
    uintptr_t factory_pool_offset;
    uintptr_t update_callback_pool_offset;
    uintptr_t controller_type_pool_offset;
    uintptr_t update_callback_pool_load;
    uint32_t displaced[4];
};

// Main computes the mapped backing file's digest before installation; support
// is resolved from instructions, never inferred from a known image hash.
// These functions independently prove ELF bounds, bounded instruction shapes,
// String ABI, direct call coverage, x16 deadness and pool-slot relationships.
// No memory read precedes a supplied-readable-range check. All failures clear
// output; no resolver changes hooks, globals, Dart objects or launcher caches.
// The simple overload requires the ENTIRE supplied span to be readable.
// Use the segmented overload for a live image with unmapped/PROT_NONE holes.
bool ResolveMonetSelection(const uint8_t* imageBase, size_t mappedSize,
                           MonetSelectionProfile* out);
bool ResolveMonetSelection(const MonetSelectionImageView& image,
                           MonetSelectionProfile* out,
                           const char** rejection = nullptr);

// Requires the actual nonzero SHA computed by main from this mapped backing
// ELF. No image allowlist is consulted. The first publication binds the entire
// code-only profile and this digest; any subsequent different identity fails.
// Publishes only stable code addresses/offsets; the assembler checks live PP
// Function.entry_point linkage on EVERY enabled call, under the original live
// roots. No external PP capture, C callback or stable safepoint is necessary.
// Call after successful Resolve and BEFORE InstallInlineHook. Once published,
// another profile is rejected; the identical profile may be published again.
bool PublishMonetSelectionProfile(const MonetSelectionProfile& profile,
                                  const uint8_t verified_file_sha256[32]);

// RCU lifetime is shared with monet_policy_server.h. The bridge increments,
// DMBs, LDARs the policy, and release-decrements after its final policy read.
// Only flags & 1 enables the veto. All counted error/miss paths release the
// reader before tail-transferring to original. x16 alone is clobbered by that
// tail transfer: the resolved AllocateContext and has-entry prove it dead.
// x15 is restored before original; no extra return PC/frame crosses a call.
#endif

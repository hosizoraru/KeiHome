// SPDX-License-Identifier: Apache-2.0
#pragma once

// Diagnostic only. No ownership/disposal/rendering behavior is changed.
#define KH_IMAGE_TRACE_CAPACITY 8192
#define KH_IMAGE_TRACE_RECORD_SIZE 80
#define KH_IMAGE_TRACE_DISPOSE 1
#define KH_IMAGE_TRACE_CLONE 2
#define KH_IMAGE_TRACE_BUILD 3

#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>
#include "selection_profile.h"

struct MonetImageTraceProfile {
    uintptr_t dispose, clone, build, clear, put, raw_update, unmount;
    uintptr_t image_allocator;
    uint32_t image_cid, dispose_selector;
};

// Read-only, bounded code-family resolution; no version offsets/hash allowlist.
// Rejecting a profile clears output and never disables the existing Monet hooks.
bool ResolveMonetImageTrace(const MonetSelectionImageView& image,
                           MonetImageTraceProfile* out, const char** reason);

// Records are native scalar observations, never roots/handles. Address bits must
// NEVER be converted back to pointers or dereferenced by a reader. GC can move or
// reuse addresses between records; equal bits alone are not a lifetime identity.
struct MonetImageTraceRecord {
    uint64_t kind, thread_bits, caller_pc, state_bits;
    uint64_t first_bits, second_bits, first_disposed, second_disposed;
    uint64_t state_dispose_pc, backing_bits;
};
static_assert(sizeof(MonetImageTraceRecord) == KH_IMAGE_TRACE_RECORD_SIZE);
static_assert(offsetof(MonetImageTraceRecord, state_dispose_pc) == 64);

struct MonetImageTraceControl {
    uint32_t enabled, busy, count, lost;
};
static_assert(sizeof(MonetImageTraceControl) == 16);

extern "C" {
extern MonetImageTraceControl kh_image_trace_control;
extern MonetImageTraceRecord kh_image_trace_records[KH_IMAGE_TRACE_CAPACITY];
extern uint32_t kh_image_trace_image_cid, kh_image_trace_dispose_selector;
extern uintptr_t kh_image_trace_raw_update_return;
extern void* kh_image_trace_dispose_original;
extern void* kh_image_trace_clone_original;
extern void* kh_image_trace_build_original;
void kh_image_trace_dispose_bridge(); // Dart ABI, not C-callable.
void kh_image_trace_clone_bridge();
void kh_image_trace_build_bridge();
}

// Only the first, pre-isolate libapp load may call this. The diagnostic build
// installs three observers and starts one bounded, detached native log worker.
// It does not expose a command endpoint or alter policy/Java hot-reload ownership.
void InstallMonetImageTraceAtLibraryLoad(const MonetSelectionImageView& image);
#endif

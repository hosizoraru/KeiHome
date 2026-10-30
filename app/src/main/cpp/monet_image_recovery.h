// SPDX-License-Identifier: Apache-2.0
#pragma once

// Native scalars only. No Dart reference is published or retained by this hook.
#define KH_IMAGE_RECOVERY_LIMIT 65535
#define KH_IR_RETURN 0
#define KH_IR_IMAGE_CID 8
#define KH_IR_RENDER_CID 12
#define KH_IR_SPILL 16
#define KH_IR_RENDER_IMAGE 20
#define KH_IR_DISPOSED 24
#define KH_IR_BACKING 28
#define KH_IR_FRAME_SIZE 32
#define KH_IR_ENABLED 36
#define KH_IR_CONFIG_SIZE 40

#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>
#include "selection_profile.h"

struct MonetImageRecoveryConfig {
    uintptr_t update_return;
    uint32_t image_cid, render_cid;
    int32_t receiver_spill;
    uint32_t render_image, disposed, backing, frame_size, enabled;
};
static_assert(sizeof(MonetImageRecoveryConfig) == KH_IR_CONFIG_SIZE);
static_assert(offsetof(MonetImageRecoveryConfig, update_return) == KH_IR_RETURN);
static_assert(offsetof(MonetImageRecoveryConfig, image_cid) == KH_IR_IMAGE_CID);
static_assert(offsetof(MonetImageRecoveryConfig, render_cid) == KH_IR_RENDER_CID);
static_assert(offsetof(MonetImageRecoveryConfig, receiver_spill) == KH_IR_SPILL);
static_assert(offsetof(MonetImageRecoveryConfig, render_image) == KH_IR_RENDER_IMAGE);
static_assert(offsetof(MonetImageRecoveryConfig, disposed) == KH_IR_DISPOSED);
static_assert(offsetof(MonetImageRecoveryConfig, backing) == KH_IR_BACKING);
static_assert(offsetof(MonetImageRecoveryConfig, frame_size) == KH_IR_FRAME_SIZE);
static_assert(offsetof(MonetImageRecoveryConfig, enabled) == KH_IR_ENABLED);

struct MonetImageRecoveryProfile {
    uintptr_t clone, dispose, raw_update, raw_create, render_set, render_dispose;
    uintptr_t image_allocator, render_allocator;
    MonetImageRecoveryConfig config;
};
bool ResolveMonetImageRecovery(const MonetSelectionImageView& image,
                              MonetImageRecoveryProfile* out, const char** reason);

struct MonetImageRecoveryCounts { uint32_t repairs, uncounted; };
static_assert(sizeof(MonetImageRecoveryCounts) == 8);
extern "C" {
extern MonetImageRecoveryConfig kh_image_recovery_config;
extern MonetImageRecoveryCounts kh_image_recovery_counts;
extern void* kh_image_recovery_original;
void kh_image_recovery_bridge(); // Dart ABI; never C-callable.
}

// First pre-isolate libapp notification only. Trace builds deliberately disable
// recovery so the diagnostic sees stock failures and never double-hooks clone.
void InstallMonetImageRecoveryAtLibraryLoad(const MonetSelectionImageView& image);
// Called solely by the existing native policy worker. No additional timer/thread.
void ReportMonetImageRecovery(uint64_t monotonic_ns);
#endif

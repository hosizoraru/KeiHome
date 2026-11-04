// SPDX-License-Identifier: Apache-2.0
#pragma once
#define KH_DOCK_MODE_CALLER       0
#define KH_DOCK_MODE_RESUME       8
#define KH_DOCK_MODE_COMPUTE     16
#define KH_DOCK_MODE_ENABLED     24
#define KH_DOCK_MODE_VALUE       32
#define KH_DOCK_MODE_RX_SLOT     40
#define KH_DOCK_MODE_CURVE_GETTER 48
#define KH_DOCK_MODE_CURVE_VALUE 56
#define KH_DOCK_MODE_CONFIG_SIZE 64
#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>
// Install one non-linking B at the original post-sync continuation. caller is
// that mapped original PC; resume is caller + 4. Copy its authenticated original
// instruction into the mapped kh_dock_mode_replay word before publishing code.
// enabled/value are aligned addresses of atomic uint32_t primitives. rx_slot
// is the current isolate field-table byte offset, not a managed reference.
struct DockGlassModeConfig {
    uint64_t caller, resume, compute, enabled, value, rx_slot;
    uint64_t curve_getter, curve_value;
};
#define KH_DOCK_MODE_CHECK(field, offset) \
    static_assert(offsetof(DockGlassModeConfig, field) == offset)
KH_DOCK_MODE_CHECK(caller, KH_DOCK_MODE_CALLER);
KH_DOCK_MODE_CHECK(resume, KH_DOCK_MODE_RESUME);
KH_DOCK_MODE_CHECK(compute, KH_DOCK_MODE_COMPUTE);
KH_DOCK_MODE_CHECK(enabled, KH_DOCK_MODE_ENABLED);
KH_DOCK_MODE_CHECK(value, KH_DOCK_MODE_VALUE);
KH_DOCK_MODE_CHECK(rx_slot, KH_DOCK_MODE_RX_SLOT);
KH_DOCK_MODE_CHECK(curve_getter, KH_DOCK_MODE_CURVE_GETTER);
KH_DOCK_MODE_CHECK(curve_value, KH_DOCK_MODE_CURVE_VALUE);
#undef KH_DOCK_MODE_CHECK
static_assert(sizeof(DockGlassModeConfig) == KH_DOCK_MODE_CONFIG_SIZE);
extern "C" const uint8_t kh_dock_mode_post[], kh_dock_mode_replay[];
extern "C" const uint8_t kh_dock_mode_config[], kh_dock_mode_end[];
#endif

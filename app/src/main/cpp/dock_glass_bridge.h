// SPDX-License-Identifier: Apache-2.0
#pragma once

// Copied render config: fifty-seven uint64_t words, offsets in bytes. Addresses are
// absolute native addresses; pool/table offsets are bytes, never Dart pointers.
#define KH_DOCK_ENABLED                 0
#define KH_DOCK_GATE_ENTER              8
#define KH_DOCK_GATE_SKIP              16
#define KH_DOCK_ORIGINAL_BACKGROUND    24
#define KH_DOCK_GRID_SLOT              32
#define KH_DOCK_GRID_RX_FIELD          40
#define KH_DOCK_RX_VALUE_FIELD         48
#define KH_DOCK_DEVICE_SLOT            56
#define KH_DOCK_MATERIAL_MODE_ADDRESS  64
#define KH_DOCK_MATERIAL_TAGS          72 // mode 0/1/2/3 at 72/80/88/96
#define KH_DOCK_MATERIAL_CONFIG_PP    104
#define KH_DOCK_POSITIONED_TAG        112
#define KH_DOCK_STACK_TAG             120
#define KH_DOCK_SIZED_BOX_TAG         128
#define KH_DOCK_BACKGROUND_TAG        136
#define KH_DOCK_ALIGNMENT_PP          144
#define KH_DOCK_FIT_PP                152
#define KH_DOCK_CLIP_PP               160
#define KH_DOCK_DOUBLE_TAG            168
#define KH_DOCK_MODE_CONTROLLER_SLOT  176
#define KH_DOCK_MODE_RX_FIELD         184
#define KH_DOCK_ELDERLY_ENUM_PP       192
#define KH_DOCK_DEVICE_LAYOUT_FIELD   200
#define KH_DOCK_CORNER_LIST_FIELD     208
#define KH_DOCK_ICON_METRICS_FIELD    216
#define KH_DOCK_ICON_WIDTH_FIELD      224
#define KH_DOCK_CORNER_RADIUS_ADDRESS 232
#define KH_DOCK_ICON_TOP_FIELD        240
#define KH_DOCK_CELL_HEIGHT_FIELD     248
#define KH_DOCK_NO_WORD_RX_FIELD      256
#define KH_DOCK_NO_WORD_SUPPRESS_RX_FIELD 264
#define KH_DOCK_IGNORE_POINTER_TAG    272
#define KH_DOCK_NATIVE_CLIP_PP        280
#define KH_DOCK_PARENT_CLIP_RESUME    288
#define KH_DOCK_CORNER_STYLE_ADDRESS  296
#define KH_DOCK_CURVE_ENABLED_ADDRESS 304
#define KH_DOCK_VALUE_KEY_TAG         312
#define KH_DOCK_GLASS_SUPER_ALLOC     320
#define KH_DOCK_GLASS_RRECT_ALLOC     328
#define KH_DOCK_GLASS_SCOPE_RESUME     336
#define KH_DOCK_CHILD_SCOPE_RESUME    344
#define KH_DOCK_GLASS_RADIUS_SLOT     352
#define KH_DOCK_RECENTS_DEFAULT_BITS  360
#define KH_DOCK_RECENTS_MIN_BITS      368
#define KH_DOCK_GLASS_SCOPE_SLOT      376
#define KH_DOCK_GLASS_CHILD_SLOT      384
#define KH_DOCK_RECENTS_RADIUS_ADDRESS 392
#define KH_DOCK_SCOPE_COUNTS_ADDRESS  400
#define KH_DOCK_OWNED_KEY_VALUE       0x969088
#define KH_DOCK_FLIGHT_RADIUS_RESUME 408
#define KH_DOCK_FLIGHT_RADIUS_SLOT   416
#define KH_DOCK_GLASS_VISUAL_RESUME   424
#define KH_DOCK_UNLOCK_TAG           432
#define KH_DOCK_UNLOCK_ZERO_PP       440
#define KH_DOCK_UNLOCK_DEBUG_PP      448
#define KH_DOCK_CONFIG_SIZE           456
#define KH_DOCK_MATERIAL_GLASS_OS4      0
#define KH_DOCK_MATERIAL_MIUIX_OS3      1
#define KH_DOCK_MATERIAL_BASIC_BLUR     2
#define KH_DOCK_MATERIAL_NORMAL         3

#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>

// Parent must authenticate ALL frame, class/header, field, pool and allocator
// contracts before installing. The parent owns the primitive mode global
// (initially zero), native mode-capture thunk and outer Obx dependency. No Rx or
// Stateful outer Dock widget is fabricated here. Mode is acquired ONCE per
// render so tag, size and field writes always describe the same native class.
struct DockGlassBridgeConfig {
    uint64_t enabled;                 // &uint32_t enabled; acquire, exactly 1
    uint64_t gate_enter;              // original TBNZ address + 4
    uint64_t gate_skip;               // decoded original TBNZ false target
    uint64_t original_background;     // decoded original _buildBackground BL
    uint64_t grid_slot;               // full pointer in [x26+0x78] table
    uint64_t grid_rx_field;           // tagged GridController -> Rx
    uint64_t rx_value_field;          // generic Rx -> value (grid AND mode Rx)
    uint64_t device_slot;             // full DeviceConfig pointer, same table
    uint64_t material_mode_address;   // &uint32_t kh_dock_material_mode, 0..3
    uint64_t material_tags[4];        // GlassOs4 / MiuiXOs3 / BasicBlur / Normal
    uint64_t material_config_pp;      // PP-relative native material config
    uint64_t positioned_tag;          // complete 48-byte fresh header
    uint64_t stack_tag;               // complete 32-byte fresh header
    uint64_t sized_box_tag;           // complete 32-byte fresh header
    uint64_t background_tag;          // exact 16-byte native ItemBackground
    uint64_t alignment_pp;            // native Stack alignment
    uint64_t fit_pp;                  // native Stack fit
    uint64_t clip_pp;                 // authenticated native Clip.none
    uint64_t double_tag;              // complete 16-byte native Double header
    // Max count is independent of the DeviceConfig flag selecting cell WIDTH.
    // Parent derives these from hotSeatMaxCount -> isElderlyManMode's graph.
    uint64_t mode_controller_slot;    // cached LauncherModeController table slot
    uint64_t mode_rx_field;           // tagged LauncherModeController -> mode Rx
    uint64_t elderly_enum_pp;         // PP-relative native elderly enum singleton
    uint64_t device_layout_field;     // tagged DeviceConfig width-path Bool field
    // Legacy reserved field; never read. Corners use the native Q8 global below.
    uint64_t corner_list_field;       // tagged DeviceConfig native corner list
    uint64_t icon_metrics_field;      // tagged GridConfig -> native icon metrics
    uint64_t icon_width_field;        // tagged metrics -> raw painted width
    uint64_t corner_radius_address;   // &uint32_t bottom radius in dp Q8, 0..65536
    uint64_t icon_top_field;          // tagged metrics -> raw painted top Y
    uint64_t cell_height_field;       // tagged GridConfig -> normal raw height
    uint64_t no_word_rx_field;        // LauncherModeController -> no-word Bool Rx
    // Zero selects the legacy exact-elderly guard. Nonzero selects the native
    // suppression Bool + enum-index guard, derived from the effective helper.
    uint64_t no_word_suppress_rx_field;
    uint64_t ignore_pointer_tag;      // authenticated native 32-byte IgnorePointer
    uint64_t native_clip_pp;          // original parent clip when disabled
    uint64_t parent_clip_resume;      // original clip store +4; x1 overwritten next
    uint64_t corner_style_address;    // &uint32_t: 0 rounded, 1 native Recents
    uint64_t curve_enabled_address;   // native eligibility Bool, primitive 0/1
    uint64_t value_key_tag;
    uint64_t glass_super_alloc, glass_rrect_alloc;
    uint64_t glass_scope_resume, child_scope_resume, glass_radius_slot;
    uint64_t recents_default_bits, recents_min_bits;
    uint64_t glass_scope_slot, glass_child_slot, recents_radius_address;
    uint64_t scope_counts_address;
    uint64_t flight_radius_resume, flight_radius_slot;
    uint64_t glass_visual_resume;
    uint64_t unlock_tag, unlock_zero_pp, unlock_debug_pp;
};

#define KH_DOCK_CHECK(member, offset) \
    static_assert(offsetof(DockGlassBridgeConfig, member) == offset)
KH_DOCK_CHECK(enabled, KH_DOCK_ENABLED);
KH_DOCK_CHECK(gate_enter, KH_DOCK_GATE_ENTER);
KH_DOCK_CHECK(gate_skip, KH_DOCK_GATE_SKIP);
KH_DOCK_CHECK(original_background, KH_DOCK_ORIGINAL_BACKGROUND);
KH_DOCK_CHECK(grid_slot, KH_DOCK_GRID_SLOT);
KH_DOCK_CHECK(grid_rx_field, KH_DOCK_GRID_RX_FIELD);
KH_DOCK_CHECK(rx_value_field, KH_DOCK_RX_VALUE_FIELD);
KH_DOCK_CHECK(device_slot, KH_DOCK_DEVICE_SLOT);
KH_DOCK_CHECK(material_mode_address, KH_DOCK_MATERIAL_MODE_ADDRESS);
KH_DOCK_CHECK(material_tags, KH_DOCK_MATERIAL_TAGS);
KH_DOCK_CHECK(material_config_pp, KH_DOCK_MATERIAL_CONFIG_PP);
KH_DOCK_CHECK(positioned_tag, KH_DOCK_POSITIONED_TAG);
KH_DOCK_CHECK(stack_tag, KH_DOCK_STACK_TAG);
KH_DOCK_CHECK(sized_box_tag, KH_DOCK_SIZED_BOX_TAG);
KH_DOCK_CHECK(background_tag, KH_DOCK_BACKGROUND_TAG);
KH_DOCK_CHECK(alignment_pp, KH_DOCK_ALIGNMENT_PP);
KH_DOCK_CHECK(fit_pp, KH_DOCK_FIT_PP);
KH_DOCK_CHECK(clip_pp, KH_DOCK_CLIP_PP);
KH_DOCK_CHECK(double_tag, KH_DOCK_DOUBLE_TAG);
KH_DOCK_CHECK(mode_controller_slot, KH_DOCK_MODE_CONTROLLER_SLOT);
KH_DOCK_CHECK(mode_rx_field, KH_DOCK_MODE_RX_FIELD);
KH_DOCK_CHECK(elderly_enum_pp, KH_DOCK_ELDERLY_ENUM_PP);
KH_DOCK_CHECK(device_layout_field, KH_DOCK_DEVICE_LAYOUT_FIELD);
KH_DOCK_CHECK(corner_list_field, KH_DOCK_CORNER_LIST_FIELD);
KH_DOCK_CHECK(icon_metrics_field, KH_DOCK_ICON_METRICS_FIELD);
KH_DOCK_CHECK(icon_width_field, KH_DOCK_ICON_WIDTH_FIELD);
KH_DOCK_CHECK(corner_radius_address, KH_DOCK_CORNER_RADIUS_ADDRESS);
KH_DOCK_CHECK(icon_top_field, KH_DOCK_ICON_TOP_FIELD);
KH_DOCK_CHECK(cell_height_field, KH_DOCK_CELL_HEIGHT_FIELD);
KH_DOCK_CHECK(no_word_rx_field, KH_DOCK_NO_WORD_RX_FIELD);
KH_DOCK_CHECK(no_word_suppress_rx_field, KH_DOCK_NO_WORD_SUPPRESS_RX_FIELD);
KH_DOCK_CHECK(ignore_pointer_tag, KH_DOCK_IGNORE_POINTER_TAG);
KH_DOCK_CHECK(native_clip_pp, KH_DOCK_NATIVE_CLIP_PP);
KH_DOCK_CHECK(parent_clip_resume, KH_DOCK_PARENT_CLIP_RESUME);
KH_DOCK_CHECK(corner_style_address, KH_DOCK_CORNER_STYLE_ADDRESS);
KH_DOCK_CHECK(curve_enabled_address, KH_DOCK_CURVE_ENABLED_ADDRESS);
KH_DOCK_CHECK(value_key_tag, KH_DOCK_VALUE_KEY_TAG);
KH_DOCK_CHECK(glass_super_alloc, KH_DOCK_GLASS_SUPER_ALLOC);
KH_DOCK_CHECK(glass_rrect_alloc, KH_DOCK_GLASS_RRECT_ALLOC);
KH_DOCK_CHECK(glass_scope_resume, KH_DOCK_GLASS_SCOPE_RESUME);
KH_DOCK_CHECK(child_scope_resume, KH_DOCK_CHILD_SCOPE_RESUME);
KH_DOCK_CHECK(glass_radius_slot, KH_DOCK_GLASS_RADIUS_SLOT);
KH_DOCK_CHECK(recents_default_bits, KH_DOCK_RECENTS_DEFAULT_BITS);
KH_DOCK_CHECK(recents_min_bits, KH_DOCK_RECENTS_MIN_BITS);
KH_DOCK_CHECK(glass_scope_slot, KH_DOCK_GLASS_SCOPE_SLOT);
KH_DOCK_CHECK(glass_child_slot, KH_DOCK_GLASS_CHILD_SLOT);
KH_DOCK_CHECK(recents_radius_address, KH_DOCK_RECENTS_RADIUS_ADDRESS);
KH_DOCK_CHECK(scope_counts_address, KH_DOCK_SCOPE_COUNTS_ADDRESS);
KH_DOCK_CHECK(flight_radius_resume, KH_DOCK_FLIGHT_RADIUS_RESUME);
KH_DOCK_CHECK(flight_radius_slot, KH_DOCK_FLIGHT_RADIUS_SLOT);
KH_DOCK_CHECK(glass_visual_resume, KH_DOCK_GLASS_VISUAL_RESUME);
KH_DOCK_CHECK(unlock_tag, KH_DOCK_UNLOCK_TAG);
KH_DOCK_CHECK(unlock_zero_pp, KH_DOCK_UNLOCK_ZERO_PP);
KH_DOCK_CHECK(unlock_debug_pp, KH_DOCK_UNLOCK_DEBUG_PP);
#undef KH_DOCK_CHECK
static_assert(sizeof(DockGlassBridgeConfig) == KH_DOCK_CONFIG_SIZE);

extern "C" {
extern uint32_t kh_dock_material_mode; // parent definition, default 0
extern uint32_t kh_dock_superellipse_enabled;
extern uint64_t kh_dock_recents_corner_bits;
extern uint64_t kh_dock_glass_scope_counts[3];
extern const uint8_t kh_dock_template[], kh_dock_gate[], kh_dock_background[];
extern const uint8_t kh_dock_parent_clip[];
extern const uint8_t kh_dock_parent_cid[];
extern const uint8_t kh_dock_glass_scope[], kh_dock_glass_child_scope[], kh_dock_glass_clip[], kh_dock_recents_radius[], kh_dock_recents_flight_radius[];
extern const uint8_t kh_dock_config[], kh_dock_end[];
}
#endif

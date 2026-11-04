// SPDX-License-Identifier: Apache-2.0
#pragma once

// Shared with dock_glass_refresh.inc. All entries are 64-bit words, including
// Dart field/table offsets. Addresses below are absolute live addresses.
#define KH_DOCK_REFRESH_INSTALLED          0
#define KH_DOCK_REFRESH_REVISION           8
#define KH_DOCK_REFRESH_NOTIFIED          16
#define KH_DOCK_REFRESH_LOGGER            24
#define KH_DOCK_REFRESH_GETTER            32
#define KH_DOCK_REFRESH_EVENT_RETURN      40
#define KH_DOCK_REFRESH_VALUE             48
#define KH_DOCK_REFRESH_REFRESH           56
#define KH_DOCK_REFRESH_GRID_SLOT         64
#define KH_DOCK_REFRESH_CONFIG_RX_FIELD   72
#define KH_DOCK_REFRESH_RX_VALUE_FIELD    80
#define KH_DOCK_REFRESH_RX_SUBJECT_FIELD  88
#define KH_DOCK_REFRESH_OBSERVER_SLOT     96
#define KH_DOCK_REFRESH_CONFIG_SIZE      104

#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>

// Parent owns resolution, relocation, patching and installation. Populate this
// immutable block before either BL is patched. Only the three pointed-to native
// globals are mutable; never place a Dart pointer in this block.
struct DockGlassRefreshConfig {
    uint64_t installed;           // &kh_dock_glass_installed (uint32_t)
    uint64_t revision;            // &kh_dock_glass_revision (uint64_t)
    uint64_t notified;            // &kh_dock_glass_notified (uint64_t)
    uint64_t logger;              // original SECOND discarded logger's target
    uint64_t getter;              // native GridController.currentConfig entry
    uint64_t event_return;        // original second logger BL address + 4
    uint64_t value;               // original getter BL's generic Rx.value target
    uint64_t refresh;             // matching generic Rx.refresh, NOT RxList.refresh
    uint64_t grid_slot;           // byte offset in [x26 + 0x78] field table
    uint64_t config_rx_field;     // tagged GridController -> GridConfig Rx
    uint64_t rx_value_field;      // tagged generic Rx -> current GridConfig
    uint64_t rx_subject_field;    // tagged generic Rx -> GetStream subject
    uint64_t observer_slot;       // active Rx observer slot in the field table
};

#define KH_DOCK_REFRESH_CHECK(member, offset) \
    static_assert(offsetof(DockGlassRefreshConfig, member) == offset)
KH_DOCK_REFRESH_CHECK(installed, KH_DOCK_REFRESH_INSTALLED);
KH_DOCK_REFRESH_CHECK(revision, KH_DOCK_REFRESH_REVISION);
KH_DOCK_REFRESH_CHECK(notified, KH_DOCK_REFRESH_NOTIFIED);
KH_DOCK_REFRESH_CHECK(logger, KH_DOCK_REFRESH_LOGGER);
KH_DOCK_REFRESH_CHECK(getter, KH_DOCK_REFRESH_GETTER);
KH_DOCK_REFRESH_CHECK(event_return, KH_DOCK_REFRESH_EVENT_RETURN);
KH_DOCK_REFRESH_CHECK(value, KH_DOCK_REFRESH_VALUE);
KH_DOCK_REFRESH_CHECK(refresh, KH_DOCK_REFRESH_REFRESH);
KH_DOCK_REFRESH_CHECK(grid_slot, KH_DOCK_REFRESH_GRID_SLOT);
KH_DOCK_REFRESH_CHECK(config_rx_field, KH_DOCK_REFRESH_CONFIG_RX_FIELD);
KH_DOCK_REFRESH_CHECK(rx_value_field, KH_DOCK_REFRESH_RX_VALUE_FIELD);
KH_DOCK_REFRESH_CHECK(rx_subject_field, KH_DOCK_REFRESH_RX_SUBJECT_FIELD);
KH_DOCK_REFRESH_CHECK(observer_slot, KH_DOCK_REFRESH_OBSERVER_SLOT);
#undef KH_DOCK_REFRESH_CHECK
static_assert(sizeof(DockGlassRefreshConfig) == KH_DOCK_REFRESH_CONFIG_SIZE);

struct IconHighlightImage;
struct LauncherLabelProfile;

// All code locations are ELF-relative offsets, like LauncherLabelProfile.
// Resolve BEFORE label installation changes the first item-event logger BL.
// The parent relocates code addresses and fills the separate immutable config.
struct DockGlassRefreshProfile {
    size_t second_event_call;
    size_t second_event_target;
    uint32_t second_event_expected;
    size_t getter_value_call;
    uint32_t getter_value_expected;
    size_t refresh;
    size_t stream_add;
    uint32_t rx_subject_field;
    uint32_t observer_slot;
};

// Read-only, bounded instruction/linked-role resolution. Requires the complete
// already-resolved label profile from this same original mapped image. No
// symbols, image/version/address allowlist, patch, allocation or global writes.
// Every failure clears out and supplies a static reason when why is non-null.
bool ResolveDockGlassRefresh(const IconHighlightImage& image,
                            const LauncherLabelProfile& labels,
                            DockGlassRefreshProfile* out,
                            const char** why = nullptr);

extern "C" {
extern const uint8_t kh_dock_refresh_begin[], kh_dock_refresh_event[];
extern const uint8_t kh_dock_refresh_value[], kh_dock_refresh_config[];
extern const uint8_t kh_dock_refresh_end[];
}
#endif

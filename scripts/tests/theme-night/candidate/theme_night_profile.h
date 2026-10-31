// SPDX-License-Identifier: Apache-2.0
#pragma once

#define KH_TN_MODE 0
#define KH_TN_TABLE 4
#define KH_TN_ICONS 8
#define KH_TN_NIGHT 12
#define KH_TN_FIELD 16
#define KH_TN_CID 20
#define KH_TN_TRUE 24
#define KH_TN_FALSE 28
#define KH_TN_ICONS_POOL 32
#define KH_TN_NIGHT_POOL 36
#define KH_TN_NORMAL_POOL 40
#define KH_TN_STRING_CID 44
#define KH_TN_STRING_LENGTH 48
#define KH_TN_STRING_DATA 52
#define KH_TN_SIZE 56

#ifndef __ASSEMBLER__
#include <stddef.h>
#include <stdint.h>
#include "selection_profile.h"

// EXPERIMENTAL: do not integrate into production. A global admission flag is
// not bound to an archive revision; even the same root can change its package.
// Mode 0 bypasses without reading Dart roots. Mode 1 synchronizes the active
// icons root; mode 2 restores its constructor value on the next bitmap read.
// Neither disabling mode 1 nor root identity revokes an already stored true.
// The stock reader has no same-package day retry. Host ABI checks do not solve
// this admission/lifetime issue. Main owns archive admission and hook lifecycle.
struct ThemeNightConfig {
    uint32_t mode, thread_table, icons_slot, mode_slot, night_field, resource_cid;
    uint32_t true_offset, false_offset, icons_pool, night_pool, normal_pool;
    uint32_t string_cid, string_length, string_data;
};
static_assert(sizeof(ThemeNightConfig) == KH_TN_SIZE);
#define KH_TN_CHECK(field, offset) static_assert(offsetof(ThemeNightConfig, field) == offset)
KH_TN_CHECK(mode, KH_TN_MODE);
KH_TN_CHECK(thread_table, KH_TN_TABLE);
KH_TN_CHECK(icons_slot, KH_TN_ICONS);
KH_TN_CHECK(mode_slot, KH_TN_NIGHT);
KH_TN_CHECK(night_field, KH_TN_FIELD);
KH_TN_CHECK(resource_cid, KH_TN_CID);
KH_TN_CHECK(true_offset, KH_TN_TRUE);
KH_TN_CHECK(false_offset, KH_TN_FALSE);
KH_TN_CHECK(icons_pool, KH_TN_ICONS_POOL);
KH_TN_CHECK(night_pool, KH_TN_NIGHT_POOL);
KH_TN_CHECK(normal_pool, KH_TN_NORMAL_POOL);
KH_TN_CHECK(string_cid, KH_TN_STRING_CID);
KH_TN_CHECK(string_length, KH_TN_STRING_LENGTH);
KH_TN_CHECK(string_data, KH_TN_STRING_DATA);
#undef KH_TN_CHECK

struct ThemeNightProfile {
    uintptr_t bitmap_local, system_bitmap, node_bitmap, init, create, seed;
    uintptr_t read_mode, apply, system_setter, node_setter, get_system;
    uintptr_t stream, has_file, string_equals, allocator;
    ThemeNightConfig config;
    uint32_t displaced[4];
};

// Current mapped instructions only: no symbols, version RVAs or image allowlist.
// Each range must already be readable. All failures clear output. Does not
// publish configuration, enable a feature, install hooks, or retain Dart refs.
bool ResolveThemeNight(const MonetSelectionImageView& image,
                       ThemeNightProfile* out, const char** reason = nullptr);

extern "C" {
// For isolated host tests: publish config (mode=0) and original before detour
// activation; offsets then stay immutable. Mode uses release/acquire access.
// Production activation needs a separate proven archive-lifetime protocol.
extern ThemeNightConfig kh_theme_night_config;
extern void* kh_theme_night_original;
void kh_theme_night_bitmap_bridge(); // Dart ABI; never call from C/C++.
}
#endif

// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stdint.h>

// Called only from the first fresh libapp.so notification, before that image
// executes Dart. Never invoke on hot attach/backfill or an existing isolate.
bool InstallIconHighlightAtLibraryLoad(void* handle);

// Policy owner uses these independently of Monet colors/count/flags.
// Requested true is rejected until the exact patch is actually installed.
// false always clears effective state; return value reports installation.
bool IsIconHighlightInstalled();
bool SetIconHighlightEnabled(bool enabled);
bool IsIconHighlightEnabled();

extern "C" {
// Storage belongs to the independently owned policy server. Installer alone
// publishes installed; authenticated command/lease owner publishes enabled.
// Bridge reads one naturally aligned uint32_t with LDAR; only 1 enables.
extern uint32_t kh_theme_materials_installed;
extern uint32_t kh_theme_materials_enabled;
}

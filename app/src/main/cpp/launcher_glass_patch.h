// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stddef.h>
#include <stdint.h>

bool LauncherGlassBranch(uintptr_t from, uintptr_t to, uint32_t* word);
void* AllocateLauncherGlassCode(const uintptr_t* sites, size_t count, size_t bytes);
bool SealLauncherGlassCode(void* code, size_t bytes);

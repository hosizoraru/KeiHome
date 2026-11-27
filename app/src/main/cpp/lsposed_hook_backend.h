// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "native_api.h"

constexpr int kHookSuccess = 0;
constexpr int kHookFailed = 1;

bool InitializeLsposedHookBackend(const NativeAPIEntries* entries);
bool EnsureLsposedMadviseGuard(const char* runtime_name = nullptr);
int InstallPltHook(void* base_addr, const char* symbol, void* hook_handler,
                   void** original);
int InstallInlineHook(void* target, void* replacement, void** original);
int RemoveInlineHook(void* target);
// Atomically replaces one validated AArch64 instruction and keeps the code
// page registered against Flutter's MADV_DONTNEED cleanup.
int PatchArm64Instruction(void* target, uint32_t expected, uint32_t replacement);
// Explicit x0 absolute detour; no hookFunc/trampoline/relocation assumptions.
// Exactly 16 bytes, only on a fresh image before Dart execution. Failure may
// leave a reachable disabled bridge after an OS protection error: its memory
// must remain alive for process lifetime even when the return is failure.
int PatchArm64HighlightTail16(void* target, const uint32_t expected[4],
                             const uint32_t replacement[4], bool fresh_image);
int PatchArm64Label16(void* target, const uint32_t expected[4],
                     const uint32_t replacement[4], bool lines, bool fresh_image);

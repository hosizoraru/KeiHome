// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <stddef.h>
#include <stdint.h>

// Hash the exact library opened by the linker, including a stored APK entry.
// Compressed/malformed archives and oversized images are rejected.
bool HashMonetLibrary(const char* linker_path, uint8_t digest[32]);
void MonetSha256(const void* data, size_t size, uint8_t digest[32]);

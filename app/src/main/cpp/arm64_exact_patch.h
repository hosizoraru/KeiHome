// SPDX-License-Identifier: Apache-2.0
#pragma once

#include <stddef.h>
#include <stdint.h>

struct Arm64ExactPatchOps {
    void* context;
    size_t page_size;
    int (*protection)(void*, uintptr_t);
    bool (*guard)(void*, uintptr_t);
    bool (*protect)(void*, uintptr_t, size_t, int);
    void (*flush)(void*, void*, size_t);
};
enum class Arm64ExactPatchKind { Highlight, LabelText, LabelLines };

// Fresh, nonexecuting image only: the 16-byte write is NOT atomic. The
// transaction never delegates to an opaque inline-hook relocator. The ops
// supply Linux PROT_READ/WRITE/EXEC bit values (1/2/4).
bool PatchArm64Tail16(void* target, const uint32_t expected[4],
                     const uint32_t replacement[4], bool fresh_image,
                     const Arm64ExactPatchOps& ops,
                     Arm64ExactPatchKind kind = Arm64ExactPatchKind::Highlight);

// SPDX-License-Identifier: Apache-2.0
#include "arm64_exact_patch.h"

#include <string.h>

bool PatchArm64Tail16(void* target, const uint32_t expected[4],
                     const uint32_t replacement[4], bool fresh_image,
                     const Arm64ExactPatchOps& ops, Arm64ExactPatchKind kind) {
    const uintptr_t at = reinterpret_cast<uintptr_t>(target);
    const size_t page_size = ops.page_size;
    if (!fresh_image || !target || !expected || !replacement || (at & 3u) ||
            at > UINTPTR_MAX - 15 || page_size < 4096 || page_size > 65536 ||
            (page_size & (page_size - 1)) || !ops.protection || !ops.guard ||
            !ops.protect || !ops.flush) return false;
    const bool highlight = kind == Arm64ExactPatchKind::Highlight &&
            (expected[0] & 0xffe00fffu) == 0xb8400022u &&
            expected[1] == 0x8b1c8042 && expected[2] == 0xaa0203e0 &&
            expected[3] == 0x14000002;
    const bool text = kind == Arm64ExactPatchKind::LabelText &&
            (expected[0] & 0xffe00fffu) == 0xf84003a2 &&
            (expected[1] & 0xffe00fffu) == 0xf80003a0 && expected[2] == 0xb800b002 &&
            (expected[3] & 0xffe00fffu) == 0xf84003a3;
    const bool lines = kind == Arm64ExactPatchKind::LabelLines &&
            expected[0] == 0xaa0103e0 && expected[1] == 0xaa1d03ef &&
            expected[2] == 0xa8c179fd && expected[3] == 0xd65f03c0;
    const uint32_t load = highlight ? 0x58000040 : 0x58000050;
    const uint32_t branch = highlight ? 0xd61f0000 : 0xd61f0200;
    if ((!highlight && !text && !lines) || replacement[0] != load || replacement[1] != branch ||
            (!replacement[2] && !replacement[3]) || (replacement[2] & 3u)) return false;
    const uintptr_t pages[2] = {at & ~(uintptr_t(page_size) - 1),
                                (at + 15) & ~(uintptr_t(page_size) - 1)};
    const size_t count = pages[0] == pages[1] ? 1 : 2;
    int protections[2] = {};
    for (size_t i = 0; i < count; ++i) {
        protections[i] = ops.protection(ops.context, pages[i]);
        if (protections[i] < 0 || (protections[i] & 5) != 5 ||
                !ops.guard(ops.context, pages[i])) return false;
    }
    if (memcmp(target, expected, 16)) return false;
    size_t writable = 0;
    for (; writable < count; ++writable) {
        // Match the existing backend's temporary write permission. Retaining
        // EXEC is intentional: even an OS restore/rollback failure must not
        // leave this new library's code page nonexecutable.
        if (!ops.protect(ops.context, pages[writable], page_size,
                         protections[writable] | 2)) break;
    }
    if (writable != count || memcmp(target, expected, 16)) {
        for (size_t i = 0; i < writable; ++i)
            ops.protect(ops.context, pages[i], page_size, protections[i]);
        return false;
    }
    memcpy(target, replacement, 16);
    ops.flush(ops.context, target, 16);
    bool restored = true;
    for (size_t i = 0; i < count; ++i)
        restored = ops.protect(ops.context, pages[i], page_size, protections[i]) && restored;
    if (restored) return true;

    // A failed restore follows publication. Reopen every page before rollback;
    // if any cannot be reopened, never free the reachable bridge (caller owns
    // it for process lifetime) and keep its policy disabled.
    bool reopen = true;
    for (size_t i = 0; i < count; ++i)
        reopen = ops.protect(ops.context, pages[i], page_size,
                             protections[i] | 2) && reopen;
    if (reopen) {
        memcpy(target, expected, 16);
        ops.flush(ops.context, target, 16);
    }
    for (size_t i = 0; i < count; ++i)
        ops.protect(ops.context, pages[i], page_size, protections[i]);
    return false;
}

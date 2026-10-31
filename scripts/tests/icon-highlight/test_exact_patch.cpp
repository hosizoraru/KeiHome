// SPDX-License-Identifier: Apache-2.0
#include "arm64_exact_patch.h"

#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <set>
#include <vector>

namespace {
unsigned checks = 0;
void Check(bool result, const char* name) {
    ++checks;
    if (!result) { std::fprintf(stderr, "FAIL %s\n", name); std::exit(1); }
}
struct State {
    int protection = 5;
    unsigned guards = 0, protections = 0, flushes = 0;
    unsigned fail_guard = 0;
    std::set<unsigned> fail_protect;
    std::vector<int> requested;
};
Arm64ExactPatchOps Ops(State& s, size_t page) {
    return {&s, page,
        [](void* p, uintptr_t) { return static_cast<State*>(p)->protection; },
        [](void* p, uintptr_t) {
            auto& s = *static_cast<State*>(p);
            return ++s.guards != s.fail_guard;
        },
        [](void* p, uintptr_t, size_t, int protection) {
            auto& s = *static_cast<State*>(p);
            s.requested.push_back(protection);
            return s.fail_protect.count(++s.protections) == 0;
        },
        [](void* p, void*, size_t n) {
            Check(n == 16, "flush exactly patched bytes");
            ++static_cast<State*>(p)->flushes;
        }};
}
}
int main() {
    alignas(65536) std::array<uint8_t, 131072> memory{};
    const uint32_t original[] = {0xb84ef022, 0x8b1c8042, 0xaa0203e0, 0x14000002};
    const uint32_t detour[] = {0x58000040, 0xd61f0000, 0, 0x78};
    for (size_t page : {4096u, 16384u, 65536u}) {
        for (bool cross : {false, true}) {
            uint8_t* target = memory.data() + (cross ? page - 8 : 128);
            const unsigned count = cross ? 2 : 1;
            auto reset = [&] {
                memory.fill(0x5a);
                std::memcpy(target, original, 16);
            };
            auto unchanged = [&] {
                Check(!std::memcmp(target, original, 16), "original restored/intact");
                Check(target[-1] == 0x5a && target[16] == 0x5a,
                      "neighbor false/epilogue bytes untouched");
            };
            reset(); State s{};
            Check(PatchArm64Tail16(target, original, detour, true, Ops(s, page)),
                  "exact16 transaction");
            Check(!std::memcmp(target, detour, 16), "actual x0 absolute detour bytes");
            Check(target[-1] == 0x5a && target[16] == 0x5a, "no overwrite outside window");
            Check(s.guards == count && s.protections == count * 2 && s.flushes == 1,
                  "all pages guarded/protected/cache-flushed");
            for (int p : s.requested) Check((p & 5) == 5, "target remains readable/executable on OS failure");
            reset(); s = {};
            Check(!PatchArm64Tail16(target, original, detour, false, Ops(s, page)) &&
                  !s.guards && !s.protections, "live image rejected without side effects");
            unchanged();
            reset(); s = {}; s.protection = 1;
            Check(!PatchArm64Tail16(target, original, detour, true, Ops(s, page)),
                  "non-executable target rejected");
            unchanged();
            for (unsigned fail = 1; fail <= count; ++fail) {
                reset(); s = {}; s.fail_guard = fail;
                Check(!PatchArm64Tail16(target, original, detour, true, Ops(s, page)),
                      "guard failure rejected");
                unchanged();
            }
            for (unsigned fail = 1; fail <= count * 2; ++fail) {
                reset(); s = {}; s.fail_protect.insert(fail);
                Check(!PatchArm64Tail16(target, original, detour, true, Ops(s, page)),
                      "write/protection failure rejected");
                unchanged();
            }
            reset(); s = {}; s.fail_protect = {count + 1, count * 2 + 1};
            Check(!PatchArm64Tail16(target, original, detour, true, Ops(s, page)),
                  "restore plus rollback-reopen failure is not installed");
            Check(!std::memcmp(target, detour, 16) && target[16] == 0x5a,
                  "failed rollback retains complete disabled resident detour, not partial bytes");
            for (int p : s.requested) Check((p & 5) == 5, "failed rollback never removes execution");
            reset(); s = {}; target[0] ^= 1;
            Check(!PatchArm64Tail16(target, original, detour, true, Ops(s, page)) &&
                    !s.protections && target[0] != uint8_t(original[0]),
                    "foreign bytes preserved without write");
            for (size_t index = 0; index < 4; ++index) {
                reset(); s = {}; uint32_t bad[4]; std::memcpy(bad, original, 16);
                bad[index] ^= 1;
                Check(!PatchArm64Tail16(target, bad, detour, true, Ops(s, page)),
                      "unrecognized displaced instruction rejected");
                unchanged();
            }
        }
    }
    std::printf("%u exact-patch assertions passed; injected OS callbacks, no live image\n", checks);
}

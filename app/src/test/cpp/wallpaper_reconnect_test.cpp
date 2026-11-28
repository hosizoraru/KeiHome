// SPDX-License-Identifier: Apache-2.0
#include "wallpaper_reconnect_hooks.h"
#include "wallpaper_reconnect_profile.h"

#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <fstream>
#include <iterator>
#include <string>
#include <vector>

namespace {

struct MappedElf {
    std::vector<uint8_t> mem;
    std::vector<IconHighlightRange> ranges;
    IconHighlightImage image{};
};

MappedElf LoadElf(const char* path) {
    std::ifstream file(path, std::ios::binary);
    assert(file.good());
    std::vector<uint8_t> raw((std::istreambuf_iterator<char>(file)), {});
    assert(raw.size() >= 64u);

    auto u16 = [&](size_t at) {
        uint16_t v = 0u;
        memcpy(&v, raw.data() + at, sizeof(v));
        return v;
    };
    auto u64 = [&](size_t at) {
        uint64_t v = 0u;
        memcpy(&v, raw.data() + at, sizeof(v));
        return v;
    };

    struct Phdr {
        uint32_t type, flags;
        uint64_t offset, vaddr, paddr, filesz, memsz, align;
    };
    assert(sizeof(Phdr) == 56u && u16(54u) == sizeof(Phdr));

    std::vector<Phdr> loads;
    size_t max_end = 0u;
    MappedElf out{};
    const uint64_t phoff = u64(32u);
    const uint16_t phnum = u16(56u);
    for (uint16_t i = 0u; i < phnum; ++i) {
        Phdr p{};
        assert(phoff + (i + 1u) * sizeof(p) <= raw.size());
        memcpy(&p, raw.data() + phoff + i * sizeof(p), sizeof(p));
        if (p.type != 1u || (p.flags & 4u) == 0u || p.memsz == 0u) continue;
        assert(p.offset + p.filesz <= raw.size());
        const size_t end = static_cast<size_t>(p.vaddr + p.memsz);
        if (end > max_end) max_end = end;
        out.ranges.push_back(
            {static_cast<size_t>(p.vaddr), end, (p.flags & 1u) != 0u});
        loads.push_back(p);
    }
    out.mem.assign(max_end, 0u);
    for (const auto& p : loads) {
        memcpy(out.mem.data() + p.vaddr, raw.data() + p.offset, p.filesz);
    }
    out.image = {out.mem.data(), out.mem.size(), out.ranges.data(),
                 out.ranges.size()};
    return out;
}

uint32_t ReadWord(const std::vector<uint8_t>& mem, size_t at) {
    uint32_t w = 0u;
    memcpy(&w, mem.data() + at, sizeof(w));
    return w;
}

void WriteWord(std::vector<uint8_t>& mem, size_t at, uint32_t w) {
    memcpy(mem.data() + at, &w, sizeof(w));
}

void TestResolverOnBinary(const char* path, size_t expected_bind,
                          size_t expected_trig, size_t expected_gs,
                          bool run_mutations) {
    MappedElf elf = LoadElf(path);
    WallpaperReconnectProfile profile{};
    const char* why = nullptr;
    if (!ResolveWallpaperReconnect(elf.image, &profile, &why)) {
        fprintf(stderr, "ResolveWallpaperReconnect failed on %s: %s\n", path,
                why ? why : "(null)");
        abort();
    }
    const uintptr_t base = reinterpret_cast<uintptr_t>(elf.mem.data());
    assert(profile.do_bind_service == base + expected_bind);
    assert(profile.trigger_reconnect == base + expected_trig);
    assert(profile.get_service == base + expected_gs);
    assert(profile.arc_header_size == 0x10u);
    assert(profile.destroyed_offset == 0xd5u);
    assert(profile.is_reconnecting_offset == 0x101u);
    assert(profile.is_bound_offset == 0x38u);

    if (!run_mutations) return;

    // 1. Corrupt do_bind_service prologue -> must fail closed.
    {
        const uint32_t saved = ReadWord(elf.mem, expected_bind);
        WriteWord(elf.mem, expected_bind, 0xd503201fu);  // nop
        WallpaperReconnectProfile bad{};
        assert(!ResolveWallpaperReconnect(elf.image, &bad, &why));
        assert(bad.do_bind_service == 0u);
        WriteWord(elf.mem, expected_bind, saved);
    }
    // 2. Corrupt trigger_reconnect prologue -> must fail closed.
    {
        const uint32_t saved = ReadWord(elf.mem, expected_trig);
        WriteWord(elf.mem, expected_trig, 0xd503201fu);
        WallpaperReconnectProfile bad{};
        assert(!ResolveWallpaperReconnect(elf.image, &bad, &why));
        WriteWord(elf.mem, expected_trig, saved);
    }
    // 3. Corrupt get_service mov x19, x0 -> must fail closed.
    {
        size_t mov_pc = 0u;
        for (size_t pc = expected_gs; pc < expected_gs + 0x30u; pc += 4u) {
            if (ReadWord(elf.mem, pc) == 0xaa0003f3u) {
                mov_pc = pc;
                break;
            }
        }
        assert(mov_pc != 0u);
        const uint32_t saved = ReadWord(elf.mem, mov_pc);
        WriteWord(elf.mem, mov_pc, 0xd503201fu);
        WallpaperReconnectProfile bad{};
        assert(!ResolveWallpaperReconnect(elf.image, &bad, &why));
        WriteWord(elf.mem, mov_pc, saved);
    }
    // 4. Corrupt "Service is not bound" string -> must fail closed.
    {
        const char needle[] = "Service is not bound";
        size_t str_pos = 0u;
        for (const auto& r : elf.ranges) {
            if (r.executable) continue;
            for (size_t p = r.begin; p + sizeof(needle) - 1u <= r.end; ++p) {
                if (memcmp(elf.mem.data() + p, needle, sizeof(needle) - 1u) == 0) {
                    str_pos = p;
                    break;
                }
            }
        }
        assert(str_pos != 0u);
        const uint8_t saved = elf.mem[str_pos];
        elf.mem[str_pos] = 'X';
        WallpaperReconnectProfile bad{};
        assert(!ResolveWallpaperReconnect(elf.image, &bad, &why));
        elf.mem[str_pos] = saved;
    }
    // 5. Re-verify clean resolution after restoring mutations.
    assert(ResolveWallpaperReconnect(elf.image, &profile, &why));
}

// ---------- State machine / retry / lazy-rebind unit tests ----------

struct FakeEnv {
    uint8_t arc_storage[0x120]{};
    int bind_calls = 0;
    int bind_fail_times = 0;
    int trigger_calls = 0;
    int get_service_calls = 0;
    int drop_error_calls = 0;
    int sleep_total_ms = 0;
    int64_t clock_ms = 10000;
    bool destroyed = false;
    bool reconnecting = false;
    bool bound = false;
    bool ui_thread = false;
    int destroy_after_sleep_ms = -1;
    int bind_becomes_ready_after_sleep_ms = -1;
    bool trigger_sets_bound_after_sleep = false;
    std::string last_event;
    size_t last_event_arg = 0u;
    int err_token_seq = 1;
};

FakeEnv* g_env = nullptr;

void* FakeDoBind(void**, void**, const char*, size_t) {
    ++g_env->bind_calls;
    if (g_env->bind_calls <= g_env->bind_fail_times) {
        return reinterpret_cast<void*>(
            static_cast<uintptr_t>(0x1000u + g_env->err_token_seq++));
    }
    g_env->bound = true;
    return nullptr;
}

void FakeTrigger(void**, const char* reason, size_t len) {
    assert(len == 14u && memcmp(reason, "reconnect_lazy", 14u) == 0);
    ++g_env->trigger_calls;
    g_env->reconnecting = true;
}

void FakeGetService(WallpaperServiceResult* out, void*) {
    ++g_env->get_service_calls;
    if (g_env->bound) {
        out->data = reinterpret_cast<void*>(0xdeadbeefu);
        out->second = reinterpret_cast<void*>(0xcafebabeu);
    } else {
        out->data = nullptr;
        out->second = reinterpret_cast<void*>(
            static_cast<uintptr_t>(0x2000u + g_env->err_token_seq++));
    }
}

void FakeDropError(void* err) {
    assert(err != nullptr);
    ++g_env->drop_error_calls;
}

bool FakeIsDestroyed(const void*) { return g_env->destroyed; }
bool FakeIsReconnecting(const void*) { return g_env->reconnecting; }
bool FakeIsBound(const void*) { return g_env->bound; }

void FakeSleepMs(uint32_t ms) {
    g_env->sleep_total_ms += static_cast<int>(ms);
    g_env->clock_ms += ms;
    if (g_env->destroy_after_sleep_ms >= 0 &&
            g_env->sleep_total_ms >= g_env->destroy_after_sleep_ms) {
        g_env->destroyed = true;
    }
    if (g_env->bind_becomes_ready_after_sleep_ms >= 0 &&
            g_env->sleep_total_ms >= g_env->bind_becomes_ready_after_sleep_ms) {
        g_env->bound = true;
        g_env->reconnecting = false;
    }
}

int64_t FakeNowMs() { return g_env->clock_ms; }
bool FakeIsUiThread() { return g_env->ui_thread; }

void FakeOnEvent(const char* event, size_t arg) {
    g_env->last_event = event;
    g_env->last_event_arg = arg;
}

WallpaperReconnectOps MakeFakeOps() {
    return WallpaperReconnectOps{
        FakeDoBind,
        FakeTrigger,
        FakeGetService,
        FakeDropError,
        FakeIsDestroyed,
        FakeIsReconnecting,
        FakeIsBound,
        FakeSleepMs,
        FakeNowMs,
        FakeIsUiThread,
        FakeOnEvent,
    };
}

void TestStateMachine() {
    const auto cfg = DefaultWallpaperReconnectConfig(0x10u);
    const auto ops = MakeFakeOps();

    // Case 1: Immediate bind success -> 0 retries, 0 error drops.
    {
        FakeEnv env{};
        g_env = &env;
        void* arc = env.arc_storage;
        void* rt = nullptr;
        void* res = RunWallpaperDoBindWithRetry(ops, cfg, &arc, &rt,
                                                "reconnect_immediate", 19u);
        assert(res == nullptr && env.bind_calls == 1 && env.drop_error_calls == 0);
    }

    // Case 2: Cold boot bind_service_sync failure -> NEVER retries.
    {
        FakeEnv env{};
        env.bind_fail_times = 10;
        g_env = &env;
        void* arc = env.arc_storage;
        void* rt = nullptr;
        void* res = RunWallpaperDoBindWithRetry(ops, cfg, &arc, &rt,
                                                "bind_service_sync", 17u);
        assert(res != nullptr && env.bind_calls == 1 &&
               env.drop_error_calls == 0 && env.sleep_total_ms == 0);
    }

    // Case 3: Reconnect fails twice (mid-update), succeeds on 2nd retry.
    {
        FakeEnv env{};
        env.bind_fail_times = 2;
        g_env = &env;
        void* arc = env.arc_storage;
        void* rt = nullptr;
        void* res = RunWallpaperDoBindWithRetry(ops, cfg, &arc, &rt,
                                                "reconnect_immediate", 19u);
        assert(res == nullptr);
        assert(env.bind_calls == 3);
        assert(env.drop_error_calls == 2);
        assert(env.sleep_total_ms == 2000 + 4000);
        assert(env.last_event == "retry_succeeded" && env.last_event_arg == 2u);
    }

    // Case 4: Destroyed mid-backoff -> cancels promptly, preserves last error.
    {
        FakeEnv env{};
        env.bind_fail_times = 10;
        env.destroy_after_sleep_ms = 500;
        g_env = &env;
        void* arc = env.arc_storage;
        void* rt = nullptr;
        void* res = RunWallpaperDoBindWithRetry(ops, cfg, &arc, &rt,
                                                "reconnect_immediate", 19u);
        assert(res != nullptr);
        assert(env.bind_calls == 1);
        assert(env.drop_error_calls == 0);
        assert(env.sleep_total_ms == 500);
        assert(env.last_event == "retry_cancelled");
    }

    // Case 5: Exhausts all 6 retries -> drops 6 prior errors, returns final error.
    {
        FakeEnv env{};
        env.bind_fail_times = 10;
        g_env = &env;
        void* arc = env.arc_storage;
        void* rt = nullptr;
        void* res = RunWallpaperDoBindWithRetry(ops, cfg, &arc, &rt,
                                                "reconnect_after_cooldown", 24u);
        assert(res != nullptr);
        assert(env.bind_calls == 7);
        assert(env.drop_error_calls == 6);
        assert(env.sleep_total_ms == 79000);
        assert(env.last_event == "retry_exhausted" && env.last_event_arg == 6u);
    }

    // Case 6: get_service fast path (already bound) -> 0 overhead, 0 triggers.
    {
        FakeEnv env{};
        env.bound = true;
        g_env = &env;
        WallpaperReconnectState st{0};
        WallpaperServiceResult out{};
        RunWallpaperGetServiceWithRecovery(ops, cfg, &st, &out,
                                           env.arc_storage + 0x10u);
        assert(out.data != nullptr && env.get_service_calls == 1 &&
               env.trigger_calls == 0 && env.sleep_total_ms == 0);
    }

    // Case 7: get_service unbound on worker thread -> triggers lazy reconnect,
    // inline micro-wait catches connection at 45ms, drops stale error, returns live service!
    {
        FakeEnv env{};
        env.bound = false;
        env.bind_becomes_ready_after_sleep_ms = 45;
        g_env = &env;
        WallpaperReconnectState st{0};
        WallpaperServiceResult out{};
        RunWallpaperGetServiceWithRecovery(ops, cfg, &st, &out,
                                           env.arc_storage + 0x10u);
        assert(out.data == reinterpret_cast<void*>(0xdeadbeefu));
        assert(env.trigger_calls == 1);
        assert(env.get_service_calls == 2);
        assert(env.drop_error_calls == 1);
        assert(env.sleep_total_ms == 45);
        assert(env.last_event == "inline_recovered");
    }

    // Case 8: get_service on UI thread -> fires async trigger, never sleeps.
    {
        FakeEnv env{};
        env.bound = false;
        env.ui_thread = true;
        g_env = &env;
        WallpaperReconnectState st{0};
        WallpaperServiceResult out{};
        RunWallpaperGetServiceWithRecovery(ops, cfg, &st, &out,
                                           env.arc_storage + 0x10u);
        assert(out.data == nullptr);
        assert(env.trigger_calls == 1);
        assert(env.sleep_total_ms == 0);
    }

    // Case 9: Throttle prevents spamming trigger_reconnect within 3000ms.
    {
        FakeEnv env{};
        env.bound = false;
        env.ui_thread = true;
        g_env = &env;
        WallpaperReconnectState st{0};
        WallpaperServiceResult out{};
        RunWallpaperGetServiceWithRecovery(ops, cfg, &st, &out,
                                           env.arc_storage + 0x10u);
        assert(env.trigger_calls == 1);
        env.reconnecting = false;
        env.clock_ms += 1000;  // still within 3000ms throttle
        RunWallpaperGetServiceWithRecovery(ops, cfg, &st, &out,
                                           env.arc_storage + 0x10u);
        assert(env.trigger_calls == 1);
        env.clock_ms += 2500;  // past 3000ms throttle
        RunWallpaperGetServiceWithRecovery(ops, cfg, &st, &out,
                                           env.arc_storage + 0x10u);
        assert(env.trigger_calls == 2);
    }

    // Case 10: Error while is_bound == true (non-unbound error) -> no reconnect.
    {
        FakeEnv env{};
        env.bound = true;
        g_env = &env;
        auto ops_err = ops;
        ops_err.get_service = [](WallpaperServiceResult* out, void*) {
            ++g_env->get_service_calls;
            out->data = nullptr;
            out->second = reinterpret_cast<void*>(0x3000u);
        };
        WallpaperReconnectState st{0};
        WallpaperServiceResult out{};
        RunWallpaperGetServiceWithRecovery(ops_err, cfg, &st, &out,
                                           env.arc_storage + 0x10u);
        assert(out.data == nullptr && env.trigger_calls == 0 &&
               env.sleep_total_ms == 0);
    }
}

}  // namespace

int main(int argc, char** argv) {
    TestStateMachine();
    // Optional CLI args: <libapp_launcher.so> <bind_hex> <trig_hex> <gs_hex> [mutate]
    if (argc >= 5) {
        const size_t bind = strtoull(argv[2], nullptr, 0);
        const size_t trig = strtoull(argv[3], nullptr, 0);
        const size_t gs = strtoull(argv[4], nullptr, 0);
        const bool mutate = argc >= 6 && strcmp(argv[5], "mutate") == 0;
        TestResolverOnBinary(argv[1], bind, trig, gs, mutate);
    }
    printf("wallpaper_reconnect_test: PASS\n");
    return 0;
}

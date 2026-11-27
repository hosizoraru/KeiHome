// SPDX-License-Identifier: Apache-2.0
#include "monet_image_trace.h"
#include "lsposed_hook_backend.h"
#include <android/log.h>
#include <inttypes.h>
#include <poll.h>
#include <pthread.h>
#include <time.h>

#ifndef KH_IMAGE_TRACE_DELAY_MS
#define KH_IMAGE_TRACE_DELAY_MS 30000
#endif
#ifndef KH_IMAGE_TRACE_WINDOW_MS
#define KH_IMAGE_TRACE_WINDOW_MS 120000
#endif
static_assert(KH_IMAGE_TRACE_DELAY_MS >= 1000 && KH_IMAGE_TRACE_DELAY_MS <= 300000);
static_assert(KH_IMAGE_TRACE_WINDOW_MS >= 1000 && KH_IMAGE_TRACE_WINDOW_MS <= 300000);

extern "C" {
alignas(64) MonetImageTraceControl kh_image_trace_control{};
alignas(64) MonetImageTraceRecord kh_image_trace_records[KH_IMAGE_TRACE_CAPACITY]{};
uint32_t kh_image_trace_image_cid = 0, kh_image_trace_dispose_selector = 0;
uintptr_t kh_image_trace_raw_update_return = 0;
void* kh_image_trace_dispose_original = nullptr;
void* kh_image_trace_clone_original = nullptr;
void* kh_image_trace_build_original = nullptr;
}

namespace {
constexpr char kTag[] = "KeiHomeImageTrace";
uint32_t g_started = 0;
uintptr_t g_base = 0;
uint64_t NowMs() {
    timespec t{};
    if (clock_gettime(CLOCK_MONOTONIC, &t)) return 0;
    return uint64_t(t.tv_sec)*1000 + uint64_t(t.tv_nsec)/1000000;
}
uintptr_t Rva(uint64_t pc) {
    return pc >= g_base && pc-g_base < 128u*1024u*1024u ? uintptr_t(pc)-g_base : 0;
}
void Drain(uint32_t* emitted) {
    uint32_t count = __atomic_load_n(&kh_image_trace_control.count, __ATOMIC_ACQUIRE);
    if (count > KH_IMAGE_TRACE_CAPACITY) count = KH_IMAGE_TRACE_CAPACITY;
    for (; *emitted < count; ++*emitted) {
        // Each immutable record is release-published by count. None of its
        // address bits is ever dereferenced here (including disposal PCs).
        const auto& r = kh_image_trace_records[*emitted];
        __android_log_print(ANDROID_LOG_INFO, kTag,
            "KHIMG seq=%u kind=%" PRIu64 " thr=%" PRIx64 " caller=%" PRIxPTR
            " state=%" PRIx64 " first=%" PRIx64 " second=%" PRIx64
            " d1=%" PRIu64 " d2=%" PRIu64 " dispose_pc=%" PRIxPTR " backing=%" PRIx64
            " caller_abs=%" PRIx64 " dispose_abs=%" PRIx64,
            *emitted+1, r.kind, r.thread_bits, Rva(r.caller_pc), r.state_bits,
            r.first_bits, r.second_bits, r.first_disposed, r.second_disposed,
            Rva(r.state_dispose_pc), r.backing_bits, r.caller_pc, r.state_dispose_pc);
    }
}
void* Worker(void*) {
    uint64_t now = NowMs();
    if (!now) return nullptr;
    const uint64_t arm_at = now + KH_IMAGE_TRACE_DELAY_MS;
    while (now < arm_at) {
        poll(nullptr, 0, 50);
        now = NowMs();
        if (!now) return nullptr;
    }
    uint32_t emitted = 0;
    const uint64_t stop_at = now + KH_IMAGE_TRACE_WINDOW_MS;
    // Worker and static record storage live independently of Java policy owners.
    // There is one capture per process; never reset count, slots, or the loss bit.
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "KHIMG armed capacity=%u window_ms=%u monotonic_ms=%" PRIu64 " diagnostic_only=1",
        KH_IMAGE_TRACE_CAPACITY, KH_IMAGE_TRACE_WINDOW_MS, now);
    __atomic_store_n(&kh_image_trace_control.enabled, 1u, __ATOMIC_RELEASE);
    while (now && now < stop_at && emitted < KH_IMAGE_TRACE_CAPACITY) {
        Drain(&emitted);
        poll(nullptr, 0, 10);
        now = NowMs();
    }
    __atomic_store_n(&kh_image_trace_control.enabled, 0u, __ATOMIC_RELEASE);
    // Let an admitted producer publish. Storage is static forever even if the
    // bounded drain times out, so there is no free/reuse race or root lifetime.
    for (unsigned i = 0; i < 50 && __atomic_load_n(&kh_image_trace_control.busy, __ATOMIC_ACQUIRE); ++i)
        poll(nullptr, 0, 10);
    Drain(&emitted);
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "KHIMG ended emitted=%u lost=%u pending=%u full=%u clock_ok=%u diagnostic_only=1",
        emitted, __atomic_load_n(&kh_image_trace_control.lost, __ATOMIC_ACQUIRE),
        __atomic_load_n(&kh_image_trace_control.busy, __ATOMIC_ACQUIRE),
        emitted == KH_IMAGE_TRACE_CAPACITY ? 1u : 0u, now ? 1u : 0u);
    return nullptr;
}
} // namespace

void InstallMonetImageTraceAtLibraryLoad(const MonetSelectionImageView& image) {
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&g_started, &expected, 1u, false,
                                    __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return;
    MonetImageTraceProfile p{};
    const char* reason = nullptr;
    if (!ResolveMonetImageTrace(image, &p, &reason)) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "KHIMG inactive: %s", reason);
        return;
    }
    g_base = image.load_bias;
    kh_image_trace_image_cid = p.image_cid;
    kh_image_trace_dispose_selector = p.dispose_selector;
    kh_image_trace_raw_update_return = p.raw_update+0x7c;
    if (InstallInlineHook(reinterpret_cast<void*>(p.dispose),
            reinterpret_cast<void*>(kh_image_trace_dispose_bridge), &kh_image_trace_dispose_original) != kHookSuccess ||
        InstallInlineHook(reinterpret_cast<void*>(p.clone),
            reinterpret_cast<void*>(kh_image_trace_clone_bridge), &kh_image_trace_clone_original) != kHookSuccess ||
        InstallInlineHook(reinterpret_cast<void*>(p.build),
            reinterpret_cast<void*>(kh_image_trace_build_bridge), &kh_image_trace_build_original) != kHookSuccess) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "KHIMG inactive: partial installation; observers remain pass-through");
        return;
    }
    pthread_attr_t attr{};
    if (pthread_attr_init(&attr)) return;
    pthread_t thread{};
    const int detached = pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    const int started = detached ? detached : pthread_create(&thread, &attr, Worker, nullptr);
    pthread_attr_destroy(&attr);
    __android_log_print(started ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, kTag,
        "KHIMG installed worker=%d delay_ms=%u dispose=%" PRIxPTR " clone=%" PRIxPTR
        " build=%" PRIxPTR " clear=%" PRIxPTR " put=%" PRIxPTR
        " raw_update=%" PRIxPTR " image_cid=%u dispose_selector=%u diagnostic_only=1",
        started, KH_IMAGE_TRACE_DELAY_MS, Rva(p.dispose), Rva(p.clone), Rva(p.build),
        Rva(p.clear), Rva(p.put), Rva(p.raw_update), p.image_cid, p.dispose_selector);
}

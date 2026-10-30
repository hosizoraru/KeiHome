// SPDX-License-Identifier: Apache-2.0
#include "monet_image_recovery.h"
#include "lsposed_hook_backend.h"
#include <android/log.h>
#include <inttypes.h>

extern "C" {
MonetImageRecoveryConfig kh_image_recovery_config{};
alignas(64) MonetImageRecoveryCounts kh_image_recovery_counts{};
void* kh_image_recovery_original = nullptr;
}
namespace {
constexpr char kTag[] = "KeiHomeImageRecovery";
uint32_t g_started = 0;
}

void InstallMonetImageRecoveryAtLibraryLoad(const MonetSelectionImageView& image) {
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&g_started, &expected, 1u, false,
                                    __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) return;
#if defined(KH_MONET_IMAGE_TRACE)
    (void)image;
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "KHIMG_RECOVERY disabled diagnostic_stock=1; Image.clone belongs to trace only");
#else
    MonetImageRecoveryProfile p{};
    const char* reason = nullptr;
    if (!ResolveMonetImageRecovery(image, &p, &reason)) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "KHIMG_RECOVERY inactive: %s", reason);
        return;
    }
    kh_image_recovery_config = p.config;
    // Immutable scalar contract is set before any isolate can execute this ELF.
    if (InstallInlineHook(reinterpret_cast<void*>(p.clone),
            reinterpret_cast<void*>(kh_image_recovery_bridge), &kh_image_recovery_original) != kHookSuccess) {
        kh_image_recovery_config.enabled = 0;
        __android_log_print(ANDROID_LOG_WARN, kTag, "KHIMG_RECOVERY inactive: clone hook installation");
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "KHIMG_RECOVERY installed clone=%" PRIxPTR " update_return=%" PRIxPTR
        " image_cid=%u render_cid=%u spill=%d image_field=%u disposed_field=%u backing_field=%u"
        " limit=%u repairs=0 update_only=1",
        p.clone-image.load_bias, p.config.update_return-image.load_bias,
        p.config.image_cid, p.config.render_cid, p.config.receiver_spill,
        p.config.render_image, p.config.disposed, p.config.backing, KH_IMAGE_RECOVERY_LIMIT);
#endif
}

void ReportMonetImageRecovery(uint64_t now) {
    // Single existing policy worker owns these bookkeeping scalars. No Dart
    // addresses, extra worker, reset, or policy-dependent lifetime is involved.
    static uint64_t last_report = 0;
    static uint32_t last_count = 0, last_uncounted = 0;
    if (!now || (last_report && (now <= last_report || now-last_report < 1000000000u))) return;
    const uint32_t count = __atomic_load_n(&kh_image_recovery_counts.repairs, __ATOMIC_ACQUIRE);
    const uint32_t uncounted = __atomic_load_n(&kh_image_recovery_counts.uncounted, __ATOMIC_ACQUIRE);
    if (count == last_count && uncounted == last_uncounted) return;
    __android_log_print(ANDROID_LOG_INFO, kTag,
        "KHIMG_RECOVERY repairs=%u uncounted=%u saturated=%u monotonic_ns=%" PRIu64 " update_only=1",
        count, uncounted, count == KH_IMAGE_RECOVERY_LIMIT ? 1u : 0u, now);
    last_count = count;
    last_uncounted = uncounted;
    last_report = now;
}

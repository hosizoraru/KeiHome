// SPDX-License-Identifier: Apache-2.0
#include "monet_image_recovery.h"
#include "lsposed_hook_backend.h"
#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <string>
#include <vector>

static std::vector<std::string> logs;
static unsigned resolves, hooks;
static bool rejected, hook_failed;
extern "C" void kh_image_recovery_bridge() {}
extern "C" int __android_log_print(int, const char*, const char* fmt, ...) {
    char buffer[1024]; va_list args; va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args); va_end(args);
    logs.emplace_back(buffer); return 0;
}
bool ResolveMonetImageRecovery(const MonetSelectionImageView&, MonetImageRecoveryProfile* p, const char** reason) {
    ++resolves;
    if (rejected) { *reason = "test rejection"; return false; }
    p->clone = 0x2000; p->config.enabled = 1; p->config.update_return = 0x307c;
    return true;
}
int InstallInlineHook(void* pc, void* handler, void** original) {
    ++hooks;
    assert(pc == reinterpret_cast<void*>(0x2000));
    assert(handler == reinterpret_cast<void*>(kh_image_recovery_bridge));
    assert(kh_image_recovery_config.enabled == 1);
    if (hook_failed) return kHookFailed;
    *original = reinterpret_cast<void*>(0x4000); return kHookSuccess;
}
int main(int argc, char** argv) {
    rejected = argc > 1 && std::string(argv[1]) == "reject";
    hook_failed = argc > 1 && std::string(argv[1]) == "hook-fail";
    MonetSelectionImageView view{0x1000, nullptr, 0};
    InstallMonetImageRecoveryAtLibraryLoad(view);
    InstallMonetImageRecoveryAtLibraryLoad(view);
    assert(logs.size() == 1);
#if defined(KH_MONET_IMAGE_TRACE)
    assert(!resolves && !hooks && !kh_image_recovery_config.enabled);
    assert(logs[0].find("disabled diagnostic_stock=1") != std::string::npos);
#else
    assert(resolves == 1 && hooks == (rejected ? 0u : 1u));
    assert(kh_image_recovery_config.enabled == uint32_t(!rejected && !hook_failed));
    assert(logs[0].find(rejected || hook_failed ? "inactive:" : "installed") != std::string::npos);
    if (!rejected && !hook_failed) assert(kh_image_recovery_original == reinterpret_cast<void*>(0x4000));
#endif
    ReportMonetImageRecovery(1000000000); assert(logs.size() == 1); // silent when unused
    kh_image_recovery_counts.repairs = 1;
    ReportMonetImageRecovery(0); assert(logs.size() == 1);
    ReportMonetImageRecovery(2000000000); assert(logs.size() == 2);
    assert(logs.back().find("repairs=1 uncounted=0 saturated=0") != std::string::npos);
    kh_image_recovery_counts.repairs = 2;
    ReportMonetImageRecovery(2000000010); assert(logs.size() == 2); // rate bound
    ReportMonetImageRecovery(1000000000); assert(logs.size() == 2); // clock rewind
    ReportMonetImageRecovery(3000000000); assert(logs.size() == 3);
    ReportMonetImageRecovery(4000000000); assert(logs.size() == 3); // no repeated logs
    kh_image_recovery_counts.uncounted = 1;
    ReportMonetImageRecovery(4000000000); assert(logs.size() == 4);
    assert(logs.back().find("uncounted=1") != std::string::npos);
    kh_image_recovery_counts.repairs = KH_IMAGE_RECOVERY_LIMIT;
    ReportMonetImageRecovery(5000000000); assert(logs.size() == 5);
    assert(logs.back().find("saturated=1") != std::string::npos);
    ReportMonetImageRecovery(9000000000); assert(logs.size() == 5);
    assert(kh_image_recovery_counts.repairs == KH_IMAGE_RECOVERY_LIMIT && kh_image_recovery_counts.uncounted == 1);
    puts("install-once/trace-exclusion/worker scalar reporting passed");
}

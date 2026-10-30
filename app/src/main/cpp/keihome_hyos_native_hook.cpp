// SPDX-License-Identifier: Apache-2.0
//
// The launcher is a Flutter/Dart AOT process. This payload changes one
// dynamically resolved branch in the async capability check. It does not replace
// the Future-returning Dart function, retain Dart objects, replay a root
// tree, or restart the launcher.

#include "lsposed_hook_backend.h"
#include "native_api.h"
#include "monet_hooks.h"

#include <android/log.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

namespace {

constexpr char kLogTag[] = "KeiHomeMonet";
constexpr char kLauncherProcessName[] = "com.miui.home";
constexpr char kHyosSpawnerPath[] = "/system_ext/bin/hyos_spawner";
constexpr char kDartLibraryName[] = "libapp.so";
constexpr char kHyperRuntimeName[] = "libhyper_os_flutter.so";
using NativeOnModuleLoaded = void (*)(const char*, void*);

volatile uint32_t g_hook_state = 0u;
uint32_t g_existing_snapshot = 0u;

bool EndsWith(const char* value, const char* suffix) {
    if (value == nullptr || suffix == nullptr) return false;
    const size_t value_length = strlen(value);
    const size_t suffix_length = strlen(suffix);
    return value_length >= suffix_length &&
           strcmp(value + value_length - suffix_length, suffix) == 0;
}

bool StringsEqual(const char* value, const char* expected) {
    return value != nullptr && expected != nullptr && strcmp(value, expected) == 0;
}

bool IsLauncherProcess() {
    char command_line[256] = {};
    FILE* command_file = fopen("/proc/self/cmdline", "re");
    if (command_file == nullptr) return false;
    const size_t length =
        fread(command_line, 1u, sizeof(command_line) - 1u, command_file);
    fclose(command_file);
    if (length == 0u) return false;
    command_line[length] = '\0';
    return StringsEqual(command_line, kLauncherProcessName);
}

bool IsHyosSpawnerProcess() {
    char executable[128] = {};
    const ssize_t length =
        readlink("/proc/self/exe", executable, sizeof(executable) - 1u);
    if (length <= 0 || static_cast<size_t>(length) >= sizeof(executable)) {
        return false;
    }
    executable[length] = '\0';
    return StringsEqual(executable, kHyosSpawnerPath);
}

bool IsLauncherHookProcess() {
    // HYOS loads this module in the spawner before it forks the launcher.
    // Keep the executable check exact so unrelated app_process children do
    // not receive the AOT patch.
    return IsLauncherProcess() || IsHyosSpawnerProcess();
}

bool TryInstallMonochromeGatePatch(void* handle) {
    uint32_t expected = 0u;
    if (!__atomic_compare_exchange_n(
            &g_hook_state, &expected, uint32_t{1}, false, __ATOMIC_ACQ_REL,
            __ATOMIC_ACQUIRE)) {
        return expected == 2u;
    }

    const char* rejection = "unknown";
    MonochromeGatePatch patch{};
    if (!ResolveLoadedMonochromeGate(handle, &patch, &rejection)) {
        __atomic_store_n(&g_hook_state, uint32_t{3}, __ATOMIC_RELEASE);
        __android_log_print(
            ANDROID_LOG_WARN, kLogTag, "Monet hook disabled: %s", rejection);
        return false;
    }
    if (!EnsureLsposedMadviseGuard()) {
        __atomic_store_n(&g_hook_state, uint32_t{3}, __ATOMIC_RELEASE);
        __android_log_print(
            ANDROID_LOG_WARN, kLogTag,
            "Monet hook disabled: Flutter madvise guard unavailable");
        return false;
    }
    if (PatchArm64Instruction(
            patch.address, patch.expected_instruction,
            patch.replacement_instruction) != kHookSuccess) {
        __atomic_store_n(&g_hook_state, uint32_t{3}, __ATOMIC_RELEASE);
        __android_log_print(
            ANDROID_LOG_WARN, kLogTag, "Monet hook installation failed");
        return false;
    }
    __atomic_store_n(&g_hook_state, uint32_t{2}, __ATOMIC_RELEASE);
    __android_log_print(
        ANDROID_LOG_INFO, kLogTag,
        "Monet async support gate patched without launcher restart: target=%p",
        patch.address);
    return true;
}

void OnLsposedLibraryLoaded(const char* name, void* handle);
void HandleLoadedLibrary(const char* name, void* handle, bool fresh_load);

void BackfillLoadedLibrary(const char* name) {
    if (name == nullptr) return;
    void* handle = dlopen(name, RTLD_NOW | RTLD_NOLOAD);
    if (handle == nullptr) return;
    __android_log_print(
        ANDROID_LOG_INFO, kLogTag, "backfilling loaded library: %s", name);
    HandleLoadedLibrary(name, handle, false);
    dlclose(handle);
}

void BackfillLoadedLibraries() {
    BackfillLoadedLibrary(kHyperRuntimeName);
    BackfillLoadedLibrary(kDartLibraryName);
}

void OnLsposedLibraryLoaded(const char* name, void* handle) {
    HandleLoadedLibrary(name, handle, true);
}

void HandleLoadedLibrary(const char* name, void* handle, bool fresh_load) {
    if (name == nullptr || handle == nullptr || !IsLauncherHookProcess()) return;
    if (EndsWith(name, kHyperRuntimeName)) {
        EnsureLsposedMadviseGuard(name);
        return;
    }
    if (EndsWith(name, kDartLibraryName)) {
        if (!fresh_load) __atomic_store_n(&g_existing_snapshot, 1u, __ATOMIC_RELEASE);
        // Multi-instruction detours are installed before the newly loaded
        // image starts executing; an existing isolate is never repatched.
        if (fresh_load && !__atomic_load_n(&g_existing_snapshot, __ATOMIC_ACQUIRE) &&
                IsLauncherProcess()) InstallMonetHooksAtLibraryLoad(handle);
        TryInstallMonochromeGatePatch(handle);
    }
}

}  // namespace

extern "C" __attribute__((visibility("default")))
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
    const bool backend_ready = InitializeLsposedHookBackend(entries);
    const bool spawner_process = IsHyosSpawnerProcess();
    const bool launcher_process = IsLauncherProcess();
    __android_log_print(
        ANDROID_LOG_INFO, kLogTag,
        "native_init checks: entries=%u hook=%u unhook=%u backend=%u "
        "hyos_exe=%u launcher_cmdline=%u",
        entries != nullptr ? 1u : 0u,
        entries != nullptr && entries->hookFunc != nullptr ? 1u : 0u,
        entries != nullptr && entries->unhookFunc != nullptr ? 1u : 0u,
        backend_ready ? 1u : 0u, spawner_process ? 1u : 0u,
        launcher_process ? 1u : 0u);
    if (!backend_ready || !IsLauncherHookProcess()) {
        __android_log_print(
            ANDROID_LOG_WARN, kLogTag,
            "native_init rejected non-launcher HYOS process");
        return nullptr;
    }
    __android_log_print(
        ANDROID_LOG_INFO, kLogTag,
        "native_init accepted %s",
        spawner_process ? "hyos_spawner" : "com.miui.home");
    BackfillLoadedLibraries();
    return OnLsposedLibraryLoaded;
}

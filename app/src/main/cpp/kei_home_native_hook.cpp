// SPDX-License-Identifier: Apache-2.0

#include "lsposed_hook_backend.h"
#include "native_api.h"

#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

namespace {

constexpr char kLogTag[] = "KeiHomeLsp";
constexpr char kLauncherPackage[] = "com.miui.home";
constexpr char kSpawnerPath[] = "/system_ext/bin/hyos_spawner";
constexpr char kHyperRuntimeName[] = "libhyper_os_flutter.so";
constexpr char kDartLibraryName[] = "libapp.so";
constexpr char kDartSnapshotInstructionsSymbol[] =
        "_kDartIsolateSnapshotInstructions";
constexpr char kDartSnapshotBuildIdSymbol[] = "_kDartSnapshotBuildId";

constexpr uintptr_t kDartSnapshotInstructionsOffset = 0x866940u;
constexpr uintptr_t kApplyNightModeOffset = 0x8fc850u;
constexpr uintptr_t kLauncherLifecycleOffset = 0xce0cc8u;

constexpr int64_t kStartupSettleTimeMs = 3000;
constexpr int64_t kBackgroundRestartDelayMs = 15000;

constexpr uint8_t kExpectedSnapshotBuildId[32] = {
        0x04, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00,
        0x03, 0x00, 0x00, 0x00, 0x47, 0x4e, 0x55, 0x00,
        0x4f, 0x1b, 0xda, 0xed, 0x62, 0xb7, 0x7e, 0xd2,
        0xde, 0x7d, 0x3e, 0xbc, 0x34, 0xe4, 0x53, 0xca,
};

constexpr uint8_t kApplyNightModePrologue[16] = {
        0xfd, 0x79, 0xbf, 0xa9, 0xfd, 0x03, 0x0f, 0xaa,
        0xef, 0x41, 0x00, 0xd1, 0xe0, 0x03, 0x02, 0xaa,
};

constexpr uint8_t kLauncherLifecyclePrologue[16] = {
        0xfd, 0x79, 0xbf, 0xa9, 0xfd, 0x03, 0x0f, 0xaa,
        0xef, 0x21, 0x00, 0xd1, 0x70, 0xaf, 0x63, 0xf9,
};

extern "C" {

__attribute__((visibility("hidden")))
void* g_kei_home_apply_night_original = nullptr;

__attribute__((visibility("hidden")))
void KeiHomeApplyNightModeHook(
        void* argument_descriptor, void* receiver, void* enabled);

__attribute__((visibility("hidden")))
void* g_kei_home_lifecycle_original = nullptr;

__attribute__((visibility("hidden")))
void KeiHomeLauncherLifecycleHook(
        void* argument_descriptor, void* state, void* lifecycle);

}

void* g_dart_base = nullptr;
volatile uint32_t g_hooks_state = 0u;
volatile uint32_t g_apply_night_count = 0u;
volatile uintptr_t g_last_night_value = 0u;
volatile uint32_t g_have_night_value = 0u;
volatile uint32_t g_mode_generation = 0u;
volatile uint32_t g_launcher_resumed = 1u;
volatile uint32_t g_restart_when_paused = 0u;
volatile uint32_t g_restart_scheduled = 0u;
volatile int64_t g_hooks_ready_at_ms = 0;

bool StringsEqual(const char* left, const char* right) {
  if (left == nullptr || right == nullptr) return false;
  return strcmp(left, right) == 0;
}

bool IsLauncherProcess() {
  const int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
  if (fd < 0) return false;

  char command_line[64]{};
  ssize_t result;
  do {
    result = read(fd, command_line, sizeof(command_line));
  } while (result < 0 && errno == EINTR);
  close(fd);
  if (result <= 0) return false;

  constexpr size_t expected_length = sizeof(kLauncherPackage) - 1u;
  return static_cast<size_t>(result) > expected_length &&
         command_line[expected_length] == '\0' &&
         StringsEqual(command_line, kLauncherPackage);
}

bool IsHyosSpawnerProcess() {
  char executable[128]{};
  const ssize_t length = readlink(
          "/proc/self/exe", executable, sizeof(executable) - 1u);
  if (length <= 0 || static_cast<size_t>(length) >= sizeof(executable)) {
    return false;
  }
  executable[length] = '\0';
  return StringsEqual(executable, kSpawnerPath);
}

bool IsTargetProcess() {
  return IsLauncherProcess() || IsHyosSpawnerProcess();
}

bool IsDartLibraryPath(const char* name) {
  if (name == nullptr) return false;
  const size_t name_length = strlen(name);
  constexpr size_t suffix_length = sizeof(kDartLibraryName) - 1u;
  return StringsEqual(name, kDartLibraryName) ||
         (name_length >= suffix_length &&
          StringsEqual(name + name_length - suffix_length,
                       kDartLibraryName));
}

bool MatchesBytes(
        const void* base, uintptr_t offset, const uint8_t* expected,
        size_t size) {
  return base != nullptr && expected != nullptr &&
         memcmp(static_cast<const uint8_t*>(base) + offset,
                expected, size) == 0;
}

int64_t MonotonicTimeMs() {
  timespec now{};
  if (clock_gettime(CLOCK_MONOTONIC, &now) != 0) return 0;
  return static_cast<int64_t>(now.tv_sec) * 1000 +
         now.tv_nsec / 1000000;
}

void ScheduleBackgroundLauncherRestart();

void* RestartLauncherAfterDelay(void* opaque) {
  const uint32_t generation =
          static_cast<uint32_t>(reinterpret_cast<uintptr_t>(opaque));
  timespec delay{
          static_cast<time_t>(kBackgroundRestartDelayMs / 1000),
          static_cast<long>((kBackgroundRestartDelayMs % 1000) * 1000000)};
  while (nanosleep(&delay, &delay) != 0 && errno == EINTR) {
  }

  const bool still_paused =
          __atomic_load_n(&g_launcher_resumed, __ATOMIC_ACQUIRE) == 0u;
  const bool still_pending =
          __atomic_load_n(&g_restart_when_paused, __ATOMIC_ACQUIRE) != 0u;
  const bool same_generation =
          __atomic_load_n(&g_mode_generation, __ATOMIC_ACQUIRE) == generation;
  if (!still_paused || !still_pending || !same_generation) {
    __atomic_store_n(&g_restart_scheduled, uint32_t{0}, __ATOMIC_RELEASE);
    __android_log_print(
            ANDROID_LOG_INFO, kLogTag,
            "deferred launcher restart canceled: resumed=%u pending=%u "
            "generation=%u",
            still_paused ? 0u : 1u, still_pending ? 1u : 0u,
            __atomic_load_n(&g_mode_generation, __ATOMIC_ACQUIRE));
    if (still_paused && still_pending) {
      ScheduleBackgroundLauncherRestart();
    }
    return nullptr;
  }

  __android_log_print(
          ANDROID_LOG_INFO, kLogTag,
          "restarting launcher in background after icon mode change");
  _exit(0);
}

void ScheduleBackgroundLauncherRestart() {
  uint32_t expected = 0u;
  if (!__atomic_compare_exchange_n(
              &g_restart_scheduled, &expected, uint32_t{1}, false,
              __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
    return;
  }

  const uint32_t generation =
          __atomic_load_n(&g_mode_generation, __ATOMIC_ACQUIRE);
  pthread_t thread{};
  if (pthread_create(
              &thread, nullptr, RestartLauncherAfterDelay,
              reinterpret_cast<void*>(
                      static_cast<uintptr_t>(generation))) != 0) {
    __atomic_store_n(&g_restart_scheduled, uint32_t{0}, __ATOMIC_RELEASE);
    __android_log_print(
            ANDROID_LOG_WARN, kLogTag,
            "failed to schedule background launcher restart");
    return;
  }
  pthread_detach(thread);
}

void BeginModeChange(int32_t state) {
  const uint32_t generation = __atomic_add_fetch(
          &g_mode_generation, uint32_t{1}, __ATOMIC_ACQ_REL);
  __atomic_store_n(&g_restart_when_paused, uint32_t{1}, __ATOMIC_RELEASE);

  if (__atomic_load_n(&g_launcher_resumed, __ATOMIC_ACQUIRE) == 0u) {
    __android_log_print(
            ANDROID_LOG_INFO, kLogTag,
            "night mode changed to %d while launcher is paused "
            "(generation=%u); restart will run out of sight",
            state, generation);
    ScheduleBackgroundLauncherRestart();
    return;
  }

  __android_log_print(
          ANDROID_LOG_INFO, kLogTag,
          "night mode changed to %d while launcher is visible "
          "(generation=%u); deferring restart until it leaves foreground",
          state, generation);
}

void ResetAfterFork() {
  g_dart_base = nullptr;
  g_kei_home_apply_night_original = nullptr;
  g_kei_home_lifecycle_original = nullptr;
  __atomic_store_n(&g_apply_night_count, uint32_t{0}, __ATOMIC_RELEASE);
  __atomic_store_n(&g_last_night_value, uintptr_t{0}, __ATOMIC_RELEASE);
  __atomic_store_n(&g_have_night_value, uint32_t{0}, __ATOMIC_RELEASE);
  __atomic_store_n(&g_mode_generation, uint32_t{0}, __ATOMIC_RELEASE);
  __atomic_store_n(&g_launcher_resumed, uint32_t{1}, __ATOMIC_RELEASE);
  __atomic_store_n(&g_restart_when_paused, uint32_t{0}, __ATOMIC_RELEASE);
  __atomic_store_n(&g_restart_scheduled, uint32_t{0}, __ATOMIC_RELEASE);
  __atomic_store_n(&g_hooks_ready_at_ms, int64_t{0}, __ATOMIC_RELEASE);
  __atomic_store_n(&g_hooks_state, uint32_t{0}, __ATOMIC_RELEASE);
}

bool InstallHooks(void* handle) {
  uint32_t expected = 0u;
  if (!__atomic_compare_exchange_n(
              &g_hooks_state, &expected, uint32_t{1}, false,
              __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
    return __atomic_load_n(&g_hooks_state, __ATOMIC_ACQUIRE) == 3u;
  }

  auto* build_id = static_cast<const uint8_t*>(
          dlsym(handle, kDartSnapshotBuildIdSymbol));
  if (build_id == nullptr ||
      memcmp(build_id, kExpectedSnapshotBuildId,
             sizeof(kExpectedSnapshotBuildId)) != 0) {
    __atomic_store_n(&g_hooks_state, uint32_t{4}, __ATOMIC_RELEASE);
    __android_log_print(
            ANDROID_LOG_WARN, kLogTag,
            "unsupported libapp.so snapshot build ID; icon fix disabled");
    return false;
  }

  auto* snapshot_instructions =
          dlsym(handle, kDartSnapshotInstructionsSymbol);
  Dl_info info{};
  if (snapshot_instructions == nullptr ||
      dladdr(snapshot_instructions, &info) == 0 ||
      info.dli_fbase == nullptr) {
    __atomic_store_n(&g_hooks_state, uint32_t{5}, __ATOMIC_RELEASE);
    return false;
  }

  auto* base = static_cast<uint8_t*>(info.dli_fbase);
  if (snapshot_instructions !=
              base + kDartSnapshotInstructionsOffset ||
      !MatchesBytes(
              base, kApplyNightModeOffset, kApplyNightModePrologue,
              sizeof(kApplyNightModePrologue)) ||
      !MatchesBytes(
              base, kLauncherLifecycleOffset, kLauncherLifecyclePrologue,
              sizeof(kLauncherLifecyclePrologue))) {
    __atomic_store_n(&g_hooks_state, uint32_t{6}, __ATOMIC_RELEASE);
    __android_log_print(
            ANDROID_LOG_ERROR, kLogTag,
            "libapp.so fingerprint validation failed; icon fix disabled");
    return false;
  }

  g_dart_base = base;
  const int apply_result = InstallInlineHook(
          base + kApplyNightModeOffset,
          reinterpret_cast<void*>(KeiHomeApplyNightModeHook),
          &g_kei_home_apply_night_original);
  if (apply_result != kHookSuccess ||
      g_kei_home_apply_night_original == nullptr) {
    if (apply_result == kHookSuccess) {
      RemoveInlineHook(base + kApplyNightModeOffset);
    }
    g_kei_home_apply_night_original = nullptr;
    g_dart_base = nullptr;
    __atomic_store_n(&g_hooks_state, uint32_t{7}, __ATOMIC_RELEASE);
    __android_log_print(
            ANDROID_LOG_ERROR, kLogTag,
            "failed to install applyNightMode hook: result=%d", apply_result);
    return false;
  }

  const int lifecycle_result = InstallInlineHook(
          base + kLauncherLifecycleOffset,
          reinterpret_cast<void*>(KeiHomeLauncherLifecycleHook),
          &g_kei_home_lifecycle_original);
  if (lifecycle_result != kHookSuccess ||
      g_kei_home_lifecycle_original == nullptr) {
    if (lifecycle_result == kHookSuccess) {
      RemoveInlineHook(base + kLauncherLifecycleOffset);
    }
    RemoveInlineHook(base + kApplyNightModeOffset);
    g_kei_home_lifecycle_original = nullptr;
    g_kei_home_apply_night_original = nullptr;
    g_dart_base = nullptr;
    __atomic_store_n(&g_hooks_state, uint32_t{8}, __ATOMIC_RELEASE);
    __android_log_print(
            ANDROID_LOG_ERROR, kLogTag,
            "failed to install launcher lifecycle hook: result=%d",
            lifecycle_result);
    return false;
  }

  __atomic_store_n(
          &g_hooks_ready_at_ms, MonotonicTimeMs(), __ATOMIC_RELEASE);
  __atomic_store_n(&g_hooks_state, uint32_t{3}, __ATOMIC_RELEASE);
  __android_log_print(
          ANDROID_LOG_INFO, kLogTag,
          "launcher visibility-aware icon hooks ready: base=%p "
          "applyNight=%p lifecycle=%p",
          base, base + kApplyNightModeOffset,
          base + kLauncherLifecycleOffset);
  return true;
}

void OnLibraryLoaded(const char* name, void* handle);

void BackfillLibrary(const char* name) {
  void* handle = dlopen(name, RTLD_NOW | RTLD_NOLOAD);
  if (handle == nullptr) return;
  __android_log_print(
          ANDROID_LOG_INFO, kLogTag,
          "backfilling already-loaded native image: %s", name);
  OnLibraryLoaded(name, handle);
  dlclose(handle);
}

void BackfillLibraries() {
  if (!IsTargetProcess()) return;
  BackfillLibrary(kHyperRuntimeName);
  BackfillLibrary(kDartLibraryName);
}

void OnLibraryLoaded(const char* name, void* handle) {
  if (name == nullptr || handle == nullptr || !IsTargetProcess()) {
    return;
  }

  const size_t name_length = strlen(name);
  constexpr size_t runtime_suffix_length = sizeof(kHyperRuntimeName) - 1u;
  if (StringsEqual(name, kHyperRuntimeName) ||
      (name_length >= runtime_suffix_length &&
       StringsEqual(name + name_length - runtime_suffix_length,
                    kHyperRuntimeName))) {
    if (!EnsureLsposedMadviseGuard(name)) {
      __android_log_print(
              ANDROID_LOG_ERROR, kLogTag,
              "madvise hook-page guard is not ready");
    }
  }
  if (IsDartLibraryPath(name) && IsLauncherProcess()) {
    InstallHooks(handle);
  }
}

}  // namespace

extern "C" __attribute__((visibility("hidden")))
uint32_t KeiHomeObserveApplyNightMode(
        uintptr_t argument_descriptor, uintptr_t receiver,
        uintptr_t enabled, uintptr_t null_object) {
  const uint32_t count = __atomic_add_fetch(
          &g_apply_night_count, uint32_t{1}, __ATOMIC_RELAXED);
  const int32_t state =
          enabled == null_object + 0x20u ? int32_t{1} :
          enabled == null_object + 0x30u ? int32_t{0} : int32_t{-1};
  const uintptr_t previous = __atomic_exchange_n(
          &g_last_night_value, enabled, __ATOMIC_ACQ_REL);
  const bool had_previous =
          __atomic_exchange_n(
                  &g_have_night_value, uint32_t{1}, __ATOMIC_ACQ_REL) != 0u;

  __android_log_print(
          ANDROID_LOG_INFO, kLogTag,
          "applyNightMode #%u state=%d argdesc=%p receiver=%p enabled=%p",
          count, state,
          reinterpret_cast<void*>(argument_descriptor),
          reinterpret_cast<void*>(receiver),
          reinterpret_cast<void*>(enabled));

  if (state < 0 || previous == enabled) return 0u;

  const int64_t ready_at =
          __atomic_load_n(&g_hooks_ready_at_ms, __ATOMIC_ACQUIRE);
  const int64_t now = MonotonicTimeMs();
  if (!had_previous &&
      (ready_at == 0 || now - ready_at < kStartupSettleTimeMs)) {
    __android_log_print(
            ANDROID_LOG_INFO, kLogTag,
            "recorded startup night mode baseline: state=%d", state);
    return 0u;
  }

  BeginModeChange(state);
  return 1u;
}

extern "C" __attribute__((visibility("hidden")))
uint32_t KeiHomeObserveLauncherLifecycle(uint32_t resumed) {
  const uint32_t previous =
          __atomic_exchange_n(&g_launcher_resumed, resumed, __ATOMIC_ACQ_REL);
  if (previous != resumed) {
    __android_log_print(
            ANDROID_LOG_INFO, kLogTag,
            "launcher lifecycle: resumed=%u", resumed);
  }
  if (resumed == 0u &&
      __atomic_load_n(&g_restart_when_paused, __ATOMIC_ACQUIRE) != 0u) {
    ScheduleBackgroundLauncherRestart();
  }
  return 0u;
}

extern "C" __attribute__((visibility("default"), unused))
NativeOnModuleLoaded native_init(const NativeAPIEntries* entries) {
  const bool backend_ready = InitializeLsposedHookBackend(entries);
  __android_log_print(
          ANDROID_LOG_INFO, kLogTag,
          "native_init: backend=%d launcher=%d spawner=%d",
          backend_ready ? 1 : 0,
          IsLauncherProcess() ? 1 : 0,
          IsHyosSpawnerProcess() ? 1 : 0);
  if (!backend_ready || !IsTargetProcess()) return nullptr;

  static uint32_t atfork_registered = 0u;
  if (__atomic_exchange_n(
              &atfork_registered, uint32_t{1}, __ATOMIC_ACQ_REL) == 0u) {
    if (pthread_atfork(nullptr, nullptr, ResetAfterFork) != 0) {
      __android_log_print(
              ANDROID_LOG_WARN, kLogTag,
              "failed to register post-fork icon state reset");
    }
  }

  BackfillLibraries();
  return OnLibraryLoaded;
}

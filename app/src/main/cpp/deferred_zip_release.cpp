// SPDX-License-Identifier: Apache-2.0
#include "deferred_zip_release.h"

#include <pthread.h>
#include <sys/resource.h>
#include <unistd.h>
#ifdef __linux__
#include <sys/syscall.h>
#endif
#ifdef __ANDROID__
#include <android/trace.h>
#include <android/log.h>
#endif

extern "C" {
uint64_t kh_zip_release_queued = 0;
uint64_t kh_zip_release_completed = 0;
uint64_t kh_zip_release_fallback = 0;
uint64_t kh_zip_release_peak = 0;
}

namespace {
constexpr size_t kCapacity = 64;
struct Item { void* allocation; ZipMetadataRelease release; };
pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t ready = PTHREAD_COND_INITIALIZER;
Item items[kCapacity]{};
size_t head = 0, count = 0;
int started = 0;
pthread_t worker{};

void* Run(void*) {
#ifdef __APPLE__
    pthread_setname_np("KeiZipRelease");
#else
    pthread_setname_np(pthread_self(), "KeiZipRelease");
#endif
#ifdef __linux__
    setpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)), 10);
#endif
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "KeiHomeMamlRelease",
                       "ZIP metadata worker ready");
#endif
    for (;;) {
        pthread_mutex_lock(&mutex);
        while (count == 0) pthread_cond_wait(&ready, &mutex);
        const Item item = items[head];
        items[head] = {};
        head = (head + 1) % kCapacity;
        --count;
        pthread_mutex_unlock(&mutex);
#ifdef __ANDROID__
        ATrace_beginSection("KeiHome:zip-metadata-release");
#endif
        item.release(item.allocation);
#ifdef __ANDROID__
        ATrace_endSection();
#endif
        __atomic_add_fetch(&kh_zip_release_completed, uint64_t{1}, __ATOMIC_RELAXED);
    }
}
}

bool StartDeferredZipRelease() {
    const int state = __atomic_load_n(&started, __ATOMIC_ACQUIRE);
    if (state != 0) return state == 1;
    pthread_mutex_lock(&mutex);
    if (__atomic_load_n(&started, __ATOMIC_RELAXED) == 0) {
        const int result = pthread_create(&worker, nullptr, Run, nullptr);
        if (result == 0) pthread_detach(worker);
        __atomic_store_n(&started, result == 0 ? 1 : -1, __ATOMIC_RELEASE);
    }
    const bool success = __atomic_load_n(&started, __ATOMIC_RELAXED) == 1;
    pthread_mutex_unlock(&mutex);
    return success;
}

bool TryDeferZipMetadata(void* allocation, ZipMetadataRelease release) {
    // No blocking lock, heap allocation, polling or per-icon thread on the
    // caller. A saturated/unavailable worker keeps native synchronous cleanup.
    if (!allocation || !release ||
            __atomic_load_n(&started, __ATOMIC_ACQUIRE) != 1 ||
            pthread_mutex_trylock(&mutex) != 0) {
        __atomic_add_fetch(&kh_zip_release_fallback, uint64_t{1}, __ATOMIC_RELAXED);
        return false;
    }
    if (count == kCapacity || pthread_equal(worker, pthread_self())) {
        pthread_mutex_unlock(&mutex);
        __atomic_add_fetch(&kh_zip_release_fallback, uint64_t{1}, __ATOMIC_RELAXED);
        return false;
    }
    items[(head + count) % kCapacity] = {allocation, release};
    ++count;
    __atomic_add_fetch(&kh_zip_release_queued, uint64_t{1}, __ATOMIC_RELAXED);
    if (count > __atomic_load_n(&kh_zip_release_peak, __ATOMIC_RELAXED))
        __atomic_store_n(&kh_zip_release_peak, static_cast<uint64_t>(count), __ATOMIC_RELAXED);
    pthread_cond_signal(&ready);
    pthread_mutex_unlock(&mutex);
    return true;
}

DeferredZipReleaseStats ReadDeferredZipReleaseStats() {
    pthread_mutex_lock(&mutex);
    const size_t pending = count;
    pthread_mutex_unlock(&mutex);
    return {__atomic_load_n(&kh_zip_release_queued, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_completed, __ATOMIC_RELAXED),
            __atomic_load_n(&kh_zip_release_fallback, __ATOMIC_RELAXED), pending};
}

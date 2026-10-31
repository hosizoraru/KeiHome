// SPDX-License-Identifier: Apache-2.0
// Independently authored for KeiHome, following MiuiBackGestureHook's exact
// process/sender authentication and explicit native lifetime ownership principles.
#include "monet_policy_server.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

#if defined(__ANDROID__)
#include "monet_image_recovery.h"
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <dlfcn.h>
#endif

#if defined(__linux__)
#include <poll.h>
#include <pthread.h>
#include <stdio.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>
#endif

extern "C" {
NativeMonetPolicy* kh_monet_policy = nullptr;
uint32_t kh_monet_readers = 0u;
uint32_t kh_monet_tint_ever_enabled = 0u;
uint64_t kh_monet_selection_revision = 0u;
uint64_t kh_monet_selection_notified = 0u;
uint32_t kh_monet_installed = 0u;
uint32_t kh_theme_materials_installed = 0u;
uint32_t kh_theme_materials_enabled = 0u;
}

namespace {

constexpr uint64_t kNanosecondsPerMs = 1000000u;
constexpr char kSystemServerContext[] = "u:r:system_server:s0";
constexpr uint32_t kSystemUid = 1000u;
// A reader that fails to leave cannot cause unbounded allocation. Updates are
// rejected under pressure; one preallocated expiry publication is still allowed.
constexpr uint32_t kMaxRetiredPolicies = 4u;

struct PolicyAllocation {
    NativeMonetPolicy policy;
    PolicyAllocation* retired_next;
};
static_assert(offsetof(PolicyAllocation, policy) == 0u);

struct PolicyState {
    PolicyAllocation* retired;
    uint32_t retired_count;
    PolicyAllocation* expiration;
    uint64_t expires_at;
    uint64_t materials_expires_at;
};

struct RequestHeader {
    uint32_t flags;
    uint32_t foreground;
    uint32_t background;
    uint32_t count;
};

uint32_t InstalledCapabilities() {
    const uint32_t monet = __atomic_load_n(&kh_monet_installed, __ATOMIC_ACQUIRE) == 1u
            ? kNativeMonetCapability : 0u;
    const uint32_t materials = __atomic_load_n(&kh_theme_materials_installed, __ATOMIC_ACQUIRE) == 1u
            ? kNativeThemeMaterialsCapability : 0u;
    return monet | materials;
}

uint32_t ReadLe32(const uint8_t* bytes) {
    return uint32_t{bytes[0]} | (uint32_t{bytes[1]} << 8u) |
            (uint32_t{bytes[2]} << 16u) | (uint32_t{bytes[3]} << 24u);
}

void WriteLe32(uint8_t* bytes, uint32_t value) {
    for (uint32_t i = 0u; i < 4u; ++i) {
        bytes[i] = static_cast<uint8_t>(value >> (i * 8u));
    }
}

bool IsSystemServerPeer(uint32_t uid, const char* context, size_t length) {
    if (uid != kSystemUid || context == nullptr) return false;
    // Linux may return the context with or without its terminating NUL.
    return (length == sizeof(kSystemServerContext) - 1u ||
            (length == sizeof(kSystemServerContext) && context[length - 1u] == '\0')) &&
            memcmp(context, kSystemServerContext, sizeof(kSystemServerContext) - 1u) == 0;
}

bool IsSafePackageName(const char* name, uint32_t length) {
    if (length == 0u || length > kNativeMonetMaxPackageLength) return false;
    bool segment_start = true;
    for (uint32_t i = 0u; i < length; ++i) {
        const unsigned char c = static_cast<unsigned char>(name[i]);
        const bool letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        if (letter) {
            segment_start = false;
        } else if (!segment_start && (c == '_' || (c >= '0' && c <= '9'))) {
            continue;
        } else if (!segment_start && c == '.') {
            segment_start = true;
        } else {
            return false;
        }
    }
    return !segment_start;
}

// The same streaming decoder serves the authenticated socket and in-memory
// host tests. No network/authentication bypass is compiled for tests.
template <typename Reader>
bool ReadRequestHeader(Reader read_exactly, RequestHeader* output) {
    uint8_t header[24];
    if (output == nullptr || !read_exactly(header, sizeof(header))) return false;
    const uint32_t flags = ReadLe32(header + 8u);
    const uint32_t foreground = ReadLe32(header + 12u);
    const uint32_t background = ReadLe32(header + 16u);
    const uint32_t count = ReadLe32(header + 20u);
    if (ReadLe32(header) != kNativeMonetMagic ||
            ReadLe32(header + 4u) != kNativeMonetProtocolVersion ||
            flags > kNativeThemeMaterialsCommand || count > kNativeMonetMaxPackages ||
            (flags == 0u && count != 0u) ||
            (flags == 1u && ((foreground >> 24u) != 0xffu ||
                            (background >> 24u) != 0xffu)) ||
            (flags == kNativeThemeMaterialsCommand &&
             (foreground > 1u || background != 0u || count != 0u))) {
        return false;
    }
    *output = {flags, foreground, background, count};
    return true;
}

template <typename Reader>
bool ReadPolicyBody(Reader read_exactly, const RequestHeader& header, NativeMonetPolicy* output) {
    if (output == nullptr || header.flags > 1u) return false;
    output->flags = header.flags;
    output->foreground = header.foreground;
    output->background = header.background;
    output->count = header.count;
    for (uint32_t i = 0u; i < header.count; ++i) {
        uint8_t length_bytes[4];
        if (!read_exactly(length_bytes, sizeof(length_bytes))) return false;
        const uint32_t length = ReadLe32(length_bytes);
        if (length == 0u || length > kNativeMonetMaxPackageLength) return false;
        NativeMonetPolicyEntry& entry = output->entries[i];
        if (!read_exactly(entry.name, length) ||
                !IsSafePackageName(entry.name, length)) return false;
        entry.len = length;
        entry.name[length] = '\0';
    }
    return true;
}

template <typename Reader>
bool ReadPolicyRequest(Reader read_exactly, NativeMonetPolicy* output) {
    RequestHeader header{};
    return ReadRequestHeader(read_exactly, &header) &&
            ReadPolicyBody(read_exactly, header, output);
}

PolicyAllocation* AllocatePolicy() {
    return static_cast<PolicyAllocation*>(calloc(1u, sizeof(PolicyAllocation)));
}

// Called only by the worker. The exchange + full fence pairs with the reader's
// increment + full fence. A reader that could have obtained an old pointer must
// be counted before we observe zero. New readers can only acquire the successor.
void ReclaimDrainedPolicies(PolicyState* state) {
    if (__atomic_load_n(&kh_monet_readers, __ATOMIC_ACQUIRE) != 0u) return;
    while (state->retired != nullptr) {
        PolicyAllocation* old = state->retired;
        state->retired = old->retired_next;
        free(old);
    }
    state->retired_count = 0u;
}

void PublishPolicy(PolicyState* state, PolicyAllocation* next) {
    if (next != nullptr && next->policy.flags == 1u) {
        // Arm invalidation before any reader can apply this tint. Never clear
        // it: retained layers may outlive withdrawal and API-102 Java reloads.
        __atomic_store_n(&kh_monet_tint_ever_enabled, uint32_t{1}, __ATOMIC_RELEASE);
    }
    NativeMonetPolicy* old = __atomic_exchange_n(
            &kh_monet_policy, next != nullptr ? &next->policy : nullptr,
            __ATOMIC_SEQ_CST);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    const uint32_t before = old != nullptr && old->flags == 1u ? old->count : 0u;
    const uint32_t after = next != nullptr && next->policy.flags == 1u ? next->policy.count : 0u;
    if (before != after || (before != 0u && memcmp(old->entries, next->policy.entries,
            before * sizeof(NativeMonetPolicyEntry)) != 0)) {
        // The Dart equality bridge consumes only this primitive generation.
        // Original Rx/stream code owns recomputation of all live icon controllers.
        __atomic_fetch_add(&kh_monet_selection_revision, uint64_t{1}, __ATOMIC_RELEASE);
    }
    if (old != nullptr) {
        auto* allocation = reinterpret_cast<PolicyAllocation*>(old);
        allocation->retired_next = state->retired;
        state->retired = allocation;
        ++state->retired_count;
    }
    // Wait for drain by retaining, never by reusing/mutating a published slot.
    // The worker polls again while readers are present, keeping I/O bounded.
    ReclaimDrainedPolicies(state);
}

void MaintainPolicy(PolicyState* state, uint64_t now) {
#if defined(__ANDROID__)
    ReportMonetImageRecovery(now);
#endif
    if (state->materials_expires_at != 0u &&
            (now >= state->materials_expires_at ||
             __atomic_load_n(&kh_theme_materials_installed, __ATOMIC_ACQUIRE) != 1u)) {
        __atomic_store_n(&kh_theme_materials_enabled, uint32_t{0}, __ATOMIC_RELEASE);
        state->materials_expires_at = 0u;
    }
    if (state->expiration != nullptr && now >= state->expires_at) {
        PolicyAllocation* expired = state->expiration;
        state->expiration = nullptr;
        state->expires_at = 0u;
        PublishPolicy(state, expired);
    }
    ReclaimDrainedPolicies(state);
}

// Takes ownership only on success. Expiry is allocated before committing so
// memory pressure at the lease boundary cannot leave an old deny list enabled.
bool CommitPolicy(PolicyState* state, PolicyAllocation* next, uint64_t now) {
    // Defense in depth: flags 2 must never reach the ABI consumed by Dart bridges.
    if (next == nullptr || next->policy.flags > 1u ||
            __atomic_load_n(&kh_monet_installed, __ATOMIC_ACQUIRE) != 1u) return false;
    MaintainPolicy(state, now);
    if (state->retired_count >= kMaxRetiredPolicies) return false;
    PolicyAllocation* expiration = nullptr;
    if (next->policy.flags == 1u && next->policy.count != 0u) {
        expiration = AllocatePolicy();
        if (expiration == nullptr) return false;
        expiration->policy.flags = next->policy.flags;
        expiration->policy.foreground = next->policy.foreground;
        expiration->policy.background = next->policy.background;
    }
    free(state->expiration);  // This allocation has never been published.
    state->expiration = expiration;
    state->expires_at = expiration != nullptr
            ? now + uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs : 0u;
    PublishPolicy(state, next);
    return true;
}

bool CommitThemeMaterials(PolicyState* state, bool enabled, uint64_t now) {
    MaintainPolicy(state, now);
    if (__atomic_load_n(&kh_theme_materials_installed, __ATOMIC_ACQUIRE) != 1u) return false;
    state->materials_expires_at = enabled
            ? now + uint64_t{kNativeMonetExpiryMs} * kNanosecondsPerMs : 0u;
    __atomic_store_n(&kh_theme_materials_enabled, enabled ? uint32_t{1} : uint32_t{0},
                     __ATOMIC_RELEASE);
    return true;
}

// Decode/dispatch is shared with host tests. Material commands allocate no policy
// and cannot reach CommitPolicy/PublishPolicy, including on missing capability.
template <typename Reader, typename Clock>
bool ReadAndCommitRequest(PolicyState* state, Reader read_exactly, Clock now_before_deadline) {
    RequestHeader header{};
    if (!ReadRequestHeader(read_exactly, &header)) return false;
    if (header.flags == kNativeThemeMaterialsCommand) {
        const uint64_t now = now_before_deadline();
        return now != 0u && CommitThemeMaterials(state, header.foreground == 1u, now);
    }
    if ((InstalledCapabilities() & kNativeMonetCapability) == 0u) return false;
    PolicyAllocation* next = AllocatePolicy();
    if (next == nullptr) return false;
    const bool decoded = ReadPolicyBody(read_exactly, header, &next->policy);
    const uint64_t now = now_before_deadline();
    if (decoded && now != 0u && CommitPolicy(state, next, now)) return true;
    free(next);
    return false;
}

uint64_t NextMaintenanceWake(const PolicyState* state, uint64_t deadline) {
    if (state->expiration != nullptr && state->expires_at < deadline) {
        deadline = state->expires_at;
    }
    if (state->materials_expires_at != 0u && state->materials_expires_at < deadline) {
        deadline = state->materials_expires_at;
    }
    return deadline;
}

#if defined(__linux__)

constexpr char kLauncherProcess[] = "com.miui.home";
constexpr uint32_t kServerStopped = 0u;
constexpr uint32_t kServerRunning = 1u;
constexpr uint32_t kServerFailed = 2u;
pthread_mutex_t g_start_lock = PTHREAD_MUTEX_INITIALIZER;
uint32_t g_server_state = kServerStopped;
pid_t g_server_pid = 0;

struct ServerState {
    PolicyState policies;
    pid_t pid;
    uid_t uid;
};

// The platform forbids new system_server <-> app socket connections. Binder
// may transfer an already connected pair instead. The private transaction is
// authenticated by the Java hook before allocation; ordinary PackageManager
// calls retain their original behavior. No service or SELinux rule is added.
int RequestPolicyChannel() {
#if defined(__ANDROID__)
    constexpr transaction_code_t kOpenChannel = 0x4b484du;
    constexpr char kDescriptor[] = "android.content.pm.IPackageManager";
    using CheckService = AIBinder* (*)(const char*);
    // ServiceManager is a platform export, not an NDK link-time API. Missing
    // export/descriptor support leaves the optional extension inactive.
    static CheckService check_service = nullptr;
    static bool resolved_service_manager = false;
    if (!resolved_service_manager) {
        resolved_service_manager = true;
        void* library = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_NOLOAD);
        if (library != nullptr) {
            check_service = reinterpret_cast<CheckService>(
                    dlsym(library, "AServiceManager_checkService"));
            // DT_NEEDED retains this library for the resident payload lifetime.
            dlclose(library);
        }
    }
    AIBinder* service = check_service != nullptr ? check_service("package") : nullptr;
    if (service == nullptr) return -1;

    // Only this worker initializes the class. It and the resident hooks have
    // process lifetime; an APK/Java reload never unloads their executable code.
    static AIBinder_Class* channel_class = nullptr;
    if (channel_class == nullptr) {
        channel_class = AIBinder_Class_define(kDescriptor,
                [](void* data) -> void* { return data; }, [](void*) {},
                [](AIBinder*, transaction_code_t, const AParcel*, AParcel*) {
                    return static_cast<binder_status_t>(STATUS_UNKNOWN_TRANSACTION);
                });
    }
    int result = -1;
    AParcel* request = nullptr;
    AParcel* reply = nullptr;
    if (channel_class != nullptr && AIBinder_associateClass(service, channel_class) &&
            AIBinder_prepareTransaction(service, &request) == STATUS_OK &&
            AParcel_writeInt32(request, kNativeMonetMagic) == STATUS_OK &&
            AParcel_writeInt32(request, kNativeMonetProtocolVersion) == STATUS_OK &&
            AParcel_writeInt32(request, static_cast<int32_t>(getuid())) == STATUS_OK &&
            AParcel_writeInt32(request, kNativeMonetProfileVersion) == STATUS_OK &&
            AIBinder_transact(service, kOpenChannel, &request, &reply, 0) == STATUS_OK) {
        int32_t exception = -1, magic = 0, version = 0, status = -1;
        if (AParcel_readInt32(reply, &exception) == STATUS_OK && exception == 0 &&
                AParcel_readInt32(reply, &magic) == STATUS_OK &&
                static_cast<uint32_t>(magic) == kNativeMonetMagic &&
                AParcel_readInt32(reply, &version) == STATUS_OK &&
                version == kNativeMonetProtocolVersion &&
                AParcel_readInt32(reply, &status) == STATUS_OK && status == 0) {
            if (AParcel_readParcelFileDescriptor(reply, &result) != STATUS_OK) {
                if (result >= 0) close(result);
                result = -1;
            }
        }
    }
    if (request != nullptr) AParcel_delete(request);
    if (reply != nullptr) AParcel_delete(reply);
    AIBinder_decStrong(service);
    return result;
#else
    return -1;
#endif
}

bool IsLauncherProcess() {
    // argv[0] must end at its own NUL, not at a truncated read or name prefix.
    char name[sizeof(kLauncherProcess)] = {};
    FILE* file = fopen("/proc/self/cmdline", "re");
    if (file == nullptr) return false;
    const size_t length = fread(name, 1u, sizeof(name), file);
    fclose(file);
    return length == sizeof(name) &&
            memcmp(name, kLauncherProcess, sizeof(name)) == 0;
}

uint64_t NowNs() {
    timespec now{};
    // Include device suspend in lease age; Android supplies CLOCK_BOOTTIME.
    if (clock_gettime(CLOCK_BOOTTIME, &now) != 0) return 0u;
    return static_cast<uint64_t>(now.tv_sec) * 1000000000u +
            static_cast<uint64_t>(now.tv_nsec);
}

bool AuthenticatePeer(int fd) {
    ucred credentials{};
    socklen_t length = sizeof(credentials);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &credentials, &length) != 0 ||
            length != sizeof(credentials) || credentials.uid != kSystemUid) {
        return false;
    }
    char context[sizeof(kSystemServerContext)] = {};
    length = sizeof(context);
    if (getsockopt(fd, SOL_SOCKET, SO_PEERSEC, context, &length) != 0) return false;
    return IsSystemServerPeer(credentials.uid, context, length);
}

enum class WaitResult { Ready, Timeout, Failed };

WaitResult WaitForFd(int fd, short events, uint64_t deadline,
                     PolicyState* policies) {
    for (;;) {
        const uint64_t now = NowNs();
        if (now == 0u) return WaitResult::Failed;
        MaintainPolicy(policies, now);
        if (now >= deadline) return WaitResult::Timeout;
        uint64_t wake = NextMaintenanceWake(policies, deadline);
        // Retired snapshots need a reader grace period, never a blocking writer
        // wait that could overrun the connection's deadline or the policy lease.
        if (policies->retired != nullptr && now + 10u * kNanosecondsPerMs < wake) {
            wake = now + 10u * kNanosecondsPerMs;
        }
        const int timeout = static_cast<int>(
                (wake - now + kNanosecondsPerMs - 1u) / kNanosecondsPerMs);
        pollfd descriptor{fd, events, 0};
        const int result = poll(&descriptor, 1u, timeout);
        if (result < 0 && errno == EINTR) continue;
        if (result < 0) return WaitResult::Failed;
        if (result == 0) continue;
        // A peer can close after sending its request: drain readable bytes even
        // when POLLHUP is also set, then let recv report EOF on an incomplete one.
        if ((descriptor.revents & events) != 0) return WaitResult::Ready;
        if ((descriptor.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            return WaitResult::Failed;
        }
    }
}

bool Transfer(int fd, uint8_t* bytes, size_t length, bool writing,
              uint64_t deadline, PolicyState* policies) {
    size_t done = 0u;
    while (done < length) {
        if (WaitForFd(fd, writing ? POLLOUT : POLLIN, deadline, policies) !=
                WaitResult::Ready) return false;
        // A ready fd after descheduling must not extend the absolute deadline.
        const uint64_t now = NowNs();
        if (now == 0u || now >= deadline) return false;
        MaintainPolicy(policies, now);
        const ssize_t result = writing
                ? send(fd, bytes + done, length - done, MSG_NOSIGNAL | MSG_DONTWAIT)
                : recv(fd, bytes + done, length - done, MSG_DONTWAIT);
        if (result < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) {
            continue;
        }
        if (result <= 0) return false;
        done += static_cast<size_t>(result);
    }
    return true;
}

void HandleConnection(ServerState* server, int fd, uint64_t accepted_at) {
    if (!AuthenticatePeer(fd)) return;
    const timeval timeout{1, 0};
    if (setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) != 0 ||
            setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout)) != 0) {
        return;
    }
    const uint64_t greeting_deadline = accepted_at +
            uint64_t{kNativeMonetIoTimeoutMs} * kNanosecondsPerMs;
    uint8_t greeting[16];
    WriteLe32(greeting, kNativeMonetMagic);
    WriteLe32(greeting + 4u, kNativeMonetProtocolVersion);
    WriteLe32(greeting + 8u, server->uid);
    WriteLe32(greeting + 12u, InstalledCapabilities());
    if (!Transfer(fd, greeting, sizeof(greeting), true, greeting_deadline, &server->policies)) {
        return;
    }
    // A single pending pair sleeps until the monitor's next exchange. Policy
    // maintenance still expires deny lists on time; no connection polling is
    // required while idle. Once the first byte arrives the full request + ACK
    // has one absolute two-second budget, including fragmented reads.
    if (WaitForFd(fd, POLLIN, accepted_at + uint64_t{20000u} * kNanosecondsPerMs,
                  &server->policies) != WaitResult::Ready) return;
    const uint64_t deadline = NowNs() +
            uint64_t{kNativeMonetIoTimeoutMs} * kNanosecondsPerMs;
    const bool accepted = ReadAndCommitRequest(&server->policies,
            [&](void* bytes, size_t length) {
                return Transfer(fd, static_cast<uint8_t*>(bytes), length, false,
                                deadline, &server->policies);
            }, [&] {
                const uint64_t now = NowNs();
                return now < deadline ? now : uint64_t{0};
            });
    uint8_t ack[12];
    WriteLe32(ack, kNativeMonetMagic);
    WriteLe32(ack + 4u, kNativeMonetProtocolVersion);
    WriteLe32(ack + 8u, accepted ? 0u : 1u);
    // Timeout/disconnect may prevent delivery; it never rolls back a committed
    // command. Repeating the authenticated policy or material command is idempotent.
    Transfer(fd, ack, sizeof(ack), true, deadline, &server->policies);
}

void* PolicyWorker(void* data) {
    auto* server = static_cast<ServerState*>(data);
    if (getpid() == server->pid && getuid() == server->uid && IsLauncherProcess()) {
        uint32_t retry_ms = 250u;
        for (;;) {
            const uint64_t now = NowNs();
            if (now == 0u) break;
            MaintainPolicy(&server->policies, now);
            const int connection = RequestPolicyChannel();
            if (connection < 0) {
                // A retiring Java owner closes its pair and temporarily
                // rejects acquisition. The successor can accept automatically.
                const uint64_t after_request = NowNs();
                if (after_request == 0u) break;
                MaintainPolicy(&server->policies, after_request);
                const uint64_t delay_ns = NextMaintenanceWake(&server->policies,
                        after_request + retry_ms * kNanosecondsPerMs) - after_request;
                timespec delay{static_cast<time_t>(delay_ns / 1000000000u),
                               static_cast<long>(delay_ns % 1000000000u)};
                nanosleep(&delay, nullptr);
                if (retry_ms < 4000u) retry_ms *= 2u;
                continue;
            }
            retry_ms = 250u;
            const uint64_t accepted_at = NowNs();
            if (accepted_at != 0u) HandleConnection(server, connection, accepted_at);
            close(connection);  // Only the worker owns accepted descriptors.
        }
    }
    free(server->policies.expiration);
    server->policies.expiration = nullptr;
    __atomic_store_n(&kh_theme_materials_enabled, uint32_t{0}, __ATOMIC_RELEASE);
    server->policies.materials_expires_at = 0u;
    PublishPolicy(&server->policies, nullptr);
    __atomic_store_n(&g_server_state, kServerFailed, __ATOMIC_RELEASE);
    // With the public pointer null, new readers cannot prolong access to an old
    // snapshot. Wait without holding any socket; process exit needs no action.
    while (server->policies.retired != nullptr) {
        timespec delay{0, 1000000};
        nanosleep(&delay, nullptr);
        ReclaimDrainedPolicies(&server->policies);
    }
    free(server);
    return nullptr;
}

bool StartLocked() {
    const uint32_t state = __atomic_load_n(&g_server_state, __ATOMIC_ACQUIRE);
    if (state != kServerStopped) {
        return state == kServerRunning && g_server_pid == getpid();
    }
    if (NowNs() == 0u) return false;
    auto* server = static_cast<ServerState*>(calloc(1u, sizeof(ServerState)));
    if (server == nullptr) return false;
    server->pid = getpid();
    server->uid = getuid();
    pthread_attr_t attributes{};
    const bool attributes_ready = pthread_attr_init(&attributes) == 0;
    const bool detached = attributes_ready &&
            pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED) == 0;
    pthread_t thread{};
    g_server_pid = server->pid;
    // The start mutex prevents another caller observing running before create
    // returns. Do not overwrite a failure already reported by a fast worker.
    __atomic_store_n(&g_server_state, kServerRunning, __ATOMIC_RELEASE);
    const int result = detached ? pthread_create(&thread, &attributes, PolicyWorker, server)
                                : EINVAL;
    if (attributes_ready) pthread_attr_destroy(&attributes);
    if (result != 0) {
        __atomic_store_n(&g_server_state, kServerFailed, __ATOMIC_RELEASE);
        free(server);
        return false;
    }
    return __atomic_load_n(&g_server_state, __ATOMIC_ACQUIRE) == kServerRunning;
}

#endif  // defined(__linux__)
}  // namespace

bool StartNativeMonetPolicyServer() {
#if defined(__linux__)
    if (!IsLauncherProcess() || pthread_mutex_lock(&g_start_lock) != 0) return false;
    const bool result = StartLocked();
    pthread_mutex_unlock(&g_start_lock);
    return result;
#else
    // Abstract sockets and SELinux peer authentication are Linux requirements.
    return false;
#endif
}

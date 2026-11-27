// SPDX-License-Identifier: Apache-2.0
#include "lsposed_hook_backend.h"
#include "arm64_exact_patch.h"

#include <android/log.h>
#include <elf.h>
#include <errno.h>
#include <inttypes.h>
#include <link.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

namespace {

constexpr char kLogTag[] = "KeiHomeLspMadvise";
constexpr char kHyperRuntimeName[] = "libhyper_os_flutter.so";
constexpr size_t kMaxSegments = 16u;
constexpr size_t kMaxProtectedPages = 128u;
constexpr size_t kMaxPltSlots = 16u;
constexpr size_t kMaxMadviseSlots = 8u;
constexpr size_t kMaxSymbols = 1u << 24;
constexpr size_t kInlinePatchGuardSpan = 32u;

const NativeAPIEntries* g_lsposed_api = nullptr;
using MadviseFn = int (*)(void*, size_t, int);
MadviseFn g_madvise_slot_backups[kMaxMadviseSlots]{};
uint32_t g_direct_madvise_guard_state = 0u;  // 0 new, 2 installed, 3 failed
pthread_mutex_t g_madvise_init_mutex = PTHREAD_MUTEX_INITIALIZER;
pthread_mutex_t g_code_patch_mutex = PTHREAD_MUTEX_INITIALIZER;
volatile uint32_t g_protected_page_lock = 0u;
uintptr_t g_protected_pages[kMaxProtectedPages]{};
size_t g_protected_page_count = 0u;

struct Segment {
    uintptr_t begin;
    uintptr_t end;
    uint32_t flags;
};

struct Image {
    uintptr_t base;
    const ElfW(Phdr)* phdr;
    size_t phnum;
    Segment segments[kMaxSegments];
    size_t segment_count;
    char path[512];
};

struct DynamicView {
    const ElfW(Sym)* symbols;
    const char* strings;
    size_t string_size;
    size_t symbol_count;
    const ElfW(Rela)* plt_rela;
    size_t plt_rela_count;
    const ElfW(Rela)* dyn_rela;
    size_t dyn_rela_count;
};

int PltHookRaw(void* base, const char* symbol, void* replacement,
               void** original);
bool BuildDynamicView(const Image& image, DynamicView* output);
bool CollectRelocationSlots(const Image& image, const DynamicView& view,
                            const ElfW(Rela)* relocations, size_t count,
                            const char* symbol, void*** slots,
                            size_t* slot_count);
bool WritePointerLocked(void** slot, void* value);

size_t PageSize() {
    static size_t value = 0u;
    if (value == 0u) {
        const long queried = sysconf(_SC_PAGESIZE);
        value = queried > 0 ? static_cast<size_t>(queried) : size_t{4096};
    }
    return value;
}

uintptr_t PageStart(uintptr_t address) {
    return address & ~(static_cast<uintptr_t>(PageSize()) - 1u);
}

const char* BaseName(const char* path) {
    if (path == nullptr) return nullptr;
    const char* slash = strrchr(path, '/');
    return slash == nullptr ? path : slash + 1;
}

bool AddProtectedPage(uintptr_t address) {
    const uintptr_t page = PageStart(address);
    while (__atomic_exchange_n(&g_protected_page_lock, uint32_t{1},
                               __ATOMIC_ACQUIRE) != 0u) {
    }
    size_t count = __atomic_load_n(&g_protected_page_count, __ATOMIC_RELAXED);
    for (size_t index = 0u; index < count; ++index) {
        if (g_protected_pages[index] == page) {
            __atomic_store_n(&g_protected_page_lock, uint32_t{0},
                             __ATOMIC_RELEASE);
            return true;
        }
    }
    if (count >= kMaxProtectedPages) {
        __atomic_store_n(&g_protected_page_lock, uint32_t{0},
                         __ATOMIC_RELEASE);
        return false;
    }
    g_protected_pages[count] = page;
    __atomic_store_n(&g_protected_page_count, count + 1u, __ATOMIC_RELEASE);
    __atomic_store_n(&g_protected_page_lock, uint32_t{0}, __ATOMIC_RELEASE);
    return true;
}

// Shared body for every madvise slot this module installs.  `original` is the
// backup LSPlt returned for that specific slot, which is the previous module's
// guard (or libc madvise when this module is first in the chain).  Calling the
// backup instead of libc keeps the GOT chain intact when several modules hook
// the same runtime.
int GuardedMadviseWith(MadviseFn original, void* address, size_t length,
                       int advice) {
    if (original == nullptr) {
        errno = ENOSYS;
        return -1;
    }
    if (advice != MADV_DONTNEED || length == 0u) {
        return original(address, length, advice);
    }
    const uintptr_t begin = reinterpret_cast<uintptr_t>(address);
    if (begin % PageSize() != 0u || length > UINTPTR_MAX - begin) {
        return original(address, length, advice);
    }
    const uintptr_t end = begin + length;
    uintptr_t cursor = begin;
    bool protected_page_found = false;
    while (cursor < end) {
        uintptr_t next_page = end;
        const size_t count = __atomic_load_n(&g_protected_page_count,
                                              __ATOMIC_ACQUIRE);
        for (size_t index = 0u; index < count; ++index) {
            const uintptr_t page = g_protected_pages[index];
            if (page >= cursor && page < end && page < next_page) {
                next_page = page;
            }
        }
        if (next_page == end) break;
        if (next_page > cursor &&
                original(reinterpret_cast<void*>(cursor), next_page - cursor,
                         advice) != 0) {
            return -1;
        }
        if (next_page > UINTPTR_MAX - PageSize()) {
            errno = EINVAL;
            return -1;
        }
        cursor = next_page + PageSize();
        if (cursor > end) cursor = end;
        protected_page_found = true;
    }
    if (!protected_page_found) return original(address, length, advice);
    if (cursor < end &&
            original(reinterpret_cast<void*>(cursor), end - cursor, advice) != 0) {
        return -1;
    }
    __android_log_print(ANDROID_LOG_DEBUG, kLogTag,
                        "preserved hook page in MADV_DONTNEED range %p-%p",
                        address, reinterpret_cast<void*>(end));
    return 0;
}

// Per-slot wrappers so each .rela.plt / .rela.dyn madvise slot forwards to its
// own prior target even when another module hooked only one relocation table.
template <size_t Slot>
int GuardedMadviseSlot(void* address, size_t length, int advice) {
    return GuardedMadviseWith(
            __atomic_load_n(&g_madvise_slot_backups[Slot], __ATOMIC_ACQUIRE),
            address, length, advice);
}

constexpr MadviseFn kMadviseSlotTrampolines[kMaxMadviseSlots] = {
    GuardedMadviseSlot<0>, GuardedMadviseSlot<1>,
    GuardedMadviseSlot<2>, GuardedMadviseSlot<3>,
    GuardedMadviseSlot<4>, GuardedMadviseSlot<5>,
    GuardedMadviseSlot<6>, GuardedMadviseSlot<7>,
};

bool RangeInImage(const Image& image, uintptr_t address, size_t size,
                  uint32_t required_flags = 0u) {
    if (size > UINTPTR_MAX - address) return false;
    const uintptr_t end = address + size;
    for (size_t index = 0u; index < image.segment_count; ++index) {
        const Segment& segment = image.segments[index];
        if (address >= segment.begin && end <= segment.end &&
                (segment.flags & required_flags) == required_flags) {
            return true;
        }
    }
    return false;
}

struct FindImageRequest {
    const char* path;
    uintptr_t base;
    Image result;
    size_t matches;
};

int FindImageCallback(dl_phdr_info* info, size_t, void* opaque) {
    auto* request = static_cast<FindImageRequest*>(opaque);
    const char* mapped_path = info->dlpi_name;
    bool matches = false;
    if (request->base != 0u) {
        matches = static_cast<uintptr_t>(info->dlpi_addr) == request->base;
    } else if (request->path != nullptr && mapped_path != nullptr) {
        if (strchr(request->path, '/') != nullptr) {
            matches = strcmp(request->path, mapped_path) == 0;
        } else {
            matches = strcmp(request->path, BaseName(mapped_path)) == 0;
        }
    }
    if (!matches) return 0;
    ++request->matches;
    if (request->matches != 1u || info->dlpi_phnum == 0u ||
            info->dlpi_phnum > 128u) {
        return 0;
    }
    Image& image = request->result;
    image.base = static_cast<uintptr_t>(info->dlpi_addr);
    image.phdr = info->dlpi_phdr;
    image.phnum = info->dlpi_phnum;
    if (mapped_path != nullptr) {
        const size_t length = strlen(mapped_path);
        if (length >= sizeof(image.path)) return 0;
        memcpy(image.path, mapped_path, length + 1u);
    }
    for (size_t index = 0u; index < image.phnum; ++index) {
        const ElfW(Phdr)& phdr = image.phdr[index];
        if (phdr.p_type != PT_LOAD || phdr.p_memsz == 0u ||
                image.segment_count >= kMaxSegments ||
                phdr.p_vaddr > UINTPTR_MAX - image.base ||
                phdr.p_memsz > UINTPTR_MAX - (image.base + phdr.p_vaddr)) {
            continue;
        }
        const uintptr_t begin = image.base + phdr.p_vaddr;
        image.segments[image.segment_count++] = {
                begin, begin + phdr.p_memsz, phdr.p_flags};
    }
    return 0;
}

bool FindImage(const char* path, uintptr_t base, Image* output) {
    if (output == nullptr || (path == nullptr && base == 0u)) return false;
    FindImageRequest request{path, base, {}, 0u};
    dl_iterate_phdr(FindImageCallback, &request);
    if (request.matches != 1u || request.result.segment_count == 0u) {
        return false;
    }
    *output = request.result;
    return true;
}

bool InstallPerSlotMadviseGuardLocked(const Image& image) {
    DynamicView view{};
    if (!BuildDynamicView(image, &view)) return false;
    void** raw_slots[kMaxPltSlots]{};
    size_t raw_count = 0u;
    if (!CollectRelocationSlots(image, view, view.plt_rela,
                                view.plt_rela_count, "madvise", raw_slots,
                                &raw_count) ||
            !CollectRelocationSlots(image, view, view.dyn_rela,
                                    view.dyn_rela_count, "madvise", raw_slots,
                                    &raw_count) ||
            raw_count == 0u) {
        return false;
    }
    void** slots[kMaxMadviseSlots]{};
    MadviseFn originals[kMaxMadviseSlots]{};
    size_t slot_count = 0u;
    for (size_t i = 0u; i < raw_count; ++i) {
        bool duplicate = false;
        for (size_t j = 0u; j < slot_count; ++j) {
            if (slots[j] == raw_slots[i]) {
                duplicate = true;
                break;
            }
        }
        if (duplicate) continue;
        if (slot_count >= kMaxMadviseSlots) return false;
        auto* previous = reinterpret_cast<MadviseFn>(
                __atomic_load_n(raw_slots[i], __ATOMIC_ACQUIRE));
        if (previous == nullptr ||
                previous == kMadviseSlotTrampolines[slot_count]) {
            return false;
        }
        slots[slot_count] = raw_slots[i];
        originals[slot_count] = previous;
        ++slot_count;
    }
    if (slot_count == 0u) return false;
    for (size_t i = 0u; i < slot_count; ++i) {
        if (!AddProtectedPage(reinterpret_cast<uintptr_t>(slots[i]))) {
            return false;
        }
    }
    pthread_mutex_lock(&g_code_patch_mutex);
    size_t written = 0u;
    for (; written < slot_count; ++written) {
        __atomic_store_n(&g_madvise_slot_backups[written], originals[written],
                         __ATOMIC_RELEASE);
        if (!WritePointerLocked(
                    slots[written],
                    reinterpret_cast<void*>(kMadviseSlotTrampolines[written]))) {
            break;
        }
    }
    if (written != slot_count) {
        for (size_t i = 0u; i <= written && i < slot_count; ++i) {
            void* installed = reinterpret_cast<void*>(kMadviseSlotTrampolines[i]);
            if (__atomic_load_n(slots[i], __ATOMIC_ACQUIRE) == installed &&
                    !WritePointerLocked(
                            slots[i], reinterpret_cast<void*>(originals[i]))) {
                __android_log_print(
                    ANDROID_LOG_ERROR, kLogTag,
                    "failed to restore madvise slot %zu after installation failure",
                    i);
            }
        }
        pthread_mutex_unlock(&g_code_patch_mutex);
        return false;
    }
    pthread_mutex_unlock(&g_code_patch_mutex);
    return true;
}

bool RegisterMadviseGuardLocked(const Image& image) {
    if (!InstallPerSlotMadviseGuardLocked(image)) {
        __atomic_store_n(&g_direct_madvise_guard_state, uint32_t{3},
                         __ATOMIC_RELEASE);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "failed to hook runtime %s madvise PLT",
                            image.path);
        return false;
    }
    __atomic_store_n(&g_direct_madvise_guard_state, uint32_t{2},
                     __ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "hooked direct runtime %s madvise", image.path);
    return true;
}

bool install_madvise_hook(const char* runtime_name) {
    const uint32_t fast = __atomic_load_n(
            &g_direct_madvise_guard_state, __ATOMIC_ACQUIRE);
    if (fast == 2u) return true;
    if (fast == 3u) return false;
    pthread_mutex_lock(&g_madvise_init_mutex);
    const uint32_t state = __atomic_load_n(
            &g_direct_madvise_guard_state, __ATOMIC_ACQUIRE);
    if (state == 2u) {
        pthread_mutex_unlock(&g_madvise_init_mutex);
        return true;
    }
    if (state == 3u) {
        pthread_mutex_unlock(&g_madvise_init_mutex);
        return false;
    }
    Image image{};
    const char* requested = runtime_name == nullptr
            ? kHyperRuntimeName : runtime_name;
    if (!FindImage(requested, 0u, &image) &&
            (runtime_name == nullptr ||
             !FindImage(kHyperRuntimeName, 0u, &image))) {
        pthread_mutex_unlock(&g_madvise_init_mutex);
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "cannot resolve loaded %s", requested);
        return false;
    }
    const bool ok = RegisterMadviseGuardLocked(image);
    pthread_mutex_unlock(&g_madvise_init_mutex);
    return ok;
}

uintptr_t RuntimeAddress(const Image& image, ElfW(Addr) value) {
    if (value == 0u) return 0u;
    const uintptr_t address = static_cast<uintptr_t>(value);
    if (RangeInImage(image, address, 1u)) return address;
    if (address > UINTPTR_MAX - image.base) return 0u;
    return image.base + address;
}

bool ReadGnuSymbolCount(const Image& image, uintptr_t address, size_t* output) {
    if (output == nullptr || !RangeInImage(image, address, 16u, PF_R)) {
        return false;
    }
    const auto* header = reinterpret_cast<const uint32_t*>(address);
    const uint32_t bucket_count = header[0];
    const uint32_t symbol_offset = header[1];
    const uint32_t bloom_count = header[2];
    if (bucket_count == 0u || bloom_count == 0u ||
            bucket_count > kMaxSymbols || bloom_count > kMaxSymbols) {
        return false;
    }
    uintptr_t buckets_address = address + 16u;
    const size_t bloom_bytes = static_cast<size_t>(bloom_count) * sizeof(ElfW(Addr));
    if (bloom_bytes > UINTPTR_MAX - buckets_address) return false;
    buckets_address += bloom_bytes;
    const size_t bucket_bytes = static_cast<size_t>(bucket_count) * sizeof(uint32_t);
    if (!RangeInImage(image, buckets_address, bucket_bytes, PF_R) ||
            bucket_bytes > UINTPTR_MAX - buckets_address) {
        return false;
    }
    const auto* buckets = reinterpret_cast<const uint32_t*>(buckets_address);
    const uintptr_t chains_address = buckets_address + bucket_bytes;
    uint32_t maximum = symbol_offset;
    for (uint32_t index = 0u; index < bucket_count; ++index) {
        uint32_t symbol = buckets[index];
        if (symbol < symbol_offset) continue;
        if (symbol >= kMaxSymbols) return false;
        while (true) {
            const size_t chain_index = static_cast<size_t>(symbol - symbol_offset);
            const uintptr_t chain_address = chains_address +
                    chain_index * sizeof(uint32_t);
            if (!RangeInImage(image, chain_address, sizeof(uint32_t), PF_R)) {
                return false;
            }
            const uint32_t chain =
                    *reinterpret_cast<const uint32_t*>(chain_address);
            if (symbol >= maximum) maximum = symbol + 1u;
            if ((chain & 1u) != 0u) break;
            if (++symbol >= kMaxSymbols) return false;
        }
    }
    *output = maximum;
    return maximum != 0u;
}

bool BuildDynamicView(const Image& image, DynamicView* output) {
    if (output == nullptr) return false;
    DynamicView view{};
    uintptr_t dynamic_address = 0u;
    size_t dynamic_count = 0u;
    for (size_t index = 0u; index < image.phnum; ++index) {
        const ElfW(Phdr)& phdr = image.phdr[index];
        if (phdr.p_type == PT_DYNAMIC && phdr.p_memsz >= sizeof(ElfW(Dyn)) &&
                phdr.p_vaddr <= UINTPTR_MAX - image.base) {
            dynamic_address = image.base + phdr.p_vaddr;
            dynamic_count = phdr.p_memsz / sizeof(ElfW(Dyn));
            break;
        }
    }
    if (dynamic_address == 0u || dynamic_count == 0u ||
            !RangeInImage(image, dynamic_address,
                          dynamic_count * sizeof(ElfW(Dyn)), PF_R)) {
        return false;
    }
    uintptr_t symbols = 0u;
    uintptr_t strings = 0u;
    uintptr_t sysv_hash = 0u;
    uintptr_t gnu_hash = 0u;
    uintptr_t plt_rela = 0u;
    size_t plt_size = 0u;
    uintptr_t dyn_rela = 0u;
    size_t dyn_rela_size = 0u;
    size_t string_size = 0u;
    bool plt_is_rela = false;
    const auto* dynamic = reinterpret_cast<const ElfW(Dyn)*>(dynamic_address);
    for (size_t index = 0u; index < dynamic_count; ++index) {
        const ElfW(Dyn)& entry = dynamic[index];
        if (entry.d_tag == DT_NULL) break;
        switch (entry.d_tag) {
            case DT_SYMTAB: symbols = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_STRTAB: strings = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_STRSZ: string_size = entry.d_un.d_val; break;
            case DT_HASH: sysv_hash = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_GNU_HASH: gnu_hash = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_JMPREL: plt_rela = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_PLTRELSZ: plt_size = entry.d_un.d_val; break;
            case DT_PLTREL: plt_is_rela = entry.d_un.d_val == DT_RELA; break;
            case DT_RELA: dyn_rela = RuntimeAddress(image, entry.d_un.d_ptr); break;
            case DT_RELASZ: dyn_rela_size = entry.d_un.d_val; break;
            default: break;
        }
    }
    size_t symbol_count = 0u;
    if (sysv_hash != 0u && RangeInImage(image, sysv_hash, 8u, PF_R)) {
        symbol_count = reinterpret_cast<const uint32_t*>(sysv_hash)[1];
    } else if (gnu_hash != 0u && !ReadGnuSymbolCount(
                       image, gnu_hash, &symbol_count)) {
        return false;
    }
    if (symbols == 0u || strings == 0u || string_size == 0u ||
            symbol_count == 0u || symbol_count > kMaxSymbols ||
            !RangeInImage(image, symbols,
                          symbol_count * sizeof(ElfW(Sym)), PF_R) ||
            !RangeInImage(image, strings, string_size, PF_R)) {
        return false;
    }
    view.symbols = reinterpret_cast<const ElfW(Sym)*>(symbols);
    view.strings = reinterpret_cast<const char*>(strings);
    view.string_size = string_size;
    view.symbol_count = symbol_count;
    if (plt_rela != 0u && plt_size != 0u && plt_is_rela &&
            plt_size % sizeof(ElfW(Rela)) == 0u &&
            RangeInImage(image, plt_rela, plt_size, PF_R)) {
        view.plt_rela = reinterpret_cast<const ElfW(Rela)*>(plt_rela);
        view.plt_rela_count = plt_size / sizeof(ElfW(Rela));
    }
    if (dyn_rela != 0u && dyn_rela_size != 0u &&
            dyn_rela_size % sizeof(ElfW(Rela)) == 0u &&
            RangeInImage(image, dyn_rela, dyn_rela_size, PF_R)) {
        view.dyn_rela = reinterpret_cast<const ElfW(Rela)*>(dyn_rela);
        view.dyn_rela_count = dyn_rela_size / sizeof(ElfW(Rela));
    }
    *output = view;
    return true;
}

bool SymbolNameEquals(const DynamicView& view, uint32_t symbol_index,
                      const char* expected) {
    if (expected == nullptr || symbol_index >= view.symbol_count) return false;
    const uint32_t offset = view.symbols[symbol_index].st_name;
    if (offset >= view.string_size) return false;
    const char* name = view.strings + offset;
    const size_t remaining = view.string_size - offset;
    const size_t length = strnlen(name, remaining);
    return length < remaining && strlen(expected) == length &&
            memcmp(name, expected, length) == 0;
}

int ReadProtection(uintptr_t address) {
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return -1;
    char line[768]{};
    int result = -1;
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (strchr(line, '\n') == nullptr) {
            int ch;
            while ((ch = fgetc(maps)) != '\n' && ch != EOF) {}
        }
        unsigned long long begin = 0u;
        unsigned long long end = 0u;
        char permissions[5]{};
        if (sscanf(line, "%llx-%llx %4s", &begin, &end, permissions) != 3) {
            continue;
        }
        if (address < begin || address >= end) continue;
        result = (permissions[0] == 'r' ? PROT_READ : 0) |
                (permissions[1] == 'w' ? PROT_WRITE : 0) |
                (permissions[2] == 'x' ? PROT_EXEC : 0);
        break;
    }
    fclose(maps);
    return result;
}

bool WritePointerLocked(void** slot, void* value) {
    const uintptr_t page = PageStart(reinterpret_cast<uintptr_t>(slot));
    const int protection = ReadProtection(reinterpret_cast<uintptr_t>(slot));
    if (protection < 0 || mprotect(reinterpret_cast<void*>(page), PageSize(),
                                  protection | PROT_WRITE) != 0) {
        return false;
    }
    __atomic_store_n(slot, value, __ATOMIC_RELEASE);
    return mprotect(reinterpret_cast<void*>(page), PageSize(), protection) == 0;
}

bool CollectRelocationSlots(const Image& image, const DynamicView& view,
                            const ElfW(Rela)* relocations, size_t count,
                            const char* symbol, void*** slots,
                            size_t* slot_count) {
    for (size_t index = 0u; index < count; ++index) {
        const ElfW(Rela)& relocation = relocations[index];
        const uint32_t type = ELF64_R_TYPE(relocation.r_info);
        if (type != R_AARCH64_JUMP_SLOT && type != R_AARCH64_GLOB_DAT) continue;
        const uint32_t symbol_index = ELF64_R_SYM(relocation.r_info);
        if (!SymbolNameEquals(view, symbol_index, symbol)) continue;
        const uintptr_t address = RuntimeAddress(image, relocation.r_offset);
        if (address == 0u || !RangeInImage(image, address, sizeof(void*))) {
            return false;
        }
        if (*slot_count >= kMaxPltSlots) return false;
        slots[(*slot_count)++] = reinterpret_cast<void**>(address);
    }
    return true;
}

int PltHookRaw(void* base, const char* symbol, void* replacement,
               void** original) {
    if (base == nullptr || symbol == nullptr || replacement == nullptr ||
            original == nullptr) {
        return kHookFailed;
    }
    Image image{};
    DynamicView view{};
    if (!FindImage(nullptr, reinterpret_cast<uintptr_t>(base), &image) ||
            !BuildDynamicView(image, &view)) {
        return kHookFailed;
    }
    void** slots[kMaxPltSlots]{};
    size_t slot_count = 0u;
    if (!CollectRelocationSlots(image, view, view.plt_rela,
                                view.plt_rela_count, symbol, slots,
                                &slot_count) ||
            !CollectRelocationSlots(image, view, view.dyn_rela,
                                    view.dyn_rela_count, symbol, slots,
                                    &slot_count) ||
            slot_count == 0u) {
        return kHookFailed;
    }
    void* expected = __atomic_load_n(slots[0], __ATOMIC_ACQUIRE);
    if (expected == nullptr || expected == replacement) return kHookFailed;
    for (size_t index = 1u; index < slot_count; ++index) {
        if (__atomic_load_n(slots[index], __ATOMIC_ACQUIRE) != expected) {
            return kHookFailed;
        }
    }
    for (size_t index = 0u; index < slot_count; ++index) {
        if (!AddProtectedPage(reinterpret_cast<uintptr_t>(slots[index]))) {
            return kHookFailed;
        }
    }
    pthread_mutex_lock(&g_code_patch_mutex);
    // The replacement can run immediately after the first slot changes.
    __atomic_store_n(original, expected, __ATOMIC_RELEASE);
    size_t written = 0u;
    for (; written < slot_count; ++written) {
        if (!WritePointerLocked(slots[written], replacement)) break;
    }
    if (written != slot_count) {
        // WritePointerLocked can fail after writing when restoring protection fails.
        // Only undo slots still owned by us, including the failing slot.
        for (size_t index = 0u; index <= written; ++index) {
            if (__atomic_load_n(slots[index], __ATOMIC_ACQUIRE) == replacement &&
                    !WritePointerLocked(slots[index], expected)) {
                __android_log_print(
                    ANDROID_LOG_ERROR, kLogTag,
                    "failed to restore PLT slot after installation failure");
            }
        }
        pthread_mutex_unlock(&g_code_patch_mutex);
        return kHookFailed;
    }
    pthread_mutex_unlock(&g_code_patch_mutex);
    return kHookSuccess;
}

}  // namespace

int InstallPltHook(void* base, const char* symbol, void* replacement,
                   void** original) {
    if (!EnsureLsposedMadviseGuard()) return kHookFailed;
    return PltHookRaw(base, symbol, replacement, original);
}

int InstallInlineHook(void* target, void* replacement, void** original) {
    if (g_lsposed_api == nullptr || g_lsposed_api->hookFunc == nullptr ||
            !EnsureLsposedMadviseGuard() || target == nullptr ||
            replacement == nullptr || original == nullptr) {
        return kHookFailed;
    }
    const uintptr_t begin = reinterpret_cast<uintptr_t>(target);
    const uintptr_t end = begin <= UINTPTR_MAX - (kInlinePatchGuardSpan - 1u)
            ? begin + kInlinePatchGuardSpan - 1u : begin;
    if (!AddProtectedPage(begin) || !AddProtectedPage(end)) {
        return kHookFailed;
    }
    // Publish both possible patch pages before LSPosed writes the trampoline.
    // Xiaomi may issue MADV_DONTNEED concurrently with hook installation, so
    // registering only after hookFunc returns leaves a small destructive race.
    *original = nullptr;
    const int result = g_lsposed_api->hookFunc(target, replacement, original);
    if (result != 0) return kHookFailed;
    if (*original == nullptr) {
        g_lsposed_api->unhookFunc(target);
        return kHookFailed;
    }
    return kHookSuccess;
}

int PatchArm64Instruction(void* target, uint32_t expected,
                          uint32_t replacement) {
    if (target == nullptr ||
            (reinterpret_cast<uintptr_t>(target) & (sizeof(uint32_t) - 1u)) !=
                0u ||
            !EnsureLsposedMadviseGuard()) {
        return kHookFailed;
    }
    pthread_mutex_lock(&g_code_patch_mutex);
    const uintptr_t address = reinterpret_cast<uintptr_t>(target);
    const int protection = ReadProtection(address);
    if (protection < 0 || (protection & PROT_EXEC) == 0 ||
            !AddProtectedPage(address)) {
        pthread_mutex_unlock(&g_code_patch_mutex);
        return kHookFailed;
    }

    uint32_t current = 0u;
    memcpy(&current, target, sizeof(current));
    if (current == replacement) {
        pthread_mutex_unlock(&g_code_patch_mutex);
        return kHookSuccess;
    }
    if (current != expected) {
        pthread_mutex_unlock(&g_code_patch_mutex);
        return kHookFailed;
    }

    const uintptr_t page = PageStart(address);
    if (mprotect(reinterpret_cast<void*>(page), PageSize(),
                 protection | PROT_WRITE) != 0) {
        pthread_mutex_unlock(&g_code_patch_mutex);
        return kHookFailed;
    }
    __atomic_store_n(static_cast<uint32_t*>(target), replacement,
                     __ATOMIC_RELEASE);
    __builtin___clear_cache(
        reinterpret_cast<char*>(target),
        reinterpret_cast<char*>(target) + sizeof(uint32_t));
    if (mprotect(reinterpret_cast<void*>(page), PageSize(), protection) == 0) {
        pthread_mutex_unlock(&g_code_patch_mutex);
        return kHookSuccess;
    }
    // A failed protection restore happens after the instruction was written.
    // Undo that write while the page is still writable before reporting failure.
    __atomic_store_n(static_cast<uint32_t*>(target), expected,
                     __ATOMIC_RELEASE);
    __builtin___clear_cache(
        reinterpret_cast<char*>(target),
        reinterpret_cast<char*>(target) + sizeof(uint32_t));
    if (mprotect(reinterpret_cast<void*>(page), PageSize(), protection) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "failed to restore code page protection");
    }
    pthread_mutex_unlock(&g_code_patch_mutex);
    return kHookFailed;
}

int RemoveInlineHook(void* target) {
    if (g_lsposed_api == nullptr || g_lsposed_api->unhookFunc == nullptr ||
            target == nullptr) {
        return kHookFailed;
    }
    return g_lsposed_api->unhookFunc(target) == 0 ? kHookSuccess : kHookFailed;
}

static int PatchExactSequence16(void* target, const uint32_t expected[4],
        const uint32_t replacement[4], bool fresh_image, Arm64ExactPatchKind kind) {
    if (!fresh_image || !EnsureLsposedMadviseGuard()) return kHookFailed;
    const Arm64ExactPatchOps ops{
        nullptr, PageSize(),
        [](void*, uintptr_t page) { return ReadProtection(page); },
        [](void*, uintptr_t page) { return AddProtectedPage(page); },
        [](void*, uintptr_t page, size_t size, int protection) {
            return mprotect(reinterpret_cast<void*>(page), size, protection) == 0;
        },
        [](void*, void* address, size_t size) {
            auto* start = static_cast<char*>(address);
            __builtin___clear_cache(start, start + size);
        }};
    pthread_mutex_lock(&g_code_patch_mutex);
    const bool ok = PatchArm64Tail16(target, expected, replacement, fresh_image, ops, kind);
    pthread_mutex_unlock(&g_code_patch_mutex);
    return ok ? kHookSuccess : kHookFailed;
}

int PatchArm64HighlightTail16(void* target, const uint32_t expected[4],
                             const uint32_t replacement[4], bool fresh_image) {
    return PatchExactSequence16(target, expected, replacement, fresh_image, Arm64ExactPatchKind::Highlight);
}
int PatchArm64Label16(void* target, const uint32_t expected[4],
                     const uint32_t replacement[4], bool lines, bool fresh_image) {
    return PatchExactSequence16(target, expected, replacement, fresh_image,
            lines ? Arm64ExactPatchKind::LabelLines : Arm64ExactPatchKind::LabelText);
}

bool EnsureLsposedMadviseGuard(const char* runtime_name) {
    return install_madvise_hook(runtime_name);
}

bool InitializeLsposedHookBackend(const NativeAPIEntries* entries) {
    if (entries == nullptr || entries->hookFunc == nullptr ||
            entries->unhookFunc == nullptr) {
        return false;
    }
    g_lsposed_api = entries;
    return true;
}

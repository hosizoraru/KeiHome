// SPDX-License-Identifier: Apache-2.0
#include "verified_elf_image.h"
#include "monet_image_identity.h"

#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <string.h>

namespace {

constexpr size_t kMaxImageSpan = 128u * 1024u * 1024u;
constexpr size_t kMaxSegments = 16u;

struct RawSegment {
    uintptr_t begin;
    uintptr_t end;
    size_t vaddr;
    size_t size;
    bool executable;
    uintptr_t cursor;
};

struct PhdrCollector {
    uintptr_t base;
    RawSegment segments[kMaxSegments];
    size_t count;
    bool failed;
};

int CollectLoadSegments(dl_phdr_info* info, size_t, void* opaque) {
    auto* collector = static_cast<PhdrCollector*>(opaque);
    if (info->dlpi_addr != collector->base) return 0;
    if (info->dlpi_phnum > 64u) {
        collector->failed = true;
        return 1;
    }
    for (size_t i = 0u; i < info->dlpi_phnum; ++i) {
        const auto& p = info->dlpi_phdr[i];
        if (p.p_type != PT_LOAD || (p.p_flags & PF_R) == 0u || p.p_memsz == 0u) {
            continue;
        }
        if (collector->count == kMaxSegments ||
                p.p_vaddr > UINTPTR_MAX - collector->base ||
                p.p_memsz > UINTPTR_MAX - (collector->base + p.p_vaddr) ||
                p.p_memsz > kMaxImageSpan ||
                p.p_vaddr > kMaxImageSpan - p.p_memsz) {
            collector->failed = true;
            return 1;
        }
        const uintptr_t begin = collector->base + p.p_vaddr;
        const uintptr_t end = begin + p.p_memsz;
        collector->segments[collector->count++] = {
            begin, end, size_t(p.p_vaddr), size_t(p.p_memsz),
            (p.p_flags & PF_X) != 0u, begin,
        };
    }
    return 1;
}

bool VerifySegmentsMapped(RawSegment* segments, size_t count) {
    if (count == 0u) return false;
    FILE* maps = fopen("/proc/self/maps", "re");
    if (maps == nullptr) return false;
    char line[768];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        // Drain overlong paths so the remainder never parses as a fake mapping.
        if (strchr(line, '\n') == nullptr) {
            int ch;
            while ((ch = fgetc(maps)) != '\n' && ch != EOF) {}
        }
        unsigned long long begin = 0u;
        unsigned long long finish = 0u;
        char permissions[5]{};
        if (sscanf(line, "%llx-%llx %4s", &begin, &finish, permissions) != 3) {
            continue;
        }
        const bool readable = permissions[0] == 'r';
        const bool executable = permissions[2] == 'x';
        bool all_done = true;
        for (size_t i = 0u; i < count; ++i) {
            RawSegment& seg = segments[i];
            if (seg.cursor >= seg.end) continue;
            if (finish > seg.cursor && begin <= seg.cursor) {
                if (readable && (!seg.executable || executable)) {
                    seg.cursor = finish < seg.end ? uintptr_t(finish) : seg.end;
                }
            }
            if (seg.cursor < seg.end) all_done = false;
        }
        if (all_done) break;
    }
    fclose(maps);
    for (size_t i = 0u; i < count; ++i) {
        if (segments[i].cursor != segments[i].end) return false;
    }
    return true;
}

}  // namespace

bool BuildVerifiedDartImage(void* handle, VerifiedDartImage* out,
                            bool compute_sha256) {
    if (out == nullptr) return false;
    *out = {};
    if (handle == nullptr) return false;
    void* snapshot = dlsym(handle, "_kDartIsolateSnapshotInstructions");
    void* build_id = dlsym(handle, "_kDartSnapshotBuildId");
    Dl_info info{};
    if (snapshot == nullptr || !dladdr(snapshot, &info) ||
            info.dli_fbase == nullptr) {
        return false;
    }
    PhdrCollector collector{};
    collector.base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(CollectLoadSegments, &collector);
    if (collector.failed || collector.count == 0u ||
            !VerifySegmentsMapped(collector.segments, collector.count)) {
        return false;
    }
    if (compute_sha256 && info.dli_fname != nullptr &&
            HashMonetLibrary(info.dli_fname, out->sha256)) {
        out->has_sha256 = true;
    }
    out->base = collector.base;
    out->snapshot_instructions = reinterpret_cast<uintptr_t>(snapshot);
    out->snapshot_build_id = reinterpret_cast<uintptr_t>(build_id);
    out->count = collector.count;
    for (size_t i = 0u; i < collector.count; ++i) {
        const RawSegment& seg = collector.segments[i];
        const size_t rel_end = seg.vaddr + seg.size;
        out->relative_ranges[i] = {seg.vaddr, rel_end, seg.executable};
        out->absolute_tint_ranges[i] = {seg.begin, seg.size, seg.executable};
        out->absolute_selection_ranges[i] = {seg.begin, seg.size, seg.executable};
        if (rel_end > out->span) out->span = rel_end;
    }
    out->valid = true;
    return true;
}

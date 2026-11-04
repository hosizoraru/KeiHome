// SPDX-License-Identifier: Apache-2.0
// Host-only fixture runner. Produces ELF-relative profile JSON for independent
// symbol-oracle checks, then exercises production rejection paths.
#include "dock_glass_refresh.h"
#include "launcher_label_profile.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <vector>

namespace {
unsigned checks = 0;
void Check(bool ok, const char* what) {
    if (!ok) { std::fprintf(stderr, "FAIL %s\n", what); std::exit(1); }
    ++checks;
}
template<class T> T Read(const std::vector<uint8_t>& b, size_t at) {
    if (at > b.size() || sizeof(T) > b.size() - at) throw std::runtime_error("short ELF");
    T n; std::memcpy(&n, b.data() + at, sizeof(n)); return n;
}
void Write(std::vector<uint8_t>& b, size_t at, uint32_t w) {
    Check(at <= b.size() && 4 <= b.size() - at, "bounded test mutation");
    std::memcpy(b.data() + at, &w, 4);
}
uint32_t Bl(size_t from, size_t to) {
    const int64_t delta = int64_t(to) - int64_t(from);
    Check(!(delta & 3) && delta >= -0x8000000 && delta < 0x8000000, "test BL reach");
    return 0x94000000u | (uint32_t(delta / 4) & 0x3ffffffu);
}
bool Empty(const DockGlassRefreshProfile& p) {
    return !p.second_event_call && !p.second_event_target && !p.second_event_expected &&
            !p.getter_value_call && !p.getter_value_expected && !p.refresh &&
            !p.stream_add && !p.rx_subject_field && !p.observer_slot;
}
void Run(const char* path) {
    std::ifstream f(path, std::ios::binary);
    const std::vector<uint8_t> file{std::istreambuf_iterator<char>(f), {}};
    Check(file.size() >= 64, "fixture loaded");
    const size_t ph = Read<uint64_t>(file, 32);
    const size_t count = Read<uint16_t>(file, 56);
    std::vector<IconHighlightRange> ranges;
    size_t span = 0;
    for (size_t i = 0; i < count; ++i) {
        const size_t at = ph + i * 56;
        if (Read<uint32_t>(file, at) != 1 || !(Read<uint32_t>(file, at + 4) & 4)) continue;
        const size_t begin = Read<uint64_t>(file, at + 16);
        const size_t mem = Read<uint64_t>(file, at + 40);
        Check(begin <= 128u*1024u*1024u && mem <= 128u*1024u*1024u - begin, "bounded LOAD");
        if (!mem) continue;
        ranges.push_back({begin, begin + mem, bool(Read<uint32_t>(file, at + 4) & 1)});
        if (begin + mem > span) span = begin + mem;
    }
    std::vector<uint8_t> bytes(span);
    for (size_t i = 0; i < count; ++i) {
        const size_t at = ph + i * 56;
        if (Read<uint32_t>(file, at) != 1 || !(Read<uint32_t>(file, at + 4) & 4)) continue;
        const size_t offset = Read<uint64_t>(file, at + 8);
        const size_t begin = Read<uint64_t>(file, at + 16);
        const size_t n = Read<uint64_t>(file, at + 32);
        Check(offset <= file.size() && n <= file.size() - offset &&
                begin <= span && n <= span - begin, "file-to-VA LOAD mapping");
        std::memcpy(bytes.data() + begin, file.data() + offset, n);
    }
    IconHighlightImage image{bytes.data(), bytes.size(), ranges.data(), ranges.size()};
    LauncherLabelProfile labels{}; const char* why = nullptr;
    Check(ResolveLauncherLabels(image, &labels, &why), why ? why : "original labels resolve");
    DockGlassRefreshProfile p{};
    Check(ResolveDockGlassRefresh(image, labels, &p, &why), why ? why : "refresh resolves");
    Check(!why, "success clears reason");
    Check(p.second_event_call > labels.refresh_call &&
            p.second_event_target == labels.refresh_logger, "second logger ownership");
    Check(p.getter_value_call == labels.config + 0x54 &&
            p.getter_value_expected == Read<uint32_t>(bytes, p.getter_value_call) &&
            p.second_event_expected == Read<uint32_t>(bytes, p.second_event_call),
            "exact original patch words");
    std::printf("PROFILE {\"fixture\":\"%s\",\"second_event_call\":%zu,"
                "\"second_event_target\":%zu,\"second_event_expected\":%u,"
                "\"getter_value_call\":%zu,\"getter_value_expected\":%u,"
                "\"refresh\":%zu,\"stream_add\":%zu,"
                "\"rx_subject_field\":%u,\"observer_slot\":%u}\n",
                path, p.second_event_call, p.second_event_target, p.second_event_expected,
                p.getter_value_call, p.getter_value_expected, p.refresh, p.stream_add,
                p.rx_subject_field, p.observer_slot);

    const auto reject = [&](const LauncherLabelProfile& input, const char* name) {
        DockGlassRefreshProfile bad = p; const char* reason = nullptr;
        Check(!ResolveDockGlassRefresh(image, input, &bad, &reason) &&
                Empty(bad) && reason, name);
    };
    const auto mutation = [&](size_t at, uint32_t word, const char* name) {
        const uint32_t saved = Read<uint32_t>(bytes, at);
        Write(bytes, at, word); reject(labels, name); Write(bytes, at, saved);
    };
    for (size_t i = 0; i < 0x38; i += 4)
        mutation(p.refresh + i, Read<uint32_t>(bytes, p.refresh + i) ^ 4u,
                 "refresh shape/link mutation rejected");
    mutation(p.second_event_call, Bl(p.second_event_call, labels.value), "second target drift");
    mutation(p.second_event_call + 12, 0xaa0003e2u, "second result became live");
    mutation(labels.refresh_call, 0xd503201fu, "prepare after label patch rejected");
    mutation(p.getter_value_call, Bl(p.getter_value_call, p.stream_add), "getter value target drift");
    mutation(labels.config + 12, 0xaa0003e9u, "cached path x9 clobber rejected");
    mutation(labels.value + 0x14, 0xf9403f42u, "observer lookup base/register drift");
    mutation(labels.value + 0x2c, Read<uint32_t>(bytes, labels.value + 0x2c) ^ 0x4000u,
             "value/refresh subject mismatch");
    mutation(p.stream_add, 0xd503201fu, "stream-add shape drift");
    mutation(p.stream_add + 0x54, Bl(p.stream_add + 0x54, labels.value), "notify-data target drift");

    auto wrong = labels; wrong.refresh_call = p.second_event_call;
    reject(wrong, "first logger ownership drift");
    wrong = labels; wrong.grid_slot += 8;
    reject(wrong, "cached owner slot linkage drift");
    wrong = labels; wrong.config = size_t(-1);
    reject(wrong, "out-of-bounds label input");
    why = nullptr;
    Check(!ResolveDockGlassRefresh(image, labels, nullptr, &why) && why, "null output");
    auto invalid = image; invalid.size = 0;
    auto bad = p;
    Check(!ResolveDockGlassRefresh(invalid, labels, &bad, &why) && Empty(bad) && why,
          "invalid image clears output");

    // The observer table offset is derived, not an implicit all-version slot.
    const uint32_t observer = Read<uint32_t>(bytes, labels.value + 0x18);
    Write(bytes, labels.value + 0x18, observer + 0x400u);
    DockGlassRefreshProfile derived{};
    Check(ResolveDockGlassRefresh(image, labels, &derived, &why) &&
            derived.observer_slot == p.observer_slot + 8, "observer slot derived from code");
    Write(bytes, labels.value + 0x18, observer);

    // An identical linked refresh at another executable address is ambiguous.
    size_t duplicate = 0;
    for (const auto& r : ranges)
        if (r.executable && r.end - r.begin >= 0x100) duplicate = (r.end - 0x80) & ~size_t(3);
    Check(duplicate && (duplicate > p.refresh + 0x38 || duplicate + 0x38 <= p.refresh),
          "independent executable duplicate location");
    std::vector<uint8_t> saved(bytes.begin() + duplicate, bytes.begin() + duplicate + 0x38);
    std::memcpy(bytes.data() + duplicate, bytes.data() + p.refresh, 0x38);
    Write(bytes, duplicate + 0x18, Bl(duplicate + 0x18, labels.value));
    Write(bytes, duplicate + 0x24, Bl(duplicate + 0x24, p.stream_add));
    reject(labels, "duplicate linked refresh rejected");
    std::memcpy(bytes.data() + duplicate, saved.data(), saved.size());

    // A different backing allocation produces the same image-relative result.
    const auto relocated = bytes;
    const IconHighlightImage moved{relocated.data(), relocated.size(), ranges.data(), ranges.size()};
    Check(ResolveDockGlassRefresh(moved, labels, &derived, &why) &&
            derived.refresh == p.refresh && derived.second_event_call == p.second_event_call,
          "different host load base");
}
} // namespace

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    try {
        for (int i = 1; i < argc; ++i) Run(argv[i]);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "Fixture error: %s\n", e.what()); return 2;
    }
    std::printf("PASS %u host fixture/rejection checks\n", checks);
}

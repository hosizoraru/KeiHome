// SPDX-License-Identifier: Apache-2.0
// Host-only complete ELF fixtures and private-symbol/serialized-GC-map oracles.
#include "launcher_glass_material_profile.h"
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <map>
#include <string>
#include <utility>
#include <vector>

namespace {
unsigned checks = 0, rejected = 0;
void Check(bool ok, const char* message) {
    if (!ok) { std::fprintf(stderr, "FAIL %s\n", message); std::exit(1); }
    ++checks;
}
template<class T> T Read(const std::vector<uint8_t>& b, size_t at) {
    Check(at <= b.size() && sizeof(T) <= b.size() - at, "fixture read bounds");
    T result;
    memcpy(&result, b.data() + at, sizeof(T));
    return result;
}
void Write(std::vector<uint8_t>& b, size_t at, uint32_t word) {
    Check(at <= b.size() && 4 <= b.size() - at, "fixture write bounds");
    memcpy(b.data() + at, &word, 4);
}
uint32_t Bl(size_t at, size_t to) {
    return 0x94000000u | (uint32_t((int64_t(to) - int64_t(at)) / 4) & 0x3ffffffu);
}
template<class T> bool Zero(const T& p) {
    for (size_t i = 0; i < sizeof(p); ++i)
        if (reinterpret_cast<const uint8_t*>(&p)[i]) return false;
    return true;
}
void Export(const LauncherGlassMaterialProfile& p, const char* oracleName) {
    const char* directory = std::getenv("KEIHOME_MATERIAL_EXPORT_DIR");
    if (!directory) return;
    const char* leaf = std::strrchr(oracleName, '/');
    std::string name = leaf ? leaf + 1 : oracleName;
    Check(name.compare(0, 9, "material-") == 0, "known oracle filename for export");
    name.insert(9, "resolved-");
    std::ofstream output(std::string(directory) + "/" + name);
    auto field = [&](const char* key, uint64_t value) {
        output << key << "\t0x" << std::hex << value << '\n';
    };
#define FIELD(key) field(#key, uint64_t(p.key))
    FIELD(build); FIELD(closure); FIELD(valueCall); FIELD(valueOriginal); FIELD(valueReturnPc);
    FIELD(branch); FIELD(branchWord); FIELD(resultRegister); FIELD(boolBit); FIELD(reserved);
    FIELD(darkTarget); FIELD(lightTarget); FIELD(nativeJoin);
    FIELD(controllerSlot); FIELD(controllerSourceCount);
    FIELD(wallpaperRxField); FIELD(darkCacheField); FIELD(lightCacheField); FIELD(capturedStateField);
    FIELD(rxSubjectField); FIELD(rxValueField); FIELD(observerSlot); FIELD(refreshEntry);
    FIELD(subjectListenersField); FIELD(subjectBusyField); FIELD(subjectListenerLengthField);
    FIELD(subjectCloseEntry); FIELD(subjectNotifyDataEntry); FIELD(subjectNotifyDoneEntry);
    FIELD(wallpaperSetter); FIELD(nativeTrueOffset); FIELD(nativeFalseOffset);
    FIELD(returnPc); FIELD(replayWord); FIELD(eventFrameBytes); FIELD(resume);
    FIELD(firstLoggerCall); FIELD(secondLoggerCall); FIELD(event); FIELD(instructionsTable);
#define MAP(key) FIELD(key.payload); FIELD(key.bitmap); FIELD(key.spillBits); FIELD(key.otherBits)
    MAP(branchMap); MAP(eventMap); MAP(refreshValueMap); MAP(refreshAddMap);
#undef MAP
    FIELD(rx.second_event_call); FIELD(rx.second_event_target); FIELD(rx.second_event_expected);
    FIELD(rx.getter_value_call); FIELD(rx.getter_value_expected); FIELD(rx.refresh);
    FIELD(rx.stream_add); FIELD(rx.rx_subject_field); FIELD(rx.observer_slot);
#undef FIELD
    output.close();
    Check(bool(output), "all production-resolved fields exported as TSV");
}
void Extras(const std::vector<uint8_t>& b, const IconHighlightImage& view,
        const LauncherGlassMaterialProfile& material, const LauncherLabelProfile& labels,
        const char* oracleName) {
    std::string metadata = oracleName;
    metadata.insert(metadata.find_last_of('/') + 1 + 9, "extra-");
    std::ifstream input(metadata);
    std::map<std::string, size_t> expected;
    std::string key;
    size_t number;
    while (input >> key >> std::hex >> number) expected[key] = number;
    Check(expected.size() == 3 * 21, "three complete optional source/polarity oracles loaded");
    LauncherGlassMaterialChooseSites sites{};
    const char* reason = nullptr;
    Check(ResolveLauncherGlassMaterialChooseSites(view, material, &sites, &reason),
            "optional source resolver accepts admitted pristine Dock");
    Check(sites.count == 3 && sites.admittedMask == 7 && !sites.ambiguousMask,
            "press/search and shared finite native default constructor admitted");
    LauncherGlassMaterialChooseSites shipping{};
    Check(ResolveLauncherGlassSearchCapsule(view, material, &shipping, &reason),
            "shipping search resolver accepts pristine source");
    Check(shipping.count == 1 && shipping.admittedMask == 2 &&
            !shipping.ambiguousMask && shipping.sites[0].kind == kLauncherGlassMaterialSearchCapsule,
            "shipping optional scope excludes every folder experiment");
    Check(shipping.sites[0].branch == sites.sites[1].branch &&
            shipping.sites[0].lightSchemeTarget == sites.sites[1].lightSchemeTarget &&
            shipping.sites[0].darkSchemeTarget == sites.sites[1].darkSchemeTarget,
            "shipping search matches independent native source and polarity");
    auto damagedSearch = b;
    const uint32_t damagedWord = Read<uint32_t>(b, sites.sites[1].source) ^ 0x20u;
    std::memcpy(damagedSearch.data() + sites.sites[1].source, &damagedWord, 4);
    IconHighlightImage damagedView{damagedSearch.data(), damagedSearch.size(), view.ranges, view.range_count};
    Check(ResolveLauncherGlassSearchCapsule(damagedView, material, &shipping, &reason) &&
            !shipping.count && !shipping.admittedMask,
            "missing search proof keeps the optional scope inactive");
    std::ofstream exported;
    if (const char* directory = std::getenv("KEIHOME_MATERIAL_EXPORT_DIR")) {
        std::string leaf = metadata.substr(metadata.find_last_of('/') + 1);
        leaf.insert(15, "resolved-");
        exported.open(std::string(directory) + "/" + leaf);
    }
    auto field = [&](const std::string& name, uint64_t value) {
        if (exported.is_open()) exported << name << "\t0x" << std::hex << value << '\n';
    };
    field("count", sites.count);
    field("admittedMask", sites.admittedMask);
    field("ambiguousMask", sites.ambiguousMask);
    field("reserved", sites.reserved);
    for (unsigned i = 0; i < sites.count; ++i) {
        const auto& s = sites.sites[i];
        const std::string prefix = i == 0 ? "folder." :
                i == 1 ? "searchCapsule." : "folderDefaultColors.";
        const std::string output = "sites[" + std::to_string(i) + "].";
#define SITE(key) Check(uint64_t(s.key) == expected[prefix + #key], "optional " #key); \
        field(output + #key, uint64_t(s.key))
        SITE(kind); SITE(word); SITE(branch); SITE(darkTarget); SITE(lightTarget);
        SITE(resultRegister); SITE(boolBit); SITE(trueTokenSlot); SITE(falseTokenSlot);
        SITE(valueCall); SITE(valueOriginal); SITE(nativeJoin); SITE(source); SITE(widgetAllocator);
        SITE(lightSchemeTarget); SITE(darkSchemeTarget);
        SITE(darkConfigFactory); SITE(lightConfigFactory);
#undef SITE
        const int32_t imm14 = int32_t((s.word >> 5 & 0x3fffu) ^ 0x2000u) - 0x2000;
        const size_t taken = size_t(int64_t(s.branch) + int64_t(imm14) * 4);
        const size_t fallthrough = s.branch + 4;
        Check((s.word & 0x7e000000u) == 0x36000000u &&
                (s.word & 31) == s.resultRegister &&
                (s.word >> 19 & 31) == s.boolBit &&
                taken == expected[prefix + "branchTaken"] &&
                fallthrough == expected[prefix + "branchFallthrough"],
                "original branch encodes authenticated register, bit and successors");
        const bool trueIsLight = expected[prefix + "nativeTrueIsLight"] != 0;
        const size_t nativeTrue = s.word & 0x01000000u ? fallthrough : taken;
        const size_t nativeFalse = s.word & 0x01000000u ? taken : fallthrough;
        Check(s.lightSchemeTarget && s.darkSchemeTarget &&
                s.lightSchemeTarget != s.darkSchemeTarget &&
                s.lightSchemeTarget == (trueIsLight ? nativeTrue : nativeFalse) &&
                s.darkSchemeTarget == (trueIsLight ? nativeFalse : nativeTrue) &&
                trueIsLight == (s.kind != kLauncherGlassMaterialFolderDefaultColors),
                "explicit UI polarity follows native Wallpaper/default-color semantics");
        field(output + "reserved", s.reserved);
        Check(!s.reserved && s.darkTarget == s.branch + 4 &&
                s.resultRegister == (i == 2 ? 1 : 0) && s.boolBit == 4,
                "native TRUE is clear bit 4/fallthrough");
        Check(i == 2 ? s.lightSchemeTarget == s.lightTarget :
                s.lightSchemeTarget == s.darkTarget, "explicit per-role scheme polarity");
    }
    if (exported.is_open()) {
        exported.close();
        Check(bool(exported), "all optional choose fields exported");
    }
    auto resolve = [&](std::vector<uint8_t>& bytes, uint32_t mask, uint32_t ambiguous) {
        IconHighlightImage mutated = view;
        mutated.base = bytes.data();
        LauncherGlassMaterialChooseSites result;
        memset(&result, 0xa5, sizeof(result));
        Check(ResolveLauncherGlassMaterialChooseSites(mutated, material, &result, &reason),
                "unsupported optional role preserves successful optional query");
        const unsigned count = unsigned(bool(mask & 1)) + unsigned(bool(mask & 2)) +
                unsigned(bool(mask & 4));
        if (result.admittedMask != mask || result.ambiguousMask != ambiguous ||
                result.count != count) {
            std::fprintf(stderr, "optional actual count=%u mask=%x ambiguity=%x; "
                    "expected count=%u mask=%x ambiguity=%x\n", result.count,
                    result.admittedMask, result.ambiguousMask, count, mask, ambiguous);
        }
        Check(result.admittedMask == mask && result.ambiguousMask == ambiguous &&
                result.count == count, "independent optional role rejection/count");
        Check(!result.sites[count].branch, "unused optional slot cleared");
        ++rejected;
    };
    auto wordCase = [&](size_t at, uint32_t value, uint32_t mask, uint32_t ambiguous = 0) {
        auto changed = b;
        Write(changed, at, value);
        resolve(changed, mask, ambiguous);
    };
    for (unsigned i = 0; i < 2; ++i) {
        const auto& s = sites.sites[i];
        const uint32_t other = 7u ^ (1u << (s.kind - 1));
        wordCase(s.valueCall, Bl(s.valueCall, material.rx.stream_add), other);
        wordCase(s.source + 0x40, Read<uint32_t>(b, s.source + 0x40) + 0x4000u, other);
        wordCase(s.branch, s.word ^ (1u << 19), other);
        wordCase(s.branch, s.word ^ (1u << 24), other);
        wordCase(s.lightTarget + 4, Read<uint32_t>(b, s.lightTarget + 4) + 0x400u, other);
        const size_t allocation = s.source + (i ? 0xd8 :
                (Read<uint32_t>(b, s.source + 0x50) == 0xf9403f40u ? 0xe0 : 0xe8));
        wordCase(allocation, Bl(allocation, material.valueOriginal), other);
        auto changed = b;
        Write(changed, s.branch, s.word ^ (1u << 19));
        IconHighlightImage mutated = view;
        mutated.base = changed.data();
        LauncherGlassMaterialProfile dock{};
        Check(ResolveLauncherGlassMaterial(mutated, labels, &dock, &reason) &&
                dock.branch == material.branch, "optional rejection leaves Dock admission intact");
    }
    wordCase(sites.sites[0].widgetAllocator + 0x18,
            Read<uint32_t>(b, sites.sites[0].widgetAllocator + 0x18) ^ 0x20u, 4);
    const auto& folder = sites.sites[0];
    if (Read<uint32_t>(b, folder.source + 0x50) != 0xf9403f40u) {
        wordCase(folder.source - 8, Read<uint32_t>(b, folder.source - 8) ^ 0x20u, 6);
        wordCase(folder.source + 0x54, Read<uint32_t>(b, folder.source + 0x54) ^ 1u, 6);
    }
    auto clone = [&](std::vector<uint8_t>& bytes, unsigned i, size_t destination) {
        const auto& s = sites.sites[i];
        const size_t begin = s.source - 8;
        const size_t size = 8 + (i ? 0x1a0 :
                (Read<uint32_t>(b, s.source + 0x50) == 0xf9403f40u ? 0x180 : 0x188));
        Check(destination <= bytes.size() && size <= bytes.size() - destination,
                "bounded duplicate source fixture space");
        memcpy(bytes.data() + destination, b.data() + begin, size);
        for (size_t off = 0; off < size; off += 4) {
            const uint32_t w = Read<uint32_t>(b, begin + off);
            if ((w & 0x7c000000u) != 0x14000000u) continue;
            const int64_t delta = int64_t(int32_t((w & 0x3ffffffu) << 6) >> 6) * 4;
            size_t target = size_t(int64_t(begin + off) + delta);
            if (target >= begin && target < begin + size) target = destination + target - begin;
            Write(bytes, destination + off, (w & 0xfc000000u) |
                    (Bl(destination + off, target) & 0x3ffffffu));
        }
    };
    for (unsigned i = 0; i < 2; ++i) {
        auto changed = b;
        clone(changed, i, material.event + 0x2400);
        resolve(changed, 7u ^ sites.sites[i].kind, sites.sites[i].kind);
    }
    auto changed = b;
    clone(changed, 0, material.event + 0x2400);
    clone(changed, 1, material.event + 0x2800);
    resolve(changed, 4, 3);
    const auto& defaults = sites.sites[2];
    wordCase(defaults.branch, defaults.word ^ 0x01000000u, 3);
    wordCase(defaults.lightConfigFactory + 0x40, 0x910082c1u, 3, 4);
    wordCase(defaults.branch, defaults.word ^ (1u << 19), 3);
    wordCase(defaults.source + 0x1c,
            Bl(defaults.source + 0x1c, material.valueOriginal), 3);
    wordCase(defaults.darkConfigFactory + 0x40, 0x9100c2c1u, 3, 4);
    wordCase(defaults.lightConfigFactory + 0x48,
            Bl(defaults.lightConfigFactory + 0x48, material.valueOriginal), 3);
    wordCase(defaults.widgetAllocator + 0x18,
            Read<uint32_t>(b, defaults.widgetAllocator + 0x18) ^ 0x400u, 3);
    const size_t colorCopy = (material.event + 0x5000 + 15) & ~size_t(15),
            darkCopy = colorCopy + 0x200, lightCopy = colorCopy + 0x300;
    auto copyFactory = [&](std::vector<uint8_t>& bytes, size_t from,
            size_t to, size_t size, bool redirectConstructor) {
        for (size_t off = 0; off < size; off += 4) {
            uint32_t word = Read<uint32_t>(b, from + off);
            if ((word & 0xfc000000u) == 0x94000000u) {
                const size_t target = size_t(int64_t(from + off) +
                        int64_t(int32_t(word << 6) >> 6) * 4);
                word = Bl(to + off, redirectConstructor && target == defaults.source ?
                        colorCopy : target);
            }
            Write(bytes, to + off, word);
        }
    };
    changed = b;
    // Duplicate only a caller: ambiguity must clear only the default role.
    copyFactory(changed, defaults.darkConfigFactory, darkCopy, 0x80, false);
    resolve(changed, 3, 4);
    changed = b;
    // A second complete constructor + both source-authenticated caller roles.
    copyFactory(changed, defaults.source, colorCopy, 0xd8, false);
    copyFactory(changed, defaults.darkConfigFactory, darkCopy, 0x80, true);
    copyFactory(changed, defaults.lightConfigFactory, lightCopy, 0x80, true);
    resolve(changed, 3, 4);
    auto wrong = material;
    wrong.valueOriginal += 4;
    memset(&sites, 0xa5, sizeof(sites));
    Check(!ResolveLauncherGlassMaterialChooseSites(view, wrong, &sites, &reason) &&
            reason && Zero(sites), "unproven parent source clears optional output");
}
void FolderRefresh(const std::vector<uint8_t>& b, const IconHighlightImage& view,
        const LauncherGlassMaterialProfile& material, const LauncherLabelProfile& labels,
        const char* oracleName) {
    std::string metadata = oracleName;
    metadata.insert(metadata.find_last_of('/') + 1 + 9, "native-style-");
    std::ifstream input(metadata);
    std::map<std::string, uint64_t> expected;
    std::string key;
    uint64_t number;
    while (input >> key >> std::hex >> number) expected[key] = number;
    Check(expected.size() >= 50, "independent folder source oracle loaded");
    LauncherGlassMaterialFolderRefreshProfile p{};
    const char* why = nullptr;
    if (!ResolveLauncherGlassMaterialFolderRefresh(view, material, &p, &why)) {
        std::fprintf(stderr, "%s folder refresh: %s\n", oracleName,
                why ? why : "no reason");
        Check(false, "production folder refresh source admitted");
    }
    std::ofstream output;
    if (const char* directory = std::getenv("KEIHOME_MATERIAL_EXPORT_DIR")) {
        const std::string leaf = std::string(oracleName).substr(
                std::string(oracleName).find_last_of('/') + 1 + 9);
        output.open(std::string(directory) + "/material-folder-refresh-resolved-" + leaf);
    }
#define FOLDER(key) Check(uint64_t(p.key) == expected[#key], "folder " #key); \
    if (output.is_open()) output << #key << "\t0x" << std::hex << uint64_t(p.key) << '\n'
    FOLDER(initState); FOLDER(buildChild); FOLDER(stateBuild); FOLDER(onChanged);
    FOLDER(observerCallback); FOLDER(rebuildCallback); FOLDER(stateSetState);
    FOLDER(closureAllocator); FOLDER(controllerSlot); FOLDER(rxField);
    FOLDER(subjectField); FOLDER(valueField); FOLDER(observerSlot);
    FOLDER(subjectListenersField); FOLDER(subjectBusyField); FOLDER(subjectListenerLengthField);
    FOLDER(refresh); FOLDER(refreshRet); FOLDER(refreshRetWord);
    FOLDER(refreshValueReturn); FOLDER(refreshAddReturn);
    FOLDER(refreshSubjectFrameOffset); FOLDER(refreshRetSubjectDartSpOffset);
    FOLDER(refreshFrameBytes); FOLDER(onChangedStateFrameOffset); FOLDER(onChangedFrameBytes);
    FOLDER(refreshSubjectLiveAcrossAdd);
    FOLDER(equalityBranch); FOLDER(equalityWord); FOLDER(equalityFallthrough);
    FOLDER(equalityTaken); FOLDER(onChangedRet); FOLDER(onChangedRetWord);
    FOLDER(stateModeField); FOLDER(stateChildField); FOLDER(contextStateField);
    FOLDER(contextValueField); FOLDER(observerDescriptorSlot); FOLDER(rebuildDescriptorSlot);
    FOLDER(functionEntryField); FOLDER(closureEntryField); FOLDER(smiShift);
    FOLDER(smiValueBits); FOLDER(boxedIntValueField); FOLDER(nativeModeMin);
    FOLDER(nativeModeMax); FOLDER(x0DeadOnBothSuccessors);
    FOLDER(requiresRuntimeCallbackEntryCheck);
#undef FOLDER
    if (output.is_open()) {
        output.close();
        Check(bool(output), "all folder refresh fields exported");
    }
    Check(p.subjectField == material.rxSubjectField && p.valueField == material.rxValueField &&
            p.refresh == material.refreshEntry && p.observerSlot == material.observerSlot,
            "integer Rx uses the authenticated inherited value/refresh layout");
    Check(material.refreshAddMap.spillBits == expected["refreshAddSpillBits"] &&
            Read<uint8_t>(b, material.refreshAddMap.bitmap) == expected["refreshAddBitmap"] &&
            expected["refreshAddSpillBits"] == 1 && expected["refreshAddBitmap"] == 0 &&
            p.refreshRetSubjectDartSpOffset == p.refreshSubjectFrameOffset - 16 &&
            !p.refreshSubjectLiveAcrossAdd,
            "add-return GC map excludes the dead subject spill; raw bytes are not identity");
    Check(Read<uint32_t>(b, p.equalityFallthrough) == 0xaa0103e2u &&
            Read<uint32_t>(b, p.equalityTaken) == 0xaa1603e0u &&
            Read<uint32_t>(b, p.refreshRet - 8) == 0xaa1d03efu &&
            Read<uint32_t>(b, p.refreshRet - 4) == 0xa8c179fdu,
            "gate x0 liveness and restored FP/LR at RET are original instructions");
    auto reject = [&](std::vector<uint8_t>& changed) {
        auto mutated = view;
        mutated.base = changed.data();
        LauncherGlassMaterialFolderRefreshProfile denied;
        std::memset(&denied, 0x55, sizeof(denied));
        const char* reason = nullptr;
        Check(!ResolveLauncherGlassMaterialFolderRefresh(mutated, material, &denied, &reason),
                "mutated/ambiguous optional folder source rejected");
        Check(reason && Zero(denied), "folder failure clears all fields and supplies reason");
        ++rejected;
    };
    const size_t mutations[] = {
        p.initState + 0x14, p.initState + 0x60, p.initState + 0x70,
        p.initState + 0x144, p.equalityBranch, p.equalityFallthrough,
        p.onChanged + 0x70, p.onChanged + 0x6c, p.rebuildCallback + 0x58, p.refreshRet,
        p.refresh + 0xc, p.refresh + 0x10, p.refresh + 0x18, p.refresh + 0x24,
        p.equalityFallthrough + 4, p.equalityTaken, p.rebuildCallback + 0x50,
        p.closureAllocator, p.stateSetState, p.onChangedRet
    };
    for (size_t pc : mutations) {
        auto changed = b;
        Write(changed, pc, Read<uint32_t>(b, pc) ^ 0x20u);
        reject(changed);
    }
    auto changed = b;
    Write(changed, p.equalityBranch, p.equalityWord ^ 1u);
    reject(changed);
    auto auxiliary = view;
    auxiliary.base = changed.data();
    LauncherGlassMaterialProfile base{};
    Check(ResolveLauncherGlassMaterial(auxiliary, labels, &base, &why),
            "optional folder gate failure preserves primary material admission");
    const size_t spare = (material.event + 0x3000 + 15) & ~size_t(15);
    changed = b;
    std::memcpy(changed.data() + spare, b.data() + p.onChanged, 0x120);
    Write(changed, spare + 0xec, Bl(spare + 0xec, p.buildChild));
    reject(changed);
    changed = b;
    Write(changed, spare, Bl(spare, p.buildChild));
    reject(changed);
    changed = b;
    changed[material.refreshAddMap.bitmap] ^= 1;
    reject(changed);
    Check(!ResolveLauncherGlassMaterialFolderRefresh(view, material, nullptr, &why) && why,
            "null folder output is rejected");
}

struct MappedFixture {
    std::vector<uint8_t> bytes;
    std::vector<IconHighlightRange> ranges;
};
MappedFixture Load(const char* fileName) {
    std::ifstream input(fileName, std::ios::binary);
    std::vector<uint8_t> file{std::istreambuf_iterator<char>(input), {}};
    Check(file.size() > 64, "actual AOT loaded");
    const size_t ph = size_t(Read<uint64_t>(file, 32));
    const size_t count = Read<uint16_t>(file, 56);
    Check(count && count < 64 && Read<uint16_t>(file, 54) == 56, "AOT program headers");
    std::vector<IconHighlightRange> ranges;
    size_t span = 0;
    for (size_t i = 0; i < count; ++i) {
        const size_t p = ph + i * 56;
        if (Read<uint32_t>(file, p) != 1) continue;
        const size_t lo = size_t(Read<uint64_t>(file, p + 16));
        const size_t n = size_t(Read<uint64_t>(file, p + 40));
        Check(lo < (128u << 20) && n <= (128u << 20) - lo, "bounded real AOT span");
        if (lo + n > span) span = lo + n;
        ranges.push_back({lo, lo + n, bool(Read<uint32_t>(file, p + 4) & 1)});
    }
    std::vector<uint8_t> b(span);
    for (size_t i = 0; i < count; ++i) {
        const size_t p = ph + i * 56;
        if (Read<uint32_t>(file, p) != 1) continue;
        const size_t from = size_t(Read<uint64_t>(file, p + 8));
        const size_t to = size_t(Read<uint64_t>(file, p + 16));
        const size_t n = size_t(Read<uint64_t>(file, p + 32));
        Check(from <= file.size() && n <= file.size() - from &&
                to <= b.size() && n <= b.size() - to, "map ELF file bytes");
        memcpy(b.data() + to, file.data() + from, n);
    }
    return {std::move(b), std::move(ranges)};
}
struct SourceSeed {
    LauncherLabelProfile labels;
    LauncherGlassMaterialProfile material;
};
SourceSeed Run(const char* fileName, const char* oracleName) {
    const unsigned firstCheck = checks, firstRejection = rejected;
    auto fixture = Load(fileName);
    auto& b = fixture.bytes;
    const auto& ranges = fixture.ranges;
    std::ifstream oracle(oracleName);
    std::map<std::string, size_t> expected;
    std::string key;
    size_t number;
    while (oracle >> key >> std::hex >> number) expected[key] = number;
    Check(expected.size() > 25, "independent host oracle loaded");
    IconHighlightImage image{b.data(), b.size(), ranges.data(), ranges.size()};
    LauncherLabelProfile labels{};
    const char* why = nullptr;
    Check(ResolveLauncherLabels(image, &labels, &why), why ? why : "labels source resolved");
    LauncherGlassMaterialProfile p{};
    const bool ok = ResolveLauncherGlassMaterial(image, labels, &p, &why);
    if (!ok) std::fprintf(stderr, "%s: %s\n", fileName, why ? why : "unknown");
    Check(ok, "production material resolver accepts actual AOT");
    Check(p.closure == expected["closure"] && p.build == expected["build"] &&
            p.valueCall == expected["value_call"] && p.valueOriginal == expected["value"] &&
            p.valueReturnPc == p.branch && p.branch == expected["branch"], "closure/Rx call graph");
    Check(p.lightTarget == expected["light"] && p.darkTarget == expected["dark"] &&
            p.resultRegister == 0 && p.boolBit == 4, "native material branch polarity");
    Check(p.controllerSlot == expected["wallpaper_slot"] &&
            p.wallpaperRxField == int(expected["wallpaper_rx_field"]) &&
            p.darkCacheField == int(expected["dark_field"]) &&
            p.lightCacheField == int(expected["light_field"]), "Wallpaper and filter owner fields");
    Check(p.wallpaperSetter == expected["setter"] && p.nativeTrueOffset == 32 &&
            p.nativeFalseOffset == 48, "independent colorMode==2 source proof");
    Check(p.returnPc == expected["return_pc"] && p.resume == expected["resume"] &&
            p.replayWord == expected["replay_word"] &&
            p.firstLoggerCall == expected["first_log"] &&
            p.secondLoggerCall == expected["second_log"], "first logger replay checkpoint");
    Check(p.refreshEntry == expected["refresh"] && p.rx.stream_add == expected["stream_add"] &&
            p.rxValueField == labels.rx_value_field && p.rxSubjectField == p.rx.rx_subject_field &&
            p.observerSlot == p.rx.observer_slot, "shared native generic Rx.refresh admission");
    Check(p.eventMap.bitmap == expected["event_bitmap"] &&
            p.branchMap.bitmap == expected["branch_bitmap"] &&
            p.refreshValueMap.bitmap == expected["refresh_value_bitmap"] &&
            p.refreshAddMap.bitmap == expected["refresh_add_bitmap"], "serialized native GC maps");
    Check(p.subjectListenersField == expected["subject_listeners_field"] &&
            p.subjectBusyField == expected["subject_busy_field"] &&
            p.subjectListenerLengthField == expected["subject_listener_length_field"] &&
            p.subjectCloseEntry == expected["subject_close"] &&
            p.subjectNotifyDataEntry == expected["subject_notify_data"] &&
            p.subjectNotifyDoneEntry == expected["subject_notify_done"],
            "native close uses null listeners/busy, not an invented closed boolean");
    Export(p, oracleName);
    FolderRefresh(b, image, p, labels, oracleName);
    Extras(b, image, p, labels, oracleName);
    std::printf("%s closure=%zx branch=%zx dark=%zx light=%zx slot=%x Rx=%x "
            "return=%zx word=%08x spills=%u\n", fileName, p.closure, p.branch,
            p.darkTarget, p.lightTarget, p.controllerSlot, p.wallpaperRxField,
            p.returnPc, p.replayWord, p.eventMap.spillBits);
    auto reject = [&](const char* name, const LauncherLabelProfile& source) {
        LauncherGlassMaterialProfile bad;
        memset(&bad, 0xa5, sizeof(bad));
        const char* reason = nullptr;
        const bool accepted = ResolveLauncherGlassMaterial(image, source, &bad, &reason);
        if (accepted) std::fprintf(stderr, "unexpected acceptance: %s\n", name);
        Check(!accepted && reason && Zero(bad), name);
        ++rejected;
    };
    auto wordCase = [&](size_t at, uint32_t w, const char* name) {
        const uint32_t saved = Read<uint32_t>(b, at);
        Write(b, at, w);
        reject(name, labels);
        Write(b, at, saved);
    };
    wordCase(p.valueCall, 0xd503201f, "original subscription getter removed");
    wordCase(p.branch, p.branchWord ^ 0x01000000u, "reversed native bool polarity");
    wordCase(p.branch, p.branchWord ^ 1, "wrong getter result register");
    wordCase(p.branch, p.branchWord + 32, "branch to wrong light block");
    wordCase(p.closure + 0x78, Read<uint32_t>(b, p.closure + 0xac), "swapped dark cache owner");
    wordCase(p.closure + 0xa4, Read<uint32_t>(b, p.closure + 0xa4) + 0x4000u,
            "mismatched captured State pointer");
    wordCase(p.closure + 0x20, Read<uint32_t>(b, p.closure + 0x20) + 0x400u,
            "inconsistent cached Wallpaper controller slot");
    wordCase(p.closure + 0x1c, Read<uint32_t>(b, p.closure + 0x1c) + 0x400u,
            "cached controller uses another thread field table");
    wordCase(p.closure + 0x80, Read<uint32_t>(b, p.closure + 0x80) + 0x400u,
            "material cache uses another late-field sentinel");
    wordCase(p.closure + 0x5c, Read<uint32_t>(b, p.closure + 0x5c) + 0x4000u,
            "Wallpaper Rx field differs from colorMode setter");
    wordCase(p.closure + 0xc8, Bl(p.closure + 0xc8, p.valueOriginal),
            "light cache uses unrelated late initializer");
    wordCase(p.returnPc, 0x10000011, "PC-relative replay instruction rejected");
    wordCase(p.returnPc + 4, 0xaa0003e2, "logger result no longer discarded");
    wordCase(p.returnPc + 12, Bl(p.returnPc + 12, p.valueOriginal),
            "post-logger interpolation call changed");
    wordCase(p.refreshEntry + 0x18, Bl(p.refreshEntry + 0x18, labels.config),
            "non-generic Rx.refresh getter target rejected");
    wordCase(p.refreshEntry + 0xc, Read<uint32_t>(b, p.refreshEntry + 0xc) + 0x4000u,
            "Rx subject differs from subscription getter");
    wordCase(p.subjectCloseEntry + 0x1c, 0xb801b020u,
            "close no longer nulls listener ownership");
    wordCase(p.subjectCloseEntry + 0x20, Read<uint32_t>(b, p.subjectCloseEntry + 0x20) + 0x4000u,
            "close clears a different busy field");
    wordCase(p.subjectNotifyDoneEntry + 0x18,
            Read<uint32_t>(b, p.subjectNotifyDoneEntry + 0x18) + 0x4000u,
            "close notification reads another subscription list");
    wordCase(p.subjectNotifyDataEntry + 0x100, 0xb801f016u,
            "data notification no longer clears busy to canonical false");
    wordCase(p.subjectCloseEntry + 0x14, Bl(p.subjectCloseEntry + 0x14, p.subjectNotifyDataEntry),
            "close redirected from notifyDone to notifyData");
    for (const size_t at : {p.eventMap.bitmap, p.branchMap.bitmap}) {
        b[at] ^= 1;
        reject("native GC root bitmap mutated", labels);
        b[at] ^= 1;
    }
    b[expected["event_global"]] ^= 1;
    reject("event spill count differs from native frame", labels);
    b[expected["event_global"]] ^= 1;
    wordCase(p.eventMap.payload, Read<uint32_t>(b, p.eventMap.payload) ^ 1,
            "wrong local stack-map payload dialect");
    {
        const size_t at = p.event + 0x2000;
        std::vector<uint8_t> saved(b.begin() + at, b.begin() + at + 0x38);
        memcpy(b.data() + at, b.data() + p.refreshEntry, 0x38);
        Write(b, at + 0x18, Bl(at + 0x18, p.valueOriginal));
        Write(b, at + 0x24, Bl(at + 0x24, p.rx.stream_add));
        reject("ambiguous generic native refresh", labels);
        memcpy(b.data() + at, saved.data(), saved.size());
    }
    auto badLabels = labels;
    badLabels.value += 4;
    reject("stale/other-image label anchors", badLabels);
    {
        // A coherent slot relocation proves data-derived field-table discovery.
        std::vector<std::pair<size_t, uint32_t>> restore;
        for (const auto& field : expected) {
            if (field.first.rfind("controller_load_", 0) != 0) continue;
            const uint32_t saved = Read<uint32_t>(b, field.second);
            restore.push_back({field.second, saved});
            Write(b, field.second, saved + 0x400u);
        }
        Check(restore.size() == p.controllerSourceCount, "all same-type controller cache sources");
        const bool old = expected["setter_size"] == 0xd0;
        uint32_t oldStore = 0;
        if (old) {
            oldStore = Read<uint32_t>(b, p.wallpaperSetter + 0xbc);
            Write(b, p.wallpaperSetter + 0xbc, oldStore + 0x400u);
        }
        LauncherGlassMaterialProfile shifted{};
        Check(ResolveLauncherGlassMaterial(image, labels, &shifted, &why) &&
                shifted.controllerSlot == p.controllerSlot + 8,
                "coherent Wallpaper slot relocation accepted without any RVA/slot allowlist");
        for (const auto& field : restore) Write(b, field.first, field.second);
        if (old) {
            Write(b, p.wallpaperSetter + 0xbc, oldStore);
        }
    }
    std::printf("CASE %s checks=%u rejections=%u\n",
            fileName, checks - firstCheck, rejected - firstRejection);
    return {labels, p};
}
void Negative(const char* path, const SourceSeed& seed) {
    const unsigned firstCheck = checks, firstRejection = rejected;
    Check(seed.material.branch != 0, "negative source follows a proven positive fixture");
    auto fixture = Load(path);
    IconHighlightImage view{fixture.bytes.data(), fixture.bytes.size(),
            fixture.ranges.data(), fixture.ranges.size()};
    const auto reject = [&](const char* name, auto resolve, auto* out) {
        memset(out, 0xa5, sizeof(*out));
        const char* why = nullptr;
        const bool accepted = resolve(&why);
        ++rejected;
        Check(!accepted && why && Zero(*out), name);
    };
    LauncherLabelProfile labels;
    reject("base native image has no label roots", [&](const char** why) {
        return ResolveLauncherLabels(view, &labels, why);
    }, &labels);
    LauncherGlassMaterialProfile material;
    reject("base native image cannot reuse authenticated material roots", [&](const char** why) {
        return ResolveLauncherGlassMaterial(view, seed.labels, &material, why);
    }, &material);
    LauncherGlassMaterialChooseSites sites;
    reject("base native image cannot admit optional material roles", [&](const char** why) {
        return ResolveLauncherGlassMaterialChooseSites(view, seed.material, &sites, why);
    }, &sites);
    LauncherGlassMaterialFolderRefreshProfile folder;
    reject("base native image cannot admit folder refresh", [&](const char** why) {
        return ResolveLauncherGlassMaterialFolderRefresh(view, seed.material, &folder, why);
    }, &folder);
    std::fill(fixture.bytes.begin(), fixture.bytes.end(), 0);
    reject("zero-backed base image cannot admit optional material roles", [&](const char** why) {
        return ResolveLauncherGlassMaterialChooseSites(view, seed.material, &sites, why);
    }, &sites);
    reject("zero-backed base image cannot admit folder refresh", [&](const char** why) {
        return ResolveLauncherGlassMaterialFolderRefresh(view, seed.material, &folder, why);
    }, &folder);
    std::printf("NEGATIVE %s checks=%u rejections=%u\n",
            path, checks - firstCheck, rejected - firstRejection);
}
} // namespace
int main(int argc, char** argv) {
    Check(argc >= 3 && (argc & 1), "arguments: actual AOT/oracle pairs");
    SourceSeed source{};
    for (int i = 1; i < argc; i += 2) {
        if (std::strcmp(argv[i], "--negative") == 0) Negative(argv[i + 1], source);
        else source = Run(argv[i], argv[i + 1]);
    }
    std::printf("material profile: %u checks, %u rejection cases\n", checks, rejected);
}

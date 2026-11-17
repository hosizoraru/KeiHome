// SPDX-License-Identifier: Apache-2.0
// Dynamic, bounded instruction/call-graph contracts. Reproducible independent
// fixture checks live in scripts/tests/monet-selection; the generated contracts
// are compiled from the adjacent monet_selection_patterns.h.
#include "selection_profile.h"
#include "monet_image_identity.h"
#include "arm64_entry_filter.h"

#include <string.h>

extern "C" {
uintptr_t kh_selection_factory_entry = 0;
uintptr_t kh_selection_update_callback_entry = 0;
uintptr_t kh_selection_factory_pool_offset = 0;
uintptr_t kh_selection_update_callback_pool_offset = 0;
uint32_t kh_selection_profile_ready = 0;
}

namespace {
constexpr size_t kMaxRanges = 64;
constexpr size_t kMaxLoads = 16;
// Relocating PP makes otherwise identical enum wrappers share a body shape.
// They remain bounded and must be disambiguated by actual incoming call edges.
constexpr size_t kMaxCandidateAddresses = 128;
constexpr size_t kMaxCandidates = 512;
constexpr uintptr_t kMaxImageSpan = 0x8000000;
struct Header {
    uint8_t ident[16];
    uint16_t type, machine;
    uint32_t version;
    uint64_t entry, phoff, shoff;
    uint32_t flags;
    uint16_t ehsize, phentsize, phnum, shentsize, shnum, shstrndx;
};
struct Program {
    uint32_t type, flags;
    uint64_t offset, vaddr, paddr, filesz, memsz, align;
};
static_assert(sizeof(Header) == 64 && sizeof(Program) == 56);
struct Load { uintptr_t begin; size_t size; uint32_t flags; };
struct View {
    MonetSelectionImageView source;
    Load loads[kMaxLoads];
    size_t count;
};
// Structural decoding follows the bounded segment/branch validation approach in
// MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp
// (Apache-2.0). This Dart-specific canonicalization and graph implementation is
// independent; no launcher-version offsets or legacy fallback RVAs are used.
struct Edge { uint16_t index; int16_t role; uint16_t body; };
struct Body {
    uint16_t count, scope_count;
    uint8_t digest[32];
    uint16_t edge_begin, edge_count;
};
struct Pattern { size_t role; uint32_t prefix[4]; uint16_t body; };
#include "monet_selection_patterns.h"
constexpr auto kEntryFilter = MakeArm64EntryFilter(kPatterns);
bool OptionalRole(size_t role) {
    return role == kIconQueryCallback || role == kCacheQuery || role == kPreviewUpdate;
}
struct Candidate { uintptr_t address; const Pattern* pattern; };
// Relocated bodies can have several dependency contracts at the same entry.
// Bound addresses independently so adding a reviewed shape does not reject a
// valid image, while preserving the original search/ambiguity budget.
struct Choices { Candidate values[kMaxCandidates]; size_t count, addresses; };
constexpr size_t kMaxWords = 2048;
constexpr size_t kMaxSlots = 256;
constexpr size_t kMaxGraphNodes = 1024;

bool Fail(const char** rejection, const char* text) {
    if (rejection != nullptr) *rejection = text;
    return false;
}

bool Readable(const MonetSelectionImageView& view, uintptr_t address,
              size_t size, bool executable = false) {
    if (size == 0 || address > UINTPTR_MAX - size ||
        view.readable_ranges == nullptr || view.readable_range_count == 0 ||
        view.readable_range_count > kMaxRanges) return false;
    const uintptr_t end = address + size;
    uintptr_t cursor = address;
    // Adjacent readable mappings can jointly cover a checked read.
    for (size_t pass = 0; pass < view.readable_range_count && cursor < end; ++pass) {
        uintptr_t next = cursor;
        for (size_t i = 0; i < view.readable_range_count; ++i) {
            const auto& range = view.readable_ranges[i];
            if (range.size == 0 || range.begin > UINTPTR_MAX - range.size ||
                (executable && !range.executable)) continue;
            const uintptr_t limit = range.begin + range.size;
            if (range.begin <= cursor && limit > next) next = limit;
        }
        if (next == cursor) return false;
        cursor = next;
    }
    return cursor >= end;
}

bool LoadContains(const View& view, uintptr_t address, size_t size) {
    if (size == 0 || address > UINTPTR_MAX - size) return false;
    for (size_t i = 0; i < view.count; ++i) {
        const auto& load = view.loads[i];
        if ((load.flags & 5u) == 5u && (load.flags & 2u) == 0u &&
            address >= load.begin && address + size <= load.begin + load.size &&
            Readable(view.source, address, size, true)) return true;
    }
    return false;
}

bool Parse(const MonetSelectionImageView& image, View* view) {
    if (image.load_bias == 0 || !Readable(image, image.load_bias, sizeof(Header))) return false;
    Header h{};
    memcpy(&h, reinterpret_cast<const void*>(image.load_bias), sizeof(h));
    constexpr uint8_t ident[] = {0x7f,'E','L','F',2,1,1};
    if (memcmp(h.ident, ident, sizeof(ident)) != 0 || h.type != 3 ||
        h.machine != 183 || h.version != 1 || h.ehsize != 64 ||
        h.phentsize != 56 || h.phnum == 0 || h.phnum > 64 ||
        h.phoff < 64 || h.phoff > 0x1000 ||
        h.phoff + h.phnum * sizeof(Program) > 0x1000 ||
        image.load_bias > UINTPTR_MAX - h.phoff) return false;
    const uintptr_t ph = image.load_bias + h.phoff;
    if (!Readable(image, ph, h.phnum * sizeof(Program))) return false;
    *view = {}; view->source = image;
    for (size_t i = 0; i < h.phnum; ++i) {
        Program p{};
        memcpy(&p, reinterpret_cast<const void*>(ph + i * sizeof(p)), sizeof(p));
        if (p.type != 1) continue;
        if (view->count == kMaxLoads || p.filesz > p.memsz || p.memsz == 0 ||
            p.vaddr > kMaxImageSpan || p.memsz > kMaxImageSpan - p.vaddr ||
            p.offset > UINT64_MAX - p.filesz ||
            image.load_bias > UINTPTR_MAX - (p.vaddr + p.memsz) ||
            (p.flags & 3u) == 3u ||
            (p.align > 1 && ((p.align & (p.align - 1)) != 0 ||
                              p.offset % p.align != p.vaddr % p.align))) return false;
        const uintptr_t begin = image.load_bias + p.vaddr;
        if ((p.flags & 4u) == 0 || !Readable(image, begin, p.memsz)) return false;
        for (size_t j = 0; j < view->count; ++j) {
            const auto& old = view->loads[j];
            if (begin < old.begin + old.size && old.begin < begin + p.memsz) return false;
        }
        if ((p.flags & 1u) && ((begin & 3u) || (p.filesz & 3u) ||
                              !Readable(image, begin, p.filesz, true))) return false;
        // Only file-backed executable bytes are scanned. Non-X mappings are
        // retained solely for rejecting overlap, not used as instruction data.
        view->loads[view->count++] = {begin,
                static_cast<size_t>((p.flags & 1u) ? p.filesz : p.memsz), p.flags};
    }
    return view->count > 0;
}

uint32_t Word(uintptr_t address) {
    uint32_t word; memcpy(&word, reinterpret_cast<const void*>(address), 4); return word;
}

bool Branch(uintptr_t pc, uint32_t word, uintptr_t* target, bool* link) {
    uint32_t immediate; unsigned bits;
    *link = false;
    if ((word & 0x7c000000u) == 0x14000000u) {
        immediate = word & 0x03ffffffu; bits = 26;
        *link = (word & 0x80000000u) != 0;
    } else if ((word & 0xff000010u) == 0x54000000u ||
               (word & 0x7e000000u) == 0x34000000u) {
        immediate = (word >> 5u) & 0x7ffffu; bits = 19;
    } else if ((word & 0x7e000000u) == 0x36000000u) {
        immediate = (word >> 5u) & 0x3fffu; bits = 14;
    } else return false;
    const int64_t sign = int64_t{1} << (bits - 1);
    const int64_t delta = ((static_cast<int64_t>(immediate) ^ sign) - sign) * 4;
    if (delta < 0) {
        if (pc < static_cast<uintptr_t>(-delta)) return false;
        *target = pc - static_cast<uintptr_t>(-delta);
    } else {
        if (pc > UINTPTR_MAX - static_cast<uintptr_t>(delta)) return false;
        *target = pc + static_cast<uintptr_t>(delta);
    }
    return true;
}

// Only PC-relative external edges, PP slot indices, and static-table indices
// are normalized. Scalar-double PP loads use the same relocation rules as
// pointer loads; their opcode, width, destination and PP-derived base stay exact.
// Prefixes retain local branches within their complete function scope; every
// external branch is checked through the closed dependency graph below.
// Object/Thread fields and other instructions stay exact. Slots retain identity.
// Cross-function selection slots are checked below and linked to live PP in ASM.
bool SlotOrdinal(uintptr_t slots[kMaxSlots], size_t* count,
                 uintptr_t slot, uint32_t* ordinal) {
    for (size_t i = 0; i < *count; ++i) {
        if (slots[i] == slot) { *ordinal = static_cast<uint32_t>(i + 1); return true; }
    }
    if (*count == kMaxSlots) return false;
    slots[(*count)++] = slot;
    *ordinal = static_cast<uint32_t>(*count);
    return true;
}

uint32_t BranchMask(uint32_t word) {
    if ((word & 0x7c000000u) == 0x14000000u) return 0x03ffffffu;
    if ((word & 0xff000010u) == 0x54000000u ||
        (word & 0x7e000000u) == 0x34000000u) return 0x00ffffe0u;
    if ((word & 0x7e000000u) == 0x36000000u) return 0x0007ffe0u;
    return 0;
}

bool Match(const View& view, uintptr_t base, const Body& body) {
    if (body.count == 0 || body.count > kMaxWords || body.scope_count < body.count ||
        !LoadContains(view, base, body.scope_count * 4u)) return false;
    uint8_t digest[32];
    uint32_t words[kMaxWords];
    memcpy(words, reinterpret_cast<const void*>(base), body.count * 4u);
    uintptr_t pools[kMaxSlots]{}, statics[kMaxSlots]{};
    size_t pool_count = 0, static_count = 0, external_edges = 0;
    for (size_t i = 0; i < body.count; ++i) {
        const uintptr_t pc = base + i * 4u;
        const uint32_t w = Word(pc);  // original, not a previously canonicalized word
        uintptr_t target = 0; bool link = false;
        if (BranchMask(w) != 0) {
            if (!Branch(pc, w, &target, &link)) return false;
            if (target < base || target >= base + body.scope_count * 4u) {
                if (!LoadContains(view, target, 4)) return false;
                bool declared = false;
                for (size_t j = 0; j < body.edge_count; ++j)
                    declared |= kEdges[body.edge_begin + j].index == i;
                if (!declared) return false;
                ++external_edges;
                words[i] = w & ~BranchMask(w);
            }
        }
        uint32_t ordinal = 0;
        const bool pointer_load = (w & 0xffc003e0u) == 0xf9400360u;
        const bool double_load = (w & 0xffc003e0u) == 0xfd400360u;
        if ((pointer_load || double_load) && ((w >> 10u) & 4095u) * 8u >= 256u) {
            if (!SlotOrdinal(pools, &pool_count, ((w >> 10u) & 4095u) * 8u, &ordinal)) return false;
            words[i] = (w & ~0x003ffc00u) | (ordinal << 10u);
        }
        if (i + 1 == body.count) continue;
        const uint32_t next = Word(pc + 4), reg = w & 31u;
        const bool pointer_pair = (next & 0xffc003e0u) == (0xf9400000u | (reg << 5u));
        const bool double_pair = (next & 0xffc003e0u) == (0xfd400000u | (reg << 5u));
        if ((w & 0xffc003e0u) == 0x91400360u && (pointer_pair || double_pair)) {
            const uintptr_t slot = ((w >> 10u) & 4095u) * 4096u + ((next >> 10u) & 4095u) * 8u;
            if (slot < 256 || slot > 0x100000 ||
                !SlotOrdinal(pools, &pool_count, slot, &ordinal)) return false;
            words[i] = w & ~0x003ffc00u;
            words[i + 1] = (next & ~0x003ffc00u) | (ordinal << 10u);
        }
        if ((w & ~31u) == 0xf9403f40u &&
            (next & 0xffc003ffu) == (0xf9400000u | (reg << 5u) | reg)) {
            if (!SlotOrdinal(statics, &static_count, (next >> 10u) & 4095u, &ordinal)) return false;
            words[i + 1] = (next & ~0x003ffc00u) | (ordinal << 10u);
        }
    }
    if (external_edges != body.edge_count) return false;
    MonetSha256(words, body.count * 4u, digest);
    return memcmp(digest, body.digest, 32) == 0;
}

bool Has(const Choices& choices, uintptr_t address) {
    for (size_t i = 0; i < choices.count; ++i)
        if (choices.values[i].address == address) return true;
    return false;
}

bool CallsMatch(const View& view, uintptr_t base, const Body& body,
                const Choices choices[kPatternCount]) {
    struct Node { uintptr_t address; const Body* body; };
    Node pending[kMaxGraphNodes];
    pending[0] = {base, &body};
    size_t count = 1;
    // Authenticate every prefix edge, including cycles, without making graph
    // depth consume the native stack. Each visited address/body pair is proven.
    for (size_t cursor = 0; cursor < count; ++cursor) {
        const auto node = pending[cursor];
        if (!Match(view, node.address, *node.body)) return false;
        for (size_t i = 0; i < node.body->edge_count; ++i) {
            const auto& edge = kEdges[node.body->edge_begin + i];
            const uintptr_t pc = node.address + edge.index * 4u;
            uintptr_t target = 0; bool link = false;
            if (!Branch(pc, Word(pc), &target, &link) || !LoadContains(view, target, 4)) return false;
            if (edge.role >= 0) {
                if (!Has(choices[edge.role], target)) return false;
            } else {
                const auto* callee = &kBodies[edge.body];
                bool visited = false;
                for (size_t j = 0; j < count; ++j)
                    if (pending[j].address == target && pending[j].body == callee) {
                        visited = true;
                        break;
                    }
                if (!visited) {
                    if (count == kMaxGraphNodes) return false;
                    pending[count++] = {target, callee};
                }
            }
        }
    }
    return true;
}

bool Resolve(const View& view, uintptr_t resolved[kPatternCount],
             const Pattern* selected[kPatternCount]) {
    Choices choices[kPatternCount]{};
    // Collect every role in one segment walk; full validation and graph pruning
    // still decide acceptance, including candidates after the first full match.
    for (size_t s = 0; s < view.count; ++s) {
        const auto& load = view.loads[s];
        if ((load.flags & 5u) != 5u || load.size < 16) continue;
        for (size_t off = 0; off <= load.size - 16; off += 4) {
            const uintptr_t candidate = load.begin + off;
            if (!kEntryFilter.MayMatch(candidate)) continue;
            for (const auto& pattern : kPatterns) {
                const auto& body = kBodies[pattern.body];
                if (body.count * 4u > load.size - off ||
                    memcmp(reinterpret_cast<const void*>(candidate), pattern.prefix, sizeof(pattern.prefix)) != 0 ||
                    !Match(view, candidate, body)) continue;
                auto& list = choices[pattern.role];
                if (list.count == kMaxCandidates) return false;
                bool known_address = false;
                for (size_t i = 0; i < list.count; ++i)
                    known_address |= list.values[i].address == candidate;
                if (!known_address && ++list.addresses > kMaxCandidateAddresses) return false;
                list.values[list.count++] = {candidate, &pattern};
            }
        }
    }
    // Distinct body contracts can describe the same entry after relocation.
    // Resolve their actual dependency edges before requiring a unique address.
    bool converged = false;
    for (size_t round = 0; round < kPatternCount * kMaxCandidates; ++round) {
        bool changed = false;
        for (size_t role = 0; role < kPatternCount; ++role) {
            auto& list = choices[role]; size_t kept = 0;
            if (!list.count && OptionalRole(role)) continue;
            for (size_t i = 0; i < list.count; ++i) {
                const auto& candidate = list.values[i];
                if (CallsMatch(view, candidate.address, kBodies[candidate.pattern->body], choices))
                    list.values[kept++] = candidate;
            }
            if (kept == 0 && !OptionalRole(role)) return false;
            changed |= kept != list.count; list.count = kept;
        }
        for (size_t role = 0; role < kPatternCount; ++role) {
            if (choices[role].count != 1) continue;
            const auto& candidate = choices[role].values[0];
            const auto& body = kBodies[candidate.pattern->body];
            for (size_t i = 0; i < body.edge_count; ++i) {
                const auto& edge = kEdges[body.edge_begin + i];
                if (edge.role < 0) continue;
                const uintptr_t pc = candidate.address + edge.index * 4u;
                uintptr_t target = 0; bool link = false;
                if (!Branch(pc, Word(pc), &target, &link)) return false;
                auto& callee = choices[edge.role]; size_t kept = 0;
                for (size_t j = 0; j < callee.count; ++j)
                    if (callee.values[j].address == target) callee.values[kept++] = callee.values[j];
                if (kept == 0) return false;
                changed |= kept != callee.count; callee.count = kept;
            }
        }
        if (!changed) { converged = true; break; }
    }
    if (!converged) return false;
    for (size_t role = 0; role < kPatternCount; ++role) {
        const auto& list = choices[role];
        if (!list.count && OptionalRole(role)) continue;
        for (size_t i = 1; i < list.count; ++i)
            if (list.values[i].address != list.values[0].address) return false;
        resolved[role] = list.values[0].address;
        selected[role] = list.values[0].pattern;
    }
    return true;
}

bool CheckIncoming(const View& view, const uintptr_t r[kPatternCount],
                   const Pattern* selected[kPatternCount]) {
    // A partially matched optional family must not hide a damaged caller.
    // Each reviewed graph has either the legacy query closure, or both the
    // cache query and preview update. None/mixed is an unknown contract.
    const bool legacy = r[kIconQueryCallback] && !r[kCacheQuery] && !r[kPreviewUpdate];
    const bool cache = !r[kIconQueryCallback] && r[kCacheQuery] && r[kPreviewUpdate];
    if (!legacy && !cache) return false;
    size_t seen[4]{};
    size_t expected_count[4]{};
    for (size_t caller = 0; caller < kPatternCount; ++caller) {
        if (!selected[caller]) continue;
        const auto& body = kBodies[selected[caller]->body];
        for (size_t j = 0; j < body.edge_count; ++j) {
            const auto& edge = kEdges[body.edge_begin + j];
            if (edge.role >= 0 && edge.role < 4) ++expected_count[edge.role];
        }
    }
    for (size_t s = 0; s < view.count; ++s) {
        const auto& load = view.loads[s];
        if ((load.flags & 5u) != 5u) continue;
        for (size_t off = 0; off + 4 <= load.size; off += 4) {
            const uintptr_t pc = load.begin + off;
            uintptr_t target = 0; bool link = false;
            if (!Branch(pc, Word(pc), &target, &link)) continue;
            if (target > r[kHas] && target < r[kHas] + 16) return false;
            for (size_t root = 0; root < 4; ++root) {
                if (target != r[root]) continue;
                bool expected = false;
                for (size_t caller = 0; caller < kPatternCount; ++caller) {
                    if (!selected[caller]) continue;
                    const auto& body = kBodies[selected[caller]->body];
                    for (size_t j = 0; j < body.edge_count; ++j) {
                        const auto& site = kEdges[body.edge_begin + j];
                        if (site.role == static_cast<int>(root) &&
                            pc == r[caller] + site.index * 4u && link) expected = true;
                    }
                }
                if (!expected) return false;
                ++seen[root];
            }
        }
    }
    if (expected_count[kHas] != 2 || expected_count[kUpdate] != 2 ||
        expected_count[kResource] != (cache ? 1u : 2u) ||
        expected_count[kShould] != (cache ? 3u : 2u)) return false;
    for (size_t root = 0; root < 4; ++root)
        if (seen[root] != expected_count[root]) return false;
    return true;
}

bool PoolPair(uintptr_t pc, uintptr_t* offset) {
    const uint32_t add = Word(pc), ldr = Word(pc + 4);
    const uint32_t reg = add & 31u;
    if ((add & 0xff000000u) != 0x91000000u || ((add >> 5u) & 31u) != 27u ||
        (ldr & 0xffc00000u) != 0xf9400000u ||
        ((ldr >> 5u) & 31u) != reg || (ldr & 31u) != reg) return false;
    *offset = static_cast<uintptr_t>((add >> 10u) & 0xfffu) << ((add & (1u << 22u)) ? 12u : 0u);
    *offset += ((ldr >> 10u) & 0xfffu) * 8u;
    return *offset >= 8 && *offset <= 0x100000 && (*offset & 7u) == 0;
}
}  // namespace

bool ResolveMonetSelection(const MonetSelectionImageView& image,
                           MonetSelectionProfile* out, const char** rejection) {
    if (rejection != nullptr) *rejection = nullptr;
    if (out == nullptr) return Fail(rejection, "null selection output");
    *out = {};
    View view{};
    if (!Parse(image, &view)) return Fail(rejection, "selection ELF/readable range validation failed");
    uintptr_t r[kPatternCount]{};
    const Pattern* selected[kPatternCount]{};
    if (!Resolve(view, r, selected)) return Fail(rejection, "missing/ambiguous selection instruction/call graph");
    if (!CheckIncoming(view, r, selected)) return Fail(rejection, "selection direct callers/patch interior changed");
    uintptr_t factory, callback = 0, callback_load = 0;
    const auto& init_body = kBodies[selected[kInit]->body];
    for (size_t i = 2; i < init_body.count; ++i) {
        const uintptr_t pc = r[kInit] + i*4;
        uintptr_t target = 0; bool link = false;
        if (!Branch(pc, Word(pc), &target, &link) || !link || target != r[kClosureAllocateFast]) continue;
        if (callback_load || !PoolPair(pc-8, &callback))
            return Fail(rejection, "ambiguous selection callback pool producer");
        callback_load = pc-8;
    }
    uintptr_t type_should, type_update, type_init, initializer;
    if (!PoolPair(r[kHas] + 23 * 4, &factory) ||
        !callback_load ||
        !PoolPair(r[kShould] + 11 * 4, &type_should) ||
        !PoolPair(r[kUpdate] + 11 * 4, &type_update) ||
        !PoolPair(r[kInit] + 19 * 4, &type_init) ||
        !PoolPair(r[kHas] + 15 * 4, &initializer) ||
        type_should != type_update || type_should != type_init ||
        factory == callback || factory == type_should || factory == initializer ||
        callback == type_should || callback == initializer || initializer == type_should)
        return Fail(rejection, "selection pool-slot relationships changed");
    // These words are also covered by their complete templates. Keeping this
    // explicit documents why the original tail may clobber x16, including the
    // first callee reached before the has-body sentinel load.
    if (Word(r[kHas] + 12 * 4) != 0xf9402370u ||
        Word(r[kContextAllocateFast] + 5 * 4) != 0xf9403750u)
        return Fail(rejection, "x16 first-use overwrite proof changed");
    MonetSelectionProfile p{};
    p.load_bias = image.load_bias;
    p.entry = reinterpret_cast<void*>(r[kHas]); p.resume = r[kHas] + 16;
    p.resource_entry = r[kResource]; p.resource_probe_entry = r[kResourceProbe];
    p.update_entry = r[kUpdate]; p.update_callback_entry = r[kUpdateCallback];
    p.should_entry = r[kShould]; p.string_equals_entry = r[kOneByteEquals];
    p.string_allocator_entry = r[kOneByteAllocateFast];
    p.factory_pool_offset = factory; p.update_callback_pool_offset = callback;
    p.controller_type_pool_offset = type_should;
    p.update_callback_pool_load = callback_load;
    memcpy(p.displaced, reinterpret_cast<const void*>(r[kHas]), sizeof(p.displaced));
    *out = p;
    return true;
}

bool ResolveMonetSelection(const uint8_t* imageBase, size_t mappedSize,
                           MonetSelectionProfile* out) {
    const auto base = reinterpret_cast<uintptr_t>(imageBase);
    const MonetSelectionImageRange range{base, mappedSize, true};
    return ResolveMonetSelection({base, &range, 1}, out);
}

bool PublishMonetSelectionProfile(const MonetSelectionProfile& p,
                                  const uint8_t verified_file_sha256[32]) {
    // Main hashes the mapped backing ELF before resolving/installing. A digest
    // binds this publication, not a list of supported launcher builds. Retain
    // code/offset metadata only, never PP, a heap address or a Dart object.
    static MonetSelectionProfile published{};
    static uint8_t published_digest[32]{};
    uint8_t digest_bits = 0;
    if (verified_file_sha256 != nullptr)
        for (size_t i = 0; i < 32; ++i) digest_bits |= verified_file_sha256[i];
    constexpr uint32_t displaced[] = {0xa9bf79fdu,0xaa0f03fdu,0xd10061efu,0xf81f83a1u};
    const uintptr_t entry = reinterpret_cast<uintptr_t>(p.entry);
    if (digest_bits == 0 || p.load_bias == 0 || entry < p.load_bias ||
        entry - p.load_bias >= kMaxImageSpan || (entry & 3u) ||
        entry > UINTPTR_MAX - 16 || p.resume != entry + 16 ||
        p.entry == nullptr || p.resource_probe_entry == 0 || p.update_callback_entry == 0 ||
        (p.resource_probe_entry & 3u) || (p.update_callback_entry & 3u) ||
        p.factory_pool_offset == 0 || p.factory_pool_offset > 0x100000 ||
        p.update_callback_pool_offset == 0 || p.update_callback_pool_offset > 0x100000 ||
        (p.factory_pool_offset & 7u) || (p.update_callback_pool_offset & 7u) ||
        p.controller_type_pool_offset == 0 || p.controller_type_pool_offset > 0x100000 ||
        (p.controller_type_pool_offset & 7u) ||
        p.factory_pool_offset == p.update_callback_pool_offset ||
        p.factory_pool_offset == p.controller_type_pool_offset ||
        p.update_callback_pool_offset == p.controller_type_pool_offset ||
        memcmp(p.displaced, displaced, sizeof(displaced)) != 0) return false;
    const uintptr_t code[] = {p.resource_entry, p.resource_probe_entry, p.update_entry,
        p.update_callback_entry, p.should_entry, p.string_equals_entry, p.string_allocator_entry,
        p.update_callback_pool_load};
    for (uintptr_t address : code)
        if ((address & 3u) || address < p.load_bias || address - p.load_bias >= kMaxImageSpan) return false;
    uintptr_t actual_factory = 0, actual_callback = 0;
    if (!PoolPair(entry + 23*4, &actual_factory) || actual_factory != p.factory_pool_offset ||
        !PoolPair(p.update_callback_pool_load, &actual_callback) || actual_callback != p.update_callback_pool_offset)
        return false;
    uint32_t expected = 0;
    if (!__atomic_compare_exchange_n(&kh_selection_profile_ready, &expected, 2, false,
                                     __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
        return expected == 1 && memcmp(published_digest, verified_file_sha256, 32) == 0 &&
            published.load_bias == p.load_bias && published.entry == p.entry &&
            published.resume == p.resume && published.resource_entry == p.resource_entry &&
            published.update_entry == p.update_entry && published.should_entry == p.should_entry &&
            published.string_equals_entry == p.string_equals_entry &&
            published.string_allocator_entry == p.string_allocator_entry &&
            published.controller_type_pool_offset == p.controller_type_pool_offset &&
            published.update_callback_pool_load == p.update_callback_pool_load &&
            kh_selection_factory_entry == p.resource_probe_entry &&
            kh_selection_update_callback_entry == p.update_callback_entry &&
            kh_selection_factory_pool_offset == p.factory_pool_offset &&
            kh_selection_update_callback_pool_offset == p.update_callback_pool_offset;
    }
    published = p;
    memcpy(published_digest, verified_file_sha256, 32);
    kh_selection_factory_entry = p.resource_probe_entry;
    kh_selection_update_callback_entry = p.update_callback_entry;
    kh_selection_factory_pool_offset = p.factory_pool_offset;
    kh_selection_update_callback_pool_offset = p.update_callback_pool_offset;
    __atomic_store_n(&kh_selection_profile_ready, uint32_t{1}, __ATOMIC_RELEASE);
    return true;
}

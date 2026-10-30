// SPDX-License-Identifier: Apache-2.0
// Structural scanning and bounded ARM64 branch decoding follow the approach of
// MiuiBackGestureHook/miui-home-hyos-native/runtime_profile_resolver.cpp
// (MiuiBackGestureHook contributors, Apache-2.0). This Dart graph parser is new.
#include "monochrome_gate_resolver.h"

#include <string.h>

namespace {

constexpr size_t kMaxRanges = 32;
constexpr size_t kMaxImageSpan = 128u * 1024u * 1024u;
constexpr size_t kMaxFunctionBytes = 2048;
constexpr uint32_t kEnterFrame = 0xa9bf79fd;
constexpr uint32_t kSetFrame = 0xaa0f03fd;
constexpr uint32_t kRestoreStack = 0xaa1d03ef;
constexpr uint32_t kLeaveFrame = 0xa8c179fd;
constexpr uint32_t kRet = 0xd65f03c0;
constexpr uint32_t kTrue = 0x20;   // Dart null-relative bool immediates, not RVAs.
constexpr uint32_t kFalse = 0x30;

bool Contains(const MonochromeGateImageView& image, uintptr_t offset, size_t size) {
    if (offset > image.size || size > image.size - offset) return false;
    for (size_t i = 0; i < image.executable_range_count; ++i) {
        const auto& range = image.executable_ranges[i];
        if (offset >= range.begin && offset <= range.end && size <= range.end - offset) {
            return true;
        }
    }
    return false;
}

bool Read(const MonochromeGateImageView& image, uintptr_t pc, uint32_t* out) {
    if ((pc & 3u) != 0 || !Contains(image, pc, sizeof(*out))) return false;
    memcpy(out, image.base + pc, sizeof(*out));
    return true;
}

// Sign extension is arithmetic rather than an implementation-defined signed
// shift. All offsets stay within the validated, small mapped image span.
bool BranchTarget(const MonochromeGateImageView& image, uintptr_t pc,
                  uint32_t immediate, unsigned bits, uintptr_t* out) {
    int64_t delta = immediate;
    if (immediate & (uint32_t{1} << (bits - 1))) delta -= int64_t{1} << bits;
    const int64_t target = static_cast<int64_t>(pc) + delta * 4;
    if (target < 0 || !Contains(image, static_cast<uintptr_t>(target), 4)) return false;
    *out = static_cast<uintptr_t>(target);
    return true;
}

struct Cursor {
    const MonochromeGateImageView& image;
    uintptr_t pc;
    uintptr_t end;

    Cursor(const MonochromeGateImageView& view, uintptr_t start)
        : image(view), pc(start), end(start + kMaxFunctionBytes) {}

    bool word(uint32_t* out) {
        if (pc >= end || !Read(image, pc, out)) return false;
        pc += 4;
        return true;
    }
    bool exact(uint32_t expected) {
        uint32_t value = 0;
        return word(&value) && value == expected;
    }
    bool masked(uint32_t mask, uint32_t expected, uint32_t* out) {
        return word(out) && (*out & mask) == expected;
    }
    bool direct(bool link, uintptr_t* target) {
        const uintptr_t at = pc;
        uint32_t w = 0;
        return masked(0xfc000000u, link ? 0x94000000u : 0x14000000u, &w) &&
                BranchTarget(image, at, w & 0x03ffffffu, 26, target);
    }
    bool call(uintptr_t expected) {
        uintptr_t target = 0;
        return direct(true, &target) && target == expected;
    }
    bool conditional(uint32_t condition, uintptr_t* target) {
        const uintptr_t at = pc;
        uint32_t w = 0;
        return masked(0xff00001fu, 0x54000000u | condition, &w) &&
                BranchTarget(image, at, (w >> 5) & 0x7ffffu, 19, target);
    }
    bool testBit(unsigned reg, unsigned bit, bool nonzero, uintptr_t* target) {
        const uintptr_t at = pc;
        uint32_t w = 0;
        return masked(0xfff8001fu,
                      (nonzero ? 0x37000000u : 0x36000000u) | (bit << 19) | reg, &w) &&
                BranchTarget(image, at, (w >> 5) & 0x3fffu, 14, target);
    }
    bool immediate(uint32_t opcode, unsigned dst, unsigned src, uint32_t* imm) {
        uint32_t w = 0;
        if (!masked(0xffc003ffu, opcode | (src << 5) | dst, &w)) return false;
        *imm = (w >> 10) & 0xfffu;
        return true;
    }
    bool ldr(unsigned dst, unsigned src, uint32_t* offset) {
        if (!immediate(0xf9400000u, dst, src, offset)) return false;
        *offset *= 8;
        return true;
    }
    bool pool(unsigned reg, uint32_t* offset) {
        uint32_t w = 0;
        if (!Read(image, pc, &w)) return false;
        if ((w & 0xffc003ffu) == (0xf9400360u | reg)) return ldr(reg, 27, offset);
        if (!masked(0xff8003ffu, 0x91000360u | reg, &w)) return false;
        const uint32_t high = ((w >> 10) & 0xfffu) << ((w & (1u << 22)) ? 12 : 0);
        uint32_t low = 0;
        if (!ldr(reg, reg, &low)) return false;
        *offset = high + low;
        return true;
    }
    bool unscaled(uint32_t opcode, unsigned reg, unsigned base, int32_t* offset) {
        uint32_t w = 0;
        if (!masked(0xffe00fffu, opcode | (base << 5) | reg, &w)) return false;
        const uint32_t imm = (w >> 12) & 0x1ffu;
        *offset = static_cast<int32_t>(imm) - ((imm & 0x100u) ? 512 : 0);
        return true;
    }
    bool stack(bool load, unsigned reg, int32_t slot) {
        int32_t actual = 0;
        return unscaled(load ? 0xf8400000u : 0xf8000000u, reg, 29, &actual) && actual == slot;
    }
    bool boolean(unsigned reg, uint32_t value) {
        uint32_t actual = 0;
        return immediate(0x91000000u, reg, 22, &actual) && actual == value;
    }
};

bool Frame(Cursor& c, uint32_t* size) {
    return c.exact(kEnterFrame) && c.exact(kSetFrame) &&
            c.immediate(0xd1000000u, 15, 15, size) &&
            *size >= 16 && *size <= 256 && (*size & 7u) == 0 &&
            c.stack(false, 22, -8);
}

bool Slot(int32_t slot, uint32_t frame) {
    return slot <= -16 && slot >= -static_cast<int32_t>(frame) && (slot % 8) == 0;
}

bool InitAsync(const MonochromeGateImageView& image, uintptr_t entry) {
    Cursor c(image, entry);
    uint32_t ignored = 0;
    return c.exact(kEnterFrame) && c.exact(kSetFrame) && c.pool(4, &ignored) &&
            c.exact(0xf81f8de0) && c.ldr(30, 26, &ignored) && c.exact(0xd63f03c0) &&
            c.exact(kRestoreStack) && c.exact(kLeaveFrame) &&
            c.stack(false, 0, -8) && c.exact(kRet);
}

bool ReturnAsync(const MonochromeGateImageView& image, uintptr_t entry) {
    Cursor c(image, entry);
    uint32_t ignored = 0;
    return c.stack(true, 2, -8) && c.exact(kRestoreStack) && c.exact(kLeaveFrame) &&
            c.exact(kEnterFrame) && c.exact(kSetFrame) && c.exact(0xa9bf09e0) &&
            c.ldr(30, 26, &ignored) && c.exact(0xd63f03c0) &&
            c.exact(kRestoreStack) && c.exact(kLeaveFrame) && c.exact(kRet);
}

bool Await(const MonochromeGateImageView& image, uintptr_t entry) {
    Cursor c(image, entry);
    if (!c.stack(true, 3, -8) || !c.exact(0xaa1d03e2) || !c.exact(0xcb0f0042) ||
            !c.exact(kEnterFrame) || !c.exact(kSetFrame)) return false;
    // The suspension stub dispatches through Thread and unwinds both frames.
    // Locate its bounded epilogue; its allocation/layout details are not used.
    for (uintptr_t at = c.pc; at < entry + 256; at += 4) {
        Cursor tail(image, at);
        uint32_t ignored = 0;
        if (tail.ldr(30, 26, &ignored) && tail.exact(0xd63f03c0) &&
                tail.exact(kRestoreStack) && tail.exact(kLeaveFrame) &&
                tail.exact(kRestoreStack) && tail.exact(kLeaveFrame) && tail.exact(kRet)) {
            return true;
        }
    }
    return false;
}

struct StaticLoad {
    uint32_t table = 0, slot = 0, sentinel = 0, field = 0;
    uintptr_t initialize = 0;
    bool operator==(const StaticLoad& b) const {
        return table == b.table && slot == b.slot && sentinel == b.sentinel &&
                field == b.field && initialize == b.initialize;
    }
};

bool LoadStatic(Cursor& c, StaticLoad* out) {
    uintptr_t ready = 0;
    return c.ldr(0, 26, &out->table) && c.ldr(0, 0, &out->slot) &&
            c.pool(16, &out->sentinel) && c.exact(0x6b10001f) &&
            c.conditional(1, &ready) && c.pool(2, &out->field) &&
            c.direct(true, &out->initialize) && ready == c.pc;
}

bool PropertyGetter(const MonochromeGateImageView& image, uintptr_t entry,
                    const StaticLoad& log) {
    Cursor c(image, entry);
    StaticLoad api;
    uint32_t frame = 0, ignored = 0;
    int32_t field = 0;
    uintptr_t absent = 0, call = 0;
    // SystemProperties.getInt forwards the saved key and a zero default to
    // its initialized API, or throws on an absent API. Validate this producer
    // as well as the consumer's await + integer unboxing + SDK >= 33 check.
    return c.exact(kEnterFrame) && c.exact(kSetFrame) &&
            c.immediate(0xd1000000u, 15, 15, &frame) && frame == 8 &&
            c.exact(0xaa0103e3) && c.stack(false, 1, -8) && LoadStatic(c, &api) &&
            api.initialize == log.initialize && api.table == log.table &&
            api.sentinel == log.sentinel && api.slot != log.slot &&
            c.unscaled(0xb8400000u, 1, 0, &field) && field >= 7 && (field & 3) == 3 &&
            c.exact(0x8b1c8021) && c.exact(0x6b16003f) && c.conditional(0, &absent) &&
            c.unscaled(0xb8400000u, 0, 1, &field) && field >= 7 && (field & 3) == 3 &&
            c.exact(0x8b1c8000) && c.exact(0xaa0003e1) && c.stack(true, 3, -8) &&
            c.exact(0xd2800002) && c.direct(true, &call) && c.exact(kRestoreStack) &&
            c.exact(kLeaveFrame) && c.exact(kRet) && c.pc == absent &&
            c.direct(true, &call) && c.exact(0xaa0003e1) && c.pool(0, &ignored) &&
            c.unscaled(0xb8000000u, 0, 1, &field) && c.exact(0xaa0103e0) &&
            c.direct(true, &call) && c.exact(0xd4200000);
}

bool EnsureInitialized(const MonochromeGateImageView& image, uintptr_t entry) {
    Cursor c(image, entry);
    int32_t field = 0;
    uint32_t ignored = 0;
    uintptr_t fail = 0, call = 0;
    return c.exact(kEnterFrame) && c.exact(kSetFrame) &&
            c.unscaled(0xb8400000u, 0, 1, &field) && field >= 7 && (field & 3) == 3 &&
            c.exact(0x8b1c8000) && c.testBit(0, 4, true, &fail) &&
            c.exact(0xaa1603e0) && c.exact(kRestoreStack) && c.exact(kLeaveFrame) &&
            c.exact(kRet) && c.pc == fail && c.direct(true, &call) &&
            c.exact(0xaa0003e1) && c.pool(0, &ignored) &&
            c.unscaled(0xb8000000u, 0, 1, &field) &&
            c.exact(0xaa0103e0) && c.direct(true, &call) && c.exact(0xd4200000);
}

struct Graph {
    uintptr_t entry = 0, end = 0, init = 0, await = 0, complete = 0;
    uintptr_t closure = 0, logger = 0, gate = 0, supported = 0;
    uint32_t bool_type = 0, log_args = 0, frame = 0;
    int32_t context = 0, temp = 0;
    StaticLoad log;
};

// The three ordinary exits log through the same singleton and callees, then
// tail-call the same ReturnAsync stub with a canonical Dart bool. Pool indices,
// field-table indices, frame slots and call/branch displacements are decoded.
bool LogReturn(Cursor& c, Graph* g, bool context, uint32_t value, bool first,
               int32_t temp) {
    StaticLoad log;
    uintptr_t closure = 0, logger = 0, complete = 0;
    uint32_t message = 0, args = 0;
    if (!LoadStatic(c, &log) || (context && !c.stack(true, 2, g->context)) ||
            !c.pool(1, &message) || (!context && !c.exact(0xaa1603e2)) ||
            !c.stack(false, 0, temp) || !c.direct(true, &closure) ||
            !c.stack(true, 1, temp) || !c.exact(0xaa0003e2) || !c.pool(4, &args) ||
            !c.direct(true, &logger) || !c.boolean(0, value) || !c.direct(false, &complete)) {
        return false;
    }
    if (first) {
        g->log = log;
        g->closure = closure;
        g->logger = logger;
        g->complete = complete;
        g->log_args = args;
        return ReturnAsync(c.image, complete);
    }
    return log == g->log && closure == g->closure && logger == g->logger &&
            complete == g->complete && args == g->log_args;
}

bool CatchReturn(Cursor& c, const Graph& g) {
    uint32_t frame = 0, ignored = 0, args = 0;
    int32_t field = 0;
    uintptr_t call = 0, error_log = 0, complete = 0;
    StaticLoad log;
    return c.immediate(0xd1000000u, 15, 29, &frame) && frame == g.frame &&
            c.stack(false, 0, g.context) && LoadStatic(c, &log) && log == g.log &&
            c.exact(0xaa1603e1) && c.exact(0xd28000c2) && c.stack(false, 0, g.temp) &&
            c.direct(true, &call) && c.pool(16, &ignored) &&
            c.unscaled(0xb8000000u, 16, 0, &field) && field == 15 &&
            c.pool(16, &ignored) && c.unscaled(0xb8000000u, 16, 0, &field) && field == 19 &&
            c.stack(true, 1, g.context) &&
            c.unscaled(0xb8000000u, 1, 0, &field) && field == 23 &&
            c.exact(0xf90001e0) && c.direct(true, &call) &&
            c.stack(true, 1, g.temp) && c.exact(0xaa0003e2) && c.pool(4, &args) &&
            args == g.log_args && c.direct(true, &error_log) && error_log != g.logger &&
            c.boolean(0, kFalse) && c.direct(false, &complete) && complete == g.complete;
}

bool Candidate(const MonochromeGateImageView& image, uintptr_t start, Graph* g) {
    Cursor c(image, start);
    g->entry = start;
    uint32_t ignored = 0;
    uintptr_t call = 0, property = 0, barrier_skip = 0, compare = 0, device = 0;
    int32_t first_field = 0, second_field = 0;
    if (!Frame(c, &g->frame) || !c.pool(0, &g->bool_type) || !c.direct(true, &g->init) ||
            !InitAsync(image, g->init) || !c.exact(0xd2800041) || !c.direct(true, &call) ||
            !c.pool(1, &ignored) || !c.unscaled(0xf8000000u, 0, 29, &g->context) ||
            !Slot(g->context, g->frame) || !c.direct(true, &property) ||
            !c.exact(0xaa0003e1) || !c.unscaled(0xf8000000u, 1, 29, &g->temp) ||
            !Slot(g->temp, g->frame) || g->temp == g->context ||
            !c.direct(true, &g->await) || !Await(image, g->await) ||
            !c.exact(0xaa0003e1) || !c.stack(true, 2, g->context) ||
            !c.unscaled(0xb8000000u, 0, 2, &first_field) || first_field < 7 ||
            (first_field & 3) != 3 || !c.testBit(0, 0, false, &barrier_skip) ||
            !c.exact(0x385ff050) || !c.exact(0x385ff011) || !c.exact(0x8a500a30) ||
            !c.exact(0xea5c821f) || !c.conditional(0, &compare) || compare != barrier_skip ||
            !c.direct(true, &call) || c.pc != barrier_skip || !c.exact(0x93417c20) ||
            !c.testBit(1, 0, false, &compare) || !c.exact(0xf8407020) || c.pc != compare ||
            !c.exact(0xf100841f) || !c.conditional(10, &device) ||
            !LogReturn(c, g, true, kFalse, true, g->temp) || c.pc != device ||
            !PropertyGetter(image, property, g->log)) return false;

    // SDK >= 33 reaches an initialized device manager. The gate tests its
    // decompressed bool, after saving it in the async closure's next field.
    StaticLoad manager;
    int32_t bool_field = 0;
    if (!LoadStatic(c, &manager) || manager.initialize != g->log.initialize ||
            manager.table != g->log.table || manager.sentinel != g->log.sentinel ||
            manager.slot == g->log.slot || !c.exact(0xaa0003e1) ||
            !c.stack(false, 0, g->temp) || !c.direct(true, &call) ||
            !EnsureInitialized(image, call) || !c.stack(true, 0, g->temp) ||
            !c.unscaled(0xb8400000u, 1, 0, &bool_field) || bool_field < 7 ||
            (bool_field & 3) != 3 || !c.exact(0x8b1c8021) ||
            !c.stack(true, 2, g->context) ||
            !c.unscaled(0xb8000000u, 1, 2, &second_field) || second_field != first_field + 4) {
        return false;
    }
    g->gate = c.pc;
    if (!c.testBit(1, 4, false, &g->supported) ||
            !LogReturn(c, g, false, kFalse, false, g->temp) || c.pc != g->supported ||
            !LogReturn(c, g, true, kTrue, false, g->temp) || !CatchReturn(c, *g)) return false;
    g->end = c.pc;
    // All local control flow above lands on parsed basic-block boundaries;
    // in particular the replacement branch never leaves this function.
    return g->gate > g->entry && g->supported > g->gate && g->supported < g->end &&
            g->init != g->await && g->await != g->complete &&
            !(g->complete >= g->entry && g->complete < g->end);
}

bool Caller(const MonochromeGateImageView& image, uintptr_t start, uintptr_t call,
            const Graph& target) {
    Cursor c(image, start);
    Graph g = target;  // Only native addresses/decoded integers, never Dart objects.
    uint32_t frame = 0, type = 0;
    int32_t argument = 0, temporary = 0;
    uintptr_t yes = 0;
    return Frame(c, &frame) && c.unscaled(0xf8000000u, 1, 29, &argument) && Slot(argument, frame) &&
            c.pool(0, &type) && type == g.bool_type && c.call(g.init) && c.pc == call &&
            c.call(g.entry) && c.exact(0xaa0003e1) &&
            c.unscaled(0xf8000000u, 1, 29, &temporary) && Slot(temporary, frame) &&
            argument != temporary && c.call(g.await) && c.boolean(16, kTrue) &&
            c.exact(0x6b10001f) && c.conditional(0, &yes) &&
            LogReturn(c, &g, false, kFalse, false, argument) && c.pc == yes &&
            LogReturn(c, &g, false, kTrue, false, argument) &&
            // No cross-function branch into the gate's prologue or body.
            (c.pc <= target.entry || start >= target.end);
}

bool HasUniqueCaller(const MonochromeGateImageView& image, const Graph& g) {
    size_t matches = 0;
    for (size_t i = 0; i < image.executable_range_count; ++i) {
        const auto& range = image.executable_ranges[i];
        for (uintptr_t at = range.begin; at < range.end; at += 4) {
            uint32_t w = 0;
            uintptr_t target = 0;
            if (!Read(image, at, &w) || (w & 0xfc000000u) != 0x94000000u ||
                    !BranchTarget(image, at, w & 0x03ffffffu, 26, &target) || target != g.entry) continue;
            // The wrapper's prologue must be in this same bounded function.
            const uintptr_t lower = at - range.begin < 128 ? range.begin : at - 128;
            for (uintptr_t begin = lower; begin < at; begin += 4) {
                if (Read(image, begin, &w) && w == kEnterFrame && Caller(image, begin, at, g)) {
                    if (++matches > 1) return false;
                }
            }
        }
    }
    return matches == 1;
}

bool Reject(const char** rejection, const char* reason) {
    if (rejection != nullptr) *rejection = reason;
    return false;
}

}  // namespace

bool ResolveMonochromeGate(const MonochromeGateImageView& image,
                           MonochromeGatePatch* out, const char** rejection) {
    if (rejection != nullptr) *rejection = nullptr;
    if (out == nullptr) return Reject(rejection, "missing monochrome gate output");
    *out = {};
    const uintptr_t base = reinterpret_cast<uintptr_t>(image.base);
    if (base == 0 || (base & 3u) != 0 || image.size == 0 || image.size > kMaxImageSpan ||
            image.size > UINTPTR_MAX - base || image.executable_ranges == nullptr ||
            image.executable_range_count == 0 || image.executable_range_count > kMaxRanges) {
        return Reject(rejection, "invalid monochrome mapped image");
    }
    for (size_t i = 0; i < image.executable_range_count; ++i) {
        const auto& a = image.executable_ranges[i];
        if ((a.begin & 3u) != 0 || (a.end & 3u) != 0 || a.begin >= a.end || a.end > image.size) {
            return Reject(rejection, "invalid monochrome executable range");
        }
        for (size_t j = 0; j < i; ++j) {
            const auto& b = image.executable_ranges[j];
            if (a.begin < b.end && b.begin < a.end) return Reject(rejection, "overlapping monochrome ranges");
        }
    }
    Graph found;
    size_t matches = 0;
    for (size_t i = 0; i < image.executable_range_count; ++i) {
        const auto& range = image.executable_ranges[i];
        for (uintptr_t at = range.begin; at < range.end; at += 4) {
            uint32_t w = 0;
            if (!Read(image, at, &w) || w != kEnterFrame) continue;
            Graph candidate;
            if (!Candidate(image, at, &candidate)) continue;
            if (++matches > 1) return Reject(rejection, "ambiguous monochrome support functions");
            found = candidate;
        }
    }
    if (matches != 1) return Reject(rejection, "monochrome support graph not found");
    if (!HasUniqueCaller(image, found)) return Reject(rejection, "monochrome async caller not unique");
    uint32_t original = 0;
    uintptr_t target = 0;
    if (!Read(image, found.gate, &original) || (original & 0xfff8001fu) != 0x36200001u ||
            !BranchTarget(image, found.gate, (original >> 5) & 0x3fffu, 14, &target) ||
            target != found.supported) return Reject(rejection, "monochrome gate changed during resolution");
    const uintptr_t words = (found.supported - found.gate) / 4;
    if (words >= (uintptr_t{1} << 25)) return Reject(rejection, "monochrome branch out of range");
    out->address = const_cast<uint8_t*>(image.base + found.gate);
    out->expected_instruction = original;
    out->replacement_instruction = 0x14000000u | static_cast<uint32_t>(words);
    return true;
}

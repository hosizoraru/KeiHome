// SPDX-License-Identifier: Apache-2.0
#include "launcher_label_profile.h"
#include <string.h>

namespace {
struct LabelWord { uint32_t word, mask; };
#include "launcher_label_patterns.inc"
#include "launcher_label_variants.inc"
bool Reject(const char** why, const char* text) { if (why) *why = text; return false; }
bool Contains(const IconHighlightImage& im, size_t pc, size_t n) {
    for (size_t i = 0; i < im.range_count; ++i) {
        const auto& r = im.ranges[i];
        if (r.executable && pc >= r.begin && pc <= r.end && n <= r.end - pc) return true;
    }
    return false;
}
uint32_t Word(const IconHighlightImage& im, size_t pc) {
    uint32_t w = 0; if (Contains(im, pc, 4)) memcpy(&w, im.base + pc, 4); return w;
}
int32_t Field(uint32_t w) { const uint32_t n = w >> 12 & 511; return int32_t(n) - (n & 256 ? 512 : 0); }
uint32_t Slot(uint32_t w) { return (w >> 10 & 4095) * 8; }
size_t Call(const IconHighlightImage& im, size_t pc) {
    const uint32_t w = Word(im, pc); if ((w & 0xfc000000u) != 0x94000000u) return 0;
    const int64_t n = w & 0x3ffffff;
    const int64_t to = int64_t(pc) + (n - (n & 0x2000000 ? 0x4000000 : 0)) * 4;
    return to > 0 && Contains(im, size_t(to), 4) ? size_t(to) : 0;
}
template<size_t N>
bool Matches(const IconHighlightImage& im, size_t pc, const LabelWord (&p)[N]) {
    if (!Contains(im, pc, N * 4)) return false;
    for (size_t i = 0; i < N; ++i) if ((Word(im, pc + 4*i) & p[i].mask) != p[i].word) return false;
    return true;
}
template<size_t N, typename Predicate>
size_t FindWhere(const IconHighlightImage& im, const LabelWord (&p)[N], Predicate accepts) {
    size_t found = 0;
    for (size_t i = 0; i < im.range_count; ++i) {
        const auto& r = im.ranges[i];
        if (!r.executable || r.end - r.begin < 4*N) continue;
        for (size_t pc = r.begin; pc <= r.end - 4*N; pc += 4) {
            if ((Word(im, pc) & p[0].mask) != p[0].word || !Matches(im, pc, p)) continue;
            if (!accepts(pc)) continue;
            if (found) return 0;
            found = pc;
        }
    }
    return found;
}
template<size_t N>
size_t Find(const IconHighlightImage& im, const LabelWord (&p)[N], size_t call = 0, size_t target = 0) {
    return FindWhere(im, p, [&](size_t pc) { return !target || Call(im, pc + call) == target; });
}
uint32_t AllocationTag(const IconHighlightImage& im, size_t pc) {
    const uint32_t a = Word(im, pc), b = Word(im, pc + 4), c = Word(im, pc + 8);
    if ((a & 0xffe0001fu) != 0xd2800002u || (b & 0xffe0001fu) != 0xf2a00002u ||
            (c & 0xfc000000u) != 0x14000000u) return 0;
    const int64_t n = c & 0x3ffffff;
    const int64_t generic = int64_t(pc + 8) + (n - (n & 0x2000000 ? 0x4000000 : 0)) * 4;
    if (generic <= 0 || (!Matches(im, size_t(generic), kLabelAllocator) &&
            !Matches(im, size_t(generic), kLabelParameterizedAllocator))) return 0;
    return (a >> 5 & 65535) | (b >> 5 & 65535) << 16;
}
bool NoInteriorBranch(const IconHighlightImage& im, size_t at) {
    for (size_t i = 0; i < im.range_count; ++i) {
        const auto& r = im.ranges[i]; if (!r.executable) continue;
        for (size_t pc = r.begin; pc + 4 <= r.end; pc += 4) {
            const uint32_t w = Word(im, pc); int64_t n = 0; unsigned bits = 0;
            if ((w & 0x7c000000u) == 0x14000000u) { n = w & 0x3ffffff; bits = 26; }
            else if ((w & 0xff000010u) == 0x54000000u || (w & 0x7e000000u) == 0x34000000u) {
                n = w >> 5 & 0x7ffff; bits = 19;
            } else if ((w & 0x7e000000u) == 0x36000000u) { n = w >> 5 & 0x3fff; bits = 14; }
            if (!bits) continue;
            const int64_t to = int64_t(pc) + (n - (n & (int64_t{1} << (bits-1)) ? int64_t{1} << bits : 0)) * 4;
            if (to > int64_t(at) && to < int64_t(at + 16)) return false;
        }
    }
    return true;
}
} // namespace

bool ResolveLauncherLabels(const IconHighlightImage& im, LauncherLabelProfile* out, const char** why) {
    if (why) *why = nullptr;
    if (!out) return Reject(why, "missing label output");
    *out = {};
    if (!ValidateArm64DartImage(im)) return Reject(why, "invalid label image");
    LauncherLabelProfile p{};
    const auto either = [](size_t a, size_t b) { return a && b && a != b ? size_t{0} : a ? a : b; };
    p.builder = Find(im, kLabelBuilder);
    p.lines = either(Find(im, kLabelLines), Find(im, kLabelLinesInline));
    p.config = either(Find(im, kLabelConfig), Find(im, kLabelConfigExpanded));
    p.value = Find(im, kLabelValue);
    p.measure = Find(im, kLabelMeasure); p.marquee_constructor = Find(im, kLabelMarqueeCtor);
    p.change_event = either(Find(im, kLabelChangeEvent), Find(im, kLabelChangeEventExpanded));
    p.font_refresh = Find(im, kLabelFontRefresh);
    const size_t type = Find(im, kLabelMarqueeType);
    if (!p.builder || !p.lines || !p.config || !p.value || !p.measure || !p.marquee_constructor || !type ||
            !p.change_event || !p.font_refresh)
        return Reject(why, "ambiguous label instruction roles");
    const size_t cell = Find(im, kLabelCellContent);
    const size_t grid_body = either(Find(im, kLabelCellGridBody), Find(im, kLabelCellGridBodyExpanded));
    const size_t ticker_effective = Find(im, kLabelTickerEffective);
    const size_t ticker_type = Find(im, kLabelTickerType, 0x98, ticker_effective);
    p.cell_visibility = Find(im, kLabelGridVisibility);
    size_t visibility_tail = 0;
    if (p.cell_visibility) {
        for (size_t at = p.cell_visibility + 0x180; at <= p.cell_visibility + 0x220; at += 4) {
            if (!Matches(im, at, kLabelGridVisibilityTail)) continue;
            if (visibility_tail) return Reject(why, "ambiguous page visibility tail");
            visibility_tail = at;
        }
    }
    if (!cell || !grid_body || !ticker_effective || !ticker_type || !p.cell_visibility || !visibility_tail ||
            !Matches(im, cell + 0xbc, kLabelCellListInit) ||
            !Matches(im, Call(im, cell + 0x34), kLabelCellGridFactory) ||
            grid_body != Call(im, cell + 0x34) + 0x64)
        return Reject(why, "ambiguous page visibility graph");
    // Wrap the complete page Stack, including the icon-list Obx. The earlier
    // grid/background Obx alone does not contain app labels.
    for (size_t at = cell + 0xc0; at < cell + 0x120; at += 4) {
        if (!Matches(im, at, kLabelCellStackInit)) continue;
        if (p.cell_call) return Reject(why, "ambiguous page Stack initialization");
        p.cell_call = at - 4;
        memcpy(p.cell_replay, im.base + at, sizeof(p.cell_replay));
    }
    if (!p.cell_call || (AllocationTag(im, Call(im, p.cell_call)) & 0xfff) != 0x21c) return Reject(why, "missing page Stack allocation");
    p.cell_root_slot = Field(Word(im, p.cell_call + 4 + 9*4));
    p.cell_controller_field = uint32_t(Field(Word(im, grid_body + 0x24)));
    p.cell_screen_field = uint32_t(Field(Word(im, p.cell_visibility + 0x64)));
    p.cell_post_expected = Word(im, p.cell_call + 4);
    if (p.cell_root_slot != -8 || p.cell_controller_field != 0xb || !p.cell_screen_field ||
            Word(im, p.cell_call + 48) != 0xaa1d03efu ||
            Word(im, p.cell_call + 52) != 0xa8c179fdu ||
            Word(im, p.cell_call + 56) != 0xd65f03c0u || !NoInteriorBranch(im, p.cell_call))
        return Reject(why, "page visibility root ABI changed");
    const uint32_t ticker_cid = (Word(im, ticker_type + 0x38) >> 5) & 65535;
    for (size_t r = 0; r < im.range_count; ++r) {
        if (!im.ranges[r].executable) continue;
        for (size_t at = im.ranges[r].begin; at + 12 <= im.ranges[r].end; at += 4) {
            if ((Word(im, at) & 0xffe0001fu) != 0xd2800002u) continue;
            const uint32_t tag = AllocationTag(im, at);
            if (!tag || tag >> 12 != ticker_cid) continue;
            if (p.ticker_tag || (tag & 0xfff) != 0x21c)
                return Reject(why, "TickerMode allocation ABI changed");
            p.ticker_tag = tag;
        }
    }
    if (!p.ticker_tag) return Reject(why, "missing TickerMode allocator");
    // Reuse the live editor's reactive visibility flag. The enum-derived
    // normal/multiselect getters do not track the editor surface lifecycle.
    // The receiver's field-table identity is independently shared by the grid
    // visibility and screen-layer editing graphs, rather than a version table.
    const size_t editing_state = FindWhere(im, kLabelEditingState, [&](size_t pc) {
        return Call(im, pc + 0x54) == p.value &&
            Slot(Word(im, pc + 0x10)) == Slot(Word(im, p.cell_visibility + 0x98)) &&
            Field(Word(im, pc + 0x4c)) == Field(Word(im, p.cell_visibility + 0xd4));
    });
    p.editing = editing_state;
    if (!editing_state ||
            Word(im, p.cell_visibility + 0x94) != 0xf9403f40u ||
            Slot(Word(im, editing_state + 0x10)) != Slot(Word(im, p.cell_visibility + 0x98)) ||
            Field(Word(im, editing_state + 0x4c)) != Field(Word(im, p.cell_visibility + 0xd4)))
        return Reject(why, "ambiguous native editing subscription graph");
    p.editing_slot = Slot(Word(im, editing_state + 0x10));
    // Subscribe the reactive parent on both cached and cold ordinary-icon paths.
    // Fresh immutable drag/shortcut copies make cached GetBuilder titles rebuild;
    // only the title publishes TickerMode. Other native child classes pass through.
    const size_t cache = Find(im, kLabelCacheGet);
    const size_t factory = Find(im, kLabelShortcutFactory);
    const size_t drag_factory = FindWhere(im, kLabelDragFactory, [&](size_t pc) {
        return (AllocationTag(im, Call(im, pc + 4)) & 0xfff) == 0x51c &&
                Matches(im, Call(im, pc + 0x44) + 0x34c, kLabelDragChild);
    });
    const size_t drag_ctor = Call(im, drag_factory + 0x44);
    size_t factory_count = 0;
    if (drag_factory) for (size_t pc = drag_factory - 0x90; pc <= drag_factory + 0x48; pc += 4) {
        if ((Word(im, pc) & 0xffe00fffu) != 0xf84003a0u ||
                Word(im, pc+4) != 0xaa1d03efu || Word(im, pc+8) != 0xa8c179fdu ||
                Word(im, pc+12) != 0xd65f03c0u || Call(im, pc-4) != drag_ctor) continue;
        if (factory_count == 2 || !NoInteriorBranch(im, pc)) return Reject(why, "ambiguous drag factory returns");
        const int32_t slot = Field(Word(im, pc));
        if (factory_count && slot != p.factory_root) return Reject(why, "drag factory root mismatch");
        p.factory_root = slot; p.factory_post[factory_count] = pc;
        p.factory_expected[factory_count++] = Word(im, pc);
    }
    if (factory_count != 2 || p.factory_root >= 0 || p.factory_root < -256)
        return Reject(why, "missing cold icon factory roots");

    p.drag_tag = AllocationTag(im, Call(im, drag_factory + 4));
    if (!drag_factory || !drag_ctor || (p.drag_tag & 0xfff) != 0x51c ||
            !Matches(im, drag_ctor + 0x34c, kLabelDragChild))
        return Reject(why, "missing native draggable child contract");
    if (!cache || !factory) return Reject(why, "missing cached shortcut graph");
    p.shortcut_tag = AllocationTag(im, Call(im, factory + 0x18));
    p.shortcut_cid = p.shortcut_tag >> 12;
    if (!p.shortcut_cid || (p.shortcut_tag & 0xfff) != 0x31c || !Call(im, factory + 0x3c))
        return Reject(why, "missing shortcut allocator/constructor");
    for (size_t pc = cache + 0x100; pc < cache + 0x200; pc += 4) {
        if ((Word(im, pc) & 0xffe00fffu) != 0xf84003a1u ||
                Word(im, pc+4) != 0xb840b020u || Word(im, pc+8) != 0x8b1c8000u ||
                Word(im, pc+12) != 0xaa1d03efu || Word(im, pc+16) != 0xa8c179fdu ||
                Word(im, pc+20) != 0xd65f03c0u) continue;
        if (p.cache_post || !Call(im, pc-4) || !NoInteriorBranch(im, pc))
            return Reject(why, "ambiguous cached shortcut return");
        p.cache_post = pc; p.cache_root = Field(Word(im, pc));
        memcpy(p.cache_expected, im.base + pc, sizeof(p.cache_expected));
    }
    if (!p.cache_post || p.cache_root >= 0 || p.cache_root < -256)
        return Reject(why, "missing cached shortcut root");

    // The UI item-change handler has two adjacent info calls before it consumes
    // the change set. Both discard their result. Reuse the first original call
    // PC for the resident title-cache refresh; no extra Dart frame is invented.
    size_t log_calls = 0;
    for (size_t at = p.change_event + 0x30; at < p.change_event + 0x120; at += 4) {
        const size_t target = Call(im, at);
        if (!target || !Matches(im, target, kLabelRefreshLog)) continue;
        if (p.refresh_logger && p.refresh_logger != target)
            return Reject(why, "item-change log targets differ");
        if ((Word(im, at + 4) & 0xffe0001fu) != 0x92800011u ||
                Word(im, at + 8) != 0xf8716ba2u)
            return Reject(why, "item-change return value became live");
        if (!log_calls++) { p.refresh_call = at; p.refresh_logger = target; }
    }
    if (log_calls != 2) return Reject(why, "item-change refresh call graph changed");
    const bool inline_lines = Matches(im, p.lines, kLabelLinesInline);
    const bool line_config_graph = inline_lines ?
        (Call(im, p.lines + 0x48) == Call(im, p.config + 0x48) &&
         Slot(Word(im, p.lines + 0x10)) == Slot(Word(im, p.config + 0x10)) &&
         Word(im, p.lines + 0x4c) == Word(im, p.config + 0x4c) &&
         Call(im, p.lines + 0x54) == p.value) : Call(im, p.lines + 12) == p.config;
    if (!line_config_graph || Call(im, p.config + 0x54) != p.value)
        return Reject(why, "label configuration graph changed");
    p.grid_slot = Slot(Word(im, p.config + 16));
    p.config_rx_field = uint32_t(Field(Word(im, p.config + 0x4c)));
    p.rx_value_field = uint32_t(Field(Word(im, p.value + 0x3c)));
    p.source_slot = Field(Word(im, p.builder + 0x24));
    // Each role must occur exactly once in this builder, before a new function.
    size_t line_call = 0, max_lines = 0, source_width = 0;
    for (size_t at = p.builder + 0x30; at < p.builder + 0x740; at += 4) {
        if (Matches(im, at, kLabelTextInit)) {
            if (p.allocation_call) return Reject(why, "ambiguous label allocation");
            p.allocation_call = at;
        }
        if (Call(im, at) == p.lines) { if (line_call) return Reject(why, "ambiguous line caller"); line_call = at; }
        if (Call(im, at) == p.config && (Word(im, at + 4) & 0xffe00fffu) == 0xfc400000u) {
            const uint32_t field = uint32_t(Field(Word(im, at + 4)));
            if (p.width_field && p.width_field != field) return Reject(why, "label width consumers differ");
            p.width_field = field;
        }
        if (Word(im, at) == 0x8b1c8000u && (Word(im, at - 4) & 0xffe00fffu) == 0xb8400020u &&
                (Word(im, at + 4) & 0xffe00fffu) == 0xf80003a0u &&
                (Word(im, at + 8) & 0xffe00fffu) == 0xb8400022u && Word(im, at + 12) == 0x8b1c8042u) {
            if (source_width) return Reject(why, "ambiguous source width");
            source_width = at; p.source_width_field = uint32_t(Field(Word(im, at + 8)));
        }
    }
    if (!p.allocation_call || !line_call || !p.width_field || !source_width || line_call >= p.allocation_call)
        return Reject(why, "label data flow changed");
    p.text_allocator = Call(im, p.allocation_call);
    const uint32_t text_tag = AllocationTag(im, p.text_allocator);
    p.text_cid = text_tag >> 12;
    if ((text_tag & 0xfff) != 0x51c) return Reject(why, "Text allocation ABI changed");
    p.text_slot = Field(Word(im, p.allocation_call + 4));
    p.widget_slot = Field(Word(im, p.allocation_call + 8));
    p.style_slot = Field(Word(im, p.allocation_call + 16));
    for (size_t at = p.allocation_call + 24; at < p.allocation_call + 0x60; at += 4) {
        if (Word(im, at) == 0x93417c90u && Word(im, at + 4) == 0x1e620200u) {
            if (max_lines) return Reject(why, "ambiguous text line field");
            max_lines = at;
            for (size_t before = at - 32; before + 4 < at; before += 4) {
                if (Word(im, before) != 0xd37ff824u || Word(im, before + 4) != 0xb8037004u ||
                        (Word(im, before - 4) & 0xffe00fffu) != 0xf84003a1u) continue;
                if (p.lines_slot) return Reject(why, "ambiguous maximum-lines producer");
                p.lines_slot = Field(Word(im, before - 4));
            }
        }
    }
    if (!max_lines || Field(Word(im, line_call + 4)) != p.lines_slot ||
            (Word(im, line_call + 4) & 0xffe00fffu) != 0xf80003a0u)
        return Reject(why, "line result is not the Text maximum");
    for (size_t at = p.allocation_call + 20; at < max_lines; at += 4) {
        const uint32_t w = Word(im, at);
        const bool stack_load = (w & 0xffe00fffu) == 0xf84003a1u;
        const bool pool_add = (w & 0xffc003ffu) == 0x91400361u;
        const bool pool_load = (w & 0xffc003ffu) == 0xf9400361u || (w & 0xffc003ffu) == 0xf9400021u;
        const bool field_store = (w & 0xffe00fffu) == 0xb8000001u &&
                (Field(w) == 0x1b || Field(w) == 0x2b || Field(w) == 0x33 || Field(w) == 0x47);
        if (!stack_load && !pool_add && !pool_load && !field_store && w != 0xb8013003u &&
                w != 0xd37ff824u && w != 0xb8037004u) return Reject(why, "unknown Text initialization effect");
    }
    // The native constructor call is uniquely paired with its allocator.
    for (size_t i = 0; i < im.range_count; ++i) {
        const auto& r = im.ranges[i]; if (!r.executable) continue;
        for (size_t at = r.begin + 28; at + 4 <= r.end; at += 4) {
            if (Call(im, at) != p.marquee_constructor) continue;
            if (p.marquee_allocator || Word(im, at - 24) != 0xaa0003e1u ||
                    (Word(im, at - 20) & 0xffe00fffu) != 0xf84003a2u ||
                    (Word(im, at - 16) & 0xffe00fffu) != 0xf84003a3u ||
                    (Word(im, at - 12) & 0xffe00fffu) != 0xf84003a5u ||
                    (Word(im, at - 8) & 0xffe00fffu) != 0xf84003a6u ||
                    (Word(im, at - 4) & 0xffe00fffu) != 0xf80003a0u)
                return Reject(why, "marquee allocation/constructor graph changed");
            p.marquee_allocator = Call(im, at - 28);
        }
    }
    p.marquee_tag = AllocationTag(im, p.marquee_allocator);
    p.size_cid = AllocationTag(im, Call(im, p.measure + 0x16c)) >> 12;
    const uint32_t checked_cid = Word(im, type + 0x30) >> 5 & 65535;
    if ((p.marquee_tag & 0xfff) != 0x71c || (p.marquee_tag >> 12) != checked_cid ||
            !p.size_cid || p.size_cid == p.text_cid || p.size_cid == checked_cid)
        return Reject(why, "marquee/Size class contract changed");
    // This exact native measurement constructor supplies TextDirection.ltr.
    p.ltr_slot = Slot(Word(im, p.measure + 0x114));
    p.lines_tail = p.lines + (inline_lines ? 0x8c : 0x44);
    const uint32_t tail[] = {0xaa0103e0u, 0xaa1d03efu, 0xa8c179fdu, 0xd65f03c0u};
    memcpy(p.lines_expected, tail, 16);
    for (size_t i = 0; i < 4; ++i) {
        p.post_expected[i] = Word(im, p.allocation_call + 4 + 4*i);
        if (Word(im, p.lines_tail + 4*i) != tail[i]) return Reject(why, "line epilogue ABI changed");
    }
    const int32_t frame = int32_t((Word(im, p.builder + 8) >> 10) & 4095);
    const int32_t slots[] = {p.source_slot, p.text_slot, p.style_slot, p.widget_slot, p.lines_slot};
    for (size_t i = 0; i < 5; ++i) {
        if (slots[i] >= 0 || slots[i] < -frame || (slots[i] & 7)) return Reject(why, "invalid label stack slot");
        for (size_t j = 0; j < i; ++j) if (slots[i] == slots[j]) return Reject(why, "aliased label roots");
    }
    const uint32_t config_field = inline_lines ? 0x2f : 0x2b;
    if (!p.grid_slot || p.config_rx_field != config_field || p.rx_value_field != 0x13 ||
            !p.ltr_slot || p.width_field != 0x2b || !p.source_width_field ||
            !NoInteriorBranch(im, p.allocation_call + 4) || !NoInteriorBranch(im, p.lines_tail))
        return Reject(why, "unsupported label fields/patch ingress");
    p.after_text_init = max_lines;
    for (size_t at = p.builder + 0x300; at < p.builder + 0x600; at += 4) {
        const size_t target = Call(im, at);
        if (!target || !Matches(im, target, kLabelPrefix)) continue;
        if (p.prefix_helper || (Word(im, at - 4) & 0xffe00fffu) != 0xf84003a1u ||
                Word(im, at + 0x54) != 0xb8407001u)
            return Reject(why, "prefix title-width contract changed");
        p.prefix_helper = target;
        p.prefix_widget_slot = Field(Word(im, at - 4));
    }
    if (!p.prefix_helper || p.prefix_widget_slot != Field(Word(im, p.builder + 0x18)) ||
            p.prefix_widget_slot >= 0 || p.prefix_widget_slot < -frame ||
            (p.prefix_widget_slot & 7)) return Reject(why, "missing title prefix helper");
    const size_t marquee_build = Find(im, kLabelMarqueeBuild);
    p.fade_wrap = Find(im, kLabelFadeWrap);
    if (!marquee_build || !p.fade_wrap) return Reject(why, "ambiguous marquee fade graph");
    for (size_t at = marquee_build; at < marquee_build + 4 * (sizeof(kLabelMarqueeBuild) / sizeof(kLabelMarqueeBuild[0])); at += 4) {
        if (Call(im, at) != p.fade_wrap) continue;
        if (p.fade_call || Word(im, at + 4) != 0xaa1d03efu ||
                Word(im, at + 8) != 0xa8c179fdu || Word(im, at + 12) != 0xd65f03c0u)
            return Reject(why, "fade return contract changed");
        p.fade_call = at;
    }
    // The authenticated constructor initializes both native fade fractions.
    const uint32_t start = Word(im, p.marquee_constructor + 0xc4);
    const uint32_t end = Word(im, p.marquee_constructor + 0xc8);
    if ((start & 0xffe00fffu) != 0xf80000dfu || (end & 0xffe00fffu) != 0xf80000dfu)
        return Reject(why, "native fade fractions changed");
    p.fade_start_field = uint32_t(Field(start)); p.fade_end_field = uint32_t(Field(end));
    if (!p.fade_call || p.fade_start_field != 0x43 || p.fade_end_field != p.fade_start_field + 8)
        return Reject(why, "native fade field ABI changed");
    *out = p;
    return true;
}

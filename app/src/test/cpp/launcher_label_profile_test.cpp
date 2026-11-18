// SPDX-License-Identifier: Apache-2.0
#include "launcher_label_profile.h"
#include <stdio.h>
#include <string.h>
#include <fstream>
#include <iterator>
#include <vector>

int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream stream(argv[1], std::ios::binary);
    std::vector<uint8_t> bytes{std::istreambuf_iterator<char>(stream), {}};
    if (bytes.size() < 64) return 2;
    const auto u16 = [&](size_t at) { uint16_t n; memcpy(&n, bytes.data() + at, 2); return n; };
    const auto u32 = [&](size_t at) { uint32_t n; memcpy(&n, bytes.data() + at, 4); return n; };
    const auto u64 = [&](size_t at) { uint64_t n; memcpy(&n, bytes.data() + at, 8); return n; };
    std::vector<IconHighlightRange> ranges;
    for (size_t i = 0; i < u16(56); ++i) {
        const size_t ph = size_t(u64(32)) + i * 56;
        if (u32(ph) != 1 || !(u32(ph + 4) & 4) || !u64(ph + 40)) continue;
        const size_t begin = size_t(u64(ph + 16)), end = begin + size_t(u64(ph + 40));
        if (u64(ph + 8) != begin || end > bytes.size()) return 2;
        ranges.push_back({begin, end, bool(u32(ph + 4) & 1)});
    }
    const IconHighlightImage image{bytes.data(), bytes.size(), ranges.data(), ranges.size()};
    LauncherLabelProfile p{}; const char* why = nullptr;
    if (!ResolveLauncherLabels(image, &p, &why)) { fprintf(stderr, "Rejected: %s\n", why); return 1; }
    printf("builder=%zx lines=%zx measure=%zx ctor=%zx alloc=%zx textCid=%u sizeCid=%u marqueeTag=%x "
           "grid=%x source=%d text=%d style=%d widget=%d line=%d after=%zx textAlloc=%zx "
           "width=%x sourceWidth=%x ltr=%x event=%zx logger=%zx font=%zx "
           "cell=%zx visible=%zx tickerTag=%x cellRoot=%d controller=%x screen=%x prefix=%zx prefixRoot=%d fadeCall=%zx fadeWrap=%zx fadeStart=%x fadeEnd=%x editing=%zx editingSlot=%x cache=%zx cacheRoot=%d shortcutCid=%u dragTag=%x factoryOne=%zx factoryTwo=%zx factoryRoot=%d shortcutTag=%x ", p.builder, p.lines, p.measure,
           p.marquee_constructor, p.allocation_call, p.text_cid, p.size_cid, p.marquee_tag, p.grid_slot,
           p.source_slot, p.text_slot, p.style_slot, p.widget_slot, p.lines_slot, p.after_text_init,
           p.text_allocator, p.width_field, p.source_width_field, p.ltr_slot,
           p.refresh_call, p.refresh_logger, p.font_refresh, p.cell_call, p.cell_visibility, p.ticker_tag,
           p.cell_root_slot, p.cell_controller_field, p.cell_screen_field, p.prefix_helper, p.prefix_widget_slot, p.fade_call, p.fade_wrap, p.fade_start_field, p.fade_end_field, p.editing, p.editing_slot, p.cache_post, p.cache_root, p.shortcut_cid, p.drag_tag, p.factory_post[0], p.factory_post[1], p.factory_root, p.shortcut_tag);
    printf("wrapper=%zx wrapperInit=%zx wrapperType=%zx\n",
           p.marquee_wrapper_build, p.marquee_wrapper_init, p.marquee_wrapper_type);
    const auto rejects = [&](size_t at, uint32_t replacement) {
        uint32_t old; memcpy(&old, bytes.data() + at, 4);
        memcpy(bytes.data() + at, &replacement, 4);
        LauncherLabelProfile bad{}; const char* reason = nullptr;
        const bool ok = !ResolveLauncherLabels(image, &bad, &reason) && !bad.builder;
        memcpy(bytes.data() + at, &old, 4);
        if (!ok) fprintf(stderr, "Accepted mutation at %zx\n", at);
        return ok;
    };
    if (!rejects(p.lines + 12, 0x94000000) || !rejects(p.allocation_call + 8, 0xf81c03a0) ||
            !rejects(p.marquee_allocator + 4, 0xf2a00002) || !rejects(p.lines_tail, 0xaa0003e0) ||
            !rejects(p.measure + 0x16c, 0x94000000) || !rejects(p.refresh_call, 0x94000000) ||
            !rejects(p.font_refresh, 0xaa0003e0) || !rejects(p.refresh_call + 8, 0xaa0003e0) ||
            !rejects(p.cell_call, 0x94000000) || !rejects(p.cell_call + 4, 0xaa0003e0) ||
            !rejects(p.cell_visibility, 0xaa0003e0) || !rejects(p.prefix_helper, 0xaa0003e0) ||
            !rejects(p.editing, 0xaa0003e0) ||
            !rejects(p.editing + 0x14, 0x94000000) || !rejects(p.cache_post + 4, 0xaa0003e0) || !rejects(p.factory_post[0], 0xaa0003e0) ||
            !rejects(p.factory_post[1], 0xaa0003e0)) return 1;
    if (p.marquee_wrapper_build) {
        const auto call = [](size_t from, size_t to) {
            return 0x94000000u | (uint32_t((int64_t(to) - int64_t(from)) / 4) & 0x3ffffffu);
        };
        if (p.fade_call || p.fade_wrap ||
                !rejects(p.marquee_wrapper_init, call(p.marquee_wrapper_init, p.text_allocator)) ||
                !rejects(p.marquee_wrapper_init + 0x14, u32(p.marquee_wrapper_init + 0x14) ^ (4u << 12)) ||
                !rejects(p.marquee_wrapper_type + 0x38, u32(p.marquee_wrapper_type + 0x38) ^ (1u << 5)) ||
                !rejects(p.marquee_wrapper_build + 0xc8, call(p.marquee_wrapper_build + 0xc8, p.text_allocator)) ||
                !rejects(p.marquee_wrapper_build + 0xec, 0xaa1603e3u) ||
                !rejects(p.marquee_constructor + 0x110, 0xd503201fu)) return 1;
    } else if (!rejects(p.fade_call, 0x94000000) || !rejects(p.fade_wrap, 0xaa0003e0)) {
        return 1;
    }

    const uint32_t constructor_entry = u32(p.marquee_constructor);
    const uint32_t nop = 0xd503201f;
    memcpy(bytes.data() + p.marquee_constructor, &nop, sizeof(nop));
    LauncherLabelProfile state{}, unsupported{};
    const bool independent = ResolveLauncherLabelState(image, &state, &why) &&
        state.config == p.config && state.value == p.value &&
        state.refresh_call == p.refresh_call && state.refresh_logger == p.refresh_logger &&
        !state.builder && !state.marquee_constructor &&
        !ResolveLauncherLabels(image, &unsupported, &why) && !unsupported.builder;
    memcpy(bytes.data() + p.marquee_constructor, &constructor_entry, sizeof(constructor_entry));
    if (!independent) {
        fputs("Marquee constructor mismatch must leave shared refresh available\n", stderr);
        return 1;
    }
    puts("Label graph, rejection and shared-refresh isolation checks passed");
}

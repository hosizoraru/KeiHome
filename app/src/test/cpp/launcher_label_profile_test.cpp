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
           "cell=%zx visible=%zx tickerTag=%x cellRoot=%d controller=%x screen=%x prefix=%zx prefixRoot=%d fadeCall=%zx fadeWrap=%zx fadeStart=%x fadeEnd=%x\n", p.builder, p.lines, p.measure,
           p.marquee_constructor, p.allocation_call, p.text_cid, p.size_cid, p.marquee_tag, p.grid_slot,
           p.source_slot, p.text_slot, p.style_slot, p.widget_slot, p.lines_slot, p.after_text_init,
           p.text_allocator, p.width_field, p.source_width_field, p.ltr_slot,
           p.refresh_call, p.refresh_logger, p.font_refresh, p.cell_call, p.cell_visibility, p.ticker_tag,
           p.cell_root_slot, p.cell_controller_field, p.cell_screen_field, p.prefix_helper, p.prefix_widget_slot, p.fade_call, p.fade_wrap, p.fade_start_field, p.fade_end_field);
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
            !rejects(p.cell_visibility, 0xaa0003e0) || !rejects(p.prefix_helper, 0xaa0003e0) || !rejects(p.fade_call, 0x94000000) ||
            !rejects(p.fade_wrap, 0xaa0003e0)) return 1;
    puts("Label graph and rejection checks passed");
}

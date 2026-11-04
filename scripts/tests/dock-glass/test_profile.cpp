// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_profile.h"
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
    DockGlassProfile p{}; const char* why=nullptr;
    if (!ResolveDockGlass(image,&p,&why)) {fprintf(stderr,"Rejected: %s\n",why);return 1;}
    printf("phone=%zx bg=%zx gate=%zx outer=%zx mode=%zx tags=%x,%x,%x,%x stack=%x box=%x pos=%x device=%x corner=%x\n",p.phone,p.background_call,p.gate,p.outer_call,p.mode,p.material_tags[0],p.material_tags[1],p.material_tags[2],p.material_tags[3],p.stack_tag,p.sized_box_tag,p.positioned_tag,p.device_slot,p.corner_field);
    unsigned checks=0;
    const auto rejects=[&](size_t at,uint32_t replacement){
        const uint32_t old=u32(at);memcpy(bytes.data()+at,&replacement,4);
        DockGlassProfile rejected{};const char* reason=nullptr;
        const bool ok=!ResolveDockGlass(image,&rejected,&reason)&&!rejected.phone&&!rejected.background_call;
        memcpy(bytes.data()+at,&old,4);++checks;
        if(!ok)fprintf(stderr,"accepted mutation at %zx\n",at);
        return ok;
    };
    if(!rejects(p.gate,p.gate_expected^0x20u)||!rejects(p.background_call,0x94000000u)||
       !rejects(p.mode_post,0xd503201fu)||!rejects(p.mode,0xd503201fu)||
       !rejects(p.background+8,0x94000000u)||!rejects(p.phone+0x40,0xd503201fu)||
       !rejects(p.mode_returns[0],0xd503201fu))return 1;
    DockGlassProfile cleared{};cleared.phone=1;
    if(ResolveDockGlass({nullptr,0,nullptr,0},&cleared)||cleared.phone)return 1;
    printf("Dock structural/rejection checks=%u\n",checks+1);
    return 0;
}

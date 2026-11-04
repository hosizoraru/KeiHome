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
    printf("phone=%zx bg=%zx gate=%zx outer=%zx mode=%zx tags=%x,%x,%x,%x stack=%x box=%x pos=%x device=%x corner=%x clip_none=%x native_clip=%x parent_clip_store=%zx\n",p.phone,p.background_call,p.gate,p.outer_call,p.mode,p.material_tags[0],p.material_tags[1],p.material_tags[2],p.material_tags[3],p.stack_tag,p.sized_box_tag,p.positioned_tag,p.device_slot,p.corner_field,p.clip_pp,p.native_clip_pp,p.parent_clip_store);
    unsigned checks=0;
    const auto rejects=[&](size_t at,uint32_t replacement){
        const uint32_t old=u32(at);memcpy(bytes.data()+at,&replacement,4);
        DockGlassProfile rejected{};const char* reason=nullptr;
        rejected.phone=1;rejected.clip_pp=1;rejected.stack_paint=1;
        rejected.native_clip_pp=1;rejected.parent_clip_store=1;
        const bool ok=!ResolveDockGlass(image,&rejected,&reason)&&reason&&
            !rejected.phone&&!rejected.background_call&&!rejected.clip_pp&&!rejected.stack_paint&&
            !rejected.native_clip_pp&&!rejected.parent_clip_store;
        memcpy(bytes.data()+at,&old,4);++checks;
        if(!ok)fprintf(stderr,"accepted mutation at %zx\n",at);
        return ok;
    };
    if(!rejects(p.gate,p.gate_expected^0x20u)||!rejects(p.background_call,0x94000000u)||
       !rejects(p.mode_post,0xd503201fu)||!rejects(p.mode,0xd503201fu)||
       !rejects(p.background+8,0x94000000u)||!rejects(p.phone+0x40,0xd503201fu)||
       !rejects(p.mode_returns[0],0xd503201fu))return 1;

    const auto pool=[&](size_t at){
        return ((u32(at)>>10&4095u)<<12)+(u32(at+4)>>10&4095u)*8;
    };
    const uint32_t original_clip=pool(p.stack_clip_load);
    if(!p.clip_pp||p.clip_pp!=pool(p.stack_paint+0x28)||
       p.clip_pp!=pool(p.push_clip_rect+0x108)||p.clip_pp==original_clip||
       p.native_clip_pp!=original_clip||p.parent_clip_store!=p.stack_clip_load+8||
       u32(p.parent_clip_store)!=0xb801b001u||u32(p.parent_clip_store+4)!=0xf85d83a1u)return 1;
    ++checks;
    size_t default_load=0;
    for(size_t at=p.push_clip_rect;at<p.push_clip_rect+0x104;at+=4)
        if((u32(at)&0xffc003e0u)==0x91400360u&&
           (u32(at+4)&0xffc00000u)==0xf9400000u&&pool(at)==original_clip){
            if(default_load)return 1;default_load=at;
        }
    if(!default_load)return 1;
    const auto branch_to=[&](size_t from,size_t to){
        return 0x94000000u|(uint32_t((int64_t(to)-int64_t(from))/4)&0x3ffffffu);
    };
    const auto call_target=[&](size_t at){
        int64_t n=u32(at)&0x3ffffffu;if(n&0x2000000)n-=0x4000000;
        return size_t(int64_t(at)+n*4);
    };
    // Pool immediates and BL targets are deliberately masked in source roles;
    // these mutations must fail the linked graph, not just pattern matching.
    if(!rejects(p.stack_clip_load+4,u32(p.stack_clip_load+4)^0x400u)||
       !rejects(default_load+4,u32(default_load+4)^0x400u)||
       !rejects(p.stack_paint+0x2c,u32(p.stack_paint+0x2c)^0x400u)||
       !rejects(p.push_clip_rect+0x10c,u32(p.push_clip_rect+0x10c)^0x400u)||
       !rejects(p.stack_paint+0x120,branch_to(p.stack_paint+0x120,p.stack_create))||
       !rejects(p.stack_create+0x80,branch_to(p.stack_create+0x80,p.stack_paint))||
       !rejects(p.stack_create+0x64,branch_to(p.stack_create+0x64,p.stack_constructor))||
       !rejects(call_target(p.stack_create+0x64),u32(call_target(p.stack_create+0x64))^0x20000u)||
       !rejects(call_target(p.stack_create+0x64),u32(call_target(p.stack_create+0x64))^0x200u)||
       !rejects(p.stack_paint+0x90,u32(p.stack_paint+0x90)^0x20u))return 1;

    // Preserve no-clip condition, destination, actual painter invocation and
    // widget->constructor->paint field forwarding, not merely the PP number.
    if(!rejects(p.stack_paint+0x34,u32(p.stack_paint+0x34)^1u)||
       !rejects(p.stack_paint+0x34,u32(p.stack_paint+0x34)^0x20u)||
       !rejects(p.push_clip_rect+0x114,u32(p.push_clip_rect+0x114)^1u)||
       !rejects(p.push_clip_rect+0x12c,0xd503201fu)||
       !rejects(p.stack_create+0x58,u32(p.stack_create+0x58)^0x4000u)||
       !rejects(p.stack_constructor+0x14,u32(p.stack_constructor+0x14)^1u)||
       !rejects(p.stack_constructor+0xcc,u32(p.stack_constructor+0xcc)^0x4000u)||
       !rejects(p.stack_paint+0x20,u32(p.stack_paint+0x20)^0x4000u))return 1;
    // The parent leaf is valid only at this exact store and x1 kill point.
    if(!rejects(p.parent_clip_store,u32(p.parent_clip_store)^1u)||
       !rejects(p.parent_clip_store+4,u32(p.parent_clip_store+4)^3u)||
       !rejects(p.parent_clip_store+4,u32(p.parent_clip_store+4)^0x40u)||
       !rejects(p.parent_clip_store+4,u32(p.parent_clip_store+4)^0x10000u))return 1;

    const auto write32=[&](size_t at,uint32_t value){memcpy(bytes.data()+at,&value,4);};
    const auto set_pool=[&](size_t at,uint32_t value){
        write32(at,(u32(at)&~0x003ffc00u)|((value>>12)<<10));
        write32(at+4,(u32(at+4)&~0x003ffc00u)|((value&4095u)/8<<10));
    };
    const auto accepts=[&](const IconHighlightImage& candidate,uint32_t expected,uint32_t native=0){
        DockGlassProfile result{};const char* reason=nullptr;++checks;
        const bool ok=ResolveDockGlass(candidate,&result,&reason)&&!reason&&result.clip_pp==expected&&
            result.native_clip_pp==(native?native:original_clip)&&result.parent_clip_store==p.parent_clip_store;
        if(!ok)fprintf(stderr,"rejected coherent source graph: %s\n",reason?reason:"wrong clip");
        return ok;
    };
    // Coherently relocate the two source operands: no fixture/PP allowlist.
    const uint32_t shifted_none=p.clip_pp+8;
    const uint32_t paint_pool[2]={u32(p.stack_paint+0x28),u32(p.stack_paint+0x2c)};
    const uint32_t push_pool[2]={u32(p.push_clip_rect+0x108),u32(p.push_clip_rect+0x10c)};
    set_pool(p.stack_paint+0x28,shifted_none);set_pool(p.push_clip_rect+0x108,shifted_none);
    if(!accepts(image,shifted_none))return 1;
    set_pool(p.stack_paint+0x28,original_clip);set_pool(p.push_clip_rect+0x108,original_clip);
    DockGlassProfile alias{};const char* alias_reason=nullptr;++checks;
    if(ResolveDockGlass(image,&alias,&alias_reason)||!alias_reason||alias.clip_pp||alias.phone)return 1;
    write32(p.stack_paint+0x28,paint_pool[0]);write32(p.stack_paint+0x2c,paint_pool[1]);
    write32(p.push_clip_rect+0x108,push_pool[0]);write32(p.push_clip_rect+0x10c,push_pool[1]);

    const uint32_t native_pool[2]={u32(p.stack_clip_load),u32(p.stack_clip_load+4)};
    const uint32_t default_pool[2]={u32(default_load),u32(default_load+4)};
    set_pool(p.stack_clip_load,original_clip+8);set_pool(default_load,original_clip+8);
    if(!accepts(image,p.clip_pp,original_clip+8))return 1;
    write32(p.stack_clip_load,native_pool[0]);write32(p.stack_clip_load+4,native_pool[1]);
    write32(default_load,default_pool[0]);write32(default_load+4,default_pool[1]);

    // Append a real, separately mapped PT_LOAD using the unused GNU_STACK
    // header. First prove the extended ELF resolves. An identical role in a
    // readable non-executable segment is ignored; executable duplicates fail.
    size_t spare_ph=0;
    for(size_t i=0;i<u16(56);++i){const size_t ph=size_t(u64(32))+i*56;
        if(u32(ph)==0x6474e551u)spare_ph=ph;}
    if(!spare_ph)return 1;
    for(const auto& role:std::vector<std::pair<size_t,size_t>>{
            {p.stack_paint,0x248},{p.push_clip_rect,0x22c},
            {p.stack_create,0x94},{p.stack_constructor,0x124}}) {
        std::vector<uint8_t> duplicate=bytes;
        const size_t at=(duplicate.size()+4095u)&~size_t(4095u);
        duplicate.resize(at+role.second);
        const auto d32=[&](size_t off,uint32_t value){memcpy(duplicate.data()+off,&value,4);};
        const auto d64=[&](size_t off,uint64_t value){memcpy(duplicate.data()+off,&value,8);};
        memset(duplicate.data()+spare_ph,0,56);d32(spare_ph,1);d32(spare_ph+4,5);
        d64(spare_ph+8,at);d64(spare_ph+16,at);d64(spare_ph+24,at);
        d64(spare_ph+32,role.second);d64(spare_ph+40,role.second);d64(spare_ph+48,4096);
        auto extended_ranges=ranges;extended_ranges.push_back({at,at+role.second,true});
        const IconHighlightImage extended{duplicate.data(),duplicate.size(),extended_ranges.data(),extended_ranges.size()};
        if(!accepts(extended,p.clip_pp))return 1;
        memcpy(duplicate.data()+at,bytes.data()+role.first,role.second);
        d32(spare_ph+4,4);extended_ranges.back().executable=false;
        if(!accepts(extended,p.clip_pp))return 1;
        d32(spare_ph+4,5);extended_ranges.back().executable=true;
        DockGlassProfile rejected{};const char* reason=nullptr;++checks;
        if(ResolveDockGlass(extended,&rejected,&reason)||!reason||rejected.phone||rejected.clip_pp){
            fprintf(stderr,"accepted duplicate clip role at %zx\n",role.first);return 1;}
    }
    DockGlassProfile cleared{};cleared.phone=1;
    if(ResolveDockGlass({nullptr,0,nullptr,0},&cleared)||cleared.phone)return 1;
    printf("Dock structural/rejection checks=%u\n",checks+1);
    return 0;
}

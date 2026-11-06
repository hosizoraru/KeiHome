// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_profile.h"
#include <stdio.h>
#include <string.h>
#include <fstream>
#include <iterator>
#include <vector>

int main(int argc, char** argv) {
    if (argc != 2 && !(argc==3&&!strcmp(argv[2],"--resolve-only"))) return 2;
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
    printf("curve_getter=%zx adaptive=%zx radius=%x border=%x super=%x rect=%x behavior_pp=%x ordinary_bits=%llx stacked_min_bits=%llx dispatch=%zx..%zx\n",p.curve_getter,p.recents_adaptive_clip,p.recents_radius_tag,p.recents_border_radius_tag,p.recents_superellipse_tag,p.recents_rrect_tag,p.recents_clip_pp,(unsigned long long)p.recents_default_radius_bits,(unsigned long long)p.recents_stacked_min_radius_bits,p.recents_dispatch_start,p.recents_dispatch_end);
    printf("glass=%zx scope=%zx/%08x child_scope=%zx/%08x clip_alloc=%zx/%08x roots=%d,%d key_tag=%08x key_alloc=%zx key_caller=%zx key_equals=%zx scope_map=%zx/%02x child_map=%zx/%02x\n",p.glass_build,p.glass_scope_store,p.glass_scope_expected,p.glass_child_scope_store,p.glass_child_scope_expected,p.glass_clip_alloc_call,p.glass_clip_alloc_expected,p.glass_scope_root_slot,p.glass_child_root_slot,p.value_key_tag,p.value_key_alloc,p.value_key_caller,p.value_key_equals,p.glass_scope_stackmap,bytes[p.glass_scope_stackmap],p.glass_child_stackmap,bytes[p.glass_child_stackmap]);
    printf("unlock_search=%zx unlock_create=%zx unlock_location=%zx unlock_center=%zx unlock_tag=%x unlock_zero_pp=%x unlock_debug_pp=%x\n",p.unlock_search,p.unlock_create,p.unlock_location,p.unlock_center,p.unlock_tag,p.unlock_zero_pp,p.unlock_debug_pp);
    if(argc==3) {
        // New placement contract: a missing/changed return must disable
        // admission, without depending on a symbol or source-version table.
        const uint32_t original=u32(p.bottom_margin_return);
        if(original!=0xd65f03c0u)return 1;
        const uint32_t nop=0xd503201fu;
        memcpy(bytes.data()+p.bottom_margin_return,&nop,4);
        DockGlassProfile rejected{};const char* reason=nullptr;
        if(ResolveDockGlass(image,&rejected,&reason)||!reason||rejected.phone)return 1;
        puts("Dock margin return mutation rejected");
        return 0;
    }
    unsigned checks=0;
    if(!p.recents_flight_radius||p.recents_flight_radius_slot>=0||
       !p.recents_task_radius_returns[0]||!p.recents_task_radius_returns[1])return 1;
    const auto rejects=[&](size_t at,uint32_t replacement){
        const uint32_t old=u32(at);memcpy(bytes.data()+at,&replacement,4);
        DockGlassProfile rejected{};const char* reason=nullptr;
        rejected.phone=1;rejected.clip_pp=1;rejected.stack_paint=1;
        rejected.native_clip_pp=1;rejected.parent_clip_store=1;
        const bool ok=!ResolveDockGlass(image,&rejected,&reason)&&reason&&
            !rejected.phone&&!rejected.background_call&&!rejected.clip_pp&&!rejected.stack_paint&&
            !rejected.native_clip_pp&&!rejected.parent_clip_store&&!rejected.curve_getter&&
            !rejected.recents_superellipse_tag&&
            !rejected.recents_default_radius_bits&&!rejected.recents_dispatch_start&&
            !rejected.glass_build&&!rejected.glass_scope_store&&!rejected.value_key_tag&&
            !rejected.glass_scope_stackmap&&!rejected.glass_child_stackmap&&
            !rejected.unlock_tag&&!rejected.unlock_search&&!rejected.unlock_zero_pp;
        memcpy(bytes.data()+at,&old,4);++checks;
        if(!ok)fprintf(stderr,"accepted mutation at %zx\n",at);
        return ok;
    };
    if(!rejects(p.unlock_search,0x94000000u)||
       !rejects(p.unlock_search+0xc4,0x94000000u)||
       !rejects(p.unlock_search+0xe0,0xd2800040u)||
       !rejects(p.unlock_search+0xf4,0xd503201fu)||
       !rejects(p.unlock_create+0x24,0x94000000u)||
       !rejects(p.unlock_location,0xd503201fu)||
       !rejects(p.unlock_center,0xd503201fu))return 1;
    if(!rejects(p.gate,p.gate_expected^0x20u)||!rejects(p.background_call,0x94000000u)||
       !rejects(p.mode_post,0xd503201fu)||!rejects(p.mode,0xd503201fu)||
       !rejects(p.background+8,0x94000000u)||!rejects(p.phone+0x40,0xd503201fu)||
       !rejects(p.mode_returns[0],0xd503201fu))return 1;

    if(!rejects(p.recents_task_radius_returns[0],0xd503201fu)||
       !rejects(p.recents_task_radius_returns[1],0xd503201fu)||
       !rejects(p.recents_flight_radius,0xd503201fu))return 1;
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
    if(p.glass_scope_store!=p.glass_build+0x5c||p.glass_child_scope_store!=p.glass_build+0x13c||
       p.glass_clip_alloc_call!=p.glass_build+0x154||p.glass_scope_root_slot!=-8||
       p.glass_child_root_slot!=-40||p.glass_radius_slot!=-48||
       p.glass_scope_resume!=p.glass_scope_store+4||p.glass_child_scope_resume!=p.glass_child_scope_store+4||
       p.glass_clip_alloc_resume!=p.glass_clip_alloc_call+4||p.glass_scope_expected!=u32(p.glass_scope_store)||
       p.glass_child_scope_expected!=u32(p.glass_child_scope_store)||
       p.glass_clip_alloc_expected!=u32(p.glass_clip_alloc_call)||
       (p.value_key_tag&0xfffu)!=0x11cu||!p.value_key_caller||!p.value_key_equals||
       bytes[p.glass_scope_stackmap]!=0x0f||bytes[p.glass_child_stackmap]!=0x1a)return 1;
    ++checks;
    if(!p.glass_visual_stackmap||bytes[p.glass_visual_stackmap]!=0||
       p.glass_visual_resume!=p.glass_build+0xac||
       !rejects(p.glass_visual_resume,0xd503201fu)||
       !rejects(p.glass_build+0xa8,branch_to(p.glass_build+0xa8,p.mode))||
       !rejects(p.glass_build+0x90,branch_to(p.glass_build+0x90,p.mode)))return 1;
    const auto old_visual_map=bytes[p.glass_visual_stackmap];
    bytes[p.glass_visual_stackmap]=1;
    DockGlassProfile bad_visual{};
    if(ResolveDockGlass(image,&bad_visual,&why))return 1;
    bytes[p.glass_visual_stackmap]=old_visual_map;++checks;
    // Original Glass keeps its material and physical Clip.none. Only the
    // authenticated owned child changes its inner native clip allocation.
    if(!rejects(p.glass_scope_store,u32(p.glass_scope_store)^0x1000u)||
       !rejects(p.glass_child_scope_store,u32(p.glass_child_scope_store)^1u)||
       !rejects(p.glass_clip_alloc_call,branch_to(p.glass_clip_alloc_call,p.recents_superellipse_alloc))||
       !rejects(p.glass_build+0x13c,u32(p.glass_build+0x13c)^0x1000u)||
       !rejects(p.glass_build+0x144,u32(p.glass_build+0x144)^0x1000u)||
       !rejects(p.glass_build+0x17c,u32(p.glass_build+0x17c)^0x1000u)||
       !rejects(p.glass_build+0xc0,branch_to(p.glass_build+0xc0,p.curve_getter))||
       !rejects(p.glass_build+0x138,branch_to(p.glass_build+0x138,p.recents_superellipse_alloc))||
       !rejects(call_target(p.glass_build+0x138),u32(call_target(p.glass_build+0x138))^0x20000u)||
       !rejects(p.glass_build+0xe0,branch_to(p.glass_build+0xe0,p.recents_border_radius_alloc))||
       !rejects(p.glass_build+0xf4,branch_to(p.glass_build+0xf4,p.recents_radius_alloc))||
       !rejects(p.glass_build+0x16c,u32(p.glass_build+0x16c)^0x400u)||
       !rejects(p.glass_build+0x194,u32(p.glass_build+0x194)^0x400u)||
       !rejects(p.value_key_caller+0x88,branch_to(p.value_key_caller+0x88,p.recents_radius_alloc))||
       !rejects(p.value_key_caller+0x9c,branch_to(p.value_key_caller+0x9c,p.curve_getter))||
       !rejects(p.value_key_alloc,u32(p.value_key_alloc)^0x20000u)||
       !rejects(p.value_key_alloc,u32(p.value_key_alloc)^0x200u)||
       !rejects(p.value_key_equals+0x30,branch_to(p.value_key_equals+0x30,p.curve_getter))||
       !rejects(p.value_key_equals+0x4c,u32(p.value_key_equals+0x4c)^0x4000u)||
       !rejects(p.value_key_equals+0xc4,u32(p.value_key_equals+0xc4)^0x4000u)||
       !rejects(p.glass_scope_stackmap,u32(p.glass_scope_stackmap)^1u)||
       !rejects(p.glass_child_stackmap,u32(p.glass_child_stackmap)^0x10u))return 1;
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
    if(!p.curve_getter||!p.recents_adaptive_clip||
       (p.recents_radius_tag&0xfffu)!=0x21cu||(p.recents_border_radius_tag&0xfffu)!=0x21cu||
       (p.recents_superellipse_tag&0xfffu)!=0x21cu||(p.recents_rrect_tag&0xfffu)!=0x21cu||
       p.recents_default_radius_bits!=0x4034000000000000ull||
       p.recents_stacked_min_radius_bits!=0x403e000000000000ull||
       !p.recents_dispatch_start||p.recents_dispatch_end<=p.recents_dispatch_start)return 1;
    ++checks;
    // Complete getter, capability resolver and size-aware eligibility must stay
    // linked. Neither the cache slot nor a lookalike helper is interchangeable.
    if(!rejects(p.curve_getter+0x28,branch_to(p.curve_getter+0x28,p.recents_enabled_for_radius))||
       !rejects(p.curve_getter+0x10,u32(p.curve_getter+0x10)^0x400u)||
       !rejects(p.curve_getter+0x64,u32(p.curve_getter+0x64)^0x400u)||
       !rejects(p.curve_getter+0x24,0xd503201fu)||
       !rejects(p.recents_eligibility,0xd503201fu)||
       !rejects(p.recents_enabled_for+0x44,branch_to(p.recents_enabled_for+0x44,p.curve_getter))||
       !rejects(p.recents_enabled_for_radius+0x14,branch_to(p.recents_enabled_for_radius+0x14,p.recents_enabled_for))||
       !rejects(p.recents_enabled_for_radius+0x48,u32(p.recents_enabled_for_radius+0x48)^0x1000u))return 1;

    size_t super_call=0,rect_call=0,first_curve_pool=0;
    for(size_t at=p.recents_adaptive_clip;at<p.recents_adaptive_clip+0x110;at+=4) {
        if((u32(at)&0xfc000000u)==0x94000000u) {
            if(call_target(at)==p.recents_superellipse_alloc)super_call=at;
            if(call_target(at)==p.recents_rrect_alloc)rect_call=at;
        }
        if((u32(at)&0xffc003e0u)==0x91400360u&&pool(at)==p.recents_clip_pp&&!first_curve_pool)first_curve_pool=at;
    }
    if(!super_call||!rect_call||!first_curve_pool)return 1;
    // Widget CID -> createRenderObject -> render allocation CID -> updater
    // guard. Valid-sized headers with unrelated CIDs must still be rejected.
    if(!rejects(super_call,branch_to(super_call,p.recents_rrect_alloc))||
       !rejects(rect_call,branch_to(rect_call,p.recents_superellipse_alloc))||
       !rejects(first_curve_pool+4,u32(first_curve_pool+4)^0x400u)||
       !rejects(p.recents_superellipse_alloc,u32(p.recents_superellipse_alloc)^0x20000u)||
       !rejects(p.recents_superellipse_alloc,u32(p.recents_superellipse_alloc)^0x200u)||
       !rejects(p.recents_rrect_alloc,u32(p.recents_rrect_alloc)^0x20000u)||
       !rejects(p.recents_radius_alloc,u32(p.recents_radius_alloc)^0x200u)||
       !rejects(p.recents_border_radius_alloc,u32(p.recents_border_radius_alloc)^0x200u)||
       !rejects(p.recents_radius_caller+0xf0,branch_to(p.recents_radius_caller+0xf0,p.recents_border_radius_alloc))||
       !rejects(p.recents_dispatch_start,0u))return 1;

    if(!rejects(p.recents_stacked_radius+0x14,u32(p.recents_stacked_radius+0x14)^0x2000u)||
       !rejects(p.recents_stacked_radius+0x24,u32(p.recents_stacked_radius+0x24)^0x2000u)||
       !rejects(p.recents_stacked_radius+0x44,0xd503201fu))return 1;
    // Coherent changes to the native ordinary and threshold constants are
    // admitted and exported. This rules out production constant allowlists.
    const size_t ordinary_sites[]={p.recents_stacked_radius+0x24,p.recents_task_radius+0x9c,p.recents_radius_caller+0xd4};
    uint32_t ordinary_words[3]={};
    for(size_t i=0;i<3;++i){ordinary_words[i]=u32(ordinary_sites[i]);write32(ordinary_sites[i],ordinary_words[i]^0x2000u);}
    DockGlassProfile changed{};const char* changed_reason=nullptr;++checks;
    if(!ResolveDockGlass(image,&changed,&changed_reason)||changed_reason||
       changed.recents_default_radius_bits!=0x4035000000000000ull)return 1;
    for(size_t i=0;i<3;++i)write32(ordinary_sites[i],ordinary_words[i]);
    const uint32_t threshold_a=u32(p.recents_stacked_radius+0x14),threshold_b=u32(p.recents_stacked_radius+0x40);
    write32(p.recents_stacked_radius+0x14,threshold_a^0x2000u);write32(p.recents_stacked_radius+0x40,threshold_b^0x2000u);
    changed={};changed_reason=nullptr;++checks;
    if(!ResolveDockGlass(image,&changed,&changed_reason)||changed_reason||
       changed.recents_stacked_min_radius_bits!=0x403f000000000000ull)return 1;
    write32(p.recents_stacked_radius+0x14,threshold_a);write32(p.recents_stacked_radius+0x40,threshold_b);

    // Coherently move the actual clip token at both allocation paths and the
    // superclass constructor edge. A source token is not an enum ordinal.
    std::vector<std::pair<size_t,std::pair<uint32_t,uint32_t>>> curve_pools;
    for(const auto& span:std::vector<std::pair<size_t,size_t>>{
            {p.recents_adaptive_clip,0x110},{p.recents_superellipse_create,0xa0},
            {p.recents_superellipse_constructor,0xa4},{p.glass_build,0x1e4}}) {
        for(size_t at=span.first;at+8<=span.first+span.second;at+=4) {
            if((u32(at)&0xffc003e0u)!=0x91400360u||
               (u32(at+4)&0xffc00000u)!=0xf9400000u||pool(at)!=p.recents_clip_pp)continue;
            bool seen=false;for(const auto& saved:curve_pools)if(saved.first==at)seen=true;
            if(!seen)curve_pools.push_back({at,{u32(at),u32(at+4)}});
        }
    }
    if(curve_pools.size()<5)return 1;
    for(const auto& saved:curve_pools)set_pool(saved.first,p.recents_clip_pp+8);
    changed={};changed_reason=nullptr;++checks;
    if(!ResolveDockGlass(image,&changed,&changed_reason)||changed_reason||changed.recents_clip_pp!=p.recents_clip_pp+8)return 1;
    for(const auto& saved:curve_pools){write32(saved.first,saved.second.first);write32(saved.first+4,saved.second.second);}
    // Coherently relocate the two source operands: no fixture/PP allowlist.
    const uint32_t shifted_none=p.clip_pp+8;
    const uint32_t paint_pool[2]={u32(p.stack_paint+0x28),u32(p.stack_paint+0x2c)};
    const uint32_t push_pool[2]={u32(p.push_clip_rect+0x108),u32(p.push_clip_rect+0x10c)};
    const uint32_t glass_pool[2]={u32(p.glass_build+0x190),u32(p.glass_build+0x194)};
    set_pool(p.stack_paint+0x28,shifted_none);set_pool(p.push_clip_rect+0x108,shifted_none);
    set_pool(p.glass_build+0x190,shifted_none);
    if(!accepts(image,shifted_none))return 1;
    set_pool(p.stack_paint+0x28,original_clip);set_pool(p.push_clip_rect+0x108,original_clip);
    DockGlassProfile alias{};const char* alias_reason=nullptr;++checks;
    if(ResolveDockGlass(image,&alias,&alias_reason)||!alias_reason||alias.clip_pp||alias.phone)return 1;
    write32(p.stack_paint+0x28,paint_pool[0]);write32(p.stack_paint+0x2c,paint_pool[1]);
    write32(p.push_clip_rect+0x108,push_pool[0]);write32(p.push_clip_rect+0x10c,push_pool[1]);
    write32(p.glass_build+0x190,glass_pool[0]);write32(p.glass_build+0x194,glass_pool[1]);

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
            {p.stack_create,0x94},{p.stack_constructor,0x124},
            {p.glass_build,0x1e4},{p.value_key_caller,0xac},{p.value_key_equals,0x108}}) {
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

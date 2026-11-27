// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_hooks.h"
#include "dock_glass_profile.h"
#include "dock_glass_bridge.h"
#include "dock_glass_refresh.h"
#include "dock_glass_modes.h"
#include "lsposed_hook_backend.h"
#include "monet_policy_server.h"
#include "verified_elf_image.h"
#include <android/log.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
extern "C" const uint8_t kh_dock_payload_end[];
extern "C" { uint32_t kh_dock_material_mode=0; uint32_t kh_dock_superellipse_enabled=0; uint64_t kh_dock_recents_corner_bits=0; uint64_t kh_dock_glass_scope_counts[3]={}; }
namespace {
uint32_t phase=0;
bool Reject(const char* why) {
    __android_log_print(ANDROID_LOG_WARN, "KeiHomeDock", "Dock inactive: %s", why);
    return false;
}
VerifiedDartImage loaded{};
DockGlassProfile profile{};
DockGlassRefreshProfile refresh{};
struct Patch { size_t at; uint32_t expected; const uint8_t* entry; bool link; };
uint32_t Jump(uintptr_t from,uintptr_t to,bool link) {
    const int64_t delta=int64_t(to)-int64_t(from);
    if((delta&3)||delta < -0x8000000 || delta>=0x8000000)return 0;
    return (link?0x94000000u:0x14000000u)|(uint32_t(delta/4)&0x3ffffffu);
}
} // namespace
bool PrepareDockGlassForVerifiedImage(const VerifiedDartImage& verified) {
    uint32_t expected=0;
    if(!__atomic_compare_exchange_n(&phase,&expected,1u,false,__ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE))return expected>=2;
    if(!verified.valid)return Reject("missing live image");
    loaded=verified;
    const IconHighlightImage image=loaded.HighlightView();
    const char* why=nullptr;
    if(!ResolveDockGlass(image,&profile,&why)||!ResolveDockGlassRefresh(image,profile.labels,&refresh,&why))
        return Reject(why?why:"incomplete image");
    __atomic_store_n(&phase,2u,__ATOMIC_RELEASE);return true;
}
bool PrepareDockGlassAtLibraryLoad(void* handle) {
    VerifiedDartImage verified{};
    if(!BuildVerifiedDartImage(handle,&verified,/*compute_sha256=*/false))return Reject("missing live image");
    return PrepareDockGlassForVerifiedImage(verified);
}
bool InstallPreparedDockGlass() {
    uint32_t expected=2;
    if(!__atomic_compare_exchange_n(&phase,&expected,3u,false,__ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE))return expected==4;
    const auto& p=profile;const auto& r=refresh;const auto& l=p.labels;
    const size_t bytes=size_t(kh_dock_payload_end-kh_dock_template);
    const long page=sysconf(_SC_PAGESIZE);
    if(page<4096||page>65536||(page&(page-1))||bytes>8192)return Reject("Dock template ABI");
    const size_t mapping_bytes=(bytes+size_t(page)-1u)&~(size_t(page)-1u);
    // Extended ClipRRect initialization writes past the smaller
    // ClipRSuperellipse layout. Keep its stock allocator and radius path.
    const bool replaceClipAllocator = (p.recents_rrect_tag & 0xfffu) == 0x21cu;
    const Patch patches[]={
        {p.bottom_margin_return,0xd65f03c0u,kh_dock_bottom_margin,false},
        {p.recents_flight_radius,p.recents_flight_radius_expected,kh_dock_recents_flight_radius,false},
        {p.recents_task_radius_returns[0],0xd65f03c0u,kh_dock_recents_radius,false},
        {p.recents_task_radius_returns[1],0xd65f03c0u,kh_dock_recents_radius,false},
        {p.glass_scope_store,p.glass_scope_expected,kh_dock_glass_scope,false},
        {p.glass_child_scope_store,p.glass_child_scope_expected,kh_dock_glass_child_scope,false},
        {p.glass_clip_alloc_call,p.glass_clip_alloc_expected,kh_dock_glass_clip,true},
        {p.parent_clip_store,0xb801b001u,kh_dock_parent_clip,false},
        {p.gate,p.gate_expected,kh_dock_gate,false},
        {p.background_call,p.background_expected,kh_dock_background,true},
        {r.second_event_call,r.second_event_expected,kh_dock_refresh_event,true},
        {r.getter_value_call,r.getter_value_expected,kh_dock_refresh_value,true},
        {p.mode_post,p.mode_post_expected,kh_dock_mode_post,false},
    };
    uint8_t* bridge=nullptr;
    const uintptr_t aligned=(loaded.base+p.gate)&~uintptr_t(page-1);
    for(uintptr_t distance=0x1000000;distance<0x7000000&&!bridge;distance+=0x1000000) {
        for(int sign=-1;sign<=1;sign+=2) {
            const uintptr_t hint=sign>0?aligned+distance:aligned-distance;
            void* memory=mmap(reinterpret_cast<void*>(hint),mapping_bytes,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
            if(memory==MAP_FAILED)continue;
            bool near=true;for(const auto& patch:patches) {
                if(!replaceClipAllocator && patch.entry==kh_dock_glass_clip) continue;
                const uintptr_t to=reinterpret_cast<uintptr_t>(memory)+size_t(patch.entry-kh_dock_template);
                if(!Jump(loaded.base+patch.at,to,patch.link)){near=false;break;}
            }
            if(near){bridge=static_cast<uint8_t*>(memory);break;}
            munmap(memory,mapping_bytes);
        }
    }
    if(!bridge)return Reject("near Dock mapping unavailable");
    memcpy(bridge,kh_dock_template,bytes);
    // One dead register and unchanged NZCV: XOR each contiguous CID bit run.
    // A 20-bit CID needs at most ten AArch64 logical-immediate instructions.
    const size_t cid_at=size_t(kh_dock_parent_cid-kh_dock_template);
    const uint32_t cid=p.sized_box_tag>>12u;
    size_t cid_word=0;
    for(unsigned bit=0;bit<20u;) {
        if(!(cid&(1u<<bit))){++bit;continue;}
        const unsigned first=bit;
        while(bit<20u&&(cid&(1u<<bit)))++bit;
        const uint32_t instruction=0x52000021u|(((32u-first)&31u)<<16u)|((bit-first-1u)<<10u);
        if(cid_word>=10u||cid_at+cid_word*4u+4u>bytes) {
            munmap(bridge,mapping_bytes);return Reject("parent CID encoding");
        }
        memcpy(bridge+cid_at+cid_word++*4u,&instruction,4);
    }
    const DockGlassBridgeConfig render={
        reinterpret_cast<uintptr_t>(&kh_dock_glass_enabled),loaded.base+p.gate+4,loaded.base+p.skip,loaded.base+p.background,
        l.grid_slot,l.config_rx_field,l.rx_value_field,p.device_slot,
        reinterpret_cast<uintptr_t>(&kh_dock_material_mode),
        {p.material_tags[0],p.material_tags[1],p.material_tags[2],p.material_tags[3]},
        p.material_config_pp,p.positioned_tag,p.stack_tag,p.sized_box_tag,p.background_tag,
        p.alignment_pp,p.fit_pp,p.clip_pp,p.double_tag,p.launcher_mode_slot,p.launcher_mode_rx,p.elderly_pp,p.device_layout_field,p.corner_field,
        p.ink_metrics_field,p.ink_width_field,reinterpret_cast<uintptr_t>(&kh_dock_glass_corner_q8),
        p.ink_top_field,p.cell_height_field,p.no_word_rx_field,p.no_word_suppress_rx_field,p.ignore_pointer_tag,
        p.native_clip_pp,loaded.base+p.parent_clip_store+4,
        reinterpret_cast<uintptr_t>(&kh_dock_glass_corner_style),
        reinterpret_cast<uintptr_t>(&kh_dock_superellipse_enabled),p.value_key_tag,
        loaded.base+p.recents_superellipse_alloc,loaded.base+p.recents_rrect_alloc,
        loaded.base+p.glass_scope_store+4,loaded.base+p.glass_child_scope_store+4,
        uint64_t(int64_t(p.glass_radius_slot)),p.recents_default_radius_bits,p.recents_stacked_min_radius_bits,
        uint64_t(int64_t(p.glass_scope_root_slot)),uint64_t(int64_t(p.glass_child_root_slot)),
        reinterpret_cast<uintptr_t>(&kh_dock_recents_corner_bits),reinterpret_cast<uintptr_t>(&kh_dock_glass_scope_counts),
        loaded.base+p.recents_flight_radius+4,uint64_t(int64_t(p.recents_flight_radius_slot)),loaded.base+p.glass_visual_resume,
        p.unlock_tag,p.unlock_zero_pp,p.unlock_debug_pp
    };
    const DockGlassRefreshConfig refresh_config={
        reinterpret_cast<uintptr_t>(&kh_dock_glass_installed),reinterpret_cast<uintptr_t>(&kh_dock_glass_revision),
        reinterpret_cast<uintptr_t>(&kh_dock_glass_notified),loaded.base+r.second_event_target,loaded.base+l.config,
        loaded.base+r.second_event_call+4,loaded.base+l.value,loaded.base+r.refresh,
        l.grid_slot,l.config_rx_field,l.rx_value_field,r.rx_subject_field,r.observer_slot
    };
    const DockGlassModeConfig mode={loaded.base+p.mode_post,loaded.base+p.mode_post+4,loaded.base+p.mode,
        reinterpret_cast<uintptr_t>(&kh_dock_glass_enabled),reinterpret_cast<uintptr_t>(&kh_dock_material_mode),p.mode_slot,
        loaded.base+p.curve_getter,reinterpret_cast<uintptr_t>(&kh_dock_superellipse_enabled),
        reinterpret_cast<uintptr_t>(&kh_dock_glass_corner_style)};
    memcpy(bridge+size_t(kh_dock_mode_replay-kh_dock_template),&p.mode_post_expected,4);
    const auto copy=[&](const uint8_t* config,const void* data,size_t size) {
        const size_t offset=size_t(config-kh_dock_template);
        if(offset>bytes||size>bytes-offset)return false;
        memcpy(bridge+offset,data,size);return true;
    };
    if(!copy(kh_dock_config,&render,sizeof(render))||!copy(kh_dock_refresh_config,&refresh_config,sizeof(refresh_config))||
       !copy(kh_dock_mode_config,&mode,sizeof(mode))||mprotect(bridge,mapping_bytes,PROT_READ|PROT_EXEC)) {
        munmap(bridge,mapping_bytes);return Reject("Dock mapping publication failed");
    }
    __builtin___clear_cache(reinterpret_cast<char*>(bridge),reinterpret_cast<char*>(bridge)+bytes);
    // Original image was authenticated before label publication. Only our own
    // atomic 4-byte sites are touched here; the label-owned first event stays.
    for(const auto& patch:patches) {
        if(!replaceClipAllocator && patch.entry==kh_dock_glass_clip) continue;
        const uintptr_t to=reinterpret_cast<uintptr_t>(bridge)+size_t(patch.entry-kh_dock_template);
        if(PatchArm64Instruction(reinterpret_cast<void*>(loaded.base+patch.at),patch.expected,
                Jump(loaded.base+patch.at,to,patch.link))!=0) {
            // Keep the mapping alive: already-published disabled entries all
            // delegate to the original native code and must remain executable.
            return Reject("Dock exact instruction changed during publication");
        }
    }
    __atomic_store_n(&kh_dock_glass_installed,1u,__ATOMIC_RELEASE);
    __atomic_store_n(&phase,4u,__ATOMIC_RELEASE);
    __android_log_print(ANDROID_LOG_INFO,"KeiHomeDock","Dock native glass installed: render, native unlock, material selection, revision refresh; phone=%zx mode=%zx",p.phone,p.mode);
    return true;
}

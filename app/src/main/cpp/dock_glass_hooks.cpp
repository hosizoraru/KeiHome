// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_hooks.h"
#include "dock_glass_profile.h"
#include "dock_glass_bridge.h"
#include "dock_glass_refresh.h"
#include "dock_glass_modes.h"
#include "lsposed_hook_backend.h"
#include "monet_policy_server.h"
#include <android/log.h>
#include <dlfcn.h>
#include <link.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
extern "C" const uint8_t kh_dock_payload_end[];
extern "C" { uint32_t kh_dock_material_mode=0; }
namespace {
uint32_t phase=0;
struct Loaded { uintptr_t base; IconHighlightRange ranges[16]; size_t count, span; bool valid; };
bool Readable(uintptr_t start, size_t size, bool exec) {
    FILE* file = fopen("/proc/self/maps", "re"); if (!file) return false;
    const uintptr_t end = start + size; uintptr_t cursor = start; char line[768];
    while (cursor < end && fgets(line, sizeof(line), file)) {
        unsigned long long a = 0, b = 0; char p[5] = {};
        if (sscanf(line, "%llx-%llx %4s", &a, &b, p) != 3 || b <= cursor) continue;
        if (a > cursor || p[0] != 'r' || (exec && p[2] != 'x')) break;
        cursor = b < end ? uintptr_t(b) : end;
    }
    fclose(file); return cursor == end;
}
int FindImage(dl_phdr_info* info, size_t, void* opaque) {
    auto& im = *static_cast<Loaded*>(opaque); if (info->dlpi_addr != im.base) return 0;
    if (info->dlpi_phnum > 64) return 1;
    for (size_t i = 0; i < info->dlpi_phnum; ++i) {
        const auto& p = info->dlpi_phdr[i];
        if (p.p_type != PT_LOAD || !(p.p_flags & PF_R) || !p.p_memsz) continue;
        if (im.count == 16 || p.p_vaddr > UINTPTR_MAX - im.base ||
                p.p_memsz > UINTPTR_MAX - im.base - p.p_vaddr ||
                p.p_vaddr + p.p_memsz > 128u*1024u*1024u) return 1;
        const bool exec = p.p_flags & PF_X;
        if (!Readable(im.base + p.p_vaddr, p.p_memsz, exec)) return 1;
        const size_t end = p.p_vaddr + p.p_memsz;
        im.ranges[im.count++] = {size_t(p.p_vaddr), end, exec};
        if (end > im.span) im.span = end;
    }
    im.valid = im.count != 0; return 1;
}
bool Reject(const char* why) {
    __android_log_print(ANDROID_LOG_WARN, "KeiHomeDock", "Dock inactive: %s", why);
    return false;
}
Loaded loaded{};
DockGlassProfile profile{};
DockGlassRefreshProfile refresh{};
struct Patch { size_t at; uint32_t expected; const uint8_t* entry; bool link; };
uint32_t Jump(uintptr_t from,uintptr_t to,bool link) {
    const int64_t delta=int64_t(to)-int64_t(from);
    if((delta&3)||delta < -0x8000000 || delta>=0x8000000)return 0;
    return (link?0x94000000u:0x14000000u)|(uint32_t(delta/4)&0x3ffffffu);
}
} // namespace
bool PrepareDockGlassAtLibraryLoad(void* handle) {
    uint32_t expected=0;
    if(!__atomic_compare_exchange_n(&phase,&expected,1u,false,__ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE))return expected>=2;
    void* snapshot=handle?dlsym(handle,"_kDartIsolateSnapshotInstructions"):nullptr;
    Dl_info info{};
    if(!snapshot||!dladdr(snapshot,&info)||!info.dli_fbase)return Reject("missing live image");
    loaded.base=reinterpret_cast<uintptr_t>(info.dli_fbase);
    dl_iterate_phdr(FindImage,&loaded);
    const IconHighlightImage image{reinterpret_cast<const uint8_t*>(loaded.base),loaded.span,loaded.ranges,loaded.count};
    const char* why=nullptr;
    if(!loaded.valid||!ResolveDockGlass(image,&profile,&why)||!ResolveDockGlassRefresh(image,profile.labels,&refresh,&why))
        return Reject(why?why:"incomplete image");
    __atomic_store_n(&phase,2u,__ATOMIC_RELEASE);return true;
}
bool InstallPreparedDockGlass() {
    uint32_t expected=2;
    if(!__atomic_compare_exchange_n(&phase,&expected,3u,false,__ATOMIC_ACQ_REL,__ATOMIC_ACQUIRE))return expected==4;
    const auto& p=profile;const auto& r=refresh;const auto& l=p.labels;
    const size_t bytes=size_t(kh_dock_payload_end-kh_dock_template);
    const long page=sysconf(_SC_PAGESIZE);
    if(page<4096||page>65536||(page&(page-1))||bytes>size_t(page))return Reject("Dock template ABI");
    const Patch patches[]={
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
            void* memory=mmap(reinterpret_cast<void*>(hint),size_t(page),PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
            if(memory==MAP_FAILED)continue;
            bool near=true;for(const auto& patch:patches) {
                const uintptr_t to=reinterpret_cast<uintptr_t>(memory)+size_t(patch.entry-kh_dock_template);
                if(!Jump(loaded.base+patch.at,to,patch.link)){near=false;break;}
            }
            if(near){bridge=static_cast<uint8_t*>(memory);break;}
            munmap(memory,size_t(page));
        }
    }
    if(!bridge)return Reject("near Dock mapping unavailable");
    memcpy(bridge,kh_dock_template,bytes);
    const DockGlassBridgeConfig render={
        reinterpret_cast<uintptr_t>(&kh_dock_glass_enabled),loaded.base+p.gate+4,loaded.base+p.skip,loaded.base+p.background,
        l.grid_slot,l.config_rx_field,l.rx_value_field,p.device_slot,
        reinterpret_cast<uintptr_t>(&kh_dock_material_mode),
        {p.material_tags[0],p.material_tags[1],p.material_tags[2],p.material_tags[3]},
        p.material_config_pp,p.positioned_tag,p.stack_tag,p.sized_box_tag,p.background_tag,
        p.alignment_pp,p.fit_pp,p.clip_pp,p.double_tag,p.launcher_mode_slot,p.launcher_mode_rx,p.elderly_pp,p.device_layout_field,p.corner_field,
        p.ink_metrics_field,p.ink_width_field,reinterpret_cast<uintptr_t>(&kh_dock_glass_corner_q8),
        p.ink_top_field,p.cell_height_field,p.no_word_rx_field,p.no_word_suppress_rx_field,p.ignore_pointer_tag
    };
    const DockGlassRefreshConfig refresh_config={
        reinterpret_cast<uintptr_t>(&kh_dock_glass_installed),reinterpret_cast<uintptr_t>(&kh_dock_glass_revision),
        reinterpret_cast<uintptr_t>(&kh_dock_glass_notified),loaded.base+r.second_event_target,loaded.base+l.config,
        loaded.base+r.second_event_call+4,loaded.base+l.value,loaded.base+r.refresh,
        l.grid_slot,l.config_rx_field,l.rx_value_field,r.rx_subject_field,r.observer_slot
    };
    const DockGlassModeConfig mode={loaded.base+p.mode_post,loaded.base+p.mode_post+4,loaded.base+p.mode,
        reinterpret_cast<uintptr_t>(&kh_dock_glass_enabled),reinterpret_cast<uintptr_t>(&kh_dock_material_mode),p.mode_slot};
    memcpy(bridge+size_t(kh_dock_mode_replay-kh_dock_template),&p.mode_post_expected,4);
    const auto copy=[&](const uint8_t* config,const void* data,size_t size) {
        const size_t offset=size_t(config-kh_dock_template);
        if(offset>bytes||size>bytes-offset)return false;
        memcpy(bridge+offset,data,size);return true;
    };
    if(!copy(kh_dock_config,&render,sizeof(render))||!copy(kh_dock_refresh_config,&refresh_config,sizeof(refresh_config))||
       !copy(kh_dock_mode_config,&mode,sizeof(mode))||mprotect(bridge,size_t(page),PROT_READ|PROT_EXEC)) {
        munmap(bridge,size_t(page));return Reject("Dock mapping publication failed");
    }
    __builtin___clear_cache(reinterpret_cast<char*>(bridge),reinterpret_cast<char*>(bridge)+bytes);
    // Original image was authenticated before label publication. Only our own
    // atomic 4-byte sites are touched here; the label-owned first event stays.
    for(const auto& patch:patches) {
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
    __android_log_print(ANDROID_LOG_INFO,"KeiHomeDock","Dock native glass installed: render, native material selection, revision refresh; phone=%zx mode=%zx",p.phone,p.mode);
    return true;
}

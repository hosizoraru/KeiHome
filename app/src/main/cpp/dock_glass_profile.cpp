// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_profile.h"
#include <string.h>
#ifdef KH_DOCK_PROFILE_DIAGNOSTIC
#include <stdio.h>
#endif
namespace {
struct DockWord { uint32_t word, mask; };
struct DockPattern { const DockWord* words; size_t count; };
#include "dock_glass_patterns.inc"
bool Contains(const IconHighlightImage& im, size_t p, size_t n) {
    for (size_t i=0;i<im.range_count;++i) { const auto& r=im.ranges[i];
        if (r.executable && p>=r.begin && p<=r.end && n<=r.end-p) return true; }
    return false;
}
uint32_t Word(const IconHighlightImage& im, size_t p) {
    uint32_t w=0; if (Contains(im,p,4)) memcpy(&w,im.base+p,4); return w;
}
bool Match(const IconHighlightImage& im,size_t p,const DockPattern& pat) {
    if (!Contains(im,p,pat.count*4)) return false;
    for(size_t j=0;j<pat.count;++j) if((Word(im,p+4*j)&pat.words[j].mask)!=pat.words[j].word) return false;
    return true;
}
template<size_t N> size_t Find(const IconHighlightImage& im,const DockPattern(&v)[N]) {
    size_t found=0;
    for(size_t i=0;i<im.range_count;++i) {const auto& r=im.ranges[i];if(!r.executable)continue;
        for(size_t at=r.begin;at+4<=r.end;at+=4) {
            bool ok=false;for(const auto& p:v) if((Word(im,at)&p.words[0].mask)==p.words[0].word&&Match(im,at,p)){ok=true;break;}
            if(ok){if(found&&found!=at)return 0;found=at;}
        }
    }return found;
}
size_t Branch(const IconHighlightImage& im,size_t pc,bool link=true) {
    uint32_t w=Word(im,pc);if((w&0xfc000000u)!=(link?0x94000000u:0x14000000u))return 0;
    int64_t n=w&0x3ffffffu;if(n&0x2000000)n-=0x4000000;
    int64_t to=int64_t(pc)+n*4;return to>0&&Contains(im,size_t(to),4)?size_t(to):0;
}
uint32_t Tag(const IconHighlightImage& im,size_t at) {
    uint32_t a=Word(im,at),b=Word(im,at+4);
    if((a&0xffe0001fu)!=0xd2800002u||(b&0xffe0001fu)!=0xf2a00002u||!Branch(im,at+8,false))return 0;
    const size_t alloc=Branch(im,at+8,false);
    // Authenticated native allocator entry: parameter tag, native young-space
    // bounds, initialization and original VM slow path are never called here.
    if(Word(im,alloc)!=0xd3482c44u || Word(im,alloc+4)!=0xd37cec84u || Word(im,alloc+8)!=0xa9461740u)return 0;
    return (a>>5&65535u)|(b>>5&65535u)<<16;
}
uint32_t Pool(const IconHighlightImage& im,size_t at) {
    const uint32_t a=Word(im,at),b=Word(im,at+4);
    if((a&0xffc003e0u)!=0x91400360u||(b&0xffc00000u)!=0xf9400000u || ((b>>5)&31)!=(a&31))return 0;
    return ((a>>10&4095u)<<12)+(b>>10&4095u)*8;
}
uint32_t Slot(uint32_t w){return (w>>10&4095u)*8;}
bool Fail(const char** why,const char* s){if(why)*why=s;return false;}
bool ResolveClipNone(const IconHighlightImage& im,DockGlassProfile& p,
                     uint32_t original_clip_pp,const char** why) {
    // These full mapped roles authenticate both alternatives: equality to the
    // pool operand paints directly, while non-none plus layout overflow calls
    // pushClipRect. No snapshot object/enum ordinal or pool-neighbor inference.
    p.stack_paint=Find(im,kDockStackPaint);
    p.push_clip_rect=Find(im,kDockPushClipRect);
    p.stack_create=Find(im,kDockStackCreate);
    p.stack_constructor=Find(im,kDockStackRenderConstructor);
    if(!p.stack_paint||!p.push_clip_rect||!p.stack_create||!p.stack_constructor)
        return Fail(why,"ambiguous Dock clip instruction roles");
    p.clip_pp=Pool(im,p.stack_paint+0x28);
    if(!p.clip_pp||Pool(im,p.push_clip_rect+0x108)!=p.clip_pp||
       Branch(im,p.stack_paint+0x120)!=p.push_clip_rect)
        return Fail(why,"native Stack/painting no-clip graph");

    // Stack.createRenderObject reads widget+1b into x3 and forwards that exact
    // saved argument to this constructor, which stores it in the paint field.
    // The allocation CID must also equal paint's native RenderStack type guard.
    const uint32_t render_tag=Tag(im,Branch(im,p.stack_create+0x64));
    const uint32_t cid=Word(im,p.stack_paint+0x90);
    const uint32_t clip_store=Word(im,p.stack_constructor+0xcc);
    const uint32_t clip_load=Word(im,p.stack_paint+0x20);
    if(Branch(im,p.stack_create+0x80)!=p.stack_constructor||!render_tag||
       (render_tag&255u)!=0x1cu||
       ((render_tag>>8&15u)*16u)<(clip_load>>12&511u)+4u||
       (cid&0xffe0001fu)!=0xd2800011u||(cid>>5&65535u)!=(render_tag>>12)||
       (clip_store&0xffe00fffu)!=0xb8000020u||
       (clip_load&0xffe00fffu)!=0xb8400041u||
       (clip_store>>12&511u)!=(clip_load>>12&511u)||
       (Word(im,p.stack_paint+0xf4)>>12&511u)!=(clip_load>>12&511u))
        return Fail(why,"native Stack clip creation/forwarding graph");

    // The original Hotseat value must be pushClipRect's actual default, not
    // an arbitrary pool operand that happens to differ from the none sentinel.
    // Reviewed argument-parser forms save that default in x8/-10 or x4/-8.
    uint32_t default_clip_pp=0;
    for(size_t at=p.push_clip_rect;at<p.push_clip_rect+0x104;at+=4) {
        const bool old_default=(Word(im,at)&31u)==8u&&
            (Word(im,at+4)&31u)==8u&&Word(im,at+8)==0xd2800007u&&
            Word(im,at+12)==0xf81f03a8u;
        const bool new_default=(Word(im,at)&31u)==4u&&
            (Word(im,at+4)&31u)==4u&&Word(im,at+8)==0xf81f83a4u;
        const uint32_t pool=(old_default||new_default)?Pool(im,at):0;
        if(pool) {
            if(default_clip_pp)return Fail(why,"multiple native clip defaults");
            default_clip_pp=pool;
        }
    }
    if(!original_clip_pp||original_clip_pp!=default_clip_pp||original_clip_pp==p.clip_pp)
        return Fail(why,"native Hotseat/default clip consistency");
    return true;
}
}
bool ResolveDockGlass(const IconHighlightImage& im,DockGlassProfile* out,const char** why) {
    if(why)*why=nullptr;if(!out)return Fail(why,"missing Dock output");*out={};
    DockGlassProfile p{};
    if(!ResolveLauncherLabels(im,&p.labels,why))return false;
    p.phone=Find(im,kDockPhone);
    p.background=0;
    const size_t outer=Find(im,kDockOuter),width=Find(im,kDockCellWidth), max=Find(im,kDockMaxCount);
    const size_t scroll=Find(im,kDockScrollBuild);
    const size_t sizebox=Find(im,kDockSizedBox),pos=Find(im,kDockPositioned),corner=Find(im,kDockCorner);
    const size_t material=Find(im,kDockMaterialFactory);p.mode=Find(im,kDockMode);const size_t elderly=Branch(im,max+8);
    const size_t sync=Find(im,kDockSync), ink=Find(im,kDockInk),height=Find(im,kDockCellHeight);
    const size_t no_word=Branch(im,ink+0x60);
    bool no_word_shape=false;for(const auto& pat:kDockNoWord) if(Match(im,no_word,pat))no_word_shape=true;
    bool elderly_shape=false;for(const auto& pat:kDockElderly) if(Match(im,elderly,pat))elderly_shape=true;
#ifdef KH_DOCK_PROFILE_DIAGNOSTIC
    fprintf(stderr,"roles phone=%zx bg=%zx outer=%zx width=%zx max=%zx box=%zx pos=%zx corner=%zx mat=%zx mode=%zx\n",p.phone,p.background,outer,width,max,sizebox,pos,corner,material,p.mode);
#endif
    if(!p.phone||!outer||!width||!max||!sizebox||!pos||!corner||!scroll||!material||!p.mode||!elderly_shape||!sync||!ink||!height||!no_word_shape)
        return Fail(why,"ambiguous Dock instruction roles");
    uint32_t original_clip_pp=0;
    for(size_t at=p.phone;at<p.phone+0x4c0;at+=4){
        const size_t target=Branch(im,at);
        bool bg=false;for(const auto& pattern:kDockBackground) if(target&&Match(im,target,pattern))bg=true;
        if(bg){if(p.background_call)return Fail(why,"multiple background calls");p.background_call=at;p.background=target;}
        if(Word(im,at+12)==0xb800f001u && Word(im,at+24)==0xb8017001u &&
           Word(im,at+36)==0xb801b001u && Word(im,at+44)==0xb800b001u && Branch(im,at)) {
            if(p.stack_tag)return Fail(why,"multiple native Stacks");p.stack_tag=Tag(im,Branch(im,at));
            p.alignment_pp=Pool(im,at+4);p.fit_pp=Pool(im,at+16);
            p.stack_clip_load=at+28;original_clip_pp=Pool(im,p.stack_clip_load);
            p.parent_clip_store=at+36;
        }
    }
    if(!p.background_call)return Fail(why,"background call absent");
    // The enabled-only parent leaf may use x1 without spilling or touching
    // NZCV: the original store consumes w1, and the very next instruction
    // overwrites all of x1 with the native root list from its saved FP slot.
    // Bind that reload to the initial list save; x0 stays the allocated Stack.
    const uint32_t root_save=Word(im,p.phone+0x160);
    const uint32_t root_load=Word(im,p.parent_clip_store+4);
    if(!p.parent_clip_store||Word(im,p.parent_clip_store)!=0xb801b001u||
       (root_save&0xffe00fffu)!=0xf80003a2u||
       (root_load&0xffe00fffu)!=0xf84003a1u||
       (root_save>>12&511u)!=(root_load>>12&511u)||
       Word(im,p.parent_clip_store+8)!=0xb800b001u)
        return Fail(why,"native parent clip store/root reload liveness");
    if(!ResolveClipNone(im,p,original_clip_pp,why))return false;
    p.native_clip_pp=original_clip_pp;
    p.gate=p.background_call-16;p.gate_expected=Word(im,p.gate);p.background_expected=Word(im,p.background_call);
    if((p.gate_expected&0xfff8001fu)!=0x37200001u)return Fail(why,"native background gate changed");
    int64_t n=p.gate_expected>>5&0x3fff;if(n&0x2000)n-=0x4000;p.skip=size_t(int64_t(p.gate)+n*4);
    if(!Contains(im,p.skip,4)||p.skip<=p.background_call)return Fail(why,"background gate target");
    p.background_tag=Tag(im,Branch(im,p.background+8));
    p.sized_box_tag=Tag(im,Branch(im,sizebox+0xc8));
    p.ignore_pointer_tag=Tag(im,Branch(im,scroll+0xa0));
    for(size_t at=pos;at<pos+0x17c;at+=4) if(Word(im,at+8)==0xb8013001u && Word(im,at+16)==0xb8017001u && Branch(im,at)) {
        if(p.positioned_tag)return Fail(why,"multiple positioned allocations");p.positioned_tag=Tag(im,Branch(im,at));
    }
    // The factory's four native classes share radius/config/opacity fields;
    // only GlassOs4 has the additional raw zero field at +0x1f.
    const size_t calls[]={0xb0,0xec,0,0x168};
    p.material_tags[0]=Tag(im,Branch(im,material+calls[0]));
    p.material_tags[1]=Tag(im,Branch(im,material+calls[1]));
    p.material_tags[3]=Tag(im,Branch(im,material+calls[3]));
    // Basic blur is built by this factory's ValueListenable callback. Find its
    // constructor store shape in the bounded adjacent closure, not proximity
    // to allocator stubs or a class-number assumption.
    for(size_t at=material+0x1b4;at<material+0x400;at+=4) if(Branch(im,at)&&
        Word(im,at+8)==0xfc00b000u && Word(im,at+20)==0xb8013001u && Word(im,at+28)==0xfc017000u) {
        if(p.material_tags[2])return Fail(why,"multiple Basic blur constructors");p.material_tags[2]=Tag(im,Branch(im,at));
    }
    p.material_config_pp=Pool(im,material+0xc0);
    const uint32_t dl=Word(im,p.phone+0x78),dh=Word(im,p.phone+0x7c);
    if((dl&0xffe0001fu)!=0xd2800005u||(dh&0xffe0001fu)!=0xf2a00005u)return Fail(why,"boxed Double header");
    p.double_tag=(dl>>5&65535u)|(dh>>5&65535u)<<16;
    p.device_slot=Slot(Word(im,width+0x14));p.corner_field=(Word(im,corner+0x3c)>>12)&511;
    p.device_layout_field=(Word(im,width+0x4c)>>12)&511;
    p.launcher_mode_slot=Slot(Word(im,elderly+0xc));
    p.launcher_mode_rx=(Word(im,elderly+0x24)>>12)&511;
    p.elderly_pp=Slot(Word(im,elderly+0x30));
    if(Branch(im,max+8)!=elderly||Branch(im,elderly+0x2c)!=p.labels.value)return Fail(why,"elderly mode graph");
    if(p.device_slot!=Slot(Word(im,p.mode+0x44))||Word(im,width+0x60)!=0xfc477000u||
       Word(im,width+0x7c)!=0xfc42b001u||(Word(im,width+0x4c)&0xffe00fffu)!=0xb8400001u||
       !p.corner_field || !p.device_layout_field || !p.elderly_pp)
        return Fail(why,"native grid/device geometry fields");
    // The native highlight layout centers the painted icon inside each cell.
    // Its exact source role supplies both offsets, avoiding a five-icon plate
    // clipped to cells which intentionally overlap at the screen edge.
    p.ink_metrics_field=(Word(im,ink+0xe0)>>12)&511;
    p.ink_width_field=(Word(im,ink+0xe8)>>12)&511;
    if((Word(im,ink+0xe0)&0xffe00fffu)!=0xb8400001u||
       (Word(im,ink+0xe8)&0xffe00fffu)!=0xfc400020u)
        return Fail(why,"native painted icon metrics");
    p.ink_top_field=(Word(im,ink+0xc0)>>12)&511;
    for(size_t at=height;at<height+0x108;at+=4) if((Word(im,at)&0xffe00fffu)==0xfc400001u &&
        Word(im,at+4)==0x4ea11c20u&&Word(im,at+8)==0xaa1d03efu&&Word(im,at+16)==0xd65f03c0u) {
        if(p.cell_height_field)return Fail(why,"multiple native cell heights");p.cell_height_field=Word(im,at)>>12&511;
    }
    if(Branch(im,ink+0x6c)!=height||!p.cell_height_field||
       (Word(im,ink+0xc0)&0xffe00fffu)!=0xfc400020u)return Fail(why,"native icon height linkage");
    if(Branch(im,no_word+8)==elderly) {
        p.no_word_rx_field=(Word(im,no_word+0x2c)>>12)&511;
        if(Slot(Word(im,no_word+0x14))!=p.launcher_mode_slot||Branch(im,no_word+0x34)!=p.labels.value)
            return Fail(why,"legacy native no-word observer");
    } else {
        const size_t effective=Branch(im,no_word+0x28);bool proof=false;
        for(const auto& pat:kDockNoWordEffective)if(Match(im,effective,pat))proof=true;
        if(!proof||Slot(Word(im,no_word+0xc))!=p.launcher_mode_slot||
           Branch(im,effective+0x1c)!=p.labels.value||Branch(im,effective+0x40)!=p.labels.value||
           Branch(im,effective+0x5c)!=p.labels.value||
           (Word(im,effective+0x38)>>12&511)!=p.launcher_mode_rx)
            return Fail(why,"native effective no-word observer");
        p.no_word_rx_field=Word(im,effective+0x54)>>12&511;
        p.no_word_suppress_rx_field=Word(im,effective+0x14)>>12&511;
    }
    if(!p.no_word_rx_field)return Fail(why,"native no-word field");
    for(size_t at=outer;at<outer+0x400;at+=4) if(Branch(im,at)==sync) {
        if(p.mode_post)return Fail(why,"multiple native hotseat sync calls");
        p.mode_post=at+4;p.mode_post_expected=Word(im,at+4);
    }
    if(!p.mode_post||p.mode_post_expected!=0xf85e83a2u)return Fail(why,"hotseat sync continuation");
    p.mode_slot=Slot(Word(im,p.mode+0x10));
    // Complete source role proves the mode receiver is unused. Four returns
    // retain the exact original VM caller PC and only expose a primitive enum.
    size_t returns=0;
    for(size_t at=p.mode;at<p.mode+0x1d4;at+=4) if(Word(im,at)==0xd65f03c0u){
        if(returns==4)return Fail(why,"mode return graph");p.mode_returns[returns++]=at;
    }
    for(size_t at=outer;at<outer+0x400;at+=4) if(Branch(im,at)&&Word(im,at+4)==0xf85f83a0u &&
        Branch(im,at+8) && Word(im,at+12)==0xaa0003e1u && Word(im,at+16)==0xf85f83a0u) {
        if(p.outer_call)return Fail(why,"multiple reactive mode calls");p.outer_call=at;p.outer_logger=Branch(im,at);p.outer_expected=Word(im,at);
    }
#ifdef KH_DOCK_PROFILE_DIAGNOSTIC
    fprintf(stderr,"allocs stack=%x box=%x pos=%x bg=%x double=%x mat=%x/%x/%x/%x pp=%x align=%x fit=%x clip=%x outer=%zx rets=%zu\n",p.stack_tag,p.sized_box_tag,p.positioned_tag,p.background_tag,p.double_tag,p.material_tags[0],p.material_tags[1],p.material_tags[2],p.material_tags[3],p.material_config_pp,p.alignment_pp,p.fit_pp,p.clip_pp,p.outer_call,returns);
#endif
    if(!p.outer_call||returns!=4||!p.material_config_pp||!p.alignment_pp||!p.fit_pp||!p.clip_pp||
       (p.stack_tag&0xfff)!=0x21c||(p.positioned_tag&0xfff)!=0x31c||
       (p.sized_box_tag&0xfff)!=0x21c||(p.ignore_pointer_tag&0xfff)!=0x21c||(p.background_tag&0xfff)!=0x11c||
       (p.double_tag&0xfff)!=0x15c||(p.material_tags[0]&0xfff)!=0x31c)
        return Fail(why,"incomplete Dock allocation/return contract");
    for(size_t i=1;i<4;++i) if((p.material_tags[i]&0xfff)!=0x21c)return Fail(why,"native material allocation");
    *out=p;return true;
}

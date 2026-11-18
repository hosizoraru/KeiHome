// SPDX-License-Identifier: Apache-2.0
#include "dock_glass_profile.h"
#include "arm64_dart_entry_index.h"
#include <string.h>
#include <vector>
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
size_t Branch(const IconHighlightImage& im, size_t pc, bool link = true);
bool ScrollSlowPaths(const IconHighlightImage& im, size_t entry) {
    const auto target = [&](size_t offset) -> size_t {
        const auto pc = entry + offset;
        const uint32_t displacement = (Word(im, pc) >> 5) & 0x7ffffu;
        if (displacement < 0xc4/4 || displacement > 0x400/4) return 0;
        return pc + displacement*4;
    };
    const size_t first = target(0x24), second = target(0x4c);
    return first && second == first+4 && Branch(im, first) &&
        Branch(im, first) == Branch(im, second);
}
bool Match(const IconHighlightImage& im,size_t p,const DockPattern& pat) {
    if (!Contains(im,p,pat.count*4)) return false;
    for(size_t j=0;j<pat.count;++j) {
        const auto& expected=pat.words[j];
        const uint32_t word=Word(im,p+4*j);
        if((word&expected.mask)!=expected.word)return false;
        if(expected.mask==0xffc003ffu &&
           (expected.word&0xffc00000u)==0x91000000u) {
            // A movable low address must remain part of the authenticated
            // PP page + ADD + closure entry/context pair + BLR sequence.
            const uint32_t reg=word&31u;
            if(j==0||j+2>=pat.count||reg==27u||reg==31u||
               (Word(im,p+(j-1)*4)&0xffc003ffu)!=(0x91400360u|reg)||
               (word&0xffc003ffu)!=(0x91000000u|(reg<<5u)|reg)||
               Word(im,p+(j+1)*4)!=(0xa940141eu|(reg<<5u))||
               Word(im,p+(j+2)*4)!=0xd63f03c0u)return false;
        }
    }
    return true;
}
template<size_t N> size_t Find(const IndexedArm64Image& im,const DockPattern(&v)[N]) {
    size_t found=0;bool indexed=true,ambiguous=false;
    for(const auto& p:v)indexed&=im.Covers(p.words,p.count);
    im.VisitCandidates(indexed,[&](size_t at,uint32_t first) {
        bool ok=false;for(const auto& p:v) if((first&p.words[0].mask)==p.words[0].word&&Match(im,at,p)){ok=true;break;}
        if(ok){if(found&&found!=at){ambiguous=true;return false;}found=at;}
        return true;
    });
    return ambiguous?0:found;
}
size_t Branch(const IconHighlightImage& im,size_t pc,bool link) {
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
template<size_t N> const DockPattern* Role(const IconHighlightImage& im,size_t at,
                                        const DockPattern(&patterns)[N]) {
    if(!at)return nullptr;
    for(const auto& pattern:patterns)if(Match(im,at,pattern))return &pattern;
    return nullptr;
}
bool Readable(const IconHighlightImage& im,size_t at,size_t bytes) {
    if(at>im.size||bytes>im.size-at)return false;
    for(size_t i=0;i<im.range_count;++i) {
        const auto& r=im.ranges[i];
        if(at>=r.begin&&at<=r.end&&bytes<=r.end-at)return true;
    }
    return false;
}
uint64_t Data(const IconHighlightImage& im,size_t at,size_t bytes) {
    uint64_t value=0;if(bytes<=8&&Readable(im,at,bytes))memcpy(&value,im.base+at,bytes);return value;
}
bool SnapshotInteger(const IconHighlightImage& im,size_t& at,size_t end,int marker,int64_t& value) {
    uint64_t low=0;unsigned shift=0;
    while(at<end&&shift<=56) {
        const uint8_t b=im.base[at++];
        if(b>=128){value=int64_t(low)+(int64_t(b)-marker)*(int64_t{1}<<shift);return true;}
        low|=uint64_t(b)<<shift;shift+=7;
    }
    return false;
}
bool Leb(const IconHighlightImage& im,size_t& at,size_t end,size_t& value) {
    value=0;unsigned shift=0;
    while(at<end&&shift<sizeof(size_t)*8) {
        const uint8_t b=im.base[at++];
        if(shift==63&&(b&127u)>1)return false;
        value|=size_t(b&127u)<<shift;
        if(!(b&128u))return true;shift+=7;
    }
    return false;
}
bool StackPayload(const IconHighlightImage& im,size_t at,unsigned flags,size_t& begin,size_t& end) {
    if(!Readable(im,at,4))return false;
    const uint32_t header=uint32_t(Data(im,at,4));
    if((header&3u)!=flags||!Readable(im,at+4,header>>2))return false;
    begin=at+4;end=begin+(header>>2);return true;
}
bool GlassStackMaps(const IconHighlightImage& im,DockGlassProfile& p,
                    size_t table,size_t total,size_t code_base) {
    size_t found=0,local_offset=0;
    for(size_t i=0;i<total;++i)if(code_base+size_t(Data(im,table+16+i*8,4))==p.glass_build) {
        ++found;local_offset=size_t(Data(im,table+20+i*8,4));
    }
    size_t global_begin=0,global_end=0,local_begin=0,local_end=0;
    if(found!=1||local_offset>im.size-table||
       !StackPayload(im,table+local_offset,2,local_begin,local_end))return false;
    const size_t global_offset=size_t(Data(im,table,4));
    if(global_offset>im.size-table||!StackPayload(im,table+global_offset,1,global_begin,global_end))return false;
    // The future FP-0x28 slot carries only a Smi before the child root store;
    // no captured heap reference relies on FP-8 remaining live across calls.
    // At clip allocation the native child is traced in spill bit 4.
    const size_t sites[]={0x94,0xac,0xc4,0xd4,0xe4,0xf8,0x118,0x138,0x13c,0x158};
    unsigned seen=0;size_t cursor=local_begin,pc=0;
    while(cursor<local_end) {
        size_t delta=0,offset=0;
        if(!Leb(im,cursor,local_end,delta)||!Leb(im,cursor,local_end,offset)||
           delta>0x1e4-pc||offset>global_end-global_begin)return false;
        pc+=delta;size_t bitmap=global_begin+offset,spills=0,other=0;
        if(!Leb(im,bitmap,global_end,spills)||!Leb(im,bitmap,global_end,other)||
           spills>512||other>512||(spills+other+7)/8>global_end-bitmap)return false;
        for(size_t i=0;i<sizeof(sites)/sizeof(sites[0]);++i)if(pc==sites[i]) {
            if((seen&(1u<<i))||spills!=((pc==0x94||pc==0x138)?7u:8u)||other!=0)return false;seen|=1u<<i;
            const uint8_t roots=im.base[bitmap];
            if(pc==0xc4){if(roots!=0)return false;p.glass_visual_stackmap=bitmap;}
            if(pc==0x158) {
                if(roots!=0x1a)return false;p.glass_child_stackmap=bitmap;
            } else {
                if(pc==0x13c){if(roots!=0x0f)return false;p.glass_scope_stackmap=bitmap;}
            }
        }
    }
    return seen==1023u&&p.glass_scope_stackmap&&p.glass_child_stackmap;
}

bool ModeStackMap(const IconHighlightImage& im, DockGlassProfile& p,
                  size_t table, size_t total, size_t codeBase) {
    size_t entry = p.mode_post - 4;
    const size_t lower = entry > 0x400 ? entry - 0x400 : 0;
    for (; entry > lower; entry -= 4) {
        if (Word(im, entry) == 0xa9bf79fdu && Word(im, entry + 4) == 0xaa0f03fdu) break;
    }
    const uint32_t frame = Word(im, entry + 8);
    const size_t frameBytes = frame >> 10 & 0xfffu;
    if (entry == lower || entry < codeBase || (frame & 0xffc003ffu) != 0xd10001efu ||
            frameBytes < 24 || (frameBytes & 7u)) return false;
    size_t local = 0, localEnd = 0;
    for (size_t i = 0; i < total; ++i) {
        const size_t row = table + 16 + i * 8;
        if (Data(im, row, 4) != entry - codeBase) continue;
        const size_t offset = size_t(Data(im, row + 4, 4));
        if (local || offset > im.size - table ||
                !StackPayload(im, table + offset, 2, local, localEnd)) return false;
    }
    size_t global = 0, globalEnd = 0;
    const size_t globalOffset = size_t(Data(im, table, 4));
    if (!local || globalOffset > im.size - table ||
            !StackPayload(im, table + globalOffset, 1, global, globalEnd)) return false;
    size_t pc = entry;
    size_t matched = 0;
    while (local < localEnd) {
        size_t delta = 0, offset = 0;
        if (!Leb(im, local, localEnd, delta) || !Leb(im, local, localEnd, offset))
            return false;
        if (delta > p.mode_post - pc) break;
        pc += delta;
        if (pc != p.mode_post) continue;
        if (matched || offset >= globalEnd - global) return false;
        size_t bitmap = global + offset, spills = 0, other = 0;
        if (!Leb(im, bitmap, globalEnd, spills) || !Leb(im, bitmap, globalEnd, other) ||
                spills != frameBytes / 8 || other || (spills + 7) / 8 > globalEnd - bitmap ||
                im.base[bitmap] != 5) return false;
        for (size_t i = 1; i < (spills + 7) / 8; ++i)
            if (im.base[bitmap + i]) return false;
        matched = bitmap;
    }
    p.mode_stackmap = matched;
    return matched != 0;
}
// Decode only the bounded dispatch root and InstructionsTable. No debug data,
// exported symbols, snapshot version hashes or fixture offsets are consulted.
// All accesses stay in readable PT_LOAD ranges. Image headers locate the VM
// and isolate instruction images; serialized table entries supply code RVAs.
bool RecentsDispatch(const IconHighlightImage& im,DockGlassProfile& p,std::vector<size_t>& result) {
    size_t snapshot=0,end=0;
    for(size_t at=0x350;at<0x10000;at+=16)if(Data(im,at,4)==0xdcdcf5f5u) {
        const uint64_t length=Data(im,at+4,8);
        if(length>im.size||length<52||at>im.size-size_t(length)||
           !Readable(im,at,size_t(length)+4))continue;
        if(snapshot)return false;snapshot=at;end=at+size_t(length)+4;
    }
    if(!snapshot)return false;
    size_t header=snapshot+52;
    while(header<end&&header<snapshot+4096&&im.base[header])++header;
    if(header==end||header==snapshot+4096)return false;++header;
    size_t cursor=header;int64_t head[5]={};
    for(auto& h:head)if(!SnapshotInteger(im,cursor,end,128,h)||h<0)return false;
    if(head[4]>int64_t(im.size))return false;
    const size_t table_header=((end+63)&~size_t(63))+size_t(head[4])+16;
    if(!Readable(im,table_header,16))return false;
    const size_t total=size_t(Data(im,table_header+4,4)),first=size_t(Data(im,table_header+8,4));
    if(!total||total>500000||first>total||int64_t(total-first)!=head[3]||
       Data(im,table_header+12,4)||!Readable(im,table_header+16,total*8))return false;
    size_t code_base=0;
    for(size_t i=0;i<im.range_count;++i) {
        const auto& r=im.ranges[i];if(!r.executable)continue;
        const size_t vm_bytes=size_t(Data(im,r.begin,8));
        if(vm_bytes<64||(vm_bytes&63u)||vm_bytes>r.end-r.begin)continue;
        const size_t isolate=r.begin+vm_bytes,bytes=size_t(Data(im,isolate,8));
        if(Data(im,r.begin+8,8)!=64||Data(im,isolate+8,8)!=64||bytes<64||
           !Contains(im,isolate,bytes))continue;
        if(code_base)return false;code_base=isolate;
    }
    if(!code_base)return false;
    if(!GlassStackMaps(im,p,table_header,total,code_base)||
       !ModeStackMap(im,p,table_header,total,code_base))return false;
    std::vector<size_t> pcs;pcs.reserve(total);
    for(size_t i=0;i<total;++i) {
        const size_t offset=size_t(Data(im,table_header+16+i*8,4));
        if(offset>im.size-code_base||!Contains(im,code_base+offset,4)||
           (!pcs.empty()&&code_base+offset<=pcs.back()))return false;
        pcs.push_back(code_base+offset);
    }
    struct Token {size_t end;int64_t signed_value,unsigned_value;};
    Token previous{},before{};size_t count=0,expanded=0,valid=0,at=end;
    const size_t limit=end>500000?end-500000:header;
    while(at>header&&at>limit) {
        const size_t stop=at;--at;
        if(im.base[at]<128)break;
        while(at>header&&im.base[at-1]<128)--at;
        size_t a=at,b=at;int64_t sv=0,uv=0;
        if(!SnapshotInteger(im,a,stop,192,sv)||!SnapshotInteger(im,b,stop,128,uv)||a!=stop||b!=stop)break;
        if(count>=2) {
            expanded+=before.signed_value>0&&before.signed_value<=63?size_t(before.signed_value):1;
            if(uv==int64_t(expanded)&&uv>=77000&&uv<=500000&&previous.unsigned_value<head[1]) {
                std::vector<size_t> table;table.reserve(size_t(uv));
                size_t pc=previous.end,recent[64]={},rix=0,value=0;bool has_value=false,ok=true;
                while(ok&&table.size()<size_t(uv)) {
                    int64_t token=0;if(!SnapshotInteger(im,pc,end,192,token)){ok=false;break;}
                    if(token==0){value=0;has_value=true;table.push_back(value);}
                    else if(token<0) {
                        if(token < -64||!recent[size_t(~token)]){ok=false;break;}
                        value=recent[size_t(~token)];has_value=true;table.push_back(value);
                    } else if(token<=63) {
                        if(!has_value||size_t(token)>size_t(uv)-table.size()){ok=false;break;}
                        table.insert(table.end(),size_t(token),value);
                    } else {
                        if(token-64<1||token-64>int64_t(total)){ok=false;break;}
                        value=pcs[size_t(token-65)];recent[rix]=value;rix=(rix+1)&63;has_value=true;table.push_back(value);
                    }
                }
                if(ok&&table.size()==size_t(uv)&&pc==end) {
                    ++valid;result.swap(table);p.recents_dispatch_start=at;p.recents_dispatch_end=end;
                }
            }
        }
        before=previous;previous={stop,sv,uv};++count;
    }
    return valid==1;
}
template<size_t N> size_t DispatchRole(const IconHighlightImage& im,const std::vector<size_t>& table,
                                     size_t selector,size_t bytes,uint32_t widget_tag,
                                     const DockPattern(&roles)[N]) {
    size_t found=0;const size_t cid=widget_tag>>12;
    for(size_t at=selector;at+12<=selector+bytes;at+=4) {
        const uint32_t word=Word(im,at);
        if((word&0xffe0001fu)!=0xd2800011u)continue;
        size_t after=at+4,bias=word>>5&65535u;
        if((Word(im,after)&0xffe0001fu)==0xf2a00011u){bias|=size_t(Word(im,after)>>5&65535u)<<16;after+=4;}
        if(Word(im,after)!=0x8b11001eu||Word(im,after+4)!=0xf87e7abeu)continue;
        const size_t index=4096+bias+cid;
        if(index>=table.size()||!Role(im,table[index],roles))continue;
        if(found)return 0;found=table[index];
    }
    return found;
}
// Decode ARM VFPExpandImm for FMOV Dn,#imm. Values remain source-derived even
// if a future coherent source graph changes its ordinary/stacked constants.
uint64_t DoubleImmediate(uint32_t word) {
    if((word&0xffe01fe0u)!=0x1e601000u)return 0;
    const uint64_t imm=word>>13&255u,b6=imm>>6&1u;
    const uint64_t exponent=((b6^1u)<<10)|(b6?0x3fcu:0u)|(imm>>4&3u);
    return ((imm>>7)<<63)|(exponent<<52)|((imm&15u)<<48);
}
size_t LastCall(const IconHighlightImage& im,size_t at,size_t bytes) {
    size_t last=0;
    for(size_t p=at;p<at+bytes;p+=4) {
        if(Word(im,p)==0xd65f03c0u)break;
        if(Branch(im,p))last=p;
    }
    return last;
}
uint32_t RenderCid(const IconHighlightImage& im,size_t at,size_t bytes) {
    uint32_t cid=0;
    for(size_t p=at;p<at+bytes;p+=4) {
        const uint32_t word=Word(im,p);
        if((word&0xffe0001fu)==0xd2800011u&&Word(im,p+4)==0xeb11009fu) {
            if(cid)return 0;cid=word>>5&65535u;
        }
    }
    return cid;
}
template<size_t N> size_t FindCalling(const IconHighlightImage& im,const DockPattern(&patterns)[N],
                                    size_t call_offset,size_t target) {
    size_t found=0;if(!target)return 0;
    for(size_t i=0;i<im.range_count;++i) {const auto& range=im.ranges[i];if(!range.executable)continue;
        for(size_t at=range.begin;at+4<=range.end;at+=4) {
            if((Word(im,at)&patterns[0].words[0].mask)!=patterns[0].words[0].word||
               Branch(im,at+call_offset)!=target||!Role(im,at,patterns))continue;
            if(found)return 0;found=at;
        }
    }
    return found;
}
int32_t FrameSlot(uint32_t word) {
    const uint32_t offset=word>>12&511u;return int32_t(offset)-(offset&256u?512:0);
}
bool GlassFutureSlotUntouched(const IconHighlightImage& im,const DockGlassProfile& p) {
    // Reviewed full method has no FP/Dart-SP aliases in this interval. Decode
    // scalar/SIMD unscaled, unsigned and pair stack accesses, including byte
    // overlap, so a write of either half of the future 8-byte slot rejects.
    for(size_t at=p.glass_scope_resume;at<p.glass_child_scope_store;at+=4) {
        const uint32_t w=Word(im,at);const unsigned rn=w>>5&31u;
        if(rn!=29&&rn!=15)continue;
        int32_t offset=0;unsigned width=0;
        if((w&0x3b200000u)==0x38000000u) {
            if(w&0xc00u)return false;offset=FrameSlot(w);width=1u<<(w>>30);
        } else if((w&0x3b000000u)==0x39000000u) {
            width=1u<<(w>>30);offset=int32_t((w>>10&4095u)*width);
        } else if((w&0x3a000000u)==0x28000000u) {
            if((w&0x01800000u)!=0x01000000u)return false;
            const unsigned scale=(w&0x04000000u)?(w>>30):(w>>31);
            const int32_t encoded=int32_t(w>>15&127u);
            offset=(encoded-(encoded&64?128:0))*int32_t(1u<<(scale+2));width=2u<<(scale+2);
        } else {
            // Includes register-offset stack memory and address arithmetic.
            // Full roles permit ordinary uses, but never escaping a frame
            // address or modifying the original Dart stack pointer here.
            if((w&0x1f000000u)==0x11000000u||
               (w&0x3b200c00u)==0x38200800u)return false;
            continue;
        }
        if(rn==15)offset-=0x40;
        if(offset<p.glass_child_root_slot+8&&offset+int32_t(width)>p.glass_child_root_slot)return false;
    }
    return true;
}
bool ResolveUnlockAnimation(const IndexedArm64Image& im,DockGlassProfile& p,
                            const std::vector<size_t>& dispatch,const char** why) {
    p.unlock_search=Find(im,kDockUnlockSearch);
    p.unlock_location=Find(im,kDockUnlockLocation);
    p.unlock_center=Find(im,kDockUnlockCenter);
    const size_t stateful=Find(im,kDockRecentsStatefulConstructor);
    const auto* stateful_role=Role(im,stateful,kDockRecentsStatefulConstructor);
    const auto* location=Role(im,p.unlock_location,kDockUnlockLocation);
    if(!p.unlock_search||!p.unlock_center||!location||!stateful_role||
       Branch(im,p.unlock_search)!=p.unlock_center||
       Branch(im,p.unlock_search+0x24)!=p.unlock_center)
        return Fail(why,"native unlock/search center roles");
    const size_t call=p.unlock_search+0xc4;
    p.unlock_tag=Tag(im,Branch(im,call));
    p.unlock_create=DispatchRole(im,dispatch,stateful,stateful_role->count*4,
                                p.unlock_tag,kDockUnlockCreate);
    if(!p.unlock_tag||(p.unlock_tag&0xfffu)!=0x31cu||!p.unlock_create||
       !Role(im,Branch(im,p.unlock_create+0x24),kDockUnlockConstructor)||
       Word(im,call+0x1c)!=0xd2800060u|| // native search animation category
       Word(im,call+0x28)!=0xb8027020u||
       Word(im,call+0x30)!=0xf801f022u||
       Word(im,call+0x38)!=0xb802b022u)
        return Fail(why,"native unlock widget layout/dispatch");
    const uint32_t debug=Word(im,call+0x34);
    if((debug&0xffc003ffu)!=0xf9400362u)
        return Fail(why,"native unlock debug token");
    p.unlock_debug_pp=((debug>>10)&4095u)*8u;
    // Native failure/default branches return the canonical Offset.zero. Keep
    // that typed immutable seed; the existing search role resolves live
    // center/visibility from current GridConfig during animation preparation.
    for(size_t at=p.unlock_location;at<p.unlock_location+location->count*4-4;at+=4) {
        const uint32_t w=Word(im,at);
        if((w&0xffc003ffu)!=0xf9400360u||!Branch(im,at+4,false))continue;
        const uint32_t pool=((w>>10)&4095u)*8u;
        if(p.unlock_zero_pp&&p.unlock_zero_pp!=pool)
            return Fail(why,"native unlock zero ambiguity");
        p.unlock_zero_pp=pool;
    }
    if(!p.unlock_zero_pp)return Fail(why,"native unlock Offset seed");
    return true;
}
bool ResolveOwnedGlassSources(const IndexedArm64Image& im,DockGlassProfile& p,const char** why) {
    p.glass_build=Find(im,kDockGlassBuild);
    p.value_key_caller=Find(im,kDockValueKeyCaller);
    p.value_key_equals=Find(im,kDockValueKeyEquals);
    if(!p.glass_build||!p.value_key_caller||!p.value_key_equals)
        return Fail(why,"ambiguous owned Glass/ValueKey source roles");
    p.glass_scope_store=p.glass_build+0x5c;
    p.glass_child_scope_store=p.glass_build+0x13c;
    p.glass_clip_alloc_call=p.glass_build+0x154;
    p.glass_scope_resume=p.glass_scope_store+4;
    p.glass_visual_resume=p.glass_build+0xac;
    if(Word(im,p.glass_visual_resume)!=0xfc5d03a0u||
       Word(im,p.glass_visual_resume+4)!=0x1e6e1001u||
       !Role(im,Branch(im,p.glass_build+0x90),kDockGlassDiagnosticInterpolate)||
       !Role(im,Branch(im,p.glass_build+0xa8),kDockGlassDiagnosticLog))
        return Fail(why,"owned glass diagnostic/visual join");
    p.glass_child_scope_resume=p.glass_child_scope_store+4;
    p.glass_clip_alloc_resume=p.glass_clip_alloc_call+4;
    p.glass_scope_expected=Word(im,p.glass_scope_store);
    p.glass_child_scope_expected=Word(im,p.glass_child_scope_store);
    p.glass_clip_alloc_expected=Word(im,p.glass_clip_alloc_call);
    p.glass_scope_root_slot=FrameSlot(Word(im,p.glass_build+0x54));
    p.glass_radius_slot=FrameSlot(p.glass_scope_expected);
    p.glass_child_root_slot=FrameSlot(p.glass_child_scope_expected);
    const bool extendedRect = (p.recents_rrect_tag & 0xfffu) == 0x31cu;
    const size_t noClipLoad = p.glass_build + (extendedRect ? 0x194 : 0x190);
    // Capture only a Smi while the original receiver has just been reloaded.
    // Native code never touches that future child root before its first store.
    if(!Role(im,Branch(im,p.glass_build+0xc0),kDockGlassDockScope)||
       p.glass_scope_expected!=0xfc1d03a0u||p.glass_scope_root_slot!=-8||p.glass_radius_slot!=-48||
       Word(im,p.glass_build+0x10)!=0xf81f83a1u||
       Word(im,p.glass_build+0x54)!=0xf85f83a1u||Word(im,p.glass_build+0x58)!=0xfc40b020u||
       p.glass_child_scope_expected!=0xf81d83a0u||p.glass_child_root_slot!=-40||
       Branch(im,p.glass_clip_alloc_call)!=p.recents_rrect_alloc||
       Pool(im,p.glass_build+0x168)!=p.recents_clip_pp||
       Pool(im,noClipLoad)!=p.clip_pp||!GlassFutureSlotUntouched(im,p)||
       (extendedRect && Word(im,p.glass_build+0x17c)!=0xf801f03fu))
        return Fail(why,"owned Glass scope replay/root graph");
    p.value_key_alloc=Branch(im,p.value_key_caller+0x88);
    p.value_key_tag=Tag(im,p.value_key_alloc);
    const size_t generic=Branch(im,p.value_key_alloc+8,false);
    if((p.value_key_tag&0xfffu)!=0x11cu||!Role(im,generic,kDockValueKeyAllocator)||
       !Role(im,Branch(im,p.value_key_caller+0x9c),kDockValueKeyString)||
       !Role(im,Branch(im,p.value_key_equals+0x30),kDockValueKeyRuntimeType))
        return Fail(why,"native generic ValueKey allocation/equality graph");
    return true;
}
bool ResolveRecentsCorners(const IndexedArm64Image& im,DockGlassProfile& p,const char** why) {
    p.recents_adaptive_clip=Find(im,kDockRecentsClip);
    p.curve_getter=Find(im,kDockRecentsEnabled);
    p.recents_enabled_for=Find(im,kDockRecentsFor);
    p.recents_enabled_for_radius=Find(im,kDockRecentsForRadius);
    const auto* clip=Role(im,p.recents_adaptive_clip,kDockRecentsClip);
    if(!clip||!p.curve_getter||!p.recents_enabled_for||!p.recents_enabled_for_radius)
        return Fail(why,"ambiguous native Recents clip/getter roles");
    p.recents_eligibility=Branch(im,p.curve_getter+0x28);
    if(!Role(im,p.recents_eligibility,kDockRecentsEligibility)||
       Branch(im,p.recents_enabled_for+0x44)!=p.recents_enabled_for_radius||
       Branch(im,p.recents_enabled_for_radius+0x14)!=p.curve_getter)
        return Fail(why,"native Recents eligibility graph");
    // The full getter includes native cache lookup, nullable resolution,
    // canonical false fallback and publication. Bind its load/store slot;
    // consumers must call this entry, never a reduced capability subroutine.
    const uint32_t cache=Slot(Word(im,p.curve_getter+0x10));
    if(!cache||cache!=Slot(Word(im,p.curve_getter+0x64)))
        return Fail(why,"native Recents getter cache contract");
    unsigned getter_calls=0,predicate_calls=0,allocations=0,pools=0;
    size_t first_getter=0;
    for(size_t at=p.recents_adaptive_clip;at<p.recents_adaptive_clip+clip->count*4;at+=4) {
        const size_t target=Branch(im,at);
        if(target==p.curve_getter){++getter_calls;first_getter=at;}
        else if(target==p.recents_enabled_for)++predicate_calls;
        else if(target&&Tag(im,target)) {
            if(allocations==0)p.recents_superellipse_alloc=target;
            else if(allocations==1)p.recents_rrect_alloc=target;
            ++allocations;
        }
        // After the argument parser, all four PP pairs are the actual native
        // clipBehavior token forwarded/stored by both allocation branches.
        const uint32_t pp=first_getter?Pool(im,at):0;
        if(pp){if(p.recents_clip_pp&&p.recents_clip_pp!=pp)return Fail(why,"native Recents clip token disagreement");
            p.recents_clip_pp=pp;++pools;}
    }
    if(getter_calls!=1||predicate_calls!=1||allocations!=2||pools!=4||
       !p.recents_clip_pp||p.recents_clip_pp==p.clip_pp)
        return Fail(why,"native Recents adaptive allocation graph");
    p.recents_superellipse_tag=Tag(im,p.recents_superellipse_alloc);
    p.recents_rrect_tag=Tag(im,p.recents_rrect_alloc);
    const size_t rectReturn = p.recents_adaptive_clip + clip->count * 4;
    // ClipRRect can carry an extra unboxed radius input. Accept the larger
    // object only when the native allocation path initializes that field.
    const uint32_t rectLayout = p.recents_rrect_tag & 0xfffu;
    const bool extendedRect = rectLayout == 0x31cu &&
            Word(im, rectReturn - 24) == 0xf801f01fu;
    if((p.recents_superellipse_tag&0xfffu)!=0x21cu||
       (rectLayout!=0x21cu&&!extendedRect)||p.recents_superellipse_tag==p.recents_rrect_tag)
        return Fail(why,"native Recents clip allocation headers");
    if(!ResolveOwnedGlassSources(im,p,why))return false;

    std::vector<size_t> dispatch;
    const size_t mount=Find(im,kDockRecentsRenderMount);
    const auto* mount_role=Role(im,mount,kDockRecentsRenderMount);
    if(!mount_role||!RecentsDispatch(im,p,dispatch))return Fail(why,"native Dart dispatch root");
    if(!ResolveUnlockAnimation(im,p,dispatch,why))return false;
    const size_t key_index=4096+(p.value_key_tag>>12);
    if(key_index>=dispatch.size()||dispatch[key_index]!=p.value_key_equals)
        return Fail(why,"ValueKey CID/equality dispatch identity");
    // The caller must allocate the defining class, not a later subclass that
    // merely inherits the same equality method. The reviewed native dispatch
    // cluster starts with ValueKey itself; generator oracles authenticate that
    // ordering against the actual public ValueKey caller in every fixture.
    for(size_t i=4096;i<key_index;++i)if(dispatch[i]==p.value_key_equals)
        return Fail(why,"ValueKey inherited subclass CID");
    const size_t stateless=Find(im,kDockGlassStatelessBuild);
    const auto* stateless_role=Role(im,stateless,kDockGlassStatelessBuild);
    if(!stateless_role||DispatchRole(im,dispatch,stateless,stateless_role->count*4,
                                    p.material_tags[0],kDockGlassBuild)!=p.glass_build)
        return Fail(why,"GlassOs4 material CID/build dispatch identity");
    p.recents_superellipse_create=DispatchRole(im,dispatch,mount,mount_role->count*4,
                                              p.recents_superellipse_tag,kDockRecentsSuperCreate);
    p.recents_rrect_create=DispatchRole(im,dispatch,mount,mount_role->count*4,
                                      p.recents_rrect_tag,kDockRecentsRectCreate);
    const size_t super_update=Find(im,kDockRecentsSuperUpdate),rect_update=Find(im,kDockRecentsRectUpdate);
    const auto* super_create=Role(im,p.recents_superellipse_create,kDockRecentsSuperCreate);
    const auto* rect_create=Role(im,p.recents_rrect_create,kDockRecentsRectCreate);
    const auto* super_up=Role(im,super_update,kDockRecentsSuperUpdate);
    const auto* rect_up=Role(im,rect_update,kDockRecentsRectUpdate);
    if(!super_create||!rect_create||!super_up||!rect_up)
        return Fail(why,"ambiguous native clip render identities");
    const size_t creates[]={p.recents_superellipse_create,p.recents_rrect_create};
    const DockPattern* create_roles[]={super_create,rect_create};
    const uint32_t cids[]={RenderCid(im,super_update,super_up->count*4),RenderCid(im,rect_update,rect_up->count*4)};
    size_t directions[2]={};
    for(size_t n=0;n<2;++n) {
        const size_t begin=creates[n],end=begin+create_roles[n]->count*4;
        unsigned render_allocations=0;uint32_t render_tag=0;
        size_t direction=0;
        for(size_t at=begin;at<end;at+=4) {
            const size_t target=Branch(im,at);if(!target)continue;
            if(!direction)direction=target;
            const uint32_t tag=Tag(im,target);
            if(tag){render_tag=tag;++render_allocations;}
        }
        directions[n]=direction;
        const size_t last=LastCall(im,begin,create_roles[n]->count*4),ctor=Branch(im,last);
        if(n==0)p.recents_superellipse_constructor=ctor;else p.recents_rrect_constructor=ctor;
        const DockPattern* ctor_role=n==0?Role(im,ctor,kDockRecentsSuperConstructor):Role(im,ctor,kDockRecentsRectConstructor);
        if(render_allocations!=1||!render_tag||!cids[n]||render_tag>>12!=cids[n]||!ctor_role)
            return Fail(why,"native clip create/update render class graph");
        if(n==0) {
            // Older create methods pass the constant in their constructor;
            // newer methods pass it directly to the shared base constructor.
            unsigned token_edges=0;
            for(size_t at=begin;at<end;at+=4)
                if(Pool(im,at)==p.recents_clip_pp&&Branch(im,at+8)==ctor)++token_edges;
            for(size_t at=ctor;at<ctor+ctor_role->count*4;at+=4)
                if(Pool(im,at)==p.recents_clip_pp&&Branch(im,at+8))++token_edges;
            if(token_edges!=1)return Fail(why,"native superellipse clipBehavior forwarding");
        }
    }
    if(!directions[0]||directions[0]!=directions[1])return Fail(why,"native clip directionality graph");

    const size_t radius_caller=Find(im,kDockRecentsRadiusCaller);
    p.recents_radius_caller=radius_caller;
    if(!radius_caller)return Fail(why,"ambiguous native Radius/BorderRadius caller roles");
    // Inlined task-radius selection joins in D3, before flight-size scaling.
    p.recents_flight_radius=radius_caller+0xd8;
    p.recents_flight_radius_expected=Word(im,p.recents_flight_radius);
    if((p.recents_flight_radius_expected&0xffe00fffu)!=0xfc4003a0u||
       Word(im,radius_caller+0xe4)!=0x1e610864u)
        return Fail(why,"native flight radius join/scaling");
    p.recents_flight_radius_slot=int32_t(p.recents_flight_radius_expected<<11)>>23;
    p.recents_radius_alloc=Branch(im,radius_caller+0xf0);
    const size_t radius_scale=FindCalling(im,kDockRecentsRadiusScale,0x24,p.recents_radius_alloc);
    p.recents_border_radius_alloc=Branch(im,radius_caller+0x104);
    p.recents_radius_tag=Tag(im,p.recents_radius_alloc);
    p.recents_border_radius_tag=Tag(im,p.recents_border_radius_alloc);
    if(!radius_scale||
       (p.recents_radius_tag&0xfffu)!=0x21cu||
       (p.recents_border_radius_tag&0xfffu)!=0x21cu||p.recents_radius_tag==p.recents_border_radius_tag)
        return Fail(why,"native Radius/BorderRadius allocation graph");
    if(Branch(im,p.glass_build+0xe0)!=p.recents_radius_alloc||
       Branch(im,p.glass_build+0xf4)!=p.recents_border_radius_alloc)
        return Fail(why,"Glass native Radius/BorderRadius source graph");
    p.recents_stacked_radius=Find(im,kDockRecentsStacked);
    const size_t task_radius=Find(im,kDockRecentsTaskRadius);
    p.recents_task_radius=task_radius;
    if(!p.recents_stacked_radius||!task_radius||Branch(im,task_radius+0xac)!=p.recents_stacked_radius)
        return Fail(why,"native ordinary/stacked radius graph");
    const auto* task_role=Role(im,p.recents_task_radius,kDockRecentsTaskRadius);
    size_t radius_returns=0;
    if(!task_role)return Fail(why,"missing native task radius role");
    for(size_t at=p.recents_task_radius;at<p.recents_task_radius+task_role->count*4;at+=4)
        if(Word(im,at)==0xd65f03c0u) {
            if(radius_returns==2)return Fail(why,"native task radius return count");
            p.recents_task_radius_returns[radius_returns++]=at;
        }
    if(radius_returns!=2)return Fail(why,"incomplete task radius returns");
    p.recents_default_radius_bits=DoubleImmediate(Word(im,p.recents_stacked_radius+0x24));
    p.recents_stacked_min_radius_bits=DoubleImmediate(Word(im,p.recents_stacked_radius+0x14));
    if(!p.recents_default_radius_bits||!p.recents_stacked_min_radius_bits||
       p.recents_stacked_min_radius_bits!=DoubleImmediate(Word(im,p.recents_stacked_radius+0x40))||
       p.recents_default_radius_bits!=DoubleImmediate(Word(im,task_radius+0x9c))||
       p.recents_default_radius_bits!=DoubleImmediate(Word(im,radius_caller+0xd4)))
        return Fail(why,"native radius constant consistency");

    const size_t state_ctor=Find(im,kDockRecentsStatefulConstructor);
    const size_t state_create=FindCalling(im,kDockRecentsStatefulCreate,0x24,state_ctor);
    const auto* ctor_role=Role(im,state_ctor,kDockRecentsStatefulConstructor);
    if(!ctor_role||!state_create)return Fail(why,"native StatefulElement selector graph");
    const uint32_t glass_child_tag=Tag(im,Branch(im,p.glass_build+0x138));
    if((glass_child_tag&0xfffu)!=0x21cu||
       !DispatchRole(im,dispatch,state_ctor,ctor_role->count*4,glass_child_tag,kDockGlassChildCreate))
        return Fail(why,"owned Glass child CID/createState identity");
    return true;
}
bool ResolveClipNone(const IndexedArm64Image& im,DockGlassProfile& p,
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
bool dock_glass_profile_detail::ResolveRootListSave(const IconHighlightImage& im,
        size_t phone,size_t bytes,size_t* save,const char** why) {
    if(save)*save=0;
    if(!save||!im.base||phone>im.size||bytes>im.size-phone||bytes<20||
       ((phone|bytes)&3u)||!Contains(im,phone,bytes))
        return Fail(why,"invalid native root list bounds");
    size_t found=0;
    // The call result becomes x2, then survives the Hotseat receiver reload.
    // IC loads may fuse ADD/LDP and move this entire sequence.
    for(size_t at=phone+4;at<=phone+bytes-16;at+=4) {
        if(!Branch(im,at-4)||Word(im,at)!=0xaa0003e2u||
           Word(im,at+4)!=0xf85f83a0u||
           (Word(im,at+8)&0xffe00fffu)!=0xf80003a2u||
           Word(im,at+12)!=0xb841b001u)continue;
        if(found)return Fail(why,"multiple native root list saves");
        found=at+8;
    }
    if(!found)return Fail(why,"missing native root list save");
    *save=found;
    return true;
}
bool ResolveDockGlass(const IconHighlightImage& source,DockGlassProfile* out,const char** why) {
    if(why)*why=nullptr;if(!out)return Fail(why,"missing Dock output");*out={};
    if(!ValidateArm64DartImage(source))return Fail(why,"invalid Dock image");
    const IndexedArm64Image im(source);
    DockGlassProfile p{};
    const size_t bottom_margin=Find(im,kDockBottomMargin);
    if(!bottom_margin)return Fail(why,"native Dock bottom margin missing/ambiguous");
    size_t bottom_size=0;
    for(const auto& pattern:kDockBottomMargin)
        if(Match(im,bottom_margin,pattern))bottom_size=pattern.count*4;
    for(size_t at=bottom_margin;at<bottom_margin+bottom_size;at+=4)
        if(Word(im,at)==0xd65f03c0u) {
            if(p.bottom_margin_return)return Fail(why,"ambiguous Dock margin return");
            p.bottom_margin_return=at;
        }
    if(!p.bottom_margin_return)return Fail(why,"native Dock margin return missing");
    if(!ResolveLauncherLabelState(im,&p.labels,why))return false;
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
    if (!ScrollSlowPaths(im, scroll)) return Fail(why, "unsupported scroll slow paths");
    uint32_t original_clip_pp=0;
    const auto* phone_role=Role(im,p.phone,kDockPhone);
    if(!phone_role)return Fail(why,"native phone role missing");
    const size_t phone_end=p.phone+phone_role->count*4;
    size_t root_save_at=0;
    if(!dock_glass_profile_detail::ResolveRootListSave(
            im,p.phone,phone_role->count*4,&root_save_at,why))return false;
    for(size_t at=p.phone;at<phone_end;at+=4){
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
    const uint32_t root_save=Word(im,root_save_at);
    const uint32_t root_load=Word(im,p.parent_clip_store+4);
    if(!root_save_at||!p.parent_clip_store||Word(im,p.parent_clip_store)!=0xb801b001u||
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
    // The native width may stay unboxed in the phone closure. Its enclosing
    // hotseat builder still boxes a double with the same Dart heap layout.
    const auto resolve_double = [&](size_t begin, size_t end) {
        for (size_t at = begin + 20; at + 20 <= end; at += 4) {
            const uint32_t sub = Word(im, at);
            const uint32_t dl = Word(im, at + 4), dh = Word(im, at + 8);
            const uint32_t bounds = Word(im, at - 20);
            const uint32_t object = sub & 31u, header = dl & 31u, limit = bounds >> 10 & 31u;
            if (sub != (0xd1003c00u | object << 5 | object) ||
                    (dl & 0xffe00000u) != 0xd2800000u ||
                    (dh & 0xffe0001fu) != (0xf2a00000u | header) ||
                    Word(im, at + 12) != (0xf81ff000u | object << 5 | header) ||
                    (Word(im, at + 16) & 0xffffffe0u) != (0xfc007000u | object << 5) ||
                    bounds != (0xa9460340u | limit << 10 | object) ||
                    Word(im, at - 16) != (0x91004000u | object << 5 | object) ||
                    Word(im, at - 12) != (0xeb00001fu | object << 16 | limit << 5) ||
                    (Word(im, at - 8) & 0xff00001fu) != 0x54000009u ||
                    Word(im, at - 4) != (0xf9003340u | object)) continue;
            const uint32_t tag = (dl >> 5 & 65535u) | (dh >> 5 & 65535u) << 16;
            if ((tag & 0xfff) != 0x15c) continue;
            if (p.double_tag && p.double_tag != tag) return false;
            p.double_tag = tag;
        }
        return true;
    };
    if (!resolve_double(p.phone, phone_end) || !resolve_double(outer, outer + 0x400))
        return Fail(why, "conflicting boxed Double header");
    if (!p.double_tag) return Fail(why, "boxed Double header missing");
    const bool inlineGridWidth = Word(im, width + 0x0c) == 0xf9403f40u;
    const size_t deviceLoad = width + (inlineGridWidth ? 0x10 : 0x14);
    const size_t layoutLoad = width + (inlineGridWidth ? 0x48 : 0x4c);
    const size_t wideLoad = width + (inlineGridWidth ? 0xa0 : 0x60);
    const size_t normalLoad = width + (inlineGridWidth ? 0x100 : 0x7c);
    if (inlineGridWidth) {
        const size_t loads[] = {wideLoad, normalLoad};
        for (const size_t load : loads) {
            if (Slot(Word(im, load - 0x48)) != p.labels.grid_slot ||
                    Word(im, load - 12) != (0xb8400001u | p.labels.config_rx_field << 12) ||
                    Branch(im, load - 4) != p.labels.value)
                return Fail(why, "inlined width config graph");
        }
    } else if (Branch(im, wideLoad - 4) != p.labels.config ||
               Branch(im, normalLoad - 4) != p.labels.config) {
        return Fail(why, "width config graph");
    }
    p.device_slot=Slot(Word(im,deviceLoad));p.corner_field=(Word(im,corner+0x3c)>>12)&511;
    p.device_layout_field=(Word(im,layoutLoad)>>12)&511;
    p.launcher_mode_slot=Slot(Word(im,elderly+0xc));
    p.launcher_mode_rx=(Word(im,elderly+0x24)>>12)&511;
    p.elderly_pp=Slot(Word(im,elderly+0x30));
    if(Branch(im,max+8)!=elderly||Branch(im,elderly+0x2c)!=p.labels.value)return Fail(why,"elderly mode graph");
    if(p.device_slot!=Slot(Word(im,p.mode+0x44))||
       Word(im,wideLoad)!=0xfc477000u||
       Word(im,normalLoad)!=0xfc42b001u||
       (Word(im,layoutLoad)&0xffe00fffu)!=0xb8400001u||
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
    if(!ResolveRecentsCorners(im,p,why))return false;
    *out=p;return true;
}

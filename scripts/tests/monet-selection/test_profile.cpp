// SPDX-License-Identifier: Apache-2.0
#include "selection_profile.h"
#include "monet_image_identity.h"
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <vector>

static unsigned checks = 0;
static void Check(bool value, const char* name) {
    if (!value) { std::fprintf(stderr, "FAIL %s\n", name); std::exit(1); }
    ++checks; std::printf("PASS %s\n", name);
}
static uint32_t Read(const std::vector<uint8_t>& b, size_t o) {
    uint32_t w; std::memcpy(&w, b.data()+o,4); return w;
}
static uint64_t Read64(const std::vector<uint8_t>& b, size_t o) {
    uint64_t w; std::memcpy(&w, b.data()+o,8); return w;
}
static void Write(std::vector<uint8_t>& b, size_t o, uint32_t w) {
    std::memcpy(b.data()+o,&w,4);
}
static void Write64(std::vector<uint8_t>& b, size_t o, uint64_t w) {
    std::memcpy(b.data()+o,&w,8);
}
static size_t BlTarget(const std::vector<uint8_t>& b, size_t pc) {
    const uint32_t w=Read(b,pc);
    Check((w&0xfc000000u)==0x94000000u,"decoded BL opcode");
    return pc+((int64_t(w&0x3ffffffu)^(1<<25))-(1<<25))*4;
}
static void SetBranch(std::vector<uint8_t>& b, size_t pc, size_t target, bool link=true) {
    Write(b,pc,(link?0x94000000u:0x14000000u)|(uint32_t((int64_t(target)-int64_t(pc))/4)&0x3ffffffu));
}
struct Segment { size_t start, size; bool executable; };
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream f(argv[1],std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(f)),{});
    Check(!bytes.empty(), "fixture loaded");
    // Both supplied fixtures have file offsets == virtual addresses. Decode
    // LOADs rather than installing any fixture-specific segment/RVA constants.
    std::vector<Segment> segments;
    const size_t ph=Read64(bytes,32), phnum=bytes[56]|size_t(bytes[57])<<8;
    size_t rx=0, rxsize=0, rxph=0;
    for(size_t i=0;i<phnum;++i) {
        const size_t o=ph+i*56;
        if(Read(bytes,o)!=1)continue;
        const size_t start=Read64(bytes,o+16), size=Read64(bytes,o+40);
        Check(start==Read64(bytes,o+8)&&start+size<=bytes.size(),"fixture LOAD mapping");
        const bool executable=(Read(bytes,o+4)&1)!=0;
        segments.push_back({start,size,executable});
        if(executable){rx=start;rxsize=Read64(bytes,o+32);rxph=o;}
    }
    Check(rx!=0&&rxsize>512,"executable LOAD found");
    MonetSelectionProfile out{};
    const auto resolve_started=std::chrono::steady_clock::now();
    const auto base=reinterpret_cast<uintptr_t>(bytes.data());
    std::vector<MonetSelectionImageRange> split;
    for(const auto& s:segments)split.push_back({base+s.start,s.size,s.executable});
    const char* why=nullptr;
    const bool resolved=ResolveMonetSelection({base,split.data(),split.size()},&out,&why);
    if (!resolved) {
        std::fprintf(stderr,"Resolver rejected %s: %s\n",argv[1],why);
        return 1;
    }
    Check(resolved, "real image resolves without symbol/address/identity lookup");
    const double resolve_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-resolve_started).count();
    std::printf("TIMING {\"first_resolve_ms\":%.3f,\"executable_bytes\":%zu,\"host_only\":true}\n",resolve_ms,rxsize);
    const size_t has=reinterpret_cast<uintptr_t>(out.entry)-base;
    const size_t eq=out.string_equals_entry-base;
    const size_t context=BlTarget(bytes,has+20);
    std::printf("PROFILE {\"Has\":%zu,\"Resource\":%zu,\"ResourceProbe\":%zu,\"Update\":%zu,\"UpdateCallback\":%zu,\"Should\":%zu,\"OneByteEquals\":%zu,\"OneByteAllocateFast\":%zu,\"factory_pool\":%zu,\"callback_pool\":%zu,\"type_pool\":%zu}\n",
        has,out.resource_entry-base,out.resource_probe_entry-base,out.update_entry-base,
        out.update_callback_entry-base,out.should_entry-base,eq,out.string_allocator_entry-base,
        out.factory_pool_offset,out.update_callback_pool_offset,out.controller_type_pool_offset);
    Check(out.resume==reinterpret_cast<uintptr_t>(out.entry)+16,"displaced resume");
    Check(out.factory_pool_offset!=out.update_callback_pool_offset &&
          out.update_callback_pool_offset!=out.controller_type_pool_offset &&
          out.factory_pool_offset!=out.controller_type_pool_offset,"independently derived pool producers");
    Check(Read(bytes,eq+28)==0xf1017a1fu && Read(bytes,eq+36)==0xf8407002u &&
          Read(bytes,eq+64)==0x91003c00u && Read(bytes,eq+96)==0x9100c2c0u,
          "independent CID 94, tagged length +7, data +15, false x22+0x30");
    const MonetSelectionProfile valid=out;
    std::vector<uint8_t> relocated=bytes;
    Check(ResolveMonetSelection(relocated.data(),relocated.size(),&out) &&
          reinterpret_cast<uintptr_t>(out.entry)-reinterpret_cast<uintptr_t>(relocated.data())==has,
          "different host load base");
    auto reject=[&](std::vector<uint8_t>& bad,const char* name) {
        out=valid;
        Check(!ResolveMonetSelection(bad.data(),bad.size(),&out) && out.entry==nullptr,name);
    };
    for (size_t word=0;word<33;++word) {
        auto bad=bytes; Write(bad,has+word*4,Read(bad,has+word*4)^4u);
        char name[64]; std::snprintf(name,sizeof(name),"entry opcode/register/branch word %zu mutation",word); reject(bad,name);
    }
    for (const auto& test : std::vector<std::pair<size_t,const char*>>{
        {eq+28,"CID drift"},{eq+36,"length load drift"},
        {eq+64,"string byte offset drift"},{eq+96,"false result drift"},
        {context+20,"x16 kill drift"}}) {
        auto bad=bytes;Write(bad,test.first,Read(bad,test.first)^0x400u);reject(bad,test.second);
    }
    auto dep=bytes;const size_t dependency=BlTarget(dep,has+68);
    Write(dep,dependency,Read(dep,dependency)^4u);reject(dep,"actual dependency instruction drift");
    auto pool=bytes;Write(pool,has+96,Read(pool,has+96)^0x00400000u);reject(pool,"factory pool producer opcode changed");
    auto wrongreg=bytes;Write(wrongreg,has+92,Read(wrongreg,has+92)^0x20u);reject(wrongreg,"PP source register is not a relocation");
    std::vector<size_t> callers;
    for(size_t pc=rx;pc+4<=rx+rxsize;pc+=4) {
        const auto w=Read(bytes,pc);
        if((w&0xfc000000u)!=0x94000000u)continue;
        const int64_t target=int64_t(pc)+((int64_t(w&0x3ffffffu)^(1<<25))-(1<<25))*4;
        if(target==int64_t(has))callers.push_back(pc);
    }
    Check(callers.size()==2,"all executable bytes contain exactly two direct has callers");
    for(size_t pc=rx;pc+4<=rx+rxsize;pc+=4) {
        const auto w=Read(bytes,pc);
        if((w&0xfc000000u)!=0x94000000u)continue;
        const int64_t target=int64_t(pc)+((int64_t(w&0x3ffffffu)^(1<<25))-(1<<25))*4;
        if(target!=int64_t(valid.should_entry-base))continue;
        auto bad=bytes;Write(bad,pc,w^1u);
        reject(bad,"every predicate caller remains required in its reviewed family");
    }
    // Extend RX into the external file's zero-filled alignment gap. No original
    // instruction is overwritten and no fixture RVA is needed. This is a host
    // mutation only, not an ELF that will ever be installed or executed.
    const size_t scratch=rx+rxsize;
    bool zero=scratch+256<=bytes.size();
    for(size_t j=0;zero&&j<256;j+=4)zero=Read(bytes,scratch+j)==0;
    for(const auto& s:segments)if(s.start>rx)zero&=scratch+256<=s.start;
    Check(zero,"zero-filled LOAD gap for synthetic relocation");
    auto expanded=bytes;
    Write64(expanded,rxph+32,rxsize+256);Write64(expanded,rxph+40,rxsize+256);
    auto extra=expanded; SetBranch(extra,scratch,has);reject(extra,"extra direct caller");
    SetBranch(extra,scratch,has+4,false);reject(extra,"branch into patch interior");
    auto moved=expanded;
    std::memcpy(moved.data()+scratch,moved.data()+has,132);
    for(size_t i=0;i<132;i+=4) {
        const uint32_t w=Read(bytes,has+i);
        if((w&0xfc000000u)==0x94000000u)SetBranch(moved,scratch+i,BlTarget(bytes,has+i));
        Write(moved,has+i,0xd4200000u);
    }
    for(size_t pc:callers)SetBranch(moved,pc,scratch);
    Check(ResolveMonetSelection(moved.data(),moved.size(),&out) &&
          reinterpret_cast<uintptr_t>(out.entry)-reinterpret_cast<uintptr_t>(moved.data())==scratch,
          "moved has body and all decoded call edges resolve with a previously unknown file digest");
    auto split_target=moved;SetBranch(split_target,callers[0],has);
    reject(split_target,"callers disagree on relocated root");
    // Relocate every use of the four selection slots coherently. The immutable
    // ASM still verifies live Function.entry_point before touching an argument;
    // this synthetic file does not claim that a Dart object pool was rewritten.
    auto shifted=bytes;size_t shifts=0;
    for(size_t pc=rx;pc+8<=rx+rxsize;pc+=4) {
        const auto a=Read(shifted,pc), l=Read(shifted,pc+4), reg=a&31u;
        if((a&0xffc003e0u)!=0x91400360u||(l&0xffc003e0u)!=(0xf9400000u|(reg<<5u)))continue;
        const size_t slot=((a>>10u)&4095u)*4096u+((l>>10u)&4095u)*8u;
        const size_t initializer=((Read(bytes,has+60)>>10)&0xfffu)*4096+((Read(bytes,has+64)>>10)&0xfffu)*8;
        if(slot==valid.controller_type_pool_offset || slot==valid.factory_pool_offset ||
           slot==valid.update_callback_pool_offset || slot==initializer) {
            Write(shifted,pc,a+0x400u);++shifts;
        }
    }
    Check(shifts>=6&&ResolveMonetSelection(shifted.data(),shifted.size(),&out)&&
          out.factory_pool_offset==valid.factory_pool_offset+4096&&
          out.update_callback_pool_offset==valid.update_callback_pool_offset+4096,
          "coherent PP slot relocation derives new factory and callback offsets");
    auto wrong=bytes;wrong[18]=0x3e;wrong[19]=0;reject(wrong,"wrong ELF machine");
    out=valid;Check(!ResolveMonetSelection(bytes.data(),63,&out)&&out.entry==nullptr,"short supplied readable range");
    MonetSelectionImageRange range{base,64,false};why=nullptr;
    Check(!ResolveMonetSelection({base,&range,1},&out,&why)&&why!=nullptr,"program header outside readable ranges");
    range={base,bytes.size(),false};
    Check(!ResolveMonetSelection({base,&range,1},&out,&why),"RX segment not executable in supplied ranges");
    Check(ResolveMonetSelection({base,split.data(),split.size()},&out,&why),"segmented live-image view from actual LOADs");
    auto identity=bytes;identity[9]^=0x80; // ELF padding outside every code contract
    Check(ResolveMonetSelection(identity.data(),identity.size(),&out),"unknown file identity preserves structural support");
    const auto publish=out;
    uint8_t digest[32]{};
    Check(!PublishMonetSelectionProfile(publish,nullptr)&&!PublishMonetSelectionProfile(publish,digest)&&
          kh_selection_profile_ready==0,"publication requires a computed nonzero identity");
    MonetSha256(identity.data(),identity.size(),digest);
    auto invalid=publish;invalid.resume+=4;
    Check(!PublishMonetSelectionProfile(invalid,digest)&&kh_selection_profile_ready==0,"publication rejects invalid resume");
    Check(PublishMonetSelectionProfile(publish,digest)&&kh_selection_profile_ready==1,"unknown computed digest binds code-only publication");
    Check(PublishMonetSelectionProfile(publish,digest),"identical publication idempotent");
    digest[0]^=1;
    Check(!PublishMonetSelectionProfile(publish,digest),"published identity cannot change");
    digest[0]^=1;
    auto other=publish;other.factory_pool_offset+=8;
    Check(!PublishMonetSelectionProfile(other,digest),"active pool profile cannot be replaced");
    other=publish;other.should_entry+=4;
    Check(!PublishMonetSelectionProfile(other,digest),"all code fields belong to the published identity");
    std::printf("%u checks passed\n",checks);
}

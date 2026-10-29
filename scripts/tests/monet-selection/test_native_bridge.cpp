// SPDX-License-Identifier: Apache-2.0
// Executes the actual bridge on an ARM64 host. The heap is synthetic; this is
// not a claim to have executed Dart GC or a launcher. Old scoped roots become
// PROT_NONE between calls, so retaining/reusing them is an immediate fault.
#include "selection_profile.h"
#include <array>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <sys/mman.h>
#include <vector>

extern "C" {
NativeMonetPolicy* kh_monet_policy = nullptr;
uint32_t kh_monet_readers = 0;
void* kh_selection_original = nullptr;
uintptr_t kh_selection_factory_entry = 0x19e8678;
uintptr_t kh_selection_update_callback_entry = 0xebf8dc;
uintptr_t kh_selection_factory_pool_offset = 0x404f8;
uintptr_t kh_selection_update_callback_pool_offset = 0x404e8;
uint32_t kh_selection_profile_ready = 1;
void kh_selection_native_case(void*);
void kh_selection_native_original();
}
struct Registers {
    uint64_t input[18];
    uint64_t null_reg, pool, nzcv;
    uint64_t output[18];
    uint64_t out_nzcv;
};
static_assert(offsetof(Registers, output) == 168);
static_assert(offsetof(Registers, out_nzcv) == 312);
static unsigned checks = 0;
static std::vector<void*> retired;
static void Check(bool value, const char* name) {
    if (!value) { std::fprintf(stderr,"FAIL %s\n",name); std::exit(1); }
    ++checks;
}
static void Put(uintptr_t address, uint64_t value, size_t size=8) {
    std::memcpy(reinterpret_cast<void*>(address),&value,size);
}
struct Options {
    uint32_t flags=1, count=1, cid=94, ready=1, readers=0;
    int length=-1;
    bool bad_factory=false, bad_callback=false, null_policy=false, smi=false, null_arg=false;
};
static void Case(const char* label, const std::string& package,
                 const std::vector<std::string>& denied, bool match, Options o={}) {
    constexpr size_t arena_size=0x44000;
    void* allocation=mmap(nullptr,arena_size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANON,-1,0);
    Check(allocation!=MAP_FAILED,"allocate fresh scoped roots");
    const auto arena=reinterpret_cast<uintptr_t>(allocation);
    const uintptr_t arg=arena+0x41201, fn=arena+0x41001, callback=arena+0x41021;
    const uintptr_t null=arena+0x41041, sentinel=arena+0x41081;
    Put(arena+0x404f8,fn);Put(arena+0x404e8,callback);Put(arena+0x40,sentinel);
    Put(fn+7,kh_selection_factory_entry+(o.bad_factory?4:0));
    Put(callback+7,kh_selection_update_callback_entry+(o.bad_callback?4:0));
    Put(arg-1,uint64_t{o.cid}<<12);Put(arg+7,o.length<0?package.size()*2:uint64_t(o.length));
    std::memcpy(reinterpret_cast<void*>(arg+15),package.data(),package.size());
    auto policy=std::make_unique<NativeMonetPolicy>();
    policy->flags=o.flags;policy->count=o.count;
    for (size_t i=0;i<denied.size();++i) {
        policy->entries[i].len=static_cast<uint32_t>(denied[i].size());
        Check(denied[i].size()<=256,"fixture row bound");
        std::memcpy(policy->entries[i].name,denied[i].data(),denied[i].size());
    }
    __atomic_store_n(&kh_monet_readers,o.readers,__ATOMIC_RELEASE);
    __atomic_store_n(&kh_monet_policy,o.null_policy?nullptr:policy.get(),__ATOMIC_RELEASE);
    __atomic_store_n(&kh_selection_profile_ready,o.ready,__ATOMIC_RELEASE);
    alignas(16) std::array<uint8_t,2048> stack{};
    Registers r{};
    for (size_t i=0;i<18;++i) r.input[i]=0x11110000+i*0x100;
    r.input[1]=o.smi?2:(o.null_arg?null:arg);
    r.input[15]=reinterpret_cast<uintptr_t>(stack.data()+stack.size()-16);
    r.null_reg=null;r.pool=arena;r.nzcv=0xb0000000;
    kh_selection_native_case(&r);
    Check(r.output[0]==(match?null+0x30:0x4a11),label);
    for (size_t i=1;i<18;++i) {
        Check(r.output[i]==((i==16&&!match)?0x3344:r.input[i]),"register preserved (x16 dead on original)");
    }
    Check(r.out_nzcv==r.nzcv,"NZCV restored");
    Check(__atomic_load_n(&kh_monet_readers,__ATOMIC_ACQUIRE)==o.readers,"reader count balanced");
    Check(stack[0]==0&&stack[stack.size()-1]==0,"scratch bounded");
    __atomic_store_n(&kh_monet_policy,static_cast<NativeMonetPolicy*>(nullptr),__ATOMIC_SEQ_CST);
    Check(mprotect(allocation,arena_size,PROT_NONE)==0,"retire old roots as inaccessible");
    retired.push_back(allocation);
    std::printf("PASS %s (%s; fresh roots, retired roots inaccessible)\n",label,match?"false":"original");
}
int main() {
    kh_selection_original=reinterpret_cast<void*>(kh_selection_native_original);
    Case("exact deny","com.example.bad",{"com.example.bad"},true);
    Case("not denied","com.example.good",{"com.example.bad"},false);
    Case("prefix mismatch","com.example.bad.more",{"com.example.bad"},false);
    Case("case mismatch","com.example.Bad",{"com.example.bad"},false);
    Options o;o.flags=0;Case("disabled","com.example.bad",{"com.example.bad"},false,o);
    o={};o.flags=3;Case("flags bit one","com.example.bad",{"com.example.bad"},true,o);
    o={};o.count=0;Case("empty policy","com.example.bad",{},false,o);
    o={};o.count=513;Case("count overflow","com.example.bad",{},false,o);
    o={};o.cid=95;Case("two byte String","com.example.bad",{"com.example.bad"},false,o);
    o={};o.smi=true;Case("Smi input","com.example.bad",{"com.example.bad"},false,o);
    o={};o.null_arg=true;Case("null input","com.example.bad",{"com.example.bad"},false,o);
    o={};o.bad_factory=true;Case("live factory mismatch","com.example.bad",{"com.example.bad"},false,o);
    o={};o.bad_callback=true;Case("live callback mismatch","com.example.bad",{"com.example.bad"},false,o);
    o={};o.ready=0;Case("unpublished profile","com.example.bad",{"com.example.bad"},false,o);
    o={};o.null_policy=true;Case("null policy","com.example.bad",{},false,o);
    o={};o.length=3;Case("odd length","com.example.bad",{"com.example.bad"},false,o);
    Case("255 byte input",std::string(255,'a'),{std::string(255,'a')},true);
    Case("256 byte input",std::string(256,'a'),{std::string(256,'a')},false);
    Case("non ASCII",std::string("com.bad\xff"),{std::string("com.bad\xff")},false);
    o={};o.readers=0xffffffffu;Case("reader saturation","com.example.bad",{"com.example.bad"},false,o);
    o={};o.readers=5;Case("other reader preserved","com.example.bad",{"com.example.bad"},true,o);
    o={};o.count=2;Case("invalid row after match","com.example.bad",{"com.example.bad",""},false,o);
    std::vector<std::string> names;for(unsigned i=0;i<512;++i)names.push_back("com.p"+std::to_string(i));
    o={};o.count=512;Case("last of 512","com.p511",names,true,o);
    // Alternate exact matching with a fresh nonmatching object repeatedly.
    for (unsigned i=0;i<16;++i)
        Case("moved live roots",i%2?"com.no":"com.example.bad",{"com.example.bad"},i%2==0);
    for (void* p:retired) munmap(p,0x44000);
    std::printf("%u native assertions passed; %zu fresh root generations; no Dart VM/GC was executed\n",checks,retired.size());
}

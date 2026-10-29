#include "selection_profile.h"
#include "monet_image_identity.h"
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
static void Write(std::vector<uint8_t>& b, size_t o, uint32_t w) {
    std::memcpy(b.data()+o,&w,4);
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    std::ifstream f(argv[1],std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(f)),{});
    Check(!bytes.empty(), "fixture loaded");
    MonetSelectionProfile out{};
    Check(ResolveMonetSelection(bytes.data(),bytes.size(),&out), "exact image resolves without symbol/address lookup");
    const auto base=reinterpret_cast<uintptr_t>(bytes.data());
    Check(reinterpret_cast<uintptr_t>(out.entry)-base==0xebf5fc, "has entry");
    Check(out.resource_probe_entry-base==0x19e8678 && out.update_callback_entry-base==0xebf8dc, "independent closure entries");
    Check(out.factory_pool_offset==0x404f8 && out.update_callback_pool_offset==0x404e8, "pool links derived");
    Check(out.string_equals_entry-base==0x17b6ba0 && out.string_allocator_entry-base==0x18a785c, "same-image String proof");
    const MonetSelectionProfile valid=out;
    std::vector<uint8_t> relocated=bytes;
    Check(ResolveMonetSelection(relocated.data(),relocated.size(),&out) &&
          reinterpret_cast<uintptr_t>(out.entry)-reinterpret_cast<uintptr_t>(relocated.data())==0xebf5fc,
          "different host load base");
    auto reject=[&](std::vector<uint8_t>& bad,const char* name) {
        out=valid;
        Check(!ResolveMonetSelection(bad.data(),bad.size(),&out) && out.entry==nullptr,name);
    };
    for (size_t word=0;word<33;++word) {
        auto bad=bytes; Write(bad,0xebf5fc+word*4,Read(bad,0xebf5fc+word*4)^4u);
        char name[64]; std::snprintf(name,sizeof(name),"entry instruction %zu mutation",word); reject(bad,name);
    }
    for (const auto& test : std::vector<std::pair<size_t,const char*>>{
        {0x17b6bbc,"CID drift"},{0x17b6bc4,"length load drift"},
        {0x17b6be0,"string byte offset drift"},{0x17b6c00,"false result drift"},
        {0x1b5720c,"x16 kill drift"},{0xebf5d0,"controller input drift"}}) {
        auto bad=bytes;Write(bad,test.first,Read(bad,test.first)^0x400u);reject(bad,test.second);
    }
    auto extra=bytes; Write(extra,0x850040,0x94000000u|(((0xebf5fc-0x850040)/4)&0x3ffffff));
    reject(extra,"extra direct caller");
    Write(extra,0x850040,0x14000000u|(((0xebf600-0x850040)/4)&0x3ffffff));
    reject(extra,"branch into patch interior");
    auto duplicate=bytes;
    std::memcpy(duplicate.data()+0x850100,duplicate.data()+0xebf5fc,0x84);
    reject(duplicate,"duplicate full root candidate");
    auto wrong=bytes;wrong[18]=0x3e;wrong[19]=0;reject(wrong,"wrong ELF machine");
    out=valid;Check(!ResolveMonetSelection(bytes.data(),63,&out)&&out.entry==nullptr,"short supplied readable range");
    MonetSelectionImageRange ranges[]={{base,64,false}};
    const char* why=nullptr;
    Check(!ResolveMonetSelection({base,ranges,1},&out,&why)&&why!=nullptr,"program header outside readable ranges");
    ranges[0]={base,bytes.size(),false};
    Check(!ResolveMonetSelection({base,ranges,1},&out,&why),"RX segment not executable in supplied ranges");
    const MonetSelectionImageRange split[]={{base,0x850000,false},{base+0x850000,0x130cd50,true},{base+0x1b60000,144,false}};
    Check(ResolveMonetSelection({base,split,3},&out,&why),"segmented live-image view");
    uint8_t digest[32];MonetSha256(bytes.data(),bytes.size(),digest);
    Check(std::memcmp(digest,kMonetSelectionImageSha256,32)==0,"real full-file SHA");
    digest[0]^=1;
    Check(!PublishMonetSelectionProfile(valid,digest)&&kh_selection_profile_ready==0,"publication rejects wrong identity");
    digest[0]^=1;
    Check(PublishMonetSelectionProfile(valid,digest)&&kh_selection_profile_ready==1,"code-only profile publication");
    Check(PublishMonetSelectionProfile(valid,digest),"identical publication idempotent");
    auto other=valid;other.factory_pool_offset+=8;
    Check(!PublishMonetSelectionProfile(other,digest),"active profile cannot be replaced");
    std::printf("%u checks passed\n",checks);
}

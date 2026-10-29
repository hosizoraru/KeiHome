// SPDX-License-Identifier: Apache-2.0
#include "monet_image_identity.h"

#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace {
constexpr size_t kMaxLibrary = 64u * 1024u * 1024u;
constexpr uint32_t kRound[64] = {
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2,
};
uint32_t Rotate(uint32_t x, unsigned n) { return (x >> n) | (x << (32u - n)); }
void Block(const uint8_t* p, uint32_t h[8]) {
    uint32_t w[64];
    for (size_t i = 0; i < 16; ++i) {
        w[i] = uint32_t(p[i*4]) << 24 | uint32_t(p[i*4+1]) << 16 |
               uint32_t(p[i*4+2]) << 8 | p[i*4+3];
    }
    for (size_t i = 16; i < 64; ++i) {
        uint32_t a = w[i-15], b = w[i-2];
        w[i] = w[i-16] + (Rotate(a,7)^Rotate(a,18)^(a>>3)) + w[i-7] +
               (Rotate(b,17)^Rotate(b,19)^(b>>10));
    }
    uint32_t a=h[0], b=h[1], c=h[2], d=h[3], e=h[4], f=h[5], g=h[6], z=h[7];
    for (size_t i = 0; i < 64; ++i) {
        uint32_t t1=z+(Rotate(e,6)^Rotate(e,11)^Rotate(e,25))+((e&f)^(~e&g))+kRound[i]+w[i];
        uint32_t t2=(Rotate(a,2)^Rotate(a,13)^Rotate(a,22))+((a&b)^(a&c)^(b&c));
        z=g; g=f; f=e; e=d+t1; d=c; c=b; b=a; a=t1+t2;
    }
    h[0]+=a; h[1]+=b; h[2]+=c; h[3]+=d; h[4]+=e; h[5]+=f; h[6]+=g; h[7]+=z;
}
uint16_t U16(const uint8_t* p) { return uint16_t(p[0]) | uint16_t(p[1]) << 8; }
uint32_t U32(const uint8_t* p) { return uint32_t(U16(p)) | uint32_t(U16(p+2)) << 16; }
bool StoredEntry(const uint8_t* p, size_t n, const char* entry, size_t* start, size_t* length) {
    if (n < 22) return false;
    const size_t name_size = strlen(entry);
    size_t eocd = n - 22;
    const size_t lower = n > 65557 ? n - 65557 : 0;
    for (;;) {
        if (U32(p+eocd) == 0x06054b50 && eocd+22+U16(p+eocd+20) == n) break;
        if (eocd == lower) return false;
        --eocd;
    }
    if (U16(p+eocd+4) || U16(p+eocd+6) || U16(p+eocd+8) != U16(p+eocd+10)) return false;
    size_t at=U32(p+eocd+16), bytes=U32(p+eocd+12);
    if (at > eocd || bytes > eocd-at) return false;
    const size_t end = at+bytes;
    bool found = false;
    for (unsigned i=0; i<U16(p+eocd+10); ++i) {
        if (at > end || end-at < 46 || U32(p+at) != 0x02014b50) return false;
        const size_t name=U16(p+at+28), extra=U16(p+at+30), comment=U16(p+at+32);
        const size_t span=46+name+extra+comment;
        if (span > end-at) return false;
        if (name == name_size && !memcmp(p+at+46, entry, name)) {
            if (found || U16(p+at+10) != 0 || (U16(p+at+8)&1) != 0 || U16(p+at+34)) return false;
            const size_t size=U32(p+at+24), compressed=U32(p+at+20), local=U32(p+at+42);
            if (!size || size > kMaxLibrary || size != compressed || local > n || n-local < 30 ||
                U32(p+local) != 0x04034b50 || U16(p+local+8) != 0 ||
                U16(p+local+26) != name_size) return false;
            const size_t offset=local+30+U16(p+local+26)+U16(p+local+28);
            if (offset > at || size > at-offset || memcmp(p+local+30, entry, name_size)) return false;
            *start=offset; *length=size; found=true;
        }
        at+=span;
    }
    return found && at == end;
}
} // namespace

bool IsSupportedMonetImageDigest(const uint8_t digest[32]) {
    // The AVD's extracted library changes only the Recents timeout constant
    // load at 0xe68c7c/0xe68c80. Every other byte matches the APK library;
    // the independent renderer/selection instruction guards remain required.
    static constexpr uint8_t identities[][32] = {
        {0xfb,0x8b,0x1f,0x91,0xeb,0xa2,0xd9,0xc4,0x38,0x75,0xf4,0x9b,0xbd,0xed,0x70,0xcc,
         0xa4,0x62,0xbe,0x34,0x1f,0xc3,0x79,0xb9,0x86,0x4e,0x7c,0x61,0x36,0xd6,0xe4,0x63},
        {0x44,0xab,0x76,0x17,0x4a,0x50,0x85,0xb9,0xcc,0x45,0xd4,0x44,0x20,0xb8,0x2e,0x53,
         0xc4,0x64,0x23,0xe2,0xf5,0x8d,0x32,0xe0,0xee,0xe8,0x62,0x64,0xdc,0x68,0x3d,0xd5},
    };
    if (!digest) return false;
    for (const auto& identity : identities) if (!memcmp(identity, digest, 32)) return true;
    return false;
}

void MonetSha256(const void* data, size_t size, uint8_t digest[32]) {
    const auto* bytes=static_cast<const uint8_t*>(data);
    uint32_t state[8]={0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19};
    const size_t full=size/64*64;
    for (size_t at=0; at<full; at+=64) Block(bytes+at,state);
    uint8_t tail[128]{};
    const size_t remaining=size-full;
    if (remaining) memcpy(tail,bytes+full,remaining);
    tail[remaining]=0x80;
    const size_t padded=remaining < 56 ? 64 : 128;
    const uint64_t bits=uint64_t(size)*8;
    for (size_t i=0; i<8; ++i) tail[padded-1-i]=uint8_t(bits>>(i*8));
    Block(tail,state);
    if (padded == 128) Block(tail+64,state);
    for (size_t i=0; i<8; ++i) for (size_t j=0; j<4; ++j) digest[i*4+j]=uint8_t(state[i]>>(24-j*8));
}

bool HashMonetLibrary(const char* path, uint8_t digest[32]) {
    if (!path || !digest) return false;
    const char* separator=strstr(path,"!/");
    const size_t path_size=separator ? size_t(separator-path) : strlen(path);
    if (!path_size || path_size >= 4096) return false;
    char file_path[4096]; memcpy(file_path,path,path_size); file_path[path_size]=0;
    const int fd=open(file_path,O_RDONLY|O_CLOEXEC);
    if (fd<0) return false;
    struct stat stat{};
    if (fstat(fd,&stat) || !S_ISREG(stat.st_mode) || stat.st_size<=0 ||
        uint64_t(stat.st_size) > (separator ? 1024u*1024u*1024u : kMaxLibrary)) {
        close(fd); return false;
    }
    const size_t size=static_cast<size_t>(stat.st_size);
    void* mapping=mmap(nullptr,size,PROT_READ,MAP_PRIVATE,fd,0);
    close(fd);
    if (mapping==MAP_FAILED) return false;
    size_t start=0,length=size;
    const auto* bytes=static_cast<const uint8_t*>(mapping);
    const bool valid=(!separator || StoredEntry(bytes,size,separator+2,&start,&length)) &&
        length>=64 && !memcmp(bytes+start,"\177ELF",4);
    if (valid) MonetSha256(bytes+start,length,digest);
    munmap(mapping,size);
    return valid;
}

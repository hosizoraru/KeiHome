#!/usr/bin/env python3
"""Host-only structural resolver checks; proprietary ELF fixtures stay external.

Usage: python3 scripts/tests/monet-gate/run_tests.py [--no-sanitize] [ELF ...]
Requires a C++17 clang++/g++ and Python stdlib. Debug symbols are an independent
test oracle only: the compiled production resolver receives mapped RX bytes.

Disassembly evidence (local ELF virtual addresses, never resolver constants):
  7722: function 1a9b49c..1a9b6a0; TBZ 1a9b598 -> 1a9b5e4;
        false result 1a9b5dc, true result 1a9b624, ReturnAsync 18ac864.
  8593: function 1b1b820..1b1ba24; TBZ 1b1b91c -> 1b1b968;
        false result 1b1b960, true result 1b1b9a8, ReturnAsync 1921c9c.
Both gates test bit 4 of the decompressed DeviceTypeManager bool after its
initialization check. The target block materializes x22+0x20 (true); the
fallthrough block materializes x22+0x30 (false). Both tail-call ReturnAsync.
The _shouldShowMonochromePreference wrapper awaits this exact function and
compares its result with x22+0x20 before taking its own true-result path.
SDK < 33 and exception returns remain false; no async prologue or Dart object
is modified. Positive relocation/layout tests below prove the patch address
and displacement are derived, while duplicate-graph tests prove uniqueness.
"""

import argparse
import lzma
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[3]
DEFAULTS = [
    Path('/tmp/keihome-launcher-analysis-20261029/unpacked/lib/arm64-v8a/libapp.so'),
    Path('/tmp/keihome-launcher-8593/libapp.so'),
]


class Elf:
    def __init__(self, data):
        self.data = data
        h = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
        if h[0][:7] != b'\x7fELF\x02\x01\x01' or h[2] != 183:
            raise ValueError('expected little-endian AArch64 ELF64')
        self.loads = [struct.unpack_from('<IIQQQQQQ', data, h[5] + i*h[9])
                      for i in range(h[10])]
        self.sections = [struct.unpack_from('<IIQQQQIIQQ', data, h[6] + i*h[11])
                         for i in range(h[12])]
        names = self.sections[h[13]]
        strings = data[names[4]:names[4] + names[5]]
        self.by_name = {strings[s[0]:strings.find(b'\0', s[0])].decode(): s
                        for s in self.sections}

    def section(self, name):
        s = self.by_name[name]
        return self.data[s[4]:s[4] + s[5]]

    def symbols(self):
        symbols = {}
        for section in self.sections:
            if section[1] != 2:
                continue
            table = self.sections[section[6]]
            strings = self.data[table[4]:table[4] + table[5]]
            for off in range(section[4], section[4] + section[5], section[9]):
                name, _, _, shndx, value, size = struct.unpack_from('<IBBHQQ', self.data, off)
                if shndx:
                    name = strings[name:strings.find(b'\0', name)].decode()
                    symbols[name] = (value, size)
        return symbols


def pack_fixture(path, output):
    elf = Elf(path.read_bytes())
    symbols = Elf(lzma.decompress(elf.section('.gnu_debugdata'))).symbols()
    begin, size = symbols['MonochromeController.isSupportMonochrome']
    caller, caller_size = symbols['_IconCustomizePageState._shouldShowMonochromePreference']
    ensure, _ = symbols['DeviceTypeManager._ensureInitialized']
    loads = [p for p in elf.loads if p[0] == 1]
    span = max(p[3] + p[6] for p in loads)
    mapped = bytearray(span)
    for p in loads:
        mapped[p[3]:p[3] + p[5]] = elf.data[p[2]:p[2] + p[5]]
    ranges = [(p[3], p[3] + p[6]) for p in loads if p[1] & 5 == 5]
    gates = []
    for pc in range(begin, begin + size, 4):
        word, = struct.unpack_from('<I', mapped, pc)
        if word & 0xfff8001f == 0x36200001:  # TBZ w1,#4, imm14
            delta = (word >> 5) & 0x3fff
            if delta & 0x2000:
                delta -= 0x4000
            gates.append((pc, pc + delta*4))
    if len(gates) != 1:
        raise ValueError(f'{path}: expected one debug-symbol-scoped bool gate, got {gates}')
    gate, supported = gates[0]
    assert begin < gate < supported < begin + size
    with output.open('wb') as stream:
        stream.write(struct.pack('<9Q', span, begin, size, gate, supported,
                                 caller, caller_size, ensure, len(ranges)))
        for lo, hi in ranges:
            stream.write(struct.pack('<2Q', lo, hi))
        stream.write(mapped)
    print(f'{path}: function=0x{begin:x}+0x{size:x}, gate=0x{gate:x}, '
          f'true_target=0x{supported:x}, caller=0x{caller:x}', flush=True)


HARNESS = r'''
#include "monochrome_gate_resolver.h"
#include <algorithm>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>

struct Header {
    uint64_t span, begin, size, gate, supported, caller, caller_size, ensure, count;
};
static uint32_t get(const std::vector<uint8_t>& data, uintptr_t at) {
    uint32_t word;
    memcpy(&word, data.data() + at, 4);
    return word;
}
static void put(std::vector<uint8_t>& data, uintptr_t at, uint32_t word) {
    memcpy(data.data() + at, &word, 4);
}
static int64_t displacement(uint32_t word, unsigned bits) {
    int64_t value = word & ((uint32_t{1} << bits) - 1);
    if (value & (int64_t{1} << (bits - 1))) value -= int64_t{1} << bits;
    return value * 4;
}
static uint32_t branch(uintptr_t at, uintptr_t to, uint32_t opcode = 0x14000000) {
    int64_t delta = static_cast<int64_t>(to) - static_cast<int64_t>(at);
    assert(delta % 4 == 0 && delta >= -(int64_t{1} << 27) && delta < (int64_t{1} << 27));
    return opcode | (static_cast<uint32_t>(delta / 4) & 0x03ffffff);
}
static size_t checks = 0;
static void check(const MonochromeGateImageView& image, bool good, uintptr_t gate,
                  uintptr_t target, const char* name, const char* diagnostic = nullptr) {
    MonochromeGatePatch out{reinterpret_cast<void*>(1), 7, 9};
    const char* rejection = nullptr;
    bool resolved = ResolveMonochromeGate(image, &out, &rejection);
    if (resolved != good || (good && (rejection != nullptr ||
            out.address != image.base + gate || out.replacement_instruction != branch(gate, target))) ||
            (!good && (out.address != nullptr || out.expected_instruction || out.replacement_instruction ||
                       rejection == nullptr || (diagnostic && !strstr(rejection, diagnostic))))) {
        fprintf(stderr, "FAIL %s: resolved=%d rejection=%s address=%p replacement=%08x\n", name,
                resolved, rejection ? rejection : "none", out.address, out.replacement_instruction);
        exit(1);
    }
    if (good) {
        uint32_t word;
        memcpy(&word, image.base + gate, 4);
        assert(out.expected_instruction == word);
    }
    ++checks;
}

int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream input(argv[1], std::ios::binary);
    Header h{};
    input.read(reinterpret_cast<char*>(&h), sizeof h);
    std::vector<MonochromeGateExecutableRange> ranges(h.count);
    input.read(reinterpret_cast<char*>(ranges.data()), ranges.size() * sizeof(ranges[0]));
    std::vector<uint8_t> original(h.span);
    input.read(reinterpret_cast<char*>(original.data()), original.size());
    assert(input.good());
    auto data = original;
    MonochromeGateImageView image{data.data(), data.size(), ranges.data(), ranges.size()};
    auto verify = [&](bool good, const char* name, const char* reason = nullptr) {
        check(image, good, h.gate, h.supported, name, reason);
    };
    verify(true, "original ELF");
    verify(true, "repeat without mutation");
    assert(data == original);
    auto mutate = [&](uintptr_t pc, uint32_t word, const char* name, const char* reason = nullptr) {
        uint32_t saved = get(data, pc);
        put(data, pc, word);
        verify(false, name, reason);
        put(data, pc, saved);
    };
    uint32_t gate_word = get(data, h.gate);
    mutate(h.gate, 0xd503201f, "NOP gate");
    mutate(h.gate, gate_word ^ 0x01000000, "reversed TBNZ condition");
    mutate(h.gate, gate_word ^ (4u << 19), "wrong bool bit");
    mutate(h.gate, gate_word ^ 1, "wrong bool register");
    mutate(h.gate, (gate_word & ~0x7ffe0u) | (1u << 5), "target is fallthrough");
    mutate(h.gate, (gate_word & ~0x7ffe0u) |
           ((static_cast<uint32_t>((h.begin + h.size - h.gate) / 4) & 0x3fff) << 5),
           "target outside function but in RX mapping");
    mutate(h.gate, branch(h.gate, h.supported), "already patched gate fails closed");
    mutate(h.ensure, 0xd503201f, "broken manager call target");
    uintptr_t true_result = 0, false_result = 0, await_call = 0, complete = 0, wrapper_compare = 0;
    uintptr_t property_call = 0, property = 0;
    for (uintptr_t at = h.begin; at < h.begin + h.size; at += 4) {
        uint32_t w = get(data, at);
        if (w == 0x910082c0) true_result = at;
        if (w == 0x9100c2c0 && !false_result) false_result = at;
        if ((w & 0xfc000000) == 0x94000000) {
            uintptr_t to = at + displacement(w, 26);
            if (get(data, to) == 0xf85f83a3) await_call = at;
            if (get(data, to) == 0xa9bf79fd && get(data, to + 12) == 0xaa0103e3) {
                property_call = at;
                property = to;
            }
        }
    }
    assert(true_result && false_result && await_call && property_call && property);
    complete = true_result + 4 + displacement(get(data, true_result + 4), 26);
    for (uintptr_t at = h.caller; at < h.caller + h.caller_size; at += 4) {
        if (get(data, at) == 0x910082d0) wrapper_compare = at + 4;
    }
    assert(wrapper_compare);
    mutate(true_result, 0x9100c2c0, "supported path returns false");
    mutate(false_result, 0x910082c0, "SDK rejection path returns true");
    mutate(true_result + 4, branch(true_result + 4, h.ensure), "mismatched completion target");
    mutate(complete, 0xd503201f, "invalid async completion stub");
    mutate(await_call, branch(await_call, h.ensure, 0x94000000), "wrong await call");
    mutate(property_call, branch(property_call, h.ensure, 0x94000000), "wrong property query call");
    mutate(property, 0xd503201f, "invalid property query producer");
    mutate(wrapper_compare, 0x6b10003f, "caller compares wrong result", "caller");
    mutate(h.begin, 0xd503201f, "missing function prologue");
    mutate(h.begin + h.size - 8, 0x910082c0, "exception path returns true");

    // Calls must land in declared executable memory; readable data is excluded.
    auto without = [&](uintptr_t address, const char* name) {
        std::vector<MonochromeGateExecutableRange> parts;
        for (auto r : ranges) {
            if (address >= r.begin && address < r.end) {
                if (r.begin < address) parts.push_back({r.begin, address});
                if (address + 4 < r.end) parts.push_back({address + 4, r.end});
            } else parts.push_back(r);
        }
        MonochromeGateImageView hole{data.data(), data.size(), parts.data(), parts.size()};
        check(hole, false, 0, 0, name);
    };
    without(h.gate, "unreadable gate");
    without(h.supported, "unreadable target");
    without(complete, "non-executable completion");
    without(h.begin + h.size - 4, "truncated exception exit");

    // Range metadata is validated before any code read, including overflow.
    auto bad = image;
    bad.base = nullptr; check(bad, false, 0, 0, "null base");
    bad = image; bad.size = SIZE_MAX; check(bad, false, 0, 0, "span overflow");
    bad = image; bad.base = reinterpret_cast<uint8_t*>(UINTPTR_MAX - 3);
    check(bad, false, 0, 0, "base plus span overflow");
    bad = image; bad.executable_range_count = 0; check(bad, false, 0, 0, "no RX ranges");
    bad = image; bad.executable_range_count = 33; check(bad, false, 0, 0, "too many ranges");
    bad = image; bad.executable_ranges = nullptr; check(bad, false, 0, 0, "null ranges");
    MonochromeGateExecutableRange invalid{4, 0};
    bad = image; bad.executable_ranges = &invalid; bad.executable_range_count = 1;
    check(bad, false, 0, 0, "reversed range");
    invalid = {0, h.span + 4}; check(bad, false, 0, 0, "range outside span");
    invalid = {1, 4}; check(bad, false, 0, 0, "unaligned range");
    auto overlap = ranges; overlap.push_back(ranges[0]);
    bad = image; bad.executable_ranges = overlap.data(); bad.executable_range_count = overlap.size();
    check(bad, false, 0, 0, "overlapping ranges", "overlapping");
    const char* rejection = nullptr;
    assert(!ResolveMonochromeGate(image, nullptr, &rejection) && rejection);
    ++checks;

    // Rebase the entire wrapper/support pair, adjusting code-relative edges.
    // With cut!=0 one two-instruction pool load shrinks to one instruction;
    // the true block and B immediate must move accordingly.
    uintptr_t block_begin = h.caller, block_end = h.begin + h.size;
    assert(h.caller + h.caller_size == h.begin);
    uintptr_t relocated = (data.size() + 4095) & ~uintptr_t{4095};
    data.resize(relocated + 4096);
    ranges.push_back({relocated, relocated + 4096});
    auto reset_view = [&]() {
        image = {data.data(), data.size(), ranges.data(), ranges.size()};
    };
    auto clone = [&](uintptr_t cut) {
        auto remap = [&](uintptr_t at) { return relocated + at - block_begin - (cut && at > cut ? 4 : 0); };
        std::fill(data.begin() + relocated, data.end(), 0);
        for (uintptr_t at = block_begin; at < block_end; at += 4) {
            if (at == cut) continue;
            uint32_t w = get(original, at);
            unsigned bits = 0, shift = 0;
            if ((w & 0x7c000000) == 0x14000000) bits = 26;
            else if ((w & 0xff000010) == 0x54000000) { bits = 19; shift = 5; }
            else if ((w & 0x7e000000) == 0x36000000) { bits = 14; shift = 5; }
            if (bits) {
                uintptr_t to = at + displacement(w >> shift, bits);
                if (to >= block_begin && to < block_end) to = remap(to);
                int64_t delta = static_cast<int64_t>(to) - static_cast<int64_t>(remap(at));
                uint32_t mask = (uint32_t{1} << bits) - 1;
                assert(delta % 4 == 0 && delta / 4 >= -(int64_t{1} << (bits - 1)) &&
                       delta / 4 < (int64_t{1} << (bits - 1)));
                w = (w & ~(mask << shift)) | ((static_cast<uint32_t>(delta / 4) & mask) << shift);
            }
            if (cut && at + 4 == cut) w = 0xf9410361;  // LDR x1,[PP,#0x200]
            put(data, remap(at), w);
        }
        reset_view();
    };
    clone(0);
    verify(false, "two fully valid support functions", "ambiguous");
    put(data, h.begin, 0xd503201f);
    put(data, h.caller, 0xd503201f);
    check(image, true, relocated + h.gate - block_begin, relocated + h.supported - block_begin,
          "relocated graph without original entry");
    uintptr_t cut = 0;
    for (uintptr_t at = h.gate + 4; at < h.supported; at += 4) {
        if ((get(original, at) & 0xff8003ff) == 0x91000361) { cut = at + 4; break; }
    }
    assert(cut);
    clone(cut);
    check(image, true, relocated + h.gate - block_begin, relocated + h.supported - block_begin - 4,
          "changed pool encoding and derived branch displacement");

    // Duplicate a validated wrapper only: the support graph is no longer unique
    // in its calling relationship even though there is still only one gate.
    data = original;
    data.resize(relocated + 4096);
    clone(0);
    put(data, relocated + h.begin - block_begin, 0xd503201f);
    for (uintptr_t at = relocated; at < relocated + h.caller_size; at += 4) {
        uint32_t w = get(data, at);
        if ((w & 0xfc000000) == 0x94000000 &&
                at + displacement(w, 26) == relocated + h.begin - block_begin) {
            put(data, at, branch(at, h.begin, 0x94000000));
        }
    }
    verify(false, "ambiguous async callers", "caller");

    // A real PROT_NONE gap proves bounded scanning does not merely rely on
    // unused bytes being readable in the host vector.
    size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto* guarded = static_cast<uint8_t*>(mmap(nullptr, page * 3, PROT_NONE,
                                             MAP_PRIVATE | MAP_ANON, -1, 0));
    assert(guarded != MAP_FAILED);
    assert(mprotect(guarded + page, page, PROT_READ | PROT_WRITE) == 0);
    memset(guarded + page, 0, page);
    uint32_t prologue = 0xa9bf79fd;
    memcpy(guarded + page*2 - 4, &prologue, 4);
    assert(mprotect(guarded + page, page, PROT_READ) == 0);
    MonochromeGateExecutableRange bounded{page, page*2};
    MonochromeGateImageView sparse{guarded, page*3, &bounded, 1};
    check(sparse, false, 0, 0, "guard pages and partial prologue");
    assert(munmap(guarded, page*3) == 0);
    printf("PASS %zu checks; gate=0x%llx expected=0x%08x replacement=0x%08x\n", checks,
           static_cast<unsigned long long>(h.gate), gate_word, branch(h.gate, h.supported));
}
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--no-sanitize', action='store_true')
    parser.add_argument('elf', nargs='*', type=Path)
    args = parser.parse_args()
    fixtures = args.elf or DEFAULTS
    for fixture in fixtures:
        if not fixture.is_file():
            parser.error(f'missing external ELF fixture: {fixture}')
    with tempfile.TemporaryDirectory(prefix='keihome-monet-gate-') as temp:
        temp = Path(temp)
        harness = temp / 'host_test.cpp'
        harness.write_text(HARNESS)
        binary = temp / 'host_test'
        command = shlex.split(os.environ.get('CXX', 'clang++')) + [
            '-std=c++17', '-O1', '-g', '-Wall', '-Wextra', '-Werror', '-pedantic',
            '-I', str(ROOT / 'app/src/main/cpp'),
            str(ROOT / 'app/src/main/cpp/monochrome_gate_resolver.cpp'),
            str(harness), '-o', str(binary),
        ]
        if not args.no_sanitize:
            command += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        subprocess.run(command, check=True)
        for index, fixture in enumerate(fixtures):
            packed = temp / f'{index}.mapped'
            pack_fixture(fixture, packed)
            subprocess.run([str(binary), str(packed)], check=True)
    print('All host checks passed; no device access or Dart execution.', flush=True)


if __name__ == '__main__':
    main()

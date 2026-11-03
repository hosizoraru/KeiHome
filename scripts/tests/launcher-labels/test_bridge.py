#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute linked production ARM64 bridges; stub only original Dart callees.

Requires unicorn and pyelftools from the existing native-test environment.
This verifies register/root/return-PC contracts, not Flutter rendering or VM GC.
"""
import argparse
import math
from pathlib import Path
import struct
import subprocess
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn import arm64_const as ar

X = [getattr(ar, f"UC_ARM64_REG_X{i}") for i in range(31)]
CODE, BRIDGE, THREAD, FIELDS, POOL, POLICY, STACK, END = (
    0x10000000, 0x13000000, 0x30000000, 0x31000000, 0x32000000,
    0x33000000, 0x40010000, 0x50000000)


def linked_template(path):
    with open(path, "rb") as stream:
        elf = ELFFile(stream)
        syms = {s.name: s["st_value"] for s in elf.get_section_by_name(".symtab").iter_symbols()}
        begin, end = syms["kh_label_template"], syms["kh_label_end"]
        for segment in elf.iter_segments():
            if segment["p_type"] == "PT_LOAD" and segment["p_vaddr"] <= begin < end <= segment["p_vaddr"] + segment["p_filesz"]:
                stream.seek(segment["p_offset"] + begin - segment["p_vaddr"])
                return stream.read(end-begin), {n: syms["kh_label_"+n]-begin for n in ("pre", "post", "lines", "config")}
    raise AssertionError("bridge not in one file-backed ELF range")


def execute(template, entries, p, high, mode, width=150.0, maximum=80.0, pressure=False, relocate=False, change=None):
    heap = high << 32
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    for at, size in [(BRIDGE, 4096), (THREAD, 4096), (FIELDS, 0x10000), (POOL, 0x100000),
                     (POLICY, 4096), (STACK-0x10000, 0x20000), (END, 4096), (heap+0x100000, 0x20000)]:
        uc.mem_map(at, size)
    def put(at, value): uc.mem_write(at, struct.pack("<Q", value & 0xffffffffffffffff))
    def get(at): return struct.unpack("<Q", uc.mem_read(at, 8))[0]
    def word(at, value): uc.mem_write(at, struct.pack("<I", value & 0xffffffff))
    def number(at, value): uc.mem_write(at, struct.pack("<d", value))
    def obj(at, cid): put(at-1, (cid << 12) | 0x51c)
    null, grid, rx, config, source, double, text, style, original, size, direction = [
        heap + 0x100001 + 0x100*i for i in range(11)]
    for i, at in enumerate((null, grid, rx, config, source, double, text, style, original, size, direction)):
        obj(at, 100+i)
    obj(original, p["textCid"]); obj(size, p["sizeCid"]); obj(double, 62)
    top = heap + 0x110000
    put(THREAD + 0x60, top); put(THREAD + 0x68, top + (0x70 if pressure else 0x1000))
    put(THREAD + 0x78, FIELDS); put(FIELDS + p["grid"], grid)
    word(grid + 0x2b, rx); word(rx + 0x13, config)
    number(config + p["width"], 92.0)
    word(source + p["sourceWidth"], double); number(double + 7, maximum)
    number(size + 7, width); number(size + 15, 16.0)
    put(POOL + p["ltr"], direction); word(POLICY, mode)
    for slot, value in [("source", source), ("text", text), ("style", style), ("widget", null), ("line", 1)]:
        put(STACK + p[slot], value)
    data = [POLICY, CODE+p["textAlloc"], CODE+p["measure"], CODE+p["ctor"], CODE+p["alloc"]+20,
            CODE+p["after"], p["textCid"], p["sizeCid"], p["marqueeTag"], p["grid"], 0x2b, 0x13,
            p["width"], p["sourceWidth"], p["ltr"], p["source"], p["text"], p["style"], p["widget"], p["line"]]
    blob = bytearray(template)
    struct.pack_into("<20Q", blob, entries["config"], *(v & 0xffffffffffffffff for v in data))
    uc.mem_write(BRIDGE, bytes(blob))
    # Real methods return to this original allocation-site PC; emulate its detour.
    return_pc = CODE+p["alloc"]+4
    uc.mem_map(return_pc & ~4095, 4096)
    uc.mem_write(return_pc, struct.pack("<IIQ", 0x58000050, 0xd61f0200, BRIDGE+entries["post"]))
    pages = {return_pc & ~4095}
    for target in (CODE+p["textAlloc"], CODE+p["measure"], CODE+p["ctor"], CODE+p["after"], CODE+p["alloc"]+20):
        page = target & ~4095
        if page not in pages:
            uc.mem_map(page, 4096)
            pages.add(page)
    for i, reg in enumerate(X): uc.reg_write(reg, 0xa000+i)
    uc.reg_write(X[15], STACK-0x70); uc.reg_write(X[21], 0x21000000)
    uc.reg_write(X[22], null); uc.reg_write(X[26], THREAD); uc.reg_write(X[27], POOL)
    uc.reg_write(X[28], high); uc.reg_write(X[29], STACK); uc.reg_write(X[30], return_pc)
    uc.reg_write(ar.UC_ARM64_REG_SP, STACK+0xf000)
    calls, finished = [], []
    expected_text, expected_style, expected_marquee = text, style, None

    def hook(cpu, pc, count, unused):
        nonlocal expected_text, expected_style, expected_marquee
        if pc == CODE+p["measure"]:
            assert cpu.reg_read(X[30]) == return_pc
            assert (cpu.reg_read(X[1]), cpu.reg_read(X[2])) == (expected_text, expected_style)
            calls.append("measure")
            if relocate:
                expected_text += 0x3000; expected_style += 0x3000
                put(STACK+p["text"], expected_text); put(STACK+p["style"], expected_style)
            if change is not None: word(POLICY, change)
            cpu.reg_write(X[0], size); cpu.reg_write(ar.UC_ARM64_REG_PC, return_pc)
        elif pc == CODE+p["textAlloc"]:
            calls.append("Text")
            assert cpu.reg_read(X[30]) == return_pc
            cpu.reg_write(X[0], original); cpu.reg_write(ar.UC_ARM64_REG_PC, return_pc)
        elif pc == CODE+p["ctor"]:
            calls.append("Marquee")
            widget = cpu.reg_read(X[1]); expected_marquee = widget
            assert cpu.reg_read(X[30]) == return_pc
            assert get(widget-1) == p["marqueeTag"]
            assert cpu.reg_read(X[2]) == null
            assert cpu.reg_read(X[3]) == expected_style and cpu.reg_read(X[5]) == expected_text
            assert cpu.reg_read(X[6]) == direction and get(STACK+p["text"]) == widget
            assert get(THREAD+0x60) == top+0x70
            if relocate:
                expected_marquee = widget + 0x5000
                cpu.mem_write(expected_marquee-1, bytes(cpu.mem_read(widget-1, 0x70)))
                put(STACK+p["text"], expected_marquee)
            word(expected_marquee+0xb, expected_text); word(expected_marquee+0xf, expected_style)
            cpu.reg_write(X[0], null); cpu.reg_write(ar.UC_ARM64_REG_PC, return_pc)
        elif pc in (CODE+p["after"], CODE+p["alloc"]+20):
            finished.append(pc); cpu.emu_stop()

    uc.hook_add(UC_HOOK_CODE, hook)
    uc.emu_start(BRIDGE+entries["pre"], END, count=1000)
    assert len(finished) == 1, calls
    marquee = mode == 1 and change is None and math.isfinite(width) and not math.isnan(maximum) and width > min(92, maximum) > 0 and not pressure
    assert ("Marquee" in calls) == marquee, (mode, width, maximum, pressure, change, calls)
    assert get(STACK+p["text"]) == expected_text
    assert get(STACK+p["style"]) == expected_style
    assert get(STACK+p["source"]) == source
    assert get(STACK+p["widget"]) == (expected_marquee if marquee else original)
    if marquee: assert uc.reg_read(X[4]) == 2 and finished == [CODE+p["after"]]
    else:
        assert get(THREAD+0x60) == top
        assert uc.reg_read(X[2]) == expected_text and uc.reg_read(X[3]) == expected_style
    for i, value in [(15, STACK-0x70), (21, 0x21000000), (22, null), (26, THREAD), (27, POOL), (28, high), (29, STACK)]:
        assert uc.reg_read(X[i]) == value, (i, hex(uc.reg_read(X[i])))
    # Execute the actual line-result epilogue for both possible native results.
    for original_lines in (1, 2):
        word(POLICY, mode); put(STACK, STACK+0x100); put(STACK+8, END)
        uc.reg_write(X[1], original_lines); uc.reg_write(X[29], STACK); uc.reg_write(X[15], STACK-0x20)
        uc.emu_start(BRIDGE+entries["lines"], END, count=100)
        assert uc.reg_read(X[0]) == (original_lines if mode == 0 else mode)
        assert uc.reg_read(X[15]) == STACK+16 and uc.reg_read(X[29]) == STACK+0x100


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--payload", required=True)
    parser.add_argument("--resolver", required=True); parser.add_argument("launchers", nargs="+")
    args = parser.parse_args(); template, entries = linked_template(args.payload)
    total = 0
    for path in args.launchers:
        row = subprocess.check_output([args.resolver, path], text=True).splitlines()[0]
        decimal = {"textCid", "sizeCid", "source", "text", "style", "widget", "line"}
        p = {k: int(v, 10 if k in decimal else 16) for k, v in (pair.split("=") for pair in row.split())}
        for high in (0, 0x74):
            for mode in (0, 1, 2):
                for width in (60.0, 80.0, 80.01, 150.0, float("nan"), float("inf")):
                    execute(template, entries, p, high, mode, width=width); total += 1
                for kwargs in ({"pressure": True}, {"relocate": True}, {"change": 0}, {"change": 2}, {"maximum": float("nan")}):
                    execute(template, entries, p, high, mode, **kwargs); total += 1
        print(Path(path).parent.name, "bridge/root/return-PC cases passed")
    print(total, "ARM64 cases passed; actual Flutter/GC acceptance remains a device check")


if __name__ == "__main__": main()

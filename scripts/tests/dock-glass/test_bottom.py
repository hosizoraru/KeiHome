#!/usr/bin/env python3
"""Execute the linked native Dock margin leaf, including all preserved state."""
import argparse
import hashlib
import json
import struct
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X29, UC_ARM64_REG_X30,
    UC_ARM64_REG_Q0, UC_ARM64_REG_SP, UC_ARM64_REG_PC,
)

p = argparse.ArgumentParser()
p.add_argument("--elf", type=Path, required=True)
p.add_argument("--report", type=Path, required=True)
a = p.parse_args()
with a.elf.open("rb") as f:
    elf = ELFFile(f)
    syms = {s.name: int(s["st_value"])
            for sec in elf.iter_sections() if sec["sh_type"] == "SHT_SYMTAB"
            for s in sec.iter_symbols()}
    start, end = syms["kh_dock_template"], syms["kh_dock_payload_end"]
    seg = next(s for s in elf.iter_segments()
               if s["p_type"] == "PT_LOAD" and
               s["p_vaddr"] <= start < s["p_vaddr"] + s["p_filesz"])
    f.seek(seg["p_offset"] + start - seg["p_vaddr"])
    blob = f.read(end - start)

CODE, POLICY, STACK, DONE = 0x100000, 0x200000, 0x300000, 0x400000
entry = CODE + syms["kh_dock_bottom_margin"] - start
cfg = CODE + syms["kh_dock_config"] - start
cases = 0
for enabled in (0, 1, 2):
    for offset in (-48, -1, 0, 1, 24, 96):
        for native in (0.0, 8.0, 12.0, 24.0, 80.0, 256.0):
            for style in range(5):
                u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
                for addr in (CODE, POLICY, STACK, DONE):
                    u.mem_map(addr, 0x10000)
                u.mem_write(CODE, blob)
                u.mem_write(cfg, struct.pack("<Q", POLICY))
                u.mem_write(cfg + 296, struct.pack("<Q", POLICY + 8))
                options = style | ((offset & 255) << 16)
                u.mem_write(POLICY, struct.pack("<IIQ", enabled, 0, options))
                # X29/X30 are separate Unicorn constants; X0..X28 are contiguous.
                regs = [UC_ARM64_REG_X0 + i for i in range(29)]
                regs += [UC_ARM64_REG_X29, UC_ARM64_REG_X30, UC_ARM64_REG_SP]
                for i, reg in enumerate(regs):
                    u.reg_write(reg, 0x500000 + i * 16)
                u.reg_write(UC_ARM64_REG_SP, STACK + 0x8000)
                u.reg_write(UC_ARM64_REG_X30, DONE)
                for i in range(32):
                    u.reg_write(UC_ARM64_REG_Q0 + i, (i + 17) << 96)
                bits = struct.unpack("<Q", struct.pack("<d", native))[0]
                u.reg_write(UC_ARM64_REG_Q0, bits)
                before = {r: u.reg_read(r) for r in regs}
                vectors = [u.reg_read(UC_ARM64_REG_Q0 + i) for i in range(32)]

                def stop(cpu, pc, size, data):
                    if pc == DONE:
                        cpu.emu_stop()
                u.hook_add(UC_HOOK_CODE, stop)
                u.emu_start(entry, DONE + 4, count=200)
                assert u.reg_read(UC_ARM64_REG_PC) == DONE
                actual = struct.unpack("<d", struct.pack("<Q",
                    u.reg_read(UC_ARM64_REG_Q0) & ((1 << 64) - 1)))[0]
                expected = max(0.0, native - offset) if enabled == 1 and offset else native
                assert actual == expected, (enabled, offset, native, style, actual, expected)
                assert all(u.reg_read(r) == value for r, value in before.items()
                           if r not in (UC_ARM64_REG_X0 + 16, UC_ARM64_REG_X0 + 17))
                assert all(u.reg_read(UC_ARM64_REG_Q0 + i) == vectors[i]
                           for i in range(1, 32) if i != 16)
                cases += 1
a.report.write_text(json.dumps({"linked_native_margin_cases": cases, "sha256": hashlib.sha256(a.elf.read_bytes()).hexdigest(),
    "preserved": "all GP except IP0/IP1; all SIMD except result D0 and scratch V16",
    "managed_calls": 0, "allocations": 0}, indent=2) + "\n")
print(f"PASS: {cases} linked native margin cases")

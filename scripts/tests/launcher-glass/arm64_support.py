# SPDX-License-Identifier: Apache-2.0
"""Load the linked production ARM64 payload for bounded Unicorn checks."""
import struct
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X29, UC_ARM64_REG_X30, UC_ARM64_REG_SP,
    UC_ARM64_REG_PC, UC_ARM64_REG_NZCV, UC_ARM64_REG_Q0, UC_ARM64_REG_S0,
    UC_ARM64_REG_TPIDR_EL0,
)

BASE, STACK, HEAP, TRAP = 0x10000000, 0x50000000, 0x60000000, 0x70000000


def xreg(n):
    return UC_ARM64_REG_X0 + n if n < 29 else (UC_ARM64_REG_X29, UC_ARM64_REG_X30)[n - 29]


class Payload:
    def __init__(self, path):
        with path.open("rb") as f:
            elf = ELFFile(f)
            self.segments = [(s["p_vaddr"], s.data(), s["p_memsz"])
                             for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
            self.symbols = {s.name: (s["st_value"], s["st_size"])
                            for s in elf.get_section_by_name(".symtab").iter_symbols()}
            self.relative = []
            for section in elf.iter_sections():
                if section["sh_type"] != "SHT_RELA":
                    continue
                for r in section.iter_relocations():
                    if r["r_info_type"] == 1027:
                        self.relative.append((r["r_offset"], r["r_addend"]))

    def addr(self, name):
        return BASE + self.symbols[name][0]

    def uc(self):
        uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        end = max(a + n for a, _, n in self.segments)
        uc.mem_map(BASE, (end + 4095) & ~4095)
        for a, data, _ in self.segments:
            uc.mem_write(BASE + a, data)
        for offset, addend in self.relative:
            uc.mem_write(BASE + offset, struct.pack("<Q", BASE + addend))
        uc.mem_map(STACK, 65536)
        uc.mem_map(HEAP, 65536)
        uc.mem_map(TRAP, 65536)
        for offset in [512, 4096, 8192]:
            uc.mem_write(TRAP + offset, b"\xc0\x03\x5f\xd6")
        uc.reg_write(UC_ARM64_REG_SP, STACK + 60000)
        return uc


def read64(uc, address):
    return struct.unpack("<Q", uc.mem_read(address, 8))[0]


def write64(uc, address, value):
    uc.mem_write(address, struct.pack("<Q", value))

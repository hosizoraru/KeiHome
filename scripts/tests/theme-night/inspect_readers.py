#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Read-only disassembly of external ELF fixtures; never contacts a device."""
import argparse
import bisect
import hashlib
import io
import json
import lzma
from pathlib import Path
import sys

sys.dont_write_bytecode = True
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from capstone.arm64_const import ARM64_OP_IMM, ARM64_OP_REG
from elftools.elf.elffile import ELFFile


class Image:
    def __init__(self, path, demangled=None):
        self.path = Path(path)
        self.data = self.path.read_bytes()
        self.elf = ELFFile(io.BytesIO(self.data))
        self.loads = [(s["p_vaddr"], s.data()) for s in self.elf.iter_segments()
                      if s["p_type"] == "PT_LOAD"]
        debug = self.elf.get_section_by_name(".gnu_debugdata")
        tables = [s for s in self.elf.iter_sections()
                  if s["sh_type"] in ("SHT_SYMTAB", "SHT_DYNSYM")]
        if debug:
            d = ELFFile(io.BytesIO(lzma.decompress(debug.data())))
            tables += [s for s in d.iter_sections() if s["sh_type"] == "SHT_SYMTAB"]
        self.symbols = sorted(set((s["st_value"], s["st_size"], s.name)
                                 for t in tables for s in t.iter_symbols()
                                 if s.name and s["st_size"] and s["st_value"]))
        if demangled:
            self.symbols = [(int(a, 16), int(n, 16), name) for a, n, name in
                            (line.split(" ", 2) for line in Path(demangled).read_text().splitlines())
                            if int(n, 16)]
        self.by_addr = {a: (n, name) for a, n, name in self.symbols}
        self.addrs = sorted(self.by_addr)
        self.md = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        self.md.detail = True

    def read(self, address, size):
        for base, data in self.loads:
            if base <= address and address + size <= base + len(data):
                return data[address-base:address-base+size]
        raise ValueError(f"unmapped {address:x}+{size:x}")

    def symbol(self, address):
        i = bisect.bisect_right(self.addrs, address)-1
        if i >= 0:
            a = self.addrs[i]
            n, name = self.by_addr[a]
            if address < a+n:
                return name + (f"+0x{address-a:x}" if address != a else "")
        return f"0x{address:x}"

    def disassemble(self, address, size):
        ins = list(self.md.disasm(self.read(address, size), address))
        known = {}
        rows = []
        for i in ins:
            extra = []
            assigned = None
            if i.mnemonic in ("bl", "b") and i.operands[0].type == ARM64_OP_IMM:
                extra.append(self.symbol(i.operands[0].imm))
            # Annotate literal strings only when ADR/ADRP+ADD directly names
            # readable file bytes. Never dereference a hypothetical heap.
            literal = None
            if i.mnemonic in ("adr", "adrp") and i.operands[1].type == ARM64_OP_IMM:
                assigned = i.reg_name(i.operands[0].reg)
                known[assigned] = i.operands[1].imm
                if i.mnemonic == "adr":
                    literal = i.operands[1].imm
            elif i.mnemonic == "add" and len(i.operands) == 3:
                a, b, c = i.operands
                if a.type == b.type == ARM64_OP_REG and c.type == ARM64_OP_IMM:
                    source = known.get(i.reg_name(b.reg))
                    if source is not None:
                        literal = source + (c.imm << c.shift.value)
                        assigned = i.reg_name(a.reg)
                        known[assigned] = literal
            if literal is not None:
                try:
                    raw = self.read(literal, 96)
                    end = next((j for j, b in enumerate(raw) if not 32 <= b < 127), len(raw))
                    if end >= 3:
                        extra.append(f"file@0x{literal:x} {raw[:end]!r}")
                except ValueError:
                    pass
            for reg in i.regs_access()[1]:
                name = i.reg_name(reg)
                if name != assigned:
                    known.pop(name, None)
                    if name.startswith("w"):
                        known.pop("x"+name[1:], None)
            if i.mnemonic in ("bl", "blr") or i.mnemonic.startswith("b.") or i.mnemonic == "b":
                known = {}
            rows.append(f"{i.address:08x} +{i.address-address:03x} "
                        f"{i.mnemonic:7} {i.op_str}" + (" ; " + " | ".join(extra) if extra else ""))
        return rows


DART = [
    "ThemeResourcesSystem._createChain", "ThemeResourcesSystem._checkIconUpdate",
    "ThemeResourcesSystem._ensureIconInited", "ThemeResources.checkUpdate",
    "ThemePackage.checkUpdate", "ThemePackage._ensureZipLoaded",
    "ThemePackage._loadZipFile", "ThemePackage._getFileData",
    "ThemePackage._getFileFromZip", "ThemePackage._getFileFromDirectory",
    "ThemePackage._withRenameFallback", "ThemePackage._resolveFile",
    "ThemePackage._fallbackRenamedPath", "ThemePackage._loadFallbackMaps",
    "ThemePackage._parseFallbackXml", "ThemePackage._matchFallback",
    "ThemeResources.getFileStream", "ThemeResources._getIconBitmapLocal",
    "Archive.find", "ArchiveFile.readBytes", "ArchiveFile.getContent",
]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--library", type=Path, required=True)
    p.add_argument("--demangled", type=Path)
    p.add_argument("--contains", action="append")
    p.add_argument("--output", type=Path, required=True)
    a = p.parse_args()
    image = Image(a.library, a.demangled)
    selected = [(pc, size, name) for pc, size, name in image.symbols
                if (any(s in name for s in a.contains) if a.contains else name in DART)]
    lines, evidence = [], []
    for pc, size, name in selected:
        lines += [f"\n{name} 0x{pc:x}+0x{size:x}", *image.disassemble(pc, size)]
        evidence.append({"name": name, "rva": pc, "size": size})
    a.output.mkdir(parents=True, exist_ok=True)
    (a.output/"readers.txt").write_text("\n".join(lines)+"\n")
    (a.output/"readers.json").write_text(json.dumps({
        "library": str(image.path.resolve()),
        "sha256": hashlib.sha256(image.data).hexdigest(),
        "functions": evidence,
        "limits": "Offline external-image disassembly, no live roots or runtime acceptance.",
    }, indent=2)+"\n")
    print(f"{len(selected)} external code bodies: {a.output}")


if __name__ == "__main__":
    main()

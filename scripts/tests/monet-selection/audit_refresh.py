#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Independently disassemble contract extents against embedded debug symbols.

Consumes generator evidence, but never its instruction masks or fingerprints.
Output is offline evidence only and must not be included in a runtime resolver.
"""
import argparse
import difflib
import hashlib
import io
import json
import lzma
from pathlib import Path
import sys

sys.dont_write_bytecode = True
import capstone
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from elftools.elf.elffile import ELFFile


class Oracle:
    def __init__(self, path):
        self.path = Path(path)
        data = self.path.read_bytes()
        self.sha256 = hashlib.sha256(data).hexdigest()
        elf = ELFFile(io.BytesIO(data))
        self.loads = [(s["p_vaddr"], s.data()) for s in elf.iter_segments()
                      if s["p_type"] == "PT_LOAD"]
        self.executable = [(s["p_vaddr"], s["p_memsz"]) for s in elf.iter_segments()
                           if s["p_type"] == "PT_LOAD" and s["p_flags"] & 1]
        embedded = lzma.decompress(elf.get_section_by_name(".gnu_debugdata").data())
        assert embedded == self.path.with_name("debug.elf").read_bytes()
        debug = ELFFile(io.BytesIO(embedded))
        symbols = list(debug.get_section_by_name(".symtab").iter_symbols())
        self.symbols = [(s["st_value"], s["st_size"], s.name)
                        for s in symbols
                        if s["st_size"]]
        self.entries = {}
        for symbol in symbols:
            if symbol.name:
                self.entries.setdefault(symbol["st_value"], []).append(symbol.name)
        self.cs = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        self.cs.detail = True

    def read(self, pc, size):
        for start, data in self.loads:
            if start <= pc and pc + size <= start + len(data):
                return data[pc-start:pc-start+size]
        raise ValueError(f"unmapped extent {pc:#x}+{size:#x}")

    def describe(self, extent):
        pc, size = extent["rva"], extent["size"]
        owners = sorted((length, name, pc-start) for start, length, name in self.symbols
                        if start <= pc and pc+size <= start+length)
        assert any(start <= pc and pc+size <= start+length
                   for start, length in self.executable), (self.path, extent)
        if not owners:
            owners = [(size, "<unnamed executable>", 0)]
        code = list(self.cs.disasm(self.read(pc, size), pc))
        assert len(code)*4 == size and code[-1].address+4 == pc+size
        listing, canonical, branches = [], [], []
        for op in code:
            operands = op.op_str
            if (op.group(capstone.CS_GRP_JUMP) or
                    op.group(capstone.CS_GRP_CALL)) and op.operands:
                last = op.operands[-1]
                if last.type == capstone.arm64.ARM64_OP_IMM:
                    target = last.imm
                    if pc <= target < pc+size:
                        label = f"local+{target-pc:#x}"
                    else:
                        names = self.entries.get(target, [])
                        if names:
                            label = "|".join(sorted(names))
                        else:
                            target_owners = sorted((length, name, target-start)
                                                   for start, length, name in self.symbols
                                                   if start <= target < start+length)
                            if target_owners:
                                label = f"{target_owners[0][1]}+{target_owners[0][2]:#x}"
                            else:
                                assert any(start <= target < start+size
                                           for start, size in self.executable)
                                label = f"unnamed-executable@{target:#x}"
                    branches.append({"offset": op.address-pc, "target": target,
                                     "oracle": label})
                    operands = operands.rsplit("#", 1)[0] + label
            listing.append(f"{op.address-pc:04x} {op.mnemonic} {operands}")
            canonical.append(f"{op.mnemonic} {operands}")
        return {"owner": owners[0][1], "owner_offset": owners[0][2],
                "size": size, "branches": branches}, listing, canonical


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contracts", type=Path, action="append", required=True)
    parser.add_argument("--old-version", default="9239")
    parser.add_argument("--new-version", default="9248")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not __debug__:
        parser.error("assertions must be enabled")
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"passed": False, "capstone": capstone.__version__, "suites": {}}
    for evidence in args.contracts:
        rows = json.loads(evidence.read_text())
        selected = []
        for version in (args.old_version, args.new_version):
            matches = [row for row in rows
                       if Path(row.get("path", row.get("library", ""))).parent.name == version]
            assert len(matches) == 1
            row = matches[0]
            oracle = Oracle(row.get("path", row.get("library")))
            assert row["sha256"] == oracle.sha256
            selected.append((version, row, oracle))
        suite = {}
        assert selected[0][1]["roles"].keys() == selected[1][1]["roles"].keys()
        for role in selected[0][1]["roles"]:
            descriptions, listings, signatures = [], [], []
            for version, row, oracle in selected:
                description, listing, signature = oracle.describe(row["roles"][role])
                descriptions.append(description)
                listings.append(listing)
                signatures.append(signature)
                (args.output / f"{evidence.stem}-{role}-{version}.asm").write_text(
                    "\n".join(listing) + "\n")
            assert descriptions[0]["owner"] == descriptions[1]["owner"], (role, descriptions)
            delta = list(difflib.unified_diff(
                signatures[0], signatures[1], fromfile=args.old_version,
                tofile=args.new_version, lineterm=""))
            suite[role] = {"old": descriptions[0], "new": descriptions[1], "delta": delta}
        report["suites"][evidence.stem] = suite
        print(f"{evidence.stem}: {len(suite)} roles independently decoded")
    report["passed"] = True
    (args.output / "audit.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Host-only graph regressions; input ELF/APK files are never modified.

Example (builds just the host test_profile executable):
  python test_graph_relocations.py --fixture LIBAPP DEBUG_ELF \
      --fixture OTHER_LIBAPP OTHER_DEBUG_ELF --output-dir EVIDENCE_DIR

Fixture supplies independent debug-symbol roles and complete function sizes.
Capstone supplies branch and load operands. No generator canonicalization,
contract generation, device tools, or execution of ARM64 code is involved.
"""
import argparse
from collections import defaultdict, deque
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
from capstone import Cs, CS_ARCH_ARM64, CS_GRP_JUMP, CS_MODE_LITTLE_ENDIAN
from capstone.arm64 import ARM64_OP_IMM, ARM64_OP_MEM, ARM64_OP_REG
from generate_contracts import Fixture

HERE = Path(__file__).resolve().parent
CPP = HERE.parents[2] / "app/src/main/cpp"
DECODER = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
DECODER.detail = True


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def word(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def put(data, offset, value):
    struct.pack_into("<I", data, offset, value)


def instructions(fixture, address, size):
    data = fixture.elf.read(address, size)
    result = list(DECODER.disasm(data, address))
    require(len(data) == size and len(result) * 4 == size,
            f"incomplete debug function at {address:#x}")
    return result


def destination(op):
    if (op.mnemonic in ("bl", "b") or op.group(CS_GRP_JUMP)):
        if op.operands and op.operands[-1].type == ARM64_OP_IMM:
            return op.operands[-1].imm
    return None


def retarget(data, address, target):
    original = word(data, address)
    require(original & 0x7c000000 == 0x14000000, "expected direct B/BL")
    delta = target - address
    require(delta % 4 == 0 and -(1 << 27) <= delta < (1 << 27),
            "relocated branch is out of range")
    put(data, address, (original & 0xfc000000) | ((delta // 4) & 0x3ffffff))
    decoded = list(DECODER.disasm(data[address:address + 4], address))
    require(len(decoded) == 1 and destination(decoded[0]) == target,
            "independent branch re-decode failed")


@dataclass(frozen=True)
class Node:
    address: int
    length: int
    scope: int
    depth: int
    path: tuple


def dependency_nodes(fixture):
    """Walk real direct calls/branches, retaining the debug function scope.

    Complete roots and first-layer functions expose the next calls. Deeper
    nodes expose 32-byte prefixes but internal branches remain in full scope.
    Unknown helper sizes use only the bounded observation window; relocation
    candidates themselves always require a complete debug function size.
    """
    pending = deque(Node(a, n, n, 0, (role,))
                    for role, (a, n) in fixture.roles.items())
    seen, nodes, revisits = set(), [], 0
    while pending:
        node = pending.popleft()
        key = (node.address, node.length, node.scope)
        if key in seen:
            revisits += 1
            continue
        require(len(seen) < 4096, "oracle dependency traversal exceeded bound")
        seen.add(key)
        nodes.append(node)
        for op in instructions(fixture, node.address, node.length):
            target = destination(op)
            if target is None or node.address <= target < node.address + node.scope:
                continue
            if target in fixture.by_address:
                continue
            scope = fixture.sizes.get(target, 128 if node.depth == 0 else 32)
            length = scope if node.depth == 0 else min(32, scope)
            pending.append(Node(target, length, scope, node.depth + 1,
                                node.path + (fixture.names.get(target, hex(target)),)))
    return nodes, revisits


def relocation_candidate(fixture, nodes):
    choices = []
    for node in nodes:
        if node.depth < 2:
            continue
        for call in instructions(fixture, node.address, node.length):
            target = destination(call)
            size = fixture.sizes.get(target, 0)
            if (call.mnemonic != "bl" or target in fixture.by_address or
                    node.address <= target < node.address + node.scope or
                    not 32 <= size <= 128):
                continue
            full = instructions(fixture, target, size)
            branches, bad = [], False
            for op in full:
                end = destination(op)
                if op.mnemonic in ("adr", "adrp"):
                    bad = True
                if (op.mnemonic in ("ldr", "ldrsw", "prfm") and
                        len(op.operands) > 1 and op.operands[1].type == ARM64_OP_IMM):
                    bad = True  # Literal loads need a different relocation proof.
                if end is not None and not target <= end < target + size:
                    if op.mnemonic not in ("bl", "b") or end in fixture.by_address:
                        bad = True
                    branches.append(op)
            # Keep the first 16 bytes literally equal, and exercise a nested
            # external edge inside the prefix, not merely a leaf relocation.
            nested = [op for op in branches if 16 <= op.address - target < 32]
            semantic = [op for op in full if 16 <= op.address - target < 32
                        and destination(op) is None and
                        op.mnemonic not in ("nop", "ret", "br", "blr")]
            if (bad or not nested or not semantic or
                    any(op.address - target < 16 for op in branches)):
                continue
            choices.append((node, call, target, size, full, branches,
                            nested[0], semantic[-1]))
    require(choices, "no complete small deep callee with a nested prefix edge")
    return min(choices, key=lambda c: (c[0].depth, c[1].address))


def append_in_rx_padding(fixture, original, size):
    """Extend just the variant RX header into its existing zero alignment gap."""
    data = bytearray(original)
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phsize = struct.unpack_from("<H", data, 54)[0]
    sections = fixture.elf.sections
    for index, p in enumerate(fixture.elf.loads):
        if p[0] != 1 or not p[1] & 1:
            continue
        require(p[2] == p[3] and p[5] == p[6],
                "probe requires file-backed equal VA/file-offset RX")
        end = p[3] + p[5]
        start = (end + 15) & ~15
        limit = start + size
        next_load = min((q[3] for q in fixture.elf.loads
                         if q[0] == 1 and q[3] >= end), default=len(data))
        if limit > min(next_load, len(data)) or any(data[end:limit]):
            continue
        require(not any(s[1] != 8 and s[5] and s[4] < limit and
                        end < s[4] + s[5] for s in sections),
                "RX padding overlaps a file section")
        require(not (end < phoff + len(fixture.elf.loads) * phsize and phoff < limit),
                "RX padding overlaps program headers")
        shoff = struct.unpack_from("<Q", data, 40)[0]
        shsize, shcount = struct.unpack_from("<HH", data, 58)
        require(not (end < shoff + shsize * shcount and shoff < limit),
                "RX padding overlaps section headers")
        struct.pack_into("<QQ", data, phoff + index * phsize + 32,
                         limit - p[3], limit - p[3])
        return data, start
    raise AssertionError("no zero RX alignment gap for complete callee copy")


def copied_callee(fixture, original, candidate):
    node, call, source, size, full, branches, nested, semantic = candidate
    data, target = append_in_rx_padding(fixture, original, size)
    data[target:target + size] = fixture.elf.read(source, size)
    repairs = []
    for op in branches:
        new_pc = target + op.address - source
        retarget(data, new_pc, destination(op))
        repairs.append({"offset": op.address - source, "kind": op.mnemonic,
                        "target": hex(destination(op))})
    retarget(data, call.address, target)
    require(data[target:target + 16] == original[source:source + 16],
            "relocation did not preserve the promised first 16 bytes")
    require(data[source:source + size] == original[source:source + size],
            "positive control overwrote the original callee")
    # Local branches keep their encoding and land at the same relative offset.
    for op in full:
        end = destination(op)
        if end is not None and source <= end < source + size:
            decoded = list(DECODER.disasm(
                data[target + op.address - source:target + op.address - source + 4],
                target + op.address - source))
            require(destination(decoded[0]) == target + end - source,
                    "internal branch changed scope during relocation")
    return data, target, {
        "path": list(node.path), "caller_depth": node.depth,
        "caller": hex(node.address), "caller_prefix_bytes": node.length,
        "call": hex(call.address), "source": hex(source),
        "source_name": fixture.names[source], "complete_debug_size": size,
        "copy": hex(target), "external_branch_repairs": repairs,
        "original_callee_preserved": True,
        "first_16_bytes_equal": True,
        "semantic_mutation_offset": semantic.address - source,
        "nested_edge_offset": nested.address - source,
    }


def pp_groups(fixture, nodes):
    choices = []
    for node in nodes:
        ops = instructions(fixture, node.address, node.length)
        groups = defaultdict(list)
        for index, op in enumerate(ops):
            if (op.mnemonic != "ldr" or op.writeback or len(op.operands) != 2 or
                    op.operands[0].type != ARM64_OP_REG or
                    op.operands[1].type != ARM64_OP_MEM):
                continue
            dest = op.reg_name(op.operands[0].reg)
            mem = op.operands[1].mem
            if mem.index or mem.disp < 0 or mem.disp % 8 or dest[0] not in ("x", "d"):
                continue
            slot, add = mem.disp, None
            if op.reg_name(mem.base) != "x27":
                if not index:
                    continue
                prev = ops[index - 1]
                args = prev.operands
                if not (prev.mnemonic == "add" and len(args) == 3 and
                        args[0].type == ARM64_OP_REG and args[0].reg == mem.base and
                        args[1].type == ARM64_OP_REG and prev.reg_name(args[1].reg) == "x27"
                        and args[2].type == ARM64_OP_IMM and args[2].shift.value == 12):
                    continue
                slot += args[2].imm << 12
                add = prev.address
            groups[slot].append({"load": op.address, "add": add,
                                 "dest": dest, "instruction": op.op_str})
        for slot, uses in groups.items():
            for form in ("direct", "page"):
                doubles = [u for u in uses if u["dest"].startswith("d")
                           and (u["add"] is None) == (form == "direct")]
                if not doubles:
                    continue
                if any(u["load"] - node.address < 16 or
                       (u["add"] is not None and u["add"] - node.address < 16)
                       for u in uses):
                    continue
                # Select an unused slot independently, preserving all aliases.
                new = slot + 4096 + 8
                ceiling = 32760 if any(u["add"] is None for u in uses) else 0xffffff
                while new in groups and new <= ceiling:
                    new += 8
                if new > ceiling:
                    continue
                choices.append((form, node, slot, new, uses, doubles[0]))
    selected = []
    for form in ("direct", "page"):
        options = [c for c in choices if c[0] == form]
        require(options, f"no independently decoded {form} scalar-double PP use")
        selected.append(min(options, key=lambda c: (-len(c[4]), c[1].depth,
                                                    c[5]["load"])))
    return selected


def move_pp(data, uses, new):
    for use in uses:
        load = use["load"]
        low = new if use["add"] is None else new & 0xfff
        require(low % 8 == 0 and low // 8 < 4096, "PP displacement overflow")
        put(data, load, (word(data, load) & ~(0xfff << 10)) | ((low // 8) << 10))
        if use["add"] is not None:
            put(data, use["add"], (word(data, use["add"]) & ~(0xfff << 10)) |
                ((new >> 12) << 10))
        # Decode the new effective address without reusing contract normalizers.
        op = list(DECODER.disasm(data[load:load + 4], load))[0]
        decoded = op.operands[1].mem.disp
        if use["add"] is not None:
            add = use["add"]
            prev = list(DECODER.disasm(data[add:add + 4], add))[0]
            decoded += prev.operands[2].imm << prev.operands[2].shift.value
        require(decoded == new and op.reg_name(op.operands[0].reg) == use["dest"],
                "PP relocation changed more than the independently decoded slot")


def build_probe(output):
    compiler = shutil.which(os.environ.get("CXX", "clang++"))
    require(compiler, "host C++ compiler not found")
    binary = output / "test_profile"
    command = [compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
               "-I", str(CPP), str(HERE / "test_profile.cpp"),
               str(CPP / "selection_profile.cpp"),
               str(CPP / "monet_image_identity.cpp"), "-o", str(binary)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (output / "build.log").write_text(result.stdout + result.stderr)
    require(result.returncode == 0, f"host compilation failed: {result.stderr}")
    return binary, command


def test_fixture(binary, library, debug, output, temp, report, save):
    library, debug = library.resolve(), debug.resolve()
    original, debug_hash = library.read_bytes(), sha(debug.read_bytes())
    fixture = Fixture(library, debug)
    require(all(p[2] == p[3] and p[3] + p[6] <= len(original)
                for p in fixture.elf.loads if p[0] == 1),
            "host probe requires flat, file-backed PT_LOAD ranges")
    nodes, revisits = dependency_nodes(fixture)
    entry = {"library": str(library), "debug": str(debug),
             "library_sha256": sha(original), "debug_sha256": debug_hash,
             "oracle_nodes": len(nodes), "oracle_revisits": revisits, "cases": []}
    report["fixtures"].append(entry)
    save()

    def check(name, data, accepted, evidence=None):
        variant = temp / "variant.so"
        variant.write_bytes(data)
        try:
            result = subprocess.run([str(binary), "--probe", str(variant)],
                                    capture_output=True, text=True, timeout=90)
            expected = "accepted" if accepted else "rejected"
            good = (result.returncode == (0 if accepted else 1) and
                    result.stdout.strip() == expected)
            case = {"name": name, "expected": expected, "passed": good,
                    "returncode": result.returncode, "stdout": result.stdout,
                    "stderr": result.stderr, "variant_sha256": sha(data)}
            if evidence is not None:
                case["evidence"] = evidence
            entry["cases"].append(case)
            save()
            with (output / "probes.log").open("a") as log:
                log.write(json.dumps({"library": str(library), **case}) + "\n")
            print(f'{"PASS" if good else "FAIL"} {library.parent.name}: {name}',
                  flush=True)
            require(good, f"{name}: expected {expected}, got {result.stdout!r} "
                    f"exit={result.returncode}: {result.stderr}")
        finally:
            variant.unlink(missing_ok=True)

    check("original", original, True)
    candidate = relocation_candidate(fixture, nodes)
    moved, target, evidence = copied_callee(fixture, original, candidate)
    check("deep-external-bl-equivalent-complete-callee", moved, True, evidence)

    bad = bytearray(moved)
    semantic_offset = evidence["semantic_mutation_offset"]
    put(bad, target + semantic_offset, 0xd503201f)  # Remove a real semantic instruction.
    require(bad[target:target + 16] == original[candidate[2]:candidate[2] + 16],
            "semantic impostor must retain its first 16 bytes")
    check("same-first-16-different-prefix-semantics", bad, False, evidence)

    bad = bytearray(moved)
    # Only a normalized BL displacement changes in the copied prefix. Point it
    # back to the original complete function, creating a wrong dependency cycle.
    retarget(bad, target + evidence["nested_edge_offset"], candidate[2])
    require(bad[target:target + 16] == original[candidate[2]:candidate[2] + 16],
            "subtarget impostor must retain its first 16 bytes")
    check("same-first-16-different-subtarget-cycle", bad, False, evidence)

    for form, node, old, new, uses, chosen in pp_groups(fixture, nodes):
        pp_evidence = {"path": list(node.path), "depth": node.depth,
                       "old_slot": hex(old), "new_slot": hex(new),
                       "uses": uses, "scalar_double": chosen}
        moved_pp = bytearray(original)
        move_pp(moved_pp, uses, new)
        check(f"pp-double-{form}-consistent-slot-relocation",
              moved_pp, True, pp_evidence)
        address = chosen["load"]
        value = word(moved_pp, address)
        for name, changed in (
                ("opcode-store", value ^ (1 << 22)),
                ("width-single", value ^ (1 << 30)),
                ("destination-register", (value & ~31) | (((value & 31) + 1) % 32)),
                ("base-register", (value & ~(31 << 5)) |
                 (((((value >> 5) & 31) + 1) % 31) << 5))):
            bad = bytearray(moved_pp)
            put(bad, address, changed)
            decoded = list(DECODER.disasm(bad[address:address + 4], address))
            require(len(decoded) == 1, "negative PP mutation is not an instruction")
            check(f"pp-double-{form}-reject-{name}", bad, False,
                  {**pp_evidence, "changed_instruction":
                   decoded[0].mnemonic + " " + decoded[0].op_str})
        if len(uses) > 1:
            bad = bytearray(original)
            move_pp(bad, uses[:1], new)
            check(f"pp-double-{form}-reject-inconsistent-aliases",
                  bad, False, pp_evidence)

    require(sha(library.read_bytes()) == entry["library_sha256"] and
            sha(debug.read_bytes()) == debug_hash, "read-only fixture changed during run")
    entry["fixtures_unchanged"] = True
    save()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", nargs=2, action="append", required=True,
                        metavar=("LIBAPP", "DEBUG_ELF"), type=Path)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--probe-binary", type=Path,
                        help="reuse an already compiled host test_profile")
    args = parser.parse_args()
    output = args.output_dir.resolve()
    for pair in args.fixture:
        for source in pair:
            source = source.resolve()
            require(output != source.parent and source not in output.parents,
                    "evidence directory must not overwrite fixture inputs")
    output.mkdir(parents=True, exist_ok=True)
    report = {"host_only": True, "canonicalizer_used": False, "fixtures": [],
              "status": "running", "large_variants_removed": False}

    def save():
        (output / "evidence.json").write_text(json.dumps(report, indent=2) + "\n")

    try:
        binary = args.probe_binary.resolve() if args.probe_binary else None
        if binary is None:
            binary, report["host_compile_command"] = build_probe(output)
        report["probe_binary"] = str(binary)
        report["probe_binary_sha256"] = sha(binary.read_bytes())
        report["source_sha256"] = {
            str(p): sha(p.read_bytes()) for p in
            (HERE / "test_profile.cpp", Path(__file__).resolve(),
             CPP / "selection_profile.cpp", CPP / "monet_selection_patterns.h",
             HERE / "generate_contracts.py")}
        with tempfile.TemporaryDirectory(prefix="variants-", dir=output) as directory:
            for library, debug in args.fixture:
                test_fixture(binary, library, debug, output, Path(directory), report, save)
        report["status"] = "passed"
    except Exception as error:
        report["status"] = "failed"
        report["error"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        report["large_variants_removed"] = not any(output.glob("variants-*/variant.so"))
        save()
    print(f"PASS evidence: {output / 'evidence.json'}", flush=True)


if __name__ == "__main__":
    main()

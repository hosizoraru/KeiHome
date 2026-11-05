#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute the three linked production Dock glass scope leaves in Unicorn.

Run ONLY after rebuilding the unstripped production arm64 SO:
  python3 scripts/tests/dock-glass/test_glass_scope.py --elf /path/to/lib.so
Optional: --report /path/to/glass-scope.json

Requires unicorn, pyelftools and capstone. No assembler, replacement leaf,
Gradle, device, native allocator or managed runtime is invoked. The complete
installer-copy payload is relocated; only its 456-byte config is authored here.
Native continuations/allocators are terminal sentinels, not mocked leaf bodies.
This verifies execution/ABI contracts, not visual material acceptance on an AVD.
"""

import argparse
import hashlib
import io
import itertools
import json
import re
import struct
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from unicorn import Uc, UcError, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE
from unicorn import arm64_const as ar


MASK32, MASK64 = (1 << 32) - 1, (1 << 64) - 1
CONFIG_SIZE, KEY_SIZE, MARKER = 456, 16, 0x969088
X = [getattr(ar, f"UC_ARM64_REG_X{i}") for i in range(31)]
Q = [getattr(ar, f"UC_ARM64_REG_Q{i}") for i in range(32)]
MD = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
THREAD, CONTROL, POOL = 0x20000000, 0x21000000, 0x22000000
STACK, TERMINALS = 0x30008000, 0x40000000
SCOPE_RESUME, CHILD_RESUME = TERMINALS + 0x100, TERMINALS + 0x200
SUPER_ALLOC, STOCK_ALLOC, ORIGINAL_LR = TERMINALS + 0x300, TERMINALS + 0x400, TERMINALS + 0x500
VISUAL_RESUME = TERMINALS + 0x600
KEY_TAG = (0x4567 << 12) | (1 << 8) | 0x1c  # fresh ValueKey16
ROOT_SLOT, CHILD_SLOT, RADIUS_SLOT = -8, -40, -48


class Payload:
    def __init__(self, path):
        raw = path.read_bytes()
        self.path, self.sha256 = str(path.resolve()), hashlib.sha256(raw).hexdigest()
        elf = ELFFile(io.BytesIO(raw))
        assert elf["e_machine"] == "EM_AARCH64" and elf["e_type"] == "ET_DYN", "Expected linked production AArch64 SO"
        table = elf.get_section_by_name(".symtab")
        assert table is not None, "Use the unstripped production CMake SO"
        symbols = {s.name: s["st_value"] for s in table.iter_symbols()}
        required = ("kh_dock_template", "kh_dock_config", "kh_dock_refresh_begin", "kh_dock_payload_end",
                    "kh_dock_glass_scope", "kh_dock_glass_child_scope", "kh_dock_glass_clip", "kh_dock_recents_radius", "kh_dock_recents_flight_radius")
        missing = [name for name in required if name not in symbols]
        assert not missing, f"Rebuild the production SO with the scope leaves; missing symbols: {missing}"
        origin, end = symbols["kh_dock_template"], symbols["kh_dock_payload_end"]
        assert 0 < end - origin <= 65536, "Unexpected installer-copy payload size"
        self.offsets = {name: symbols[name] - origin for name in required}
        assert all(0 <= off <= end - origin for off in self.offsets.values())
        self.config = self.offsets["kh_dock_config"]
        assert self.offsets["kh_dock_refresh_begin"] - self.config == CONFIG_SIZE, "Rebuild SO: expected 57-word/456-byte render config"
        self.entries = {name: self.offsets["kh_dock_glass_" + name] for name in ("scope", "child_scope", "clip")}
        self.entries["radius"]=self.offsets["kh_dock_recents_radius"]
        self.entries["flight"]=self.offsets["kh_dock_recents_flight_radius"]
        assert len(set(self.entries.values())) == 5
        assert all(0 <= off < self.config and off % 4 == 0 for off in self.entries.values())
        for segment in elf.iter_segments():
            if (segment["p_type"] == "PT_LOAD" and segment["p_vaddr"] <= origin
                    and end <= segment["p_vaddr"] + segment["p_filesz"]):
                at = segment["p_offset"] + origin - segment["p_vaddr"]
                self.blob = raw[at:at + end - origin]
                break
        else:
            raise AssertionError("Copied production payload must fit one file-backed PT_LOAD")
        for section in elf.iter_sections():
            if isinstance(section, RelocationSection):
                assert not any(origin <= r["r_offset"] < end for r in section.iter_relocations()), "Dynamic relocation inside copied payload"


def double_bits(value):
    return struct.unpack("<Q", struct.pack("<d", value))[0]


class Machine:
    def __init__(self, payload, *, code=0x10000000, high=1, signed_low=False,
                 truth=True, curve=1, key="owned", pressure=17,
                 radius_bits=None, scope_slot=ROOT_SLOT, child_slot=CHILD_SLOT,
                 radius_slot=RADIUS_SLOT):
        self.payload, self.code = payload, code
        self.high, self.heap = high, high << 32
        self.radius_bits = double_bits(56.0) if radius_bits is None else radius_bits
        self.scope_slot, self.child_slot, self.radius_slot = scope_slot, child_slot, radius_slot
        assert all(abs(a - b) >= 8 for a, b in itertools.combinations(
            (scope_slot, child_slot, radius_slot), 2)), "Authored frame slots overlap"
        self.u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.heap_start = self.heap + (0x80100000 if signed_low else 0x100000)
        self.top = self.heap_start + 0x10000
        self.null = self.heap_start + 1
        self.boolean = self.null + (0x20 if truth else 0x30)
        self.widget, self.key, self.child = (self.heap_start + n for n in (0x401, 0x601, 0x801))
        self.other = self.heap_start + 0xa01
        self.curve, self.key_kind, self.pressure = curve, key, pressure
        self.scope_root, self.child_root = STACK + scope_slot, STACK + child_slot
        self.radius_store = STACK + radius_slot
        for address, length in ((code, (len(payload.blob) + 4095) & ~4095),
                                (THREAD, 4096), (CONTROL, 4096), (POOL, 4096),
                                (STACK - 0x8000, 0x10000), (TERMINALS, 4096),
                                (self.heap_start, 0x20000)):
            self.u.mem_map(address, length)
        self.u.mem_write(code, payload.blob)
        self.u.mem_write(self.heap_start, b"\xa5" * 0x20000)
        self.u.mem_write(STACK - 0x8000, b"\x5a" * 0x10000)
        self.u.mem_write(CONTROL, b"\x6b" * 4096)
        self.put(CONTROL, 1, 4)
        self.put(CONTROL + 8, curve, 4)
        self.put(CONTROL + 24, 1, 4)
        cfg = [0] * 57
        for offset, value in {
                0: CONTROL, 296: CONTROL + 24, 304: CONTROL + 8,
                312: KEY_TAG, 320: SUPER_ALLOC, 328: STOCK_ALLOC,
                336: SCOPE_RESUME, 344: CHILD_RESUME, 352: radius_slot,
                360: double_bits(20.0), 368: double_bits(30.0),
                376: scope_slot, 384: child_slot, 392: CONTROL+32, 400: CONTROL+64, 408: ORIGINAL_LR, 416: -56, 424: VISUAL_RESUME}.items():
            cfg[offset // 8] = value & MASK64
        self.config = struct.pack("<57Q", *cfg)
        self.u.mem_write(code + payload.config, self.config)
        self.put(THREAD + 96, self.top)
        self.put(THREAD + 104, self.top + pressure)
        self.put(THREAD + 0x78, POOL)
        self.put(POOL + 0x40, self.other)
        self.put(self.null - 1, 0x51c)
        self.make_key(self.key, key)
        self.put(self.widget - 1, 0x112211c)
        self.put(self.widget + 7, self.key_value(key), 4)
        self.put(self.child - 1, 0x113321c)
        self.put(self.child + 7, self.null, 4)
        self.put(self.scope_root, self.widget)
        # Dead future native child root: capture MUST replace all eight bytes.
        self.put(self.child_root, 0x5a5a5a5a96908800)
        # A separate existing Bool root must remain untouched through all leaves.
        self.bool_root = STACK - 0x100
        self.put(self.bool_root, self.boolean)
        self.writes, self.reads, self.trace = [], [], []
        self.allowed, self.poisoned = [], []
        self.terminal = None
        self.u.hook_add(UC_HOOK_CODE, self.on_code)
        self.u.hook_add(UC_HOOK_MEM_WRITE, self.on_write)
        self.u.hook_add(UC_HOOK_MEM_READ, self.on_read)

    def put(self, address, value, size=8):
        self.u.mem_write(address, int(value & ((1 << (size * 8)) - 1)).to_bytes(size, "little"))

    def get(self, address, size=8):
        return int.from_bytes(self.u.mem_read(address, size), "little")

    def key_value(self, kind):
        return self.null if kind == "null" else 2 if kind == "smi" else self.key

    def make_key(self, address, kind="owned"):
        self.put(address - 1, KEY_TAG if kind != "cid" else KEY_TAG ^ (1 << 12))
        self.put(address + 7, self.null, 4)
        self.put(address + 0xb, MARKER if kind != "marker" else MARKER + 2, 4)

    def on_write(self, cpu, access, address, size, value, data):
        if CONTROL+64<=address and address+size<=CONTROL+88:
            return  # Dedicated primitive diagnostics, never managed storage.
        assert any(lo <= address and address + size <= hi for lo, hi in self.allowed), (
            self.leaf, "write outside fresh key/owned frame/fresh child key", hex(address), size)
        self.writes.append((address, size, value & ((1 << (8 * size)) - 1)))

    def on_read(self, cpu, access, address, size, value, data):
        assert not any(address < hi and lo < address + size for lo, hi in self.poisoned), (
            self.leaf, "read of dropped parent/root/key after capture", hex(address), size)
        self.reads.append((address, size))

    def on_code(self, cpu, pc, size, data):
        if pc in (SCOPE_RESUME, CHILD_RESUME, SUPER_ALLOC, STOCK_ALLOC, ORIGINAL_LR, VISUAL_RESUME):
            self.terminal = pc
            cpu.emu_stop()
            return
        assert self.code <= pc < self.code + self.payload.config, (self.leaf, "escaped copied leaf", hex(pc))
        instruction = next(MD.disasm(bytes(cpu.mem_read(pc, size)), pc), None)
        assert instruction is not None and instruction.size == 4
        assert not instruction.mnemonic.startswith(("bl", "svc", "hvc", "smc")), (
            self.leaf, "leaf must not call or reach a safepoint", instruction.mnemonic)
        assert not re.search(r"\b(?:sp|wsp)\b", instruction.op_str), (self.leaf, "SP access", instruction.op_str)
        self.trace.append((pc, instruction.mnemonic, instruction.op_str))

    def run(self, leaf, x0, *, allowed=(), expected=None):
        self.leaf = leaf
        self.writes, self.reads, self.trace = [], [], []
        self.allowed, self.terminal = list(allowed), None
        # Native calls can destroy scratch registers. Only the Smi frame slot
        # carries ownership across the two calls; no captured pointer is reused.
        for i, register in enumerate(X):
            self.u.reg_write(register, 0x7777000000000000 + i * 0x101)
        for i, register in enumerate(Q):
            self.u.reg_write(register, 0x11112222333344445555666677770000 + i)
        self.u.reg_write(Q[0], (0xfeedface01234567 << 64) | self.radius_bits)
        if leaf=="flight":
            self.u.reg_write(Q[3],(0xfeedface01234567 << 64)|self.radius_bits)
            self.put(STACK-56, double_bits(123.0))
        for i, value in {0: x0, 1: self.boolean, 15: STACK - 0x180, 22: self.null,
                         26: THREAD, 27: POOL, 28: self.high, 29: STACK, 30: ORIGINAL_LR}.items():
            self.u.reg_write(X[i], value)
        self.u.reg_write(ar.UC_ARM64_REG_SP, STACK + 0x1000)
        self.u.reg_write(ar.UC_ARM64_REG_NZCV, 0xa0000000)
        before_x = [self.u.reg_read(r) for r in X]
        before_q = [self.u.reg_read(r) for r in Q]
        before_sp = self.u.reg_read(ar.UC_ARM64_REG_SP)
        try:
            self.u.emu_start(self.code + self.payload.entries[leaf], 0, count=512)
        except UcError as error:
            raise AssertionError((leaf, str(error), self.trace[-12:])) from error
        assert self.terminal == expected, (leaf, "wrong terminal transfer", self.terminal, expected, self.trace[-12:])
        assert len(self.trace) < 512, "Instruction budget exhausted"
        assert self.trace and self.trace[-1][1] == ("ret" if leaf=="radius" else "br"), (leaf, "Expected terminal BR", self.trace[-1:])
        for i, register in enumerate(X):
            if i not in (16, 17):
                assert self.u.reg_read(register) == before_x[i], (leaf, "preserved GPR", i)
        for i, register in enumerate(Q):
            if i not in range(17, 21) and not (leaf=="flight" and i==0):
                assert self.u.reg_read(register) == before_q[i], (leaf, "preserved SIMD including original d0", i)
        assert self.u.reg_read(ar.UC_ARM64_REG_SP) == before_sp
        assert self.get(self.bool_root) == self.boolean, "Existing Bool roots must never change"
        assert bytes(self.u.mem_read(self.code + self.payload.config, CONFIG_SIZE)) == self.config
        # NZCV deliberately has no preservation requirement at these boundaries.
        return {"leaf": leaf, "instructions": len(self.trace), "writes": len(self.writes),
                "target": hex(self.terminal)}

    def scope(self, owned):
        before_count=self.get(CONTROL+64)
        before_parent = bytes(self.u.mem_read(self.widget - 1, 32))
        result = self.run("scope", self.other,
                          allowed=((self.radius_store, self.radius_store + 8),
                                   (self.child_root, self.child_root + 8)), expected=VISUAL_RESUME if owned else SCOPE_RESUME)
        assert self.get(CONTROL+64)==before_count+int(owned)
        expected_marker = MARKER if owned else 0
        assert self.get(self.radius_store) == self.radius_bits, "Replay STUR d0 bit-for-bit"
        assert self.get(self.child_root) == expected_marker, "Future child slot carries only an untagged Smi"
        assert self.get(self.scope_root) == self.widget, "Do not replace the live parent receiver root"
        assert self.get(THREAD + 96) == self.top, "Capture must not allocate"
        assert bytes(self.u.mem_read(self.widget - 1, 32)) == before_parent
        assert sorted(self.writes) == sorted(((self.radius_store, 8, self.radius_bits),
                                             (self.child_root, 8, expected_marker))), "Exactly the two required frame stores"
        return result

    def child_scope(self, allocate):
        before_count=self.get(CONTROL+72)
        before_child = bytearray(self.u.mem_read(self.child - 1, 32))
        allowed = [(self.child_root, self.child_root + 8)]
        if allocate:
            allowed += [(self.top, self.top + KEY_SIZE), (THREAD + 96, THREAD + 104),
                        (self.child + 7, self.child + 11)]
        result = self.run("child_scope", self.child, allowed=allowed, expected=CHILD_RESUME)
        assert self.get(self.child_root) == self.child, "Always replay the original full child root store"
        assert self.get(CONTROL+72)==before_count+int(allocate)
        root_writes = [(size, value) for address, size, value in self.writes if address == self.child_root]
        assert root_writes == [(8, self.child)]
        if allocate:
            assert self.get(THREAD + 96) == self.top + KEY_SIZE
            expected_key = struct.pack("<QII", KEY_TAG, self.null & MASK32, MARKER)
            assert bytes(self.u.mem_read(self.top, KEY_SIZE)) == expected_key, "Fully initialise native ValueKey16"
            assert self.get(self.child + 7, 4) == (self.top + 1) & MASK32
            before_child[8:12] = struct.pack("<I", (self.top + 1) & MASK32)
        else:
            assert self.get(THREAD + 96) == self.top
            assert self.writes == [(self.child_root, 8, self.child)]
        assert bytes(self.u.mem_read(self.child - 1, 32)) == bytes(before_child), "All other native child/filter fields remain unchanged"
        return result

    def clip(self, superellipse):
        before_count=self.get(CONTROL+80)
        result = self.run("clip", self.other, expected=SUPER_ALLOC if superellipse else STOCK_ALLOC)
        assert self.u.reg_read(X[30]) == ORIGINAL_LR, "Allocator tail BR must retain original LR"
        assert not self.writes
        assert self.get(CONTROL+80)==before_count+int(superellipse)
        return result

    def drop_parent(self):
        # Simulate root drops and a collection after capture. The Smi is not a
        # managed reference and is unchanged while all former sources disappear.
        marker = self.get(self.child_root)
        assert marker in (0, MARKER) and marker & 1 == 0
        self.u.mem_write(self.widget - 1, b"\xdd" * 32)
        self.u.mem_write(self.key - 1, b"\xee" * KEY_SIZE)
        self.put(self.scope_root, 0)
        self.poisoned = [(self.widget - 1, self.widget - 1 + 32),
                         (self.key - 1, self.key - 1 + KEY_SIZE),
                         (self.scope_root, self.scope_root + 8)]
        assert self.get(self.child_root) == marker


def suite(payload):
    results = []

    def record(name, machine, steps):
        results.append({"name": name, "heap_high": machine.high,
                        "compressed_pointer_sign_bit": bool(machine.null & (1 << 31)),
                        "copied_code": hex(machine.code), "radius_bits": hex(machine.radius_bits),
                        "steps": steps})

    # Ownership is captured independent of curve. Only leaf2 gates its fresh
    # key allocation on the Smi marker, current curve and strict bump limit.
    for high, truth, curve, key, pressure, signed, width in itertools.product(
            (1, 2), (False, True), (0, 1), ("owned", "marker", "cid", "null", "smi"),
            (0, KEY_SIZE, KEY_SIZE + 1), (False, True), (56.0, 400.0)):
        machine = Machine(payload, high=high, truth=truth, curve=curve, key=key,
                          pressure=pressure, signed_low=signed, radius_bits=double_bits(width))
        allocate = key == "owned" and curve == 1 and pressure > KEY_SIZE
        steps = [machine.scope(key == "owned")]
        machine.drop_parent()
        steps += [machine.child_scope(allocate), machine.clip(allocate)]
        record(f"pipeline-{truth}-{curve}-{key}-{pressure}-{width}", machine, steps)

    # Exercise unchanged raw d0 bits: width/radius boundaries, signed zero,
    # subnormal, infinities and NaN payloads are stored, never recomputed.
    widths = (0, 1 << 63, 1, double_bits(1.0), double_bits(20.0), double_bits(30.0),
              double_bits(55.5), double_bits(-56.0), double_bits(1024.25),
              0x7ff0000000000000, 0xfff0000000000000, 0x7ff8000000001234, 0x7ff0000000000042)
    for bits, owned in itertools.product(widths, (False, True)):
        machine = Machine(payload, radius_bits=bits, key="owned" if owned else "null")
        record(f"radius-bits-{bits:016x}-{owned}", machine, [machine.scope(owned)])

    # No managed capture survives the GC boundary, even with code relocated a
    # second time and compressed references with the sign bit set.
    for code, high, truth, signed in itertools.product(
            (0x10000000, 0x16000000), (1, 2), (False, True), (False, True)):
        machine = Machine(payload, code=code, high=high, truth=truth, signed_low=signed)
        steps = [machine.scope(True)]
        machine.drop_parent()
        steps += [machine.child_scope(True), machine.clip(True)]
        record("Smi-survives-dropped-parent", machine, steps)

    # Curve changes after capture are evaluated afresh by the child and clip
    # leaves. Both successful and fallback paths must store the native child.
    for initial, child_curve, clip_curve in itertools.product((0, 1), repeat=3):
        machine = Machine(payload, curve=initial)
        steps = [machine.scope(True)]
        machine.drop_parent()
        machine.put(CONTROL + 8, child_curve, 4)
        steps += [machine.child_scope(child_curve == 1)]
        machine.put(CONTROL + 8, clip_curve, 4)
        steps += [machine.clip(child_curve == 1 and clip_curve == 1)]
        record(f"curve-change-{initial}-{child_curve}-{clip_curve}", machine, steps)

    # Leaf2 accepts the exact Smi marker only. Existing roots and unrelated
    # Smis cannot become an ownership key, regardless of allocation headroom.
    for high, token in itertools.product((1, 2), (0, MARKER + 2, MARKER | 1, 2, MASK32, MASK64)):
        machine = Machine(payload, high=high)
        machine.put(machine.child_root, token)
        machine.poisoned = [(machine.widget - 1, machine.widget - 1 + 32),
                            (machine.key - 1, machine.key - 1 + KEY_SIZE)]
        record(f"child-token-{token:x}", machine, [machine.child_scope(False), machine.clip(False)])

    # Clip independently reads only the native child root/key and current curve.
    for high, curve, key in itertools.product((1, 2), (0, 1), ("owned", "marker", "cid", "null", "smi")):
        machine = Machine(payload, high=high, curve=curve, key=key)
        machine.put(machine.child_root, machine.child)
        machine.put(machine.child + 7, machine.key_value(key), 4)
        record(f"clip-{curve}-{key}", machine, [machine.clip(key == "owned" and curve == 1)])

    # The intercepted native instructions guarantee a live parent receiver
    # and a freshly allocated child receiver. Invalid receiver roots are outside
    # this ABI; null/Smi/foreign *keys* remain covered above.

    # Both observed future-child slots (-40/-28) and non-default signed radius
    # slots must be loaded from config, never fixed native-build immediates.
    for parent_slot, child_slot, radius_slot in ((-8, -28, -48), (-16, -56, -72), (-24, -88, -96)):
        machine = Machine(payload, scope_slot=parent_slot, child_slot=child_slot, radius_slot=radius_slot)
        steps = [machine.scope(True)]
        machine.drop_parent()
        steps += [machine.child_scope(True), machine.clip(True)]
        record(f"configured-slots-{parent_slot}-{child_slot}-{radius_slot}", machine, steps)
    for high in (1,2):
        for radius in (0,20,30,40.2,46,256,-1,float('nan'),float('inf')):
            machine=Machine(payload,high=high,radius_bits=double_bits(radius))
            step=machine.run("radius",machine.other,allowed=((CONTROL+32,CONTROL+40),),expected=ORIGINAL_LR)
            assert machine.get(CONTROL+32)==machine.radius_bits
            record("native-radius-return",machine,[step])
    for high in (1,2):
        for radius in (20,30,40.2,46,256):
            machine=Machine(payload,high=high,radius_bits=double_bits(radius))
            step=machine.run("flight",machine.other,allowed=((CONTROL+32,CONTROL+40),),expected=ORIGINAL_LR)
            assert machine.get(CONTROL+32)==machine.radius_bits
            assert machine.u.reg_read(Q[0])==double_bits(123.0)
            record("inlined-native-flight-radius",machine,[step])
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--elf", "--library", type=Path, required=True,
                        help="Rebuilt unstripped linked production AArch64 SO")
    parser.add_argument("--report", type=Path, help="Optional standalone JSON report")
    args = parser.parse_args()
    if not __debug__:
        parser.error("Assertions must remain enabled; do not use python -O")
    payload = Payload(args.elf)
    cases = suite(payload)
    report = {"passed": True, "cases": len(cases), "elf": payload.path, "sha256": payload.sha256,
              "payload_bytes": len(payload.blob), "render_config_bytes": CONFIG_SIZE,
              "entries": payload.entries,
              "coverage": ["copied linked SO bytes; no dynamic relocations",
                           "Smi-only ownership capture; no managed capture or Bool-root mutation",
                           "radius/width d0 raw bits replayed; all original d0/Q0 state preserved",
                           "curves 0/1; owned/foreign/null keys; exact ownership marker",
                           "pressure 0/16/17; fresh native ValueKey16; strict write ranges",
                           "parent/root/key poisoned after capture; Smi handoff survives GC",
                           "heap highs 1/2; compressed pointer sign; relocated copied code",
                           "configured signed radius/parent/child slots including -40/-28",
                           "all GPR except x16/x17 and SIMD except v17-v20 preserved",
                           "native Super/RRect allocator tail BR; unchanged LR/SP; no calls",
                           "all original child/filter fields except owned key remain unchanged"],
              "results": cases}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: value for key, value in report.items() if key != "results"}, indent=2))


if __name__ == "__main__":
    main()

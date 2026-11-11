#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute linked Release Dock refresh bytes with actual launcher getter/Rx code.

Example (existing Unicorn/pyelftools/Capstone environment; no build or device):
  python -B scripts/tests/dock-glass/test_refresh.py --elf <unstripped Release SO>

The copied production payload and real fixture BL sites, getter, generic
Rx.refresh and GetStream.add execute in Unicorn. Only the info logger, Rx.value
and _notifyData managed boundaries are mocked. Fixture debugdata is a HOST TEST
oracle for addresses/fields, never a runtime resolver dependency.
"""
import argparse
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile
from elftools.elf.relocation import RelocationSection
from unicorn import (
    Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE, UC_HOOK_MEM_READ,
    UC_HOOK_MEM_WRITE,
)
from unicorn import arm64_const as ar

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location(
    "refresh_fixture_oracle", ROOT / "scripts/tests/icon-highlight/elf_fixture.py")
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)
X = [getattr(ar, f"UC_ARM64_REG_X{i}") for i in range(31)]
Q = [getattr(ar, f"UC_ARM64_REG_Q{i}") for i in range(32)]
CODE, THREAD, FIELDS, POOL = 0x10000000, 0x30000000, 0x31000000, 0x32000000
STATE, STACK, END = 0x33000000, 0x40000000, 0x50000000
INSTALLED, REVISION, NOTIFIED, ENABLED = STATE, STATE + 8, STATE + 16, STATE + 24
MD = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
MASK64 = (1 << 64) - 1


def bl(at, target):
    delta = target - at
    assert delta % 4 == 0 and -(1 << 27) <= delta < (1 << 27), "BL out of reach"
    return 0x94000000 | ((delta // 4) & 0x3ffffff)


class Payload:
    def __init__(self, path):
        self.path = str(path.resolve())
        raw = path.read_bytes()
        self.sha256 = hashlib.sha256(raw).hexdigest()
        elf = ELFFile(io.BytesIO(raw))
        assert elf["e_machine"] == "EM_AARCH64" and elf["e_type"] == "ET_DYN"
        symbols = elf.get_section_by_name(".symtab")
        assert symbols, "Use the unstripped linked Release/RelWithDebInfo SO"
        syms = {s.name: s["st_value"] for s in symbols.iter_symbols()}
        start, end = syms["kh_dock_template"], syms["kh_dock_payload_end"]
        names = ("begin", "event", "value", "config", "end")
        self.entries = {n: syms["kh_dock_refresh_" + n] - start for n in names}
        assert all(0 <= self.entries[n] <= end - start for n in names)
        assert self.entries["end"] - self.entries["config"] == 104
        assert 0 < end - start <= 65536
        for segment in elf.iter_segments():
            if (segment["p_type"] == "PT_LOAD" and segment["p_vaddr"] <= start
                    and end <= segment["p_vaddr"] + segment["p_filesz"]):
                offset = segment["p_offset"] + start - segment["p_vaddr"]
                self.blob = raw[offset:offset + end - start]
                break
        else:
            raise AssertionError("Copied payload is not wholly file backed")
        for section in elf.iter_sections():
            if isinstance(section, RelocationSection):
                assert not any(start <= r["r_offset"] < end for r in section.iter_relocations()), (
                    "Copied payload contains a dynamic relocation")
        instructions = list(MD.disasm(
            self.blob[self.entries["value"]:self.entries["config"]],
            self.entries["value"]))
        stores = [i.address for i in instructions if i.mnemonic == "stlr"
                  and i.op_str == "x9, [x16]"]
        assert len(stores) == 1, "Expected unique production notified store"
        self.store = stores[0]
        assert not any(i.mnemonic in ("bl", "blr", "svc") for i in instructions)


class Fixture:
    def __init__(self, path):
        self.path = str(path.resolve())
        self.name = path.parent.name
        self.im = oracle.Image(path)
        self.sha256 = hashlib.sha256(self.im.data).hexdigest()
        self.getter, size = self.im.syms["GridController.currentConfig"][0]
        assert size == 0x64
        self.value_call = self.getter + 0x54
        self.value = self.call(self.value_call)
        self.refresh = [pc for pc, size in self.im.syms["RxObjectMixin.refresh"]
                        if size == 0x38 and self.call(pc + 0x18) == self.value]
        assert len(self.refresh) == 1
        self.refresh = self.refresh[0]
        self.add = self.call(self.refresh + 0x24)
        assert (self.add, 0x68) in self.im.syms["GetStream.add"]
        self.notify = self.call(self.add + 0x54)
        assert self.notify == self.im.syms["GetStream._notifyData"][0][0]
        self.logger = self.im.syms["_LoggerImpl.info"][0][0]
        root = self.im.syms["ItemChangeProcessor.processItemsChanged"][0][0]
        calls = [pc for pc in range(root + 0x30, root + 0x120, 4)
                 if self.word(pc) & 0xfc000000 == 0x94000000
                 and self.call(pc) == self.logger]
        assert len(calls) == 2
        self.event_call = calls[1]
        self.grid_slot = ((self.word(self.getter + 0x10) >> 10) & 4095) * 8
        self.rx_field = self.field(self.getter + 0x4c)
        self.value_field = self.field(self.value + 0x3c)
        self.subject_field = self.field(self.refresh + 0xc)
        self.observer_slot = ((self.word(self.value + 0x18) >> 10) & 4095) * 8
        assert self.subject_field == self.field(self.value + 0x2c)
        assert self.im.read(self.getter + 0x58, 12).hex() == "ef031daafd79c1a8c0035fd6"
        # Actual cached getter path must preserve the token until its patched BL.
        fast = list(range(self.getter, self.getter + 0x1c, 4))
        fast += [self.getter + 0x4c, self.getter + 0x50]
        for pc in fast:
            ins = list(MD.disasm(self.im.read(pc, 4), pc))[0]
            assert ins.mnemonic not in ("bl", "blr") and not ins.op_str.startswith(("x9,", "w9,"))

    def word(self, pc):
        return struct.unpack("<I", self.im.read(pc, 4))[0]

    def call(self, pc):
        w = self.word(pc)
        assert w & 0xfc000000 == 0x94000000
        return pc + (((w & 0x3ffffff) ^ 0x2000000) - 0x2000000) * 4

    def field(self, pc):
        return (((self.word(pc) >> 12) & 511) ^ 256) - 256


class Machine:
    def __init__(self, payload, fixture, high, bridge):
        self.p, self.f, self.high, self.bridge = payload, fixture, high, bridge
        self.u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.pages = set()
        self.trace, self.writes, self.reads, self.results = [], [], [], []
        self.hook_handles = []
        self.heap = (high & 0xffffffff) << 32
        names = ("null", "sentinel", "grid", "rx", "config", "subject", "observer",
                 "grid2", "rx2", "config2", "subject2", "logger", "context", "args")
        self.o = {n: self.heap + 0x100001 + 0x1000 * i for i, n in enumerate(names)}
        for at, n in [(bridge, len(payload.blob)), (THREAD, 4096), (FIELDS, 65536),
                      (POOL, 4096), (STATE, 4096), (STACK, 0x20000), (END, 4096),
                      (self.heap + 0x100000, 0x20000)]:
            self.map(at, n)
        self.u.mem_write(bridge, payload.blob)
        for pc, n in [(fixture.getter, 0x64), (fixture.refresh, 0x38),
                      (fixture.add, 0x68), (fixture.value, 4), (fixture.notify, 4),
                      (fixture.logger, 4), (fixture.event_call, 8)]:
            self.map(CODE + pc, n)
            self.u.mem_write(CODE + pc, fixture.im.read(pc, n))
        self.event_return = CODE + fixture.event_call + 4
        self.getter_return = CODE + fixture.value_call + 4
        self.refresh_return = CODE + fixture.refresh + 0x1c
        self.refresh_add_return = CODE + fixture.refresh + 0x28
        self.add_return = CODE + fixture.add + 0x58
        self.word(CODE + fixture.event_call, bl(
            CODE + fixture.event_call, bridge + payload.entries["event"]))
        self.word(CODE + fixture.value_call, bl(
            CODE + fixture.value_call, bridge + payload.entries["value"]))
        cfg = [INSTALLED, REVISION, NOTIFIED, CODE + fixture.logger, CODE + fixture.getter,
               self.event_return, CODE + fixture.value, CODE + fixture.refresh,
               fixture.grid_slot, fixture.rx_field, fixture.value_field,
               fixture.subject_field, fixture.observer_slot]
        self.u.mem_write(bridge + payload.entries["config"], struct.pack("<13Q", *cfg))
        self.put(THREAD + 0x78, FIELDS)
        self.put(POOL + 0x40, self.o["sentinel"])
        self.put(FIELDS + fixture.observer_slot, self.o["null"])
        self.owner(1)
        for suffix in ("", "2"):
            self.word(self.o["grid" + suffix] + fixture.rx_field, self.o["rx" + suffix])
            self.word(self.o["rx" + suffix] + fixture.value_field, self.o["config" + suffix])
            self.word(self.o["rx" + suffix] + fixture.subject_field, self.o["subject" + suffix])
            self.word(self.o["subject" + suffix] + 7, self.o["null"])
        self.word(INSTALLED, 1)
        self.word(ENABLED, 1)  # Deliberately not part of the refresh config.
        self.put(REVISION, 7)
        self.put(NOTIFIED, 6)
        self.hook_handles = [
            self.u.hook_add(UC_HOOK_CODE, self.on_code),
            self.u.hook_add(UC_HOOK_MEM_WRITE, self.on_write),
            self.u.hook_add(UC_HOOK_MEM_READ, self.on_read),
        ]

    def close(self):
        for h in self.hook_handles:
            self.u.hook_del(h)
        self.hook_handles.clear()

    def map(self, at, n):
        for page in range(at & ~4095, (at + n + 4095) & ~4095, 4096):
            if page not in self.pages:
                self.u.mem_map(page, 4096)
                self.pages.add(page)

    def word(self, at, n):
        self.u.mem_write(at, struct.pack("<I", n & 0xffffffff))

    def put(self, at, n):
        self.u.mem_write(at, struct.pack("<Q", n & MASK64))

    def get(self, at):
        return struct.unpack("<Q", self.u.mem_read(at, 8))[0]

    def getw(self, at):
        return struct.unpack("<I", self.u.mem_read(at, 4))[0]

    def owner(self, which):
        self.active = "" if which == 1 else "2"
        self.put(FIELDS + self.f.grid_slot, self.o["grid" + self.active])

    def ready(self, part, value):
        at = {
            "grid": FIELDS + self.f.grid_slot,
            "rx": self.o["grid"] + self.f.rx_field,
            "value": self.o["rx"] + self.f.value_field,
            "subject": self.o["rx"] + self.f.subject_field,
            "observer": FIELDS + self.f.observer_slot,
        }[part]
        (self.put if part in ("grid", "observer") else self.word)(at, value)

    def on_read(self, cpu, access, at, n, value, user):
        self.reads.append((at, n))

    def on_write(self, cpu, access, at, n, value, user):
        pc = cpu.reg_read(ar.UC_ARM64_REG_PC)
        if at == NOTIFIED:
            assert n == 8 and pc == self.bridge + self.p.store
            assert value == self.initial_revision, "Consumed a newly loaded revision"
        else:
            assert self.dsp - 0x100 <= at and at + n <= self.dsp, (
                "Unexpected managed/global write", hex(pc), hex(at), n)
        self.writes.append((at, n, value))

    def on_code(self, cpu, pc, n, user):
        self.trace.append(pc)
        if pc == CODE + self.f.getter:
            self.calls.append("getter")
            if self.at_getter:
                self.at_getter(self)
        if pc == self.bridge + self.p.entries["value"] and self.at_value:
            self.at_value(self)
        if pc == self.bridge + self.p.store and self.at_store:
            self.at_store(self)
        if pc == CODE + self.f.refresh:
            self.calls.append("refresh")
            assert cpu.reg_read(X[1]) == self.o["rx" + self.active]
            assert cpu.reg_read(X[30]) == self.getter_return
            assert self.get(NOTIFIED) == self.initial_revision, "Store must precede native refresh"
        if pc == CODE + self.f.value:
            self.calls.append("value")
            assert cpu.reg_read(X[1]) == self.expected_rx
            lr = cpu.reg_read(X[30])
            assert lr in (self.getter_return, self.refresh_return), "Unmapped/artificial return PC"
            if lr == self.refresh_return:
                fp = cpu.reg_read(X[29])
                assert self.get(fp - 8) == self.o["subject" + self.active]
                assert self.get(fp + 8) == self.getter_return
            raw_value = self.getw(self.expected_rx + self.f.value_field)
            cpu.reg_write(X[0], raw_value + self.heap)
            cpu.reg_write(ar.UC_ARM64_REG_PC, lr)
        elif pc == CODE + self.f.notify:
            self.calls.append("notify")
            assert cpu.reg_read(X[1]) == self.o["subject" + self.active]
            assert cpu.reg_read(X[2]) == self.expected_config
            assert cpu.reg_read(X[30]) == self.add_return
            assert self.get(NOTIFIED) == self.initial_revision
            fp = cpu.reg_read(X[29])
            assert self.get(fp - 8) == self.o["subject" + self.active]
            assert self.get(fp - 16) == self.expected_config
            assert self.get(fp + 8) == self.refresh_add_return
            refresh_fp = self.get(fp)
            assert self.get(refresh_fp - 8) == self.o["subject" + self.active]
            assert self.get(refresh_fp + 8) == self.getter_return
            getter_fp = self.get(refresh_fp)
            assert self.get(getter_fp) == self.fp
            assert self.get(getter_fp + 8) == self.event_return
            if self.reenter and not self.reentered:
                self.reentered = True
                self.nested = ([cpu.reg_read(r) for r in X],
                               cpu.reg_read(ar.UC_ARM64_REG_NZCV))
                # Mock the managed callback's ordinary logger argument setup;
                # keep its live native frames until the nested event returns.
                for i in range(9):
                    cpu.reg_write(X[i], self.before[i])
                cpu.reg_write(ar.UC_ARM64_REG_PC, CODE + self.f.event_call)
            else:
                cpu.reg_write(X[0], self.o["null"])
                cpu.reg_write(ar.UC_ARM64_REG_PC, self.add_return)
        elif pc == CODE + self.f.logger:
            self.calls.append("logger")
            assert cpu.reg_read(X[30]) == self.event_return
            assert [cpu.reg_read(X[i]) for i in range(9)] == self.before[:9]
            if self.nested is None:
                assert cpu.reg_read(X[15]) == self.dsp and cpu.reg_read(X[29]) == self.fp
            cpu.reg_write(X[0], self.o["null"])
            cpu.reg_write(ar.UC_ARM64_REG_PC, self.event_return)
        elif pc in (self.event_return, END):
            if self.nested is not None:
                saved, flags = self.nested
                self.nested = None
                for reg, value in zip(X, saved):
                    cpu.reg_write(reg, value)
                cpu.reg_write(ar.UC_ARM64_REG_NZCV, flags)
                cpu.reg_write(X[0], self.o["null"])
                cpu.reg_write(ar.UC_ARM64_REG_PC, self.add_return)
            else:
                assert pc == self.finish
                self.finished = True
                cpu.emu_stop()

    def run(self, name, *, ordinary=False, expected="notify", frame=0, reenter=False,
            at_getter=None, at_value=None, at_store=None):
        self.calls = []
        self.trace.clear()
        self.writes.clear()
        self.reads.clear()
        self.finished = self.reentered = False
        self.nested = None
        self.reenter = reenter
        self.at_getter, self.at_value, self.at_store = at_getter, at_value, at_store
        self.dsp, self.fp = STACK + 0x8000 + frame, STACK + 0x8080 + frame
        self.expected_rx = self.o["rx" + self.active]
        self.expected_config = self.o["config" + self.active]
        self.initial_revision = self.get(REVISION)
        initial_notified = self.get(NOTIFIED)
        self.finish = END if ordinary else self.event_return
        for i, reg in enumerate(X):
            self.u.reg_write(reg, 0xa0000000 + i)
        for i, reg in enumerate(Q):
            self.u.reg_write(reg, (0x12340000 + i) | ((0x56780000 + i) << 64))
        values = {1: self.o["logger"], 2: self.o["context"], 4: self.o["args"],
                  15: self.dsp, 22: self.o["null"], 26: THREAD, 27: POOL,
                  28: self.high, 29: self.fp, 30: self.finish}
        if ordinary:
            # Give the ordinary caller a matching token and pending generation:
            # only its different saved LR may exclude this otherwise-ready Rx.
            values[9] = self.initial_revision
        for i, value in values.items():
            self.u.reg_write(X[i], value)
        csp = STACK + 0x1f000
        self.u.reg_write(ar.UC_ARM64_REG_SP, csp)
        self.before = [self.u.reg_read(r) for r in X]
        vectors = [self.u.reg_read(r) for r in Q]
        self.u.emu_start(CODE + (self.f.getter if ordinary else self.f.event_call), 0, count=2000)
        assert self.finished and len(self.trace) < 1000, (name, "No terminal transfer/storm")
        core = [c for c in self.calls if c != "getter"]
        wanted = {"notify": ["refresh", "value", "notify"],
                  "value": ["value"], "logger": ["logger"],
                  "reentry": ["refresh", "value", "notify", "logger"]}[expected]
        assert core == wanted, (name, self.calls, wanted)
        for i in (14, 15, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29):
            assert self.u.reg_read(X[i]) == self.before[i], (name, "VM register", i)
        assert self.u.reg_read(ar.UC_ARM64_REG_SP) == csp
        assert [self.u.reg_read(r) for r in Q] == vectors, (name, "SIMD register")
        stores = [v for at, n, v in self.writes if at == NOTIFIED]
        assert stores == ([self.initial_revision] if expected in ("notify", "reentry") else [])
        if expected == "logger":
            assert not self.writes, (name, "Fallback must not create frames or consume state")
        if ordinary:
            assert self.u.reg_read(X[0]) == self.expected_config
            assert not any(STATE <= at < STATE + 32 for at, n in self.reads)
        elif expected in ("notify", "reentry"):
            assert self.u.reg_read(X[0]) == self.o["null"]
        assert not any(at == ENABLED for at, n in self.reads), "Off generations must also refresh"
        result = dict(name=name, calls=self.calls[:], instructions=len(self.trace),
                      revision=self.get(REVISION), notified=self.get(NOTIFIED),
                      initial_notified=initial_notified, notified_stores=stores)
        self.results.append(result)
        return result


def cases(payload, fixture, high, bridge):
    results = []

    def case(name, setup=None, **options):
        m = Machine(payload, fixture, high, bridge)
        try:
            if setup:
                setup(m)
            result = m.run(name, **options)
            results.append(result)
            return result
        except Exception as error:
            raise AssertionError((fixture.name, high, name, str(error))) from error
        finally:
            m.close()

    case("pending-generation")
    case("wide-revision-same-low-word", lambda m: (
        m.put(REVISION, (1 << 32) + 7), m.put(NOTIFIED, 7)))
    case("wide-notified-same-low-word", lambda m: (
        m.put(REVISION, 7), m.put(NOTIFIED, (1 << 32) + 7)))
    case("off-generation-still-invalidates", lambda m: m.word(ENABLED, 0))
    case("already-notified", lambda m: m.put(NOTIFIED, 7), expected="logger")
    case("initial-zero-generation", lambda m: (m.put(REVISION, 0), m.put(NOTIFIED, 0)), expected="logger")
    for installed in (0, 2, 0xffffffff):
        case(f"installed-{installed}", lambda m, v=installed: m.word(INSTALLED, v), expected="logger")
    case("uninstalled-no-isolate-reads",
         lambda m: (m.word(INSTALLED, 0), m.put(THREAD + 0x78, 0)), expected="logger")
    for part in ("grid", "rx", "value", "subject"):
        for bad in ("null", "sentinel", "smi"):
            case(f"{part}-{bad}", lambda m, p=part, b=bad:
                 m.ready(p, 2 if b == "smi" else m.o[b]), expected="logger")
    case("active-observer", lambda m: m.ready("observer", m.o["observer"]), expected="logger")
    case("ordinary-getter", ordinary=True, expected="value")
    case("ordinary-uninstalled-observing", lambda m: (
        m.word(INSTALLED, 0), m.ready("observer", m.o["observer"])),
        ordinary=True, expected="value")
    case("owner-mismatch", at_value=lambda m: m.owner(2), expected="value")
    for part in ("grid", "rx", "value", "subject"):
        case(f"{part}-lost-after-event", at_value=lambda m, p=part:
             m.ready(p, m.o["null"]), expected="value")
    case("observer-became-active", at_value=lambda m: m.ready("observer", m.o["observer"]),
         expected="value")
    case("uninstalled-at-interceptor", at_value=lambda m: m.word(INSTALLED, 0), expected="value")
    raced = case("revision-changed-before-getter",
                 at_getter=lambda m: m.put(REVISION, 8), expected="value")
    assert raced["revision"] == 8 and raced["notified"] == 6
    raced = case("revision-changed-at-interceptor",
                 at_value=lambda m: m.put(REVISION, 8), expected="value")
    assert raced["revision"] == 8 and raced["notified"] == 6
    consumed = case("already-consumed-at-interceptor",
                    at_value=lambda m: m.put(NOTIFIED, 7), expected="value")
    assert consumed["notified"] == 7
    case("notification-reenters-same-generation", reenter=True, expected="reentry")

    m = Machine(payload, fixture, high, bridge)
    try:
        m.run("owner-replacement-defers", at_value=lambda machine: machine.owner(2),
              expected="value")
        assert m.get(NOTIFIED) == 6
        m.run("replacement-owner-retries-same-generation", frame=0x3000)
        m.run("replacement-owner-repeat-no-storm", frame=0x6000, expected="logger")
        results.extend(m.results)
    finally:
        m.close()

    m = Machine(payload, fixture, high, bridge)
    try:
        m.ready("observer", m.o["observer"])
        m.run("observer-defers-with-generation-pending", expected="logger")
        assert m.get(NOTIFIED) == 6
        m.ready("observer", m.o["null"])
        m.run("observer-cleared-retries-same-generation", frame=0x3000)
        m.run("observer-retry-repeat-no-storm", frame=0x6000, expected="logger")
        results.extend(m.results)
    finally:
        m.close()

    m = Machine(payload, fixture, high, bridge)
    try:
        result = m.run("publish-newer-after-comparison",
                       at_store=lambda machine: machine.put(REVISION, 8))
        assert result["notified"] == 7 and result["revision"] == 8
        m.run("newer-generation-remains-pending", frame=0x3000)
        m.run("fresh-frame-unchanged-no-storm", frame=0x6000, expected="logger")
        # Replace every owner/root, make old objects and caller frames unreadable,
        # then refresh a new generation without any retained managed reference.
        m.owner(2)
        for name in ("grid", "rx", "config", "subject"):
            m.u.mem_unmap(m.o[name] & ~4095, 4096)
        m.u.mem_unmap(STACK + 0x7000, 8192)
        m.put(REVISION, 9)
        m.run("fresh-owners-old-roots-unmapped", frame=0x9000)
        m.run("fresh-owners-repeat-no-storm", frame=0xc000, expected="logger")
        results.extend(m.results)
    finally:
        m.close()
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--elf", "--payload", type=Path, required=True,
                        help="Unstripped production arm64 Release SO")
    parser.add_argument("--report", type=Path)
    parser.add_argument("fixtures", type=Path, nargs="*")
    args = parser.parse_args()
    if not __debug__:
        parser.error("Assertions must be enabled; do not use -O/PYTHONOPTIMIZE")
    payload = Payload(args.elf)
    paths = args.fixtures or [ROOT / f".artifacts/inputs/launchers/{v}/libapp.so"
                              for v in json.loads((Path(__file__).parent / "fixtures.json").read_text())]
    reports = []
    for path in paths:
        fixture = Fixture(path)
        runs = []
        for high, bridge in ((1, 0x14000000), (0xc00000074, 0x15000000)):
            runs.append(dict(heap_register=high, copied_payload=bridge,
                             cases=cases(payload, fixture, high, bridge)))
        count = sum(len(run["cases"]) for run in runs)
        reports.append(dict(fixture=fixture.path, sha256=fixture.sha256,
                            case_count=count, runs=runs))
        print(f"PASS {fixture.name}: {count} linked refresh execution cases")
    report = dict(
        passed=True, cases=sum(f["case_count"] for f in reports),
        elf=payload.path, sha256=payload.sha256, payload_bytes=len(payload.blob),
        refresh_config_bytes=104, fixtures=reports,
        separate_resolver_validation=dict(
            documentation=str(ROOT / "docs/dock-glass-refresh.md"),
            runner=str(ROOT / "app/src/test/cpp/dock_glass_refresh_profile_test.cpp"),
            previously_passed_checks=495, rerun_by_this_script=False),
        limits=[
            "Real fixture getter, generic Rx.refresh and GetStream.add execute; logger, Rx.value and _notifyData are mocked.",
            "Deterministic UI/revision interleavings; not a hardware memory-model or Dart GC test.",
            "No Flutter/Obx rendering, actual framework callback scheduling, installer activation or device acceptance.",
        ])
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: report[k] for k in (
        "passed", "cases", "elf", "sha256", "payload_bytes", "refresh_config_bytes")}, indent=2))


if __name__ == "__main__":
    main()

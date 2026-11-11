#!/usr/bin/env python3
"""Execute copied native material policy/refresh bytes; no device changes."""
import argparse
import hashlib
import json
import re
import struct
from itertools import product
from pathlib import Path
from elftools.elf.elffile import ELFFile
from arm64_support import Payload, HEAP, STACK, TRAP, read64, write64, xreg
from unicorn import UC_HOOK_CODE, UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE
from unicorn.arm64_const import (
    UC_ARM64_REG_SP, UC_ARM64_REG_PC, UC_ARM64_REG_NZCV, UC_ARM64_REG_Q0,
)

ROOT = Path(__file__).resolve().parents[3]
CONFIG = {name: int(value) for name, value in re.findall(
    r"^#define (KH_GLASS_[A-Z_]+)\s+(\d+)\s*$",
    (ROOT / "app/src/main/cpp/launcher_glass_material_bridge.h").read_text(),
    re.MULTILINE)}
CONFIG_SIZE = CONFIG["KH_GLASS_MATERIAL_CONFIG_SIZE"]
CONFIG_FORMAT = f"<{CONFIG_SIZE // 8}Q"


def branch(at, to):
    delta = to - at
    assert delta % 4 == 0 and -(1 << 27) <= delta < (1 << 27)
    return 0x14000000 | ((delta // 4) & 0x3ffffff)


def template(p, uc, shift=0):
    names = ("INSTALLED", "CONTROL", "REVISION", "NOTIFIED", "CONTROLLER_SLOT",
             "RX_FIELD", "SUBJECT_FIELD", "VALUE_FIELD", "OBSERVER_SLOT", "REFRESH",
             "EVENT_RETURN", "TRUE_OFFSET", "FALSE_OFFSET", "LISTENERS_FIELD",
             "BUSY_FIELD", "LENGTH_FIELD", "POLICY_SHIFT")
    assert [CONFIG["KH_GLASS_MATERIAL_" + name] for name in names] == list(range(0, 136, 8))
    start = p.addr("kh_glass_material_begin")
    end = p.addr("kh_glass_material_end")
    data = bytearray(uc.mem_read(start, end - start))
    positions = {name: p.addr("kh_glass_material_" + name) - start for name in [
        "choose", "original", "taken", "fall", "light", "dark", "refresh",
        "event_original", "event_resume", "config"]}
    for name, target in [("light", 0x2000), ("dark", 0x2010),
                         ("taken", 0x2020), ("fall", 0x2030)]:
        struct.pack_into("<I", data, positions[name],
                         branch(TRAP + positions[name], TRAP + target))
    # Native TBNZ w0,#4: execute the real condition, selecting its local stub.
    struct.pack_into("<I", data, positions["original"], 0x37200000 |
                     (((positions["taken"] - positions["original"]) // 4) << 5))
    # Replay the native logger's outgoing-argument cleanup exactly once.
    struct.pack_into("<I", data, positions["event_original"], 0x910041EF)
    struct.pack_into("<I", data, positions["event_resume"],
                     branch(TRAP + positions["event_resume"], TRAP + 0x3004))
    state, table, pool, thread = HEAP + 0x6000, HEAP + 0x4000, HEAP + 0x5000, HEAP + 0x3000
    cfg = [state, state + 8, state + 16, state + 24, 0x20, 0x47, 0x13, 0xB,
           0x10, TRAP + 4096, TRAP + 0x3000, 0x20, 0x30, 0x1B, 0x1F, 0xB, shift]
    assert CONFIG_SIZE == 136 and len(cfg) * 8 == CONFIG_SIZE
    assert len(data) - positions["config"] == CONFIG_SIZE
    assert not any(name.startswith("kh_glass_folder_") for name in p.symbols)
    struct.pack_into(CONFIG_FORMAT, data, positions["config"], *cfg)
    uc.mem_write(TRAP, bytes(data))
    uc.mem_write(TRAP + 0x3000, struct.pack("<I",
                  branch(TRAP + 0x3000, TRAP + positions["refresh"])))
    return positions, state, table, pool, thread


def choose_cases(p):
    cases = 0
    for installed in range(2):
        for shift, policy in product((0, 2), range(16)):
            mode = (policy >> shift) & 3
            for native_bool in [0x100, 0x110]:
                for flags in [0, 0xB0000000]:
                    uc = p.uc()
                    pos, state, *_ = template(p, uc, shift)
                    write64(uc, state, installed)
                    write64(uc, state + 8, policy)
                    saved = [0x1234000000000000 + n for n in range(31)]
                    saved[0] = native_bool
                    for n, value in enumerate(saved):
                        uc.reg_write(xreg(n), value)
                    uc.reg_write(UC_ARM64_REG_NZCV, flags)
                    simd = [0x123456789abcdef00000000000000000 + n for n in range(32)]
                    for n, value in enumerate(simd):
                        uc.reg_write(UC_ARM64_REG_Q0 + n, value)
                    sp = uc.reg_read(UC_ARM64_REG_SP)
                    landed = []

                    def code(cpu, pc, _size, _data):
                        if pc in [TRAP + n for n in [0x2000, 0x2010, 0x2020, 0x2030]]:
                            landed.append(pc)
                            cpu.emu_stop()

                    uc.hook_add(UC_HOOK_CODE, code)
                    uc.emu_start(TRAP + pos["choose"], TRAP + 0x3040, count=100)
                    expected = 0x2020 if native_bool & 16 else 0x2030
                    if installed and mode in [1, 2]:
                        expected = 0x2000 if mode == 1 else 0x2010
                    assert landed == [TRAP + expected]
                    assert [uc.reg_read(xreg(n)) for n in range(16)] == saved[:16]
                    assert [uc.reg_read(xreg(n)) for n in range(18, 31)] == saved[18:]
                    assert uc.reg_read(UC_ARM64_REG_NZCV) == flags
                    assert uc.reg_read(UC_ARM64_REG_SP) == sp
                    assert [uc.reg_read(UC_ARM64_REG_Q0 + n) for n in range(32)] == simd
                    cases += 1
    return cases


def condition_cases(p):
    """Native B.cond and CB[N]Z relocation preserves the original flags/value."""
    cases = 0
    variants = [(0x54000000, "eq"), (0x54000001, "ne"),
                (0xb4000000, "cbz"), (0xb5000000, "cbnz")]
    for word, kind in variants:
        for flags in range(16):
            for value in (0, 0x100000001):
                for installed in (0, 1):
                    for shift, mode in product((0, 2), range(4)):
                        uc = p.uc()
                        pos, state, _, _, _ = template(p, uc, shift)
                        relative = (pos["taken"] - pos["original"]) // 4
                        uc.mem_write(TRAP + pos["original"], struct.pack(
                            "<I", word | ((relative & 0x7ffff) << 5)))
                        write64(uc, state, installed)
                        write64(uc, state + 8, (mode << shift) | (((mode + 1) % 4) << (2 - shift)))
                        uc.reg_write(xreg(0), value)
                        uc.reg_write(UC_ARM64_REG_NZCV, flags << 28)
                        before = {n: uc.reg_read(xreg(n)) for n in range(31)
                                  if n not in (16, 17)}
                        landed = []

                        def on_code(cpu, pc, _size, _data):
                            if pc in [TRAP + k for k in (0x2000, 0x2010, 0x2020, 0x2030)]:
                                landed.append(pc)
                                cpu.emu_stop()
                        uc.hook_add(UC_HOOK_CODE, on_code)
                        try:
                            uc.emu_start(TRAP + pos["choose"], TRAP + 0x2200, count=100)
                        except Exception as error:
                            pc = uc.reg_read(UC_ARM64_REG_PC)
                            raise AssertionError((kind, flags, value, installed, mode,
                                                  hex(pc), uc.mem_read(pc, 4).hex())) from error
                        taken = {"eq": bool(flags & 4), "ne": not (flags & 4),
                                 "cbz": value == 0, "cbnz": value != 0}[kind]
                        native = TRAP + (0x2020 if taken else 0x2030)
                        expected = TRAP + (0x2000 if mode == 1 else 0x2010)
                        if not installed or mode in (0, 3):
                            expected = native
                        assert landed == [expected], (kind, flags, value, mode, landed)
                        assert uc.reg_read(UC_ARM64_REG_NZCV) == flags << 28
                        assert all(uc.reg_read(xreg(n)) == v for n, v in before.items())
                        cases += 1
    return cases


def refresh_cases(p):
    cases = 0
    scenarios = ["ready_true", "ready_false", "disabled", "quiet", "collecting",
                 "controller_null", "controller_sentinel", "controller_smi",
                 "rx_null", "rx_sentinel", "rx_smi", "subject_null",
                 "subject_sentinel", "subject_smi", "not_bool", "listeners_null",
                 "listeners_smi", "listeners_sentinel", "listeners_empty",
                 "listeners_bad_length", "listeners_negative", "busy_true", "busy_null"]
    for scenario in scenarios:
        uc = p.uc()
        pos, state, table, pool, thread = template(p, uc)
        null, sentinel = HEAP + 0x7001, HEAP + 0x7101
        controller, rx, subject = HEAP + 0x101, HEAP + 0x201, HEAP + 0x301
        write64(uc, thread + 0x78, table)
        write64(uc, pool + 0x40, sentinel)
        write64(uc, table + 0x10, null if scenario != "collecting" else controller)
        controller_value = {
            "controller_null": null, "controller_sentinel": sentinel, "controller_smi": 2,
        }.get(scenario, controller)
        write64(uc, table + 0x20, controller_value)
        rx_value = {"rx_null": null, "rx_sentinel": sentinel, "rx_smi": 2}.get(scenario, rx)
        uc.mem_write(controller + 0x47, struct.pack("<I", rx_value))
        subject_value = {
            "subject_null": null, "subject_sentinel": sentinel, "subject_smi": 2,
        }.get(scenario, subject)
        uc.mem_write(rx + 0x13, struct.pack("<I", subject_value))
        listeners = HEAP + 0x801
        listener_value = {"listeners_null": null, "listeners_smi": 2,
                          "listeners_sentinel": sentinel}.get(scenario, listeners)
        uc.mem_write(subject + 0x1B, struct.pack("<I", listener_value))
        uc.mem_write(subject + 0x1F, struct.pack("<I", null if scenario == "busy_null"
                     else null + (0x20 if scenario == "busy_true" else 0x30)))
        length = {"listeners_empty": 0, "listeners_bad_length": 3,
                  "listeners_negative": 0xfffffffe}.get(scenario, 2)
        uc.mem_write(listeners + 0xB, struct.pack("<I", length))
        value = 2 if scenario == "not_bool" else null + (
            0x30 if scenario == "ready_false" else 0x20)
        uc.mem_write(rx + 0xB, struct.pack("<I", value))
        write64(uc, state, 0 if scenario == "disabled" else 1)
        write64(uc, state + 16, 3)
        write64(uc, state + 24, 3 if scenario == "quiet" else 1)
        uc.reg_write(xreg(22), null)
        uc.reg_write(xreg(26), thread)
        uc.reg_write(xreg(27), pool)
        uc.reg_write(xreg(28), 0)
        uc.reg_write(xreg(15), STACK + 0x2000)
        original_sp = uc.reg_read(UC_ARM64_REG_SP)
        calls, writes = [], []

        def code(cpu, pc, _size, _data):
            if pc == TRAP + 0x3004:
                cpu.emu_stop()
            elif pc == TRAP + 4096:
                assert cpu.reg_read(xreg(1)) == rx
                assert cpu.reg_read(xreg(30)) == TRAP + 0x3000
                assert cpu.reg_read(xreg(15)) == STACK + 0x2000
                assert struct.unpack("<Q", cpu.mem_read(state + 24, 8))[0] == 3
                calls.append("native_rx_refresh")
                # Native managed callee can clobber scratch, not its VM roots.
                for n in list(range(15)) + [16, 17]:
                    cpu.reg_write(xreg(n), 0x4321000000000000 + n)
                cpu.reg_write(xreg(0), null)
                cpu.reg_write(UC_ARM64_REG_PC, TRAP + 0x3000)

        def write(_cpu, _access, address, size, value, _data):
            writes.append((address, size, value))

        uc.hook_add(UC_HOOK_CODE, code)
        uc.hook_add(UC_HOOK_MEM_WRITE, write)
        uc.emu_start(TRAP + 0x3000, TRAP + 0x3008, count=500)
        expected = scenario.startswith("ready_")
        assert len(calls) == int(expected), scenario
        assert writes == ([(state + 24, 8, 3)] if expected else []), (scenario, writes)
        assert uc.reg_read(xreg(15)) == STACK + 0x2010
        assert uc.reg_read(UC_ARM64_REG_SP) == original_sp
        assert struct.unpack("<I", uc.mem_read(rx + 0xB, 4))[0] == value
        assert struct.unpack("<Q", uc.mem_read(table + 0x20, 8))[0] == controller_value
        cases += 1
    return cases


class MaterialMachine:
    """Execute the bool-only leaf; model external managed listener callbacks."""

    def __init__(self, p):
        self.uc = p.uc()
        self.pos, _, self.table, self.pool, self.thread = template(p, self.uc)
        self.addresses = {key: p.addr(symbol) for key, symbol in (
            ("installed", "kh_launcher_glass_installed"),
            ("control", "kh_launcher_glass_control"),
            ("revision", "kh_glass_material_revision"),
            ("notified", "kh_glass_material_notified"))}
        cfg = TRAP + self.pos["config"]
        for i, key in enumerate(self.addresses):
            write64(self.uc, cfg + i * 8, self.addresses[key])
        self.null, self.sentinel = HEAP + 0x7001, HEAP + 0x7101
        self.controller, self.rx, self.subject, self.listeners = (
            HEAP + n for n in (0x101, 0x201, 0x301, 0x801))
        self.frame, self.dart_sp = STACK + 0x5000, STACK + 0x4000
        self.native_value = self.null + 32
        self.calls, self.writes, self.replays, self.old_objects = [], [], [], []
        self.notify, self.nested, self.nested_returns = None, None, 0
        write64(self.uc, self.thread + 0x78, self.table)
        write64(self.uc, self.pool + 0x40, self.sentinel)
        self.ready()
        self.uc.mem_write(TRAP + 4096, bytes.fromhex("1f2003d5c0035fd6"))
        self.uc.hook_add(UC_HOOK_CODE, self.on_code)
        self.uc.hook_add(UC_HOOK_MEM_WRITE, lambda _u, _a, at, n, v, _d:
                         self.writes.append((at, n, v)))
        self.uc.hook_add(UC_HOOK_MEM_READ, self.on_read)

    def word(self, at, value):
        self.uc.mem_write(at, struct.pack("<I", value))

    def get(self, key):
        if key == "installed":
            return struct.unpack("<I", self.uc.mem_read(self.addresses[key], 4))[0]
        return read64(self.uc, self.addresses[key])

    def set(self, key, value):
        if key == "installed":
            self.word(self.addresses[key], value)
        else:
            write64(self.uc, self.addresses[key], value)

    def state(self):
        return {key: self.get(key) for key in self.addresses}

    def ready(self):
        write64(self.uc, self.table + 0x10, self.null)
        write64(self.uc, self.table + 0x20, self.controller)
        for at, value in (
            (self.controller + 0x47, self.rx), (self.rx + 0x13, self.subject),
            (self.rx + 0xB, self.native_value), (self.subject + 0x1B, self.listeners),
            (self.subject + 0x1F, self.null + 48), (self.listeners + 0xB, 2)):
            self.word(at, value)

    def pending(self):
        for key, value in (("installed", 1), ("control", 1),
                           ("revision", 3), ("notified", 1)):
            self.set(key, value)

    def nested_logger(self):
        assert self.nested is None
        self.nested = self.uc.context_save()
        self.uc.reg_write(xreg(29), self.frame - 0x100)
        self.uc.reg_write(xreg(15), self.dart_sp - 0x100)
        self.uc.reg_write(UC_ARM64_REG_PC, TRAP + 0x3000)

    def relocate(self):
        objects = ((self.controller, 0x80), (self.rx, 0x40),
                   (self.subject, 0x40), (self.listeners, 0x20))
        for at, size in objects:
            self.uc.mem_write(at + 0x2000 - 1, bytes(self.uc.mem_read(at - 1, size)))
            self.old_objects.append((at - 1, size))
        self.controller, self.rx, self.subject, self.listeners = (
            at + 0x2000 for at, _ in objects)
        self.ready()
        self.word(self.subject + 0x1F, self.null + 32)

    def on_read(self, _cpu, _access, at, size, _value, _data):
        assert not any(at < old + n and old < at + size
                       for old, n in self.old_objects), "retained moved heap pointer"

    def on_code(self, cpu, pc, _size, _data):
        if pc == TRAP + self.pos["event_original"]:
            self.replays.append(cpu.reg_read(xreg(29)))
        elif pc == TRAP + 4096:
            assert cpu.reg_read(xreg(1)) == self.rx
            assert cpu.reg_read(xreg(30)) == TRAP + 0x3000
            assert self.get("notified") == self.get("revision")
            self.calls.append((self.rx, self.state()))
            self.word(self.subject + 0x1F, self.null + 32)
            if self.notify:
                self.notify(self)
        elif pc == TRAP + 4100:
            if struct.unpack("<I", cpu.mem_read(self.subject + 0x1F, 4))[0] == self.null + 32:
                self.word(self.subject + 0x1F, self.null + 48)
            for n in (*range(15), 16, 17):
                cpu.reg_write(xreg(n), 0x4321000000000000 + n)
            cpu.reg_write(xreg(0), self.null)
        elif pc == TRAP + 0x3004:
            if self.nested is not None:
                context, self.nested = self.nested, None
                cpu.context_restore(context)
                cpu.reg_write(UC_ARM64_REG_PC, TRAP + 4100)
                self.nested_returns += 1
            else:
                cpu.emu_stop()

    def run(self):
        first_write = len(self.writes)
        for n, value in ((22, self.null), (26, self.thread), (27, self.pool),
                         (28, 0), (29, self.frame), (15, self.dart_sp)):
            self.uc.reg_write(xreg(n), value)
        sp = self.uc.reg_read(UC_ARM64_REG_SP)
        self.uc.emu_start(TRAP + 0x3000, TRAP + 0x3008, count=3000)
        assert self.uc.reg_read(xreg(15)) == self.dart_sp + 16
        assert self.uc.reg_read(xreg(29)) == self.frame
        assert self.uc.reg_read(UC_ARM64_REG_SP) == sp
        assert struct.unpack("<I", self.uc.mem_read(self.rx + 0xB, 4))[0] == self.native_value
        event_writes = self.writes[first_write:]
        assert all(at in self.addresses.values() and size in (4, 8)
                   for at, size, _ in event_writes), event_writes


def runtime_cases(p):
    cases = 0
    for value in (32, 48):
        m = MaterialMachine(p)
        m.native_value = m.null + value
        m.ready()
        m.pending()
        m.run()
        m.run()
        assert len(m.calls) == 1 and m.get("notified") == 3
        assert m.replays == [m.frame, m.frame]
        cases += 1
    for revise in (False, True):
        m = MaterialMachine(p)
        m.pending()
        once = []

        def notify(machine):
            if not once:
                once.append(True)
                if revise:
                    machine.set("control", 2)
                    machine.set("revision", 4)
                machine.nested_logger()

        m.notify = notify
        m.run()
        assert m.nested_returns == 1
        assert [state["notified"] for _, state in m.calls] == ([3, 4] if revise else [3])
        assert m.get("notified") == (4 if revise else 3)
        assert m.replays == [m.frame - 0x100, m.frame]
        cases += 1
    for blocked in ("disabled", "collecting", "closed", "empty", "busy"):
        m = MaterialMachine(p)
        m.pending()
        if blocked == "disabled":
            m.set("installed", 0)
        elif blocked == "collecting":
            write64(m.uc, m.table + 0x10, m.controller)
        elif blocked == "closed":
            m.word(m.subject + 0x1B, m.null)
            m.word(m.subject + 0x1F, m.null)
        elif blocked == "empty":
            m.word(m.listeners + 0xB, 0)
        else:
            m.word(m.subject + 0x1F, m.null + 32)
        before = m.state()
        m.run()
        assert not m.calls and m.state() == before
        m.ready()
        m.set("installed", 1)
        m.run()
        assert len(m.calls) == 1 and m.get("notified") == 3
        cases += 1
    for change in ("disabled", "closed", "moved"):
        m = MaterialMachine(p)
        m.pending()
        once = []

        def interrupt(machine):
            if once:
                return
            once.append(True)
            machine.set("revision", 4)
            if change == "disabled":
                machine.set("installed", 0)
            elif change == "closed":
                machine.word(machine.subject + 0x1B, machine.null)
                machine.word(machine.subject + 0x1F, machine.null)
            else:
                machine.relocate()

        m.notify = interrupt
        m.run()
        assert len(m.calls) == (2 if change == "moved" else 1)
        m.ready()
        m.set("installed", 1)
        m.run()
        assert len(m.calls) == 2 and m.get("notified") == 4
        cases += 1
    return cases


def publication_race_cases(p):
    """Pause the linked policy writer between control and revision publication."""
    cases = 0
    for api, value in [("Style", n) for n in range(3)] + [
            ("Policy", dock | (search << 2)) for dock, search in product(range(3), repeat=2)]:
        name, = [n for n in p.symbols if n.startswith("_Z") and "RequestLauncherGlass" + api in n]
        pair = value * 5 if api == "Style" else value
        m = MaterialMachine(p)
        m.set("installed", 1)
        m.set("control", 5 if pair != 5 else 10)
        m.set("revision", 7)
        m.set("notified", 7)
        stop = TRAP + 0x3900
        m.uc.reg_write(xreg(0), value)
        m.uc.reg_write(xreg(30), stop)
        m.uc.reg_write(UC_ARM64_REG_PC, p.addr(name))
        observed = False
        for _ in range(2000):
            pc = m.uc.reg_read(UC_ARM64_REG_PC)
            if pc == stop:
                break
            m.uc.emu_start(pc, stop + 4, count=1)
            if not observed and m.get("control") == pair and m.get("revision") == 7:
                context = m.uc.context_save()
                m.run()
                assert not m.calls and m.get("notified") == 7
                m.uc.context_restore(context)
                observed = True
        else:
            raise AssertionError("linked policy writer did not return")
        assert observed and m.get("revision") == 8
        m.run()
        m.run()
        assert len(m.calls) == 1 and m.get("notified") == 8
        cases += 1
    return cases


def linked_policy_cases(p):
    """Execute the actual C++ atomic writer and its BSS defaults."""
    machine = MaterialMachine(p)
    assert all(value == 0 for value in machine.state().values())
    machine.run()
    assert not machine.calls and not machine.writes
    name, = [name for name in p.symbols if name.startswith("_Z") and
             "RequestLauncherGlassStyle" in name]
    uc = p.uc()
    stop_pc = TRAP + 0x3900
    uc.hook_add(UC_HOOK_CODE, lambda cpu, pc, _s, _d:
                cpu.emu_stop() if pc == stop_pc else None)
    revision = 0
    previous = 0
    styles = (0, 1, 1, 2, 2, 0, 3, 0xffffffff)
    for style in styles:
        uc.reg_write(xreg(0), style)
        uc.reg_write(xreg(30), stop_pc)
        uc.emu_start(p.addr(name), stop_pc + 4, count=500)
        if style <= 2 and style != previous:
            revision += 1
            previous = style
        assert uc.reg_read(xreg(0)) == int(style <= 2)
        assert read64(uc, p.addr("kh_launcher_glass_control")) == previous * 5
        assert read64(uc, p.addr("kh_glass_material_revision")) == revision
        assert read64(uc, p.addr("kh_glass_material_notified")) == 0
    return len(styles) + 1


def linked_pair_policy_cases(p):
    """Exercise actual atomic pair writes, all transitions and invalid values."""
    name, = [n for n in p.symbols if n.startswith("_Z") and "RequestLauncherGlassPolicy" in n]
    pairs = [dock | (search << 2) for dock, search in product(range(3), repeat=2)]
    cases = 0
    for previous, policy in product(pairs, range(32)):
        uc = p.uc()
        write64(uc, p.addr("kh_launcher_glass_control"), previous)
        write64(uc, p.addr("kh_glass_material_revision"), 11)
        write64(uc, p.addr("kh_glass_material_notified"), 7)
        stop = TRAP + 0x3900
        uc.reg_write(xreg(0), policy)
        uc.reg_write(xreg(30), stop)
        uc.hook_add(UC_HOOK_CODE, lambda cpu, pc, _s, _d:
                    cpu.emu_stop() if pc == stop else None)
        uc.emu_start(p.addr(name), stop + 4, count=500)
        valid = policy in pairs
        assert uc.reg_read(xreg(0)) == int(valid)
        assert read64(uc, p.addr("kh_launcher_glass_control")) == (policy if valid else previous)
        assert read64(uc, p.addr("kh_glass_material_revision")) == 11 + int(valid and policy != previous)
        assert read64(uc, p.addr("kh_glass_material_notified")) == 7
        cases += 1
    return cases


def fixture_cases(p, image_path, metadata_path, scope="Dock"):
    """Run real native Rx.value/refresh and replay at the original event PC."""
    assert scope in ("Dock", "searchCapsule")
    source_base = 0x71000000
    meta = dict((k, int(v, 16)) for k, v in (
        line.split() for line in metadata_path.read_text().splitlines()))
    if scope == "searchCapsule":
        extra_path = metadata_path.with_name(metadata_path.name.replace(
            "material-", "material-extra-"))
        extra = dict((k, int(v, 16)) for k, v in (
            line.split() for line in extra_path.read_text().splitlines()))
        meta.update(branch=extra[scope + ".branch"],
                    dark=extra[scope + ".lightSchemeTarget"],
                    light=extra[scope + ".darkSchemeTarget"])
    maps = json.loads(metadata_path.with_name("material-oracles.json").read_text())
    event_map = maps[image_path.parent.name]["maps"]["event"]
    with image_path.open("rb") as stream:
        elf = ELFFile(stream)
        segments = [(s["p_vaddr"], s.data(), s["p_memsz"])
                    for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
    span = (max(a + size for a, _, size in segments) + 4095) & ~4095

    def source_word(at):
        for address, data, _ in segments:
            if address <= at <= address + len(data) - 4:
                return struct.unpack_from("<I", data, at - address)[0]
        raise AssertionError(hex(at))

    bitmap = bytes.fromhex(event_map["bitmap"])
    assert event_map["other"] == 0 and event_map["spills"] > 0
    assert event_map["bitmap_at"] == meta["event_bitmap"]
    assert bytes((source_word(event_map["bitmap_at"] + i) & 0xff)
                 for i in range(len(bitmap))) == bitmap
    live_bits = [i for i in range(event_map["spills"]) if bitmap[i // 8] & (1 << (i % 8))]
    assert live_bits
    value_call = meta["branch"] - 4
    call_word = source_word(value_call)
    call_delta = (call_word & 0x3ffffff) ^ 0x2000000
    assert call_word & 0xfc000000 == 0x94000000
    assert value_call + (call_delta - 0x2000000) * 4 == meta["value"]
    report_word = source_word(meta["value"] + 0x34)
    report_delta = (report_word & 0x3ffffff) ^ 0x2000000
    assert report_word & 0xfc000000 == 0x94000000
    report_read = source_base + meta["value"] + 0x34 + (report_delta - 0x2000000) * 4
    source_conditional = source_word(meta["branch"])
    delta = ((source_conditional >> 5 & 0x3fff) ^ 0x2000) - 0x2000
    original_taken = source_base + meta["branch"] + delta * 4
    # Decode independently authenticated native loads for this fixture.
    subject_field = (source_word(meta["refresh"] + 12) >> 12) & 0x1ff
    value_field = (source_word(meta["value"] + 0x3c) >> 12) & 0x1ff
    observer_slot = ((source_word(meta["value"] + 0x18) >> 10) & 4095) * 8
    # spillBits is a count. The one slot's bitmap bit is zero (dead).
    assert source_word(meta["refresh_add_global"]) & 0xFFFF == 1
    assert source_word(meta["refresh_add_bitmap"]) & 0xFF == 0
    total = 0
    for native_light_wallpaper in (False, True):
        for dock, search in product(range(3), repeat=2):
            mode = search if scope == "searchCapsule" else dock
            for relocate in (False, True):
                for pending_change in (False, True):
                    uc = p.uc()
                    uc.mem_map(source_base, span)
                    for address, data, _ in segments:
                        uc.mem_write(source_base + address, data)
                    pos, state, table, pool, thread = template(p, uc)
                    source_event = source_base + meta["return_pc"]
                    native_refresh = source_base + meta["refresh"]
                    source_branch = source_base + meta["branch"]
                    config = [state, state + 8, state + 16, state + 24,
                              meta["wallpaper_slot"], meta["wallpaper_rx_field"],
                              subject_field, value_field, observer_slot,
                              native_refresh, source_event, 32, 48,
                              meta["subject_listeners_field"], meta["subject_busy_field"],
                              meta["subject_listener_length_field"],
                              2 if scope == "searchCapsule" else 0]
                    uc.mem_write(TRAP + pos["config"], struct.pack(CONFIG_FORMAT, *config))
                    for name, target in [("dark", source_base + meta["light"]),
                                         ("light", source_base + meta["dark"]),
                                         ("taken", original_taken),
                                         ("fall", source_branch + 4),
                                         ("event_resume", source_base + meta["resume"])]:
                        at = TRAP + pos[name]
                        uc.mem_write(at, struct.pack("<I", branch(at, target)))
                    original = source_word(meta["branch"])
                    original = (original & ~0x7ffe0) | (
                        ((pos["taken"] - pos["original"]) // 4) << 5)
                    uc.mem_write(TRAP + pos["original"], struct.pack("<I", original))
                    uc.mem_write(TRAP + pos["event_original"],
                                 struct.pack("<I", source_word(meta["return_pc"])))
                    uc.mem_write(source_branch, struct.pack("<I", branch(
                        source_branch, TRAP + pos["choose"])))
                    uc.mem_write(source_event, struct.pack("<I", branch(
                        source_event, TRAP + pos["refresh"])))
                    null, sentinel = HEAP + 0x7001, HEAP + 0x7101
                    controller, rx, subject, listeners = [
                        HEAP + offset for offset in [0x101, 0x201, 0x301, 0x801]]
                    native_value = null + (32 if native_light_wallpaper else 48)
                    write64(uc, thread + 0x78, table)
                    write64(uc, pool + 0x40, sentinel)
                    collecting = relocate
                    observer = HEAP + 0x2801
                    write64(uc, table + observer_slot, observer if collecting else null)
                    write64(uc, table + meta["wallpaper_slot"], controller)
                    for at, value in [
                        (controller + meta["wallpaper_rx_field"], rx),
                        (rx + subject_field, subject), (rx + value_field, native_value),
                        (subject + meta["subject_listeners_field"], listeners),
                        (subject + meta["subject_busy_field"], null + 48),
                        (listeners + meta["subject_listener_length_field"], 2),
                    ]:
                        uc.mem_write(at, struct.pack("<I", value))
                    for offset, value in [(0, 1), (8, dock | (search << 2)), (16, 3), (24, 1)]:
                        write64(uc, state + offset, value)
                    frame = STACK + 0x5000
                    dart_sp = frame - event_map["spills"] * 8 - 16
                    roots = {frame - (i + 1) * 8: subject for i in live_bits}
                    for at, value in roots.items():
                        write64(uc, at, value)
                    for number, value in [(22, null), (26, thread), (27, pool),
                                          (28, 0), (15, dart_sp), (29, frame), (1, rx)]:
                        uc.reg_write(xreg(number), value)
                    landed, calls, writes = [], [], []
                    current_subject = subject
                    dead_slot = []
                    getters, subscriptions = [], []
                    phase = "choose"

                    def on_code(cpu, pc, _size, _data):
                        nonlocal current_subject
                        if pc == source_base + meta["value"]:
                            getters.append(phase)
                        elif pc == report_read:
                            assert collecting and phase == "choose"
                            assert cpu.reg_read(xreg(1)) == observer
                            assert cpu.reg_read(xreg(2)) == subject
                            assert cpu.reg_read(xreg(30)) == source_base + meta["value"] + 0x38
                            assert read64(cpu, cpu.reg_read(xreg(29)) - 8) == rx
                            subscriptions.append("native_report_read")
                            cpu.reg_write(UC_ARM64_REG_PC, cpu.reg_read(xreg(30)))
                        elif pc == native_refresh:
                            assert cpu.reg_read(xreg(30)) == source_event
                            assert cpu.reg_read(xreg(29)) == frame
                            assert cpu.reg_read(xreg(15)) == dart_sp
                            assert all(read64(cpu, at) == value for at, value in roots.items())
                        elif pc in (source_base + meta["dark"], source_base + meta["light"]):
                            landed.append(pc)
                            cpu.emu_stop()
                        elif pc == source_base + meta["stream_add"]:
                            assert cpu.reg_read(xreg(1)) == current_subject
                            assert cpu.reg_read(xreg(2)) == native_value
                            assert cpu.reg_read(xreg(30)) == native_refresh + 0x28
                            refresh_frame = cpu.reg_read(xreg(29))
                            assert read64(cpu, refresh_frame - 8) == current_subject
                            calls.append("native_subject_add")
                            dead_slot[:] = [(refresh_frame - 8, current_subject)]
                            if relocate:
                                # Move live heap references and the callee's
                                # argument; the caller's bitmap-00 slot stays OLD.
                                moved = current_subject + 0x2000
                                cpu.mem_write(moved - 1, bytes(cpu.mem_read(
                                    current_subject - 1, 0x40)))
                                cpu.mem_write(rx + subject_field, struct.pack("<I", moved))
                                cpu.reg_write(xreg(1), moved)
                                for at, value in roots.items():
                                    if value == current_subject:
                                        write64(cpu, at, moved)
                                        roots[at] = moved
                                current_subject = moved
                                assert read64(cpu, refresh_frame - 8) == dead_slot[0][1]
                            if pending_change and len(calls) == 1:
                                write64(cpu, state + 16, 4)
                            cpu.reg_write(xreg(0), null)
                            cpu.reg_write(UC_ARM64_REG_PC, cpu.reg_read(xreg(30)))
                        elif pc == source_event:
                            if dead_slot:
                                assert read64(cpu, dead_slot[0][0]) == dead_slot[0][1]
                                dead_slot.clear()
                        elif pc == source_base + meta["resume"]:
                            cpu.emu_stop()

                    def on_read(_cpu, _access, address, size, _value, _data):
                        assert not any(address < at + 8 and at < address + size
                                       for at, _ in dead_slot), "dead spill read after callback"

                    uc.hook_add(UC_HOOK_CODE, on_code)
                    uc.hook_add(UC_HOOK_MEM_READ, on_read)
                    uc.emu_start(source_base + value_call, source_branch + 0x200, count=300)
                    selected = "dark" if (
                        native_light_wallpaper if mode == 0 else mode == 1) else "light"
                    assert landed == [source_base + meta[selected]]
                    assert uc.reg_read(xreg(0)) == native_value
                    # Only the copied original branch's scratch registers changed.
                    assert getters == ["choose"]
                    assert len(subscriptions) == int(collecting)
                    assert uc.reg_read(xreg(15)) == dart_sp
                    write64(uc, table + observer_slot, null)
                    phase = "refresh"
                    uc.hook_add(UC_HOOK_MEM_WRITE, lambda _cpu, _access, at, size, value, _data:
                                writes.append((at, size, value)))
                    uc.emu_start(source_event, source_event + 8, count=1000)
                    assert len(calls) == (2 if pending_change else 1)
                    assert getters.count("refresh") == len(calls)
                    assert uc.reg_read(xreg(15)) == dart_sp
                    assert uc.reg_read(xreg(29)) == frame
                    assert all(read64(uc, at) == value for at, value in roots.items())
                    # CPU stores stay in native stack frames or the primitive ACK.
                    assert all(STACK <= at < STACK + 65536 or at in
                               [state + 24, state + 16] for at, _, _ in writes), writes
                    assert struct.unpack("<I", uc.mem_read(rx + value_field, 4))[0] == native_value
                    assert struct.unpack("<Q", uc.mem_read(table + meta["wallpaper_slot"], 8))[0] == controller
                    assert struct.unpack("<Q", uc.mem_read(state + 24, 8))[0] == (4 if pending_change else 3)
                    total += 1
    return dict(cases=total, native_choose_getters=total,
                subscription_paths=total // 2, native_refresh_calls=total * 3 // 2,
                moving_callback_cases=total // 2, source_live_slots=len(live_bits))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    source = ap.add_mutually_exclusive_group(required=True)
    source.add_argument("--payload", type=Path,
                    help="final shipping arm64 SO assembled by main")
    source.add_argument("--source", action="store_true",
                        help="compile production bridge/policy only; no Gradle")
    ap.add_argument("--ndk", type=Path, help="NDK for --source")
    ap.add_argument("--report", type=Path)
    ap.add_argument("--fixtures", type=Path)
    ap.add_argument("--metadata", type=Path)
    args = ap.parse_args()
    if bool(args.fixtures) != bool(args.metadata):
        ap.error("--fixtures and --metadata must be supplied together")
    if args.source:
        from material_bridge_payload import standalone_payload
        with standalone_payload(args.ndk) as payload:
            args.payload = payload
            execute(args)
    else:
        execute(args)


def execute(args):
    payload_sha256 = hashlib.sha256(args.payload.read_bytes()).hexdigest()
    p = Payload(args.payload)
    template(p, p.uc())  # reject the old ABI/exports before executing any payload
    result = dict(config_bytes=CONFIG_SIZE, shipping_scopes=["Dock", "searchCapsule"],
                  payload_kind="source-only" if args.source else "final-linked",
                  payload=str(args.payload.resolve()),
                  payload_sha256=payload_sha256,
                  folder_exports_absent=True,
                  choose=choose_cases(p), choose_conditions=condition_cases(p),
                  refresh=refresh_cases(p), runtime=runtime_cases(p),
                  policy=linked_policy_cases(p), pair_policy=linked_pair_policy_cases(p),
                  publication_races=publication_race_cases(p))
    if args.fixtures:
        result["native_fixtures"] = {version: {scope: fixture_cases(
            p, args.fixtures / version / "libapp.so",
            args.metadata / f"material-{version}.tsv", scope)
            for scope in ("Dock", "searchCapsule")}
            for version in ("7722", "8593", "8602", "9209", "9226", "8604", "9239")}
    result["cases"] = sum(result[key] for key in
                          ("choose", "choose_conditions", "refresh", "runtime",
                           "policy", "pair_policy", "publication_races"))
    result["cases"] += sum(scope["cases"] for scopes in
                           result.get("native_fixtures", {}).values()
                           for scope in scopes.values())
    assert hashlib.sha256(args.payload.read_bytes()).hexdigest() == result["payload_sha256"], \
        "payload changed during linked execution"
    result["passed"] = True
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()

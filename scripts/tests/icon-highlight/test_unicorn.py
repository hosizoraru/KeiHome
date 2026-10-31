#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute original ELF paths with the actual C++-built production bridge."""
import itertools
import random
import struct

from unicorn import (Uc, UcError, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE,
                     UC_HOOK_MEM_WRITE, UC_HOOK_MEM_READ)
from unicorn import arm64_const as ar

BRIDGE = 0x7800000000
POLICY = 0x63000000
THREAD = 0x60000000
FIELDS = 0x60100000
POOL = 0x60300000
STACK = 0x61010000
END = 0x62000000
X = [getattr(ar, f"UC_ARM64_REG_X{i}") for i in range(31)]
Q = [getattr(ar, f"UC_ARM64_REG_Q{i}") for i in range(32)]
SPECIAL = [ar.UC_ARM64_REG_SP, ar.UC_ARM64_REG_NZCV, ar.UC_ARM64_REG_FPCR, ar.UC_ARM64_REG_FPSR]


def execute(image, profile, fields, bridge):
    entry = profile["entry"]
    original = image.read(entry, 0x7c)
    code = struct.unpack("<31I", original)
    find_pc = entry + 0x4c
    find = find_pc + (((code[19] & 0x3ffffff) ^ 0x2000000) - 0x2000000) * 4
    baselines = patched_count = bypass = 0
    negative = []
    for high in (0, 0x74):
        heap = high << 32
        null, device = heap + 0x50000081, heap + 0x60200001
        true, false = null + 0x20, null + 0x30
        uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        uc.mem_map(0, (image.span + 4095) & ~4095)
        uc.mem_write(0, bytes(image.mapped))
        for address, size in ((THREAD, 0x600000), (0x61000000, 0x20000),
                              (END, 0x1000), (POLICY, 0x1000), (BRIDGE, 0x1000),
                              (heap + 0x50000000, 0x1000)):
            uc.mem_map(address, size)
        if high:
            uc.mem_map(heap + 0x60200000, 0x1000)
        uc.mem_write(BRIDGE, bridge)

        def put64(address, value):
            uc.mem_write(address, struct.pack("<Q", value & 0xffffffffffffffff))

        def put32(address, value):
            uc.mem_write(address, struct.pack("<I", value & 0xffffffff))

        put64(THREAD + 0x78, FIELDS)
        put64(POOL + 0x40, heap + 0x60500001)
        put64(FIELDS + ((code[10] >> 10) & 4095) * 8, heap + 0x60400001)
        calls, writes, policy_reads = [], [], []

        def on_code(cpu, pc, size, opaque):
            if pc in (entry, profile["material_disabled"], profile["material_supported"], BRIDGE):
                calls.append(pc)
            if pc == find:
                calls.append(pc)
                cpu.reg_write(X[0], device)
                cpu.reg_write(ar.UC_ARM64_REG_PC, cpu.reg_read(X[30]))

        def on_write(cpu, access, address, size, value, opaque):
            assert 0x61000000 <= address and address + size <= 0x61020000, (hex(address), size)
            writes.append((address, size, value))

        def on_read(cpu, access, address, size, value, opaque):
            if address == POLICY:
                assert size == 4
                policy_reads.append(address)

        uc.hook_add(UC_HOOK_CODE, on_code)
        uc.hook_add(UC_HOOK_MEM_WRITE, on_write)
        uc.hook_add(UC_HOOK_MEM_READ, on_read)

        def invoke(patched, seed, policy, bad_window=False):
            uc.mem_write(entry, original)
            if patched:
                offset = 0x64 if bad_window else 0x5c
                uc.mem_write(entry + offset, struct.pack("<IIQ", 0x58000040, 0xd61f0000, BRIDGE))
                if not bad_window:
                    assert bytes(uc.mem_read(entry + 0x6c, 16)) == original[0x6c:]
            uc.ctl_remove_cache(entry, entry + 0x7c)
            put32(POLICY, policy)
            rng = random.Random(seed)
            for reg in X:
                uc.reg_write(reg, rng.getrandbits(64))
            for reg in Q:
                uc.reg_write(reg, rng.getrandbits(128))
            for reg, value in ((X[15], STACK), (X[22], null), (X[26], THREAD), (X[27], POOL),
                               (X[28], high), (X[30], END), (ar.UC_ARM64_REG_SP, STACK - 0x8000),
                               (ar.UC_ARM64_REG_NZCV, (seed & 15) << 28),
                               (ar.UC_ARM64_REG_FPCR, 0x00400000), (ar.UC_ARM64_REG_FPSR, 0x10)):
                uc.reg_write(reg, value)
            calls.clear(); writes.clear(); policy_reads.clear()
            uc.emu_start(entry, END, count=2000)
            assert uc.reg_read(ar.UC_ARM64_REG_PC) == END
            return ([uc.reg_read(r) for r in X + Q + SPECIAL],
                    list(calls), list(writes), list(policy_reads))

        for supported, os_version, style, default, lazy, seed in itertools.product(
                (False, True), (3, 4, 5), (-1, 0, 1), (False, True), (False, True), (0, 15)):
            put64(FIELDS + fields["support_slot"], true if supported else false)
            put64(FIELDS + fields["style_slot"], style * 2)
            put64(FIELDS + fields["device_slot"], null if lazy else device)
            put64(device + fields["os_field"], os_version)
            put32(device + fields["theme_field"], true if default else false)
            native = invoke(False, seed, 0)
            material = supported and os_version >= 4 and style != -1
            assert native[0][0] == (true if material and default else false)
            baselines += 1
            # Same live synthetic receiver, repeated enable/disable and invalid
            # publications; no retained heap pointer or wrapper reconstruction.
            for policy in (0, 1, 0, 2, 0xffffffff):
                changed = invoke(True, seed, policy)
                expected = true if material and (default or policy == 1) else false
                assert changed[0][0] == expected
                assert changed[0][1:] == native[0][1:], "non-result state drift"
                assert [pc for pc in changed[1] if pc != BRIDGE] == native[1], "extra/replaced call"
                assert changed[1].count(BRIDGE) == int(material)
                assert changed[2] == native[2], "additional or changed write"
                assert len(changed[3]) == int(material), "wrong policy read count"
                patched_count += 1
                bypass += int(not material)
        put64(FIELDS + fields["support_slot"], false)
        try:
            invoke(True, 0, 1, bad_window=True)
            raise AssertionError("unsafe +0x64 detour unexpectedly completed")
        except UcError:
            negative.append(hex(uc.reg_read(ar.UC_ARM64_REG_PC)))
    return {"native_baselines": baselines, "patched_executions": patched_count,
            "disabled_bypass": bypass, "negative_plus64_faults": negative,
            "proof": "all non-result GPR/SIMD/flags/frame match, identical writes/calls, no extra stack",
            "limits": "synthetic receiver; original lookup mocked, no Dart VM/GC/renderer/BTI enforcement"}

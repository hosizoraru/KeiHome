#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Focused host tests only; all generated objects/results stay in --output."""
import argparse
import ctypes as C
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import time

from elf_fixture import Image, ROLES, masks

ROOT = Path(__file__).resolve().parents[3]
CPP = ROOT / "app/src/main/cpp"


class Range(C.Structure):
    _fields_ = [("begin", C.c_size_t), ("end", C.c_size_t), ("executable", C.c_bool)]


class Profile(C.Structure):
    _fields_ = [(name, C.c_size_t) for name in
                ("entry", "material_disabled", "material_supported", "theme_writer",
                 "stroke_consumer", "patch", "false_target")]
    _fields_ += [(name, C.c_uint32) for name in
                 ("theme_field", "device_slot", "support_slot", "style_slot", "os_field")]
    _fields_ += [("expected", C.c_uint32 * 4)]


def build(args):
    args.output.mkdir(parents=True, exist_ok=True)
    lib = args.output / "profile.dylib"
    common = [args.cxx, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
              "-fno-exceptions", "-fno-rtti", "-I", str(CPP)]
    subprocess.run(common + ["-shared", "-fPIC", str(CPP / "icon_highlight_profile.cpp"),
                   str(Path(__file__).with_name("test_api.cpp")), "-o", str(lib)], check=True)
    patch = args.output / "test_exact_patch"
    subprocess.run(common + [str(CPP / "arm64_exact_patch.cpp"),
                   str(Path(__file__).with_name("test_exact_patch.cpp")), "-o", str(patch)], check=True)
    subprocess.run([str(patch)], check=True)
    asm = args.output / "bridge.o"
    subprocess.run([str(args.ndk / "clang"), "--target=aarch64-linux-android35",
                    "-c", str(CPP / "icon_highlight_bridge.S"), "-o", str(asm)], check=True)
    raw = args.output / "bridge-template.bin"
    subprocess.run([str(args.ndk / "llvm-objcopy"), "-O", "binary",
                    "--only-section=.rodata.icon_highlight_bridge", str(asm), str(raw)], check=True)
    obj = C.CDLL(str(lib))
    obj.kh_test_resolve.argtypes = [C.c_void_p, C.c_size_t, C.POINTER(Range), C.c_size_t,
                                   C.POINTER(Profile), C.POINTER(C.c_char_p)]
    obj.kh_test_resolve.restype = C.c_bool
    obj.kh_test_bridge.argtypes = [C.POINTER(Profile), C.c_void_p, C.c_size_t,
                                  C.c_size_t, C.c_void_p]
    obj.kh_test_bridge.restype = C.c_bool
    obj.kh_test_detour.argtypes = [C.c_size_t, C.c_void_p]
    obj.kh_test_detour.restype = C.c_bool
    return obj, raw.read_bytes()


def resolver_tests(lib, image, template):
    total = 0
    ranges = (Range * len(image.ranges))(*(Range(*r) for r in image.ranges))

    def resolve(data, selected=ranges):
        buf = (C.c_uint8 * len(data)).from_buffer(data)
        p, reason = Profile(), C.c_char_p()
        success = lib.kh_test_resolve(buf, len(data), selected, len(selected), C.byref(p), C.byref(reason))
        return success, p, C.addressof(buf), reason.value

    def check(value, name):
        nonlocal total
        assert value, name
        total += 1

    started = time.monotonic()
    ok, valid, base, reason = resolve(image.mapped)
    check(ok, ("real image", reason))
    first_ms = (time.monotonic() - started) * 1000
    offsets = {name: getattr(valid, name) - base for name, _ in Profile._fields_[:7]}
    for field, (_, symbol) in zip(("entry", "material_disabled", "material_supported",
                                  "theme_writer", "stroke_consumer"), ROLES):
        check(offsets[field] == image.syms[symbol][0][0], ("independent debug oracle", symbol))
    check(offsets["patch"] == offsets["entry"] + 0x5c and
          offsets["false_target"] == offsets["entry"] + 0x6c, "selected exact window")
    bridge = (C.c_uint8 * 64)()
    check(lib.kh_test_bridge(C.byref(valid), template, len(template), 0x63000000, bridge),
          "actual assembled bridge accepted")
    patch = (C.c_uint32 * 4)()
    check(lib.kh_test_detour(0x7800000000, patch), "far detour")
    check(bytes(patch) == struct.pack("<IIQ", 0x58000040, 0xd61f0000, 0x7800000000), "exact patch bytes")
    check(not lib.kh_test_detour(0x7800000001, patch) and not any(patch), "unaligned bridge rejected")
    for index in range(len(template) // 4):
        bad = bytearray(template); bad[index * 4] ^= 1
        check(not lib.kh_test_bridge(C.byref(valid), bytes(bad), len(bad), 0x63000000, bridge)
              and not any(bridge), ("bridge template mutation", index))

    def reject(data, label, selected=ranges):
        ok, p, _, _ = resolve(data, selected)
        check(not ok and not any(bytes(p)), label)

    for role, symbol in ROLES:
        start, size = image.syms[symbol][0]
        pattern_masks = masks(image.words(symbol), role)
        for index, mask in enumerate(pattern_masks):
            data = image.mapped.copy()
            at = start + index * 4
            word = struct.unpack_from("<I", data, at)[0]
            struct.pack_into("<I", data, at, word ^ (mask & -mask))
            reject(data, (role, "instruction mutation", index))
    for field in ("device_slot", "theme_field"):
        data = image.mapped.copy()
        at = offsets["entry"] + (0x18 if field == "device_slot" else 0x5c)
        word = struct.unpack_from("<I", data, at)[0]
        struct.pack_into("<I", data, at, word + (0x400 if field == "device_slot" else 0x4000))
        reject(data, ("broken cross-function link", field))
    shifted = image.mapped.copy()
    for at in (offsets["entry"] + 0x5c, offsets["theme_writer"] + 0x9c,
               offsets["theme_writer"] + 0xac, offsets["theme_writer"] + 0xc0):
        word = struct.unpack_from("<I", shifted, at)[0]
        struct.pack_into("<I", shifted, at, word + 0x4000)
    ok, changed, _, reason = resolve(shifted)
    check(ok and changed.theme_field == valid.theme_field + 4,
          ("coherent unknown theme field relocation", reason))
    shifted = image.mapped.copy()
    for at in (offsets["entry"] + 0x18, offsets["material_supported"] + 0x1c):
        word = struct.unpack_from("<I", shifted, at)[0]
        struct.pack_into("<I", shifted, at, word + 0x400)
    ok, changed, _, reason = resolve(shifted)
    check(ok and changed.device_slot == valid.device_slot + 8,
          ("coherent unknown receiver slot relocation", reason))
    data = image.mapped.copy(); data[18] = 62
    reject(data, "wrong machine")
    reject(image.mapped, "range mismatch", (Range * 1)(Range(0, len(image.mapped), True)))
    bad_ranges = (Range * len(ranges))(*(Range(r.begin, r.end, False) for r in ranges))
    reject(image.mapped, "missing executable maps", bad_ranges)
    # Only expand a verified file gap; never mutate original mapped code to
    # manufacture a candidate. All of this is synthetic host memory.
    rx_index = next(i for i, r in enumerate(ranges) if r.executable)
    scratch = (ranges[rx_index].end + 3) & ~3
    check(all(not b for b in image.mapped[scratch:scratch + 512]), "synthetic gap")
    expanded = image.mapped.copy()
    ph = struct.unpack_from("<Q", expanded, 32)[0]
    for i in range(struct.unpack_from("<H", expanded, 56)[0]):
        at = ph + i * 56
        if struct.unpack_from("<II", expanded, at) == (1, 5):
            old_size = struct.unpack_from("<Q", expanded, at + 40)[0]
            extra = scratch + 512 - ranges[rx_index].end
            struct.pack_into("<QQ", expanded, at + 32, old_size + extra, old_size + extra)
    expanded_ranges = (Range * len(ranges))(*(Range(r.begin,
        scratch + 512 if i == rx_index else r.end, r.executable) for i, r in enumerate(ranges)))
    entry = offsets["entry"]

    def branch(data, pc, target, opcode=0x94000000):
        struct.pack_into("<I", data, pc, opcode | (((target - pc) // 4) & 0x3ffffff))

    for delta in (0x60, 0x64, 0x68, 0x5c, 0x6c):
        data = expanded.copy(); branch(data, scratch, entry + delta, 0x14000000)
        reject(data, ("external entry into highlight body", delta), expanded_ranges)
    # Conditional branch encodings have shorter reach. Inject away from all
    # five reviewed bodies, so rejection must come from the ingress scan.
    for kind, opcode, bits in (("B.cond", 0x54000000, 19),
                              ("CBZ", 0x34000000, 19), ("TBZ", 0x36000000, 14)):
        data = image.mapped.copy()
        pc, target = entry + 0x1000, entry + 0x60
        assert all(not start <= pc < start + size for _, symbol in ROLES
                   for start, size in image.syms[symbol])
        struct.pack_into("<I", data, pc, opcode | ((((target - pc) // 4) & ((1 << bits) - 1)) << 5))
        reject(data, (kind, "entry into patch interior"))
    duplicate = expanded.copy()
    duplicate[scratch:scratch + 0x7c] = duplicate[entry:entry + 0x7c]
    for i in (0x0c, 0x3c, 0x4c):
        w = struct.unpack_from("<I", duplicate, entry + i)[0]
        dest = entry + i + (((w & 0x3ffffff) ^ 0x2000000) - 0x2000000) * 4
        branch(duplicate, scratch + i, dest)
    reject(duplicate, "duplicate complete highlight candidate", expanded_ranges)
    moved = duplicate.copy()
    moved[entry:entry + 0x7c] = struct.pack("<I", 0xd4200000) * 31
    for r in expanded_ranges:
        if not r.executable:
            continue
        for pc in range(r.begin, r.end - 3, 4):
            word = struct.unpack_from("<I", moved, pc)[0]
            if word & 0x7c000000 != 0x14000000:
                continue
            target = pc + (((word & 0x3ffffff) ^ 0x2000000) - 0x2000000) * 4
            if target == entry:
                branch(moved, pc, scratch, word & 0xfc000000)
    ok, p, new_base, why = resolve(moved, expanded_ranges)
    check(ok and p.entry - new_base == scratch, ("unknown relocated function graph", why))
    # Reconstruct bridge using the genuine profile after negative builder cases.
    check(lib.kh_test_bridge(C.byref(valid), template, len(template), 0x63000000, bridge), "bridge final")
    return {"checks": total, "first_resolve_ms": round(first_ms, 2),
            "profile": offsets, "fields": {name: getattr(valid, name) for name, _ in Profile._fields_[7:-1]},
            "bridge": bytes(bridge)}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("images", nargs="*", type=Path,
                   help="exact ELFs; defaults to the shared launcher regression inventory")
    p.add_argument("--inventory", type=Path,
                   default=Path(__file__).parents[1] / "launcher-labels/fixtures.json")
    p.add_argument("--payload", type=Path, help="also authenticate and execute the linked highlight template")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--cxx", default=os.environ.get("CXX", "clang++"))
    p.add_argument("--ndk", type=Path, required=True, help="NDK LLVM bin directory")
    args = p.parse_args()
    if not args.images:
        args.images = [ROOT / ".artifacts/inputs/launchers" / version / "libapp.so"
                       for version in json.loads(args.inventory.read_text())["launchers"]]
    lib, template = build(args)
    if args.payload:
        from payload import authenticate
        template, authentication = authenticate(args.payload, args.ndk, args.output, "highlight")
        (args.output / "payload.json").write_text(json.dumps(authentication, indent=2) + "\n")
        print(json.dumps({"payload": authentication}), flush=True)
    results = []
    for path in args.images:
        image = Image(path)
        result = resolver_tests(lib, image, template)
        from test_unicorn import execute
        result["execution"] = execute(image, result["profile"], result["fields"], result.pop("bridge"))
        result["image"] = str(path)
        result["sha256"] = hashlib.sha256(image.data).hexdigest()
        results.append(result)
        print(json.dumps(result), flush=True)
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")


if __name__ == "__main__":
    main()

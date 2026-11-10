#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Framework SDK regression plus negative launcher widget-SDK role admission."""
import argparse
import ctypes as C
import hashlib
import io
import json
import lzma
import os
from pathlib import Path
import subprocess
import sys

from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "icon-highlight"))
from elf_fixture import Image


class Range(C.Structure):
    _fields_ = [("begin", C.c_size_t), ("end", C.c_size_t), ("executable", C.c_bool)]


def roles(path):
    with path.open("rb") as stream:
        elf = ELFFile(stream)
        debug = ELFFile(io.BytesIO(lzma.decompress(elf.get_section_by_name(".gnu_debugdata").data())))
        symbols = list(debug.get_section_by_name(".symtab").iter_symbols())
        roots = [s for s in symbols if "zip_archive6SharedE9drop_slow" in s.name
                 and s["st_info"]["type"] == "STT_FUNC" and s["st_size"]]
        return symbols, roots


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory", type=Path, default=HERE / "fixtures.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--cxx", default=os.environ.get("CXX", "clang++"))
    parser.add_argument("--sanitizers", action="store_true", help="also run queue/priority ASan+UBSan and TSan")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    inventory = json.loads(args.inventory.read_text())
    cpp = ROOT / "app/src/main/cpp"
    common = [args.cxx, "-std=c++17", "-Wall", "-Wextra", "-Werror", "-O2", "-I" + str(cpp)]
    sources = [str(cpp / "maml_zip_release_profile.cpp"), str(cpp / "icon_highlight_profile.cpp")]
    runner = args.output / "zip-profile"
    subprocess.run(common + [str(ROOT / "app/src/test/cpp/maml_zip_release_profile_test.cpp"),
                             *sources, "-o", str(runner)], check=True)
    adapter = args.output / "zip-probe.dylib"
    subprocess.run(common + ["-shared", "-fPIC", str(HERE / "probe.cpp"), *sources,
                             "-o", str(adapter)], check=True)
    lib = C.CDLL(str(adapter))
    lib.kh_test_zip.argtypes = [C.c_void_p, C.c_size_t, C.POINTER(Range), C.c_size_t,
                                C.POINTER(C.c_size_t), C.POINTER(C.c_char_p)]
    lib.kh_test_zip.restype = C.c_bool
    results = []
    for name in inventory["framework_sdks"] + inventory["negative_launcher_sdks"]:
        path = ROOT / name
        symbols, roots = roles(path)
        row = {"image": name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
               "debug_symbols": len(symbols),
               "zip_shared_drop_roots": [{"name": s.name, "rva": s["st_value"]} for s in roots]}
        if name in inventory["framework_sdks"]:
            assert len(roots) == 1, name
            result = subprocess.run([str(runner), str(path), hex(roots[0]["st_value"])],
                                    check=True, text=True, capture_output=True)
            row["host_test"] = result.stdout.strip()
        else:
            # Absence in the independent symbol oracle AND resolver rejection
            # are both required. A rejection alone cannot prove role absence.
            assert not roots, (name, "unexpected MAML ZIP root; review before admitting support")
            row["zip_archive_symbol_names"] = [s.name for s in symbols if "zip_archive" in s.name]
            assert not row["zip_archive_symbol_names"], name
            image = Image(path)
            data = (C.c_uint8 * len(image.mapped)).from_buffer(image.mapped)
            ranges = (Range * len(image.ranges))(*(Range(*r) for r in image.ranges))
            address, why = C.c_size_t(1), C.c_char_p()
            admitted = lib.kh_test_zip(data, len(data), ranges, len(ranges), C.byref(address), C.byref(why))
            assert not admitted and address.value == 0, name
            row.update({"admitted": admitted, "output_address": address.value,
                        "rejection": why.value.decode() if why.value else None,
                        "role": "negative fixture; framework SDK support is separate"})
        results.append(row)
        print(json.dumps(row), flush=True)
    generated = args.output / "maml_zip_release_patterns.inc"
    subprocess.run([sys.executable, str(HERE / "generate_patterns.py"),
                    *(str(ROOT / n) for n in inventory["framework_sdks"]), str(generated)], check=True)
    assert generated.read_bytes() == (cpp / "maml_zip_release_patterns.inc").read_bytes()
    if args.sanitizers:
        for sanitizer in ("address,undefined", "thread"):
            binary = args.output / ("queue-" + sanitizer.replace(",", "-"))
            subprocess.run(common + ["-O1", "-g", "-fsanitize=" + sanitizer, "-pthread",
                            str(ROOT / "app/src/test/cpp/deferred_zip_release_test.cpp"),
                            str(cpp / "deferred_zip_release.cpp"), "-o", str(binary)], check=True)
            subprocess.run([str(binary)], check=True)
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print("Both framework graphs reproduce current patterns; launcher SDK remains a negative fixture.", flush=True)


if __name__ == "__main__":
    main()

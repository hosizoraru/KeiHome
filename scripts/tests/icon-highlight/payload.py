#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Test-only linked bridge authentication against freshly assembled source."""
import hashlib
from pathlib import Path
import subprocess

from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parents[3]


def symbol_bytes(path, begin, end=None):
    with Path(path).open("rb") as stream:
        elf = ELFFile(stream)
        table = elf.get_section_by_name(".symtab")
        assert table is not None, f"unstripped symbol table required: {path}"
        symbols = {s.name: s for s in table.iter_symbols()}
        start = symbols[begin]["st_value"]
        stop = symbols[end]["st_value"] if end else start + symbols[begin]["st_size"]
        assert stop > start
        for segment in elf.iter_segments():
            if (segment["p_type"] == "PT_LOAD" and segment["p_vaddr"] <= start
                    and stop <= segment["p_vaddr"] + segment["p_filesz"]):
                stream.seek(segment["p_offset"] + start - segment["p_vaddr"])
                return stream.read(stop - start), {
                    n: s["st_value"] - start for n, s in symbols.items()
                    if n.startswith(begin.removesuffix("template")) and start <= s["st_value"] <= stop}
    raise AssertionError(f"{begin} is not in one file-backed load: {path}")


def authenticate(payload, ndk, output, family):
    source, begin, end = {
        "labels": ("launcher_label_bridge.S", "kh_label_template", "kh_label_end"),
        "highlight": ("icon_highlight_bridge.S", "kh_icon_highlight_template", None),
    }[family]
    output.mkdir(parents=True, exist_ok=True)
    snapshot = output / f"{family}-payload.so"
    snapshot.write_bytes(Path(payload).read_bytes())
    reference = output / f"{family}-source-bridge.so"
    subprocess.run([str(ndk / "clang"), "--target=aarch64-linux-android35",
                    "-shared", "-nostdlib", "-Wl,--no-undefined",
                    str(ROOT / "app/src/main/cpp" / source), "-o", str(reference)], check=True)
    expected, offsets = symbol_bytes(reference, begin, end)
    actual, linked_offsets = symbol_bytes(snapshot, begin, end)
    assert actual == expected, f"{family}: linked payload bridge differs from current source"
    assert offsets == linked_offsets, f"{family}: linked bridge entry offsets differ"
    return actual, {
        "path": str(Path(payload).resolve()),
        "snapshot": str(snapshot.resolve()),
        "sha256": hashlib.sha256(snapshot.read_bytes()).hexdigest(),
        "source": source,
        "source_sha256": hashlib.sha256((ROOT / "app/src/main/cpp" / source).read_bytes()).hexdigest(),
        "symbol": begin, "bytes": len(actual),
        "symbol_sha256": hashlib.sha256(actual).hexdigest(),
        "entry_offsets": offsets,
        "matches_current_source": True,
        "limits": "bridge bytes and entries only; does not authenticate other compiled C++ code",
    }

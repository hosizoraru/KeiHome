#!/usr/bin/env python3
"""Host-only named AOT/material/GC-map oracles. Never used by production."""
import importlib.util
import json
import os
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
OUT = Path(os.environ.get("KEIHOME_MATERIAL_METADATA_DIR",
                         ROOT / ".artifacts/research/dock-material-20261105/material-sidecar"))
VERSIONS = ("7722", "8593", "8602", "9209", "9226", "8604", "9239")
spec = importlib.util.spec_from_file_location("material_elf", HERE.parent / "icon-highlight/elf_fixture.py")
elf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(elf)


def branch(im, pc):
    w = struct.unpack("<I", im.read(pc, 4))[0]
    assert w & 0xfc000000 == 0x94000000
    return pc + (((w & 0x3ffffff) ^ 0x2000000) - 0x2000000) * 4


def var(b, p):
    value = shift = 0
    while b[p] < 128:
        value |= b[p] << shift
        shift += 7
        p += 1
        assert shift <= 56
    return value + ((b[p] - 128) << shift), p + 1


def leb(b, p):
    value = shift = 0
    while True:
        c = b[p]
        p += 1
        value |= (c & 127) << shift
        if c < 128:
            return value, p
        shift += 7
        assert shift < 64


def stack_maps(im):
    b = im.data
    start = next(a for a in range(0x350, 0x10000, 16) if b[a:a + 4] == bytes.fromhex("f5f5dcdc"))
    end = start + struct.unpack_from("<Q", b, start + 4)[0] + 4
    p = b.index(0, start + 52) + 1
    head = []
    for _ in range(5):
        value, p = var(b, p)
        head.append(value)
    table = ((end + 63) & ~63) + head[4] + 16
    g, total, first, pad = struct.unpack_from("<4I", b, table)
    assert total - first == head[3] and pad == 0
    dyn = im.by_name[".dynsym"]
    strings = im.section(".dynstr")
    code = []
    for at in range(dyn[4], dyn[4] + dyn[5], dyn[9]):
        name, _, _, _, value, _ = struct.unpack_from("<IBBHQQ", b, at)
        if strings[name:].split(b"\0", 1)[0] == b"_kDartIsolateSnapshotInstructions":
            code.append(value)
    [code] = code
    entries = {code + o: sm for i in range(total) for o, sm in [
        struct.unpack_from("<2I", b, table + 16 + i * 8)]}
    global_bits = table + g + 4

    def maps(entry):
        at = table + entries[entry]
        header = struct.unpack_from("<I", b, at)[0]
        assert header & 3 == 2
        p, end = at + 4, at + 4 + (header >> 2)
        pc = 0
        result = {}
        while p < end:
            delta, p = leb(b, p)
            pc += delta
            offset, p = leb(b, p)
            spills, q = leb(b, global_bits + offset)
            other, q = leb(b, q)
            result[entry + pc] = dict(
                spills=spills, other=other,
                bitmap=b[q:q + (spills + other + 7) // 8].hex(),
                bitmap_at=q, local_payload=at, global_entry=global_bits + offset,
            )
        assert p == end
        return result
    return maps


def mask(word):
    if word & 0xfc000000 in (0x94000000, 0x14000000):
        return 0xfc000000
    if word & 0xff000010 == 0x54000000:
        return 0xff00001f
    if word & 0x7e000000 == 0x36000000:
        return 0xfff8001f
    if word & 0xffc003e0 == 0x91400360:
        return 0xffc003ff
    if word & 0xffc00000 == 0xf9400000 and (word >> 5 & 31) != 29:
        return 0xffc003ff
    if word & 0xffe00c00 == 0xb8400000:
        return 0xffe00fff
    if word & 0xffc003ff == 0xf9000020:
        return 0xffc003ff
    return 0xffffffff


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    evidence, patterns = {}, {}
    for version in VERSIONS:
        im = elf.Image(ROOT / ".artifacts/inputs/launchers" / version / "libapp.so")
        build, build_size = im.syms["_DockGlassWidgetState.build"][0]
        dark, _ = im.syms["_DockGlassWidgetState._darkFilter"][0]
        closure = build + build_size
        assert dark - closure == 0x13c
        setter, setter_size = im.syms["WallpaperGetxController.setCurrentWallpaperColorMode"][0]
        value = im.syms["RxObjectMixin.value"][0][0]
        [refresh] = [a for a, _ in im.syms["RxObjectMixin.refresh"]
                     if branch(im, a + 0x18) == value]
        event = im.syms["ItemChangeProcessor.processItemsChanged"][0][0]
        logger = im.syms["_LoggerImpl.info"][0][0]
        [first, second] = [at for at in range(event + 0x30, event + 0x120, 4)
                           if struct.unpack("<I", im.read(at, 4))[0] & 0xfc000000 == 0x94000000
                           and branch(im, at) == logger]
        maps = stack_maps(im)
        grid = im.syms["GridController.currentConfig"][0][0]
        w = struct.unpack("<79I", im.read(closure, 0x13c))
        taken = closure + 0x68 + (((w[0x68 // 4] >> 5 & 0x3fff) ^ 0x2000) - 0x2000) * 4
        evidence[version] = dict(
            build=build, closure=closure, branch=closure + 0x68,
            value_call=closure + 0x64, value=value, light=taken, dark=closure + 0x6c,
            setter=setter, setter_size=setter_size,
            wallpaper_slot=(w[0x20 // 4] >> 10 & 4095) * 8,
            wallpaper_rx_field=(w[0x5c // 4] >> 12 & 511),
            dark_field=(w[0x78 // 4] >> 12 & 511),
            light_field=(w[0xac // 4] >> 12 & 511),
            refresh=refresh, stream_add=branch(im, refresh + 0x24),
            first_log=first, second_log=second, return_pc=first + 4,
            replay_word=struct.unpack("<I", im.read(first + 4, 4))[0],
            resume=first + 8, getter=grid,
            maps={
                "branch": maps(closure)[closure + 0x68],
                "event": maps(event)[first + 4],
                "refresh_value": maps(refresh)[refresh + 0x1c],
                "refresh_add": maps(refresh)[refresh + 0x28],
            },
        )
        close, close_size = im.syms["GetStream.close"][0]
        done = branch(im, close + 0x14)
        notify = branch(im, evidence[version]["stream_add"] + 0x54)
        evidence[version].update(
            subject_close=close, subject_notify_done=done, subject_notify_data=notify,
            subject_listeners_field=struct.unpack("<I", im.read(close + 0x1c, 4))[0] >> 12 & 511,
            subject_busy_field=struct.unpack("<I", im.read(close + 0x20, 4))[0] >> 12 & 511,
            subject_listener_length_field=struct.unpack("<I", im.read(notify + 0x38, 4))[0] >> 12 & 511,
        )
        cache_at = closure + 0x1c
        cache_words = struct.unpack("<16I", im.read(cache_at, 0x40))
        cache_type = ((cache_words[11] >> 10 & 4095) * 4096 +
                      (cache_words[12] >> 10 & 4095) * 8)
        loads = []
        for segment in im.loads:
            if segment[0] != 1 or not segment[1] & 1:
                continue
            lo, hi, offset = segment[3], segment[3] + segment[5], segment[2]
            raw = im.data[offset:offset + hi - lo]
            cursor = 0
            while True:
                found = raw.find(struct.pack("<I", cache_words[0]), cursor)
                if found < 0:
                    break
                cursor = found + 4
                if found & 3 or found + 0x40 > len(raw):
                    continue
                at = lo + found
                candidate = struct.unpack("<16I", im.read(at, 0x40))
                if any((a & mask(b)) != (b & mask(b)) for a, b in zip(candidate, cache_words)):
                    continue
                kind = ((candidate[11] >> 10 & 4095) * 4096 +
                        (candidate[12] >> 10 & 4095) * 8)
                if kind == cache_type and branch(im, at + 0x3c) == branch(im, cache_at + 0x3c):
                    assert (candidate[1] >> 10 & 4095) * 8 == evidence[version]["wallpaper_slot"]
                    loads.append(at + 4)
        assert len(loads) >= 2
        evidence[version]["controller_source_count"] = len(loads)
        for index, address in enumerate(loads):
            evidence[version][f"controller_load_{index}"] = address
        roles = {"Build": (build, build_size), "Closure": (closure, dark - closure),
                 "Cache": (closure + 0x1c, 0x40),
                 "StreamClose": (close, close_size), "NotifyDone": (done, 0x40),
                 "SetterOld" if setter_size == 0xd0 else "SetterNew": (setter, setter_size)}
        for role, (at, size) in roles.items():
            words = struct.unpack("<" + str(size // 4) + "I", im.read(at, size))
            contract = [(word & mask(word), mask(word)) for word in words]
            if role in patterns:
                assert contract == patterns[role], (version, role)
            else:
                patterns[role] = contract
    (OUT / "material-oracles.json").write_text(json.dumps(evidence, indent=2) + "\n")
    for version, row in evidence.items():
        fields = {key: value for key, value in row.items() if isinstance(value, int)}
        for name, info in row["maps"].items():
            fields[f"{name}_bitmap"] = info["bitmap_at"]
            fields[f"{name}_payload"] = info["local_payload"]
            fields[f"{name}_global"] = info["global_entry"]
        (OUT / f"material-{version}.tsv").write_text(
            "".join(f"{key} {value:x}\n" for key, value in fields.items()))
    lines = []
    for role, words in patterns.items():
        lines += [f"constexpr WordMask k{role}[] = {{"] + [
            f"    {{0x{w:08x}u, 0x{m:08x}u}}," for w, m in words] + ["};"]
    (OUT / "material-patterns.txt").write_text("\n".join(lines) + "\n")
    for version, row in evidence.items():
        print(version, "branch", hex(row["branch"]), "slot", hex(row["wallpaper_slot"]),
              "return", hex(row["return_pc"]), "replay", hex(row["replay_word"]),
              "maps", row["maps"])


if __name__ == "__main__":
    main()

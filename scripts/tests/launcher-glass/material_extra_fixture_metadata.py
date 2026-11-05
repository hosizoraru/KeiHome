#!/usr/bin/env python3
"""Host-only source oracle for folder press, search, and folder default colors."""
import importlib.util
import json
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("material_meta", HERE / "material_fixture_metadata.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def word(im, at):
    return struct.unpack("<I", im.read(at, 4))[0]


def target(im, at, bits, shift):
    w = word(im, at)
    n = w >> shift & ((1 << bits) - 1)
    return at + ((n ^ (1 << (bits - 1))) - (1 << (bits - 1))) * 4


def choose_polarity(im, row, native_true_is_light):
    w, pc = row["word"], row["branch"]
    assert w & 0x7e000000 == 0x36000000
    assert (w >> 19 & 31) == row["boolBit"] == 4
    assert (w & 31) == row["resultRegister"]
    taken, fallthrough = target(im, pc, 14, 5), pc + 4
    # Dart true (+32) has bit 4 clear; false (+48) has bit 4 set.
    native_true, native_false = ((fallthrough, taken) if w & 0x01000000
                                 else (taken, fallthrough))
    assert (row["darkTarget"], row["lightTarget"]) == (native_true, native_false)
    row.update(nativeTrueIsLight=int(native_true_is_light),
               branchTaken=taken, branchFallthrough=fallthrough)
    assert (row["lightSchemeTarget"], row["darkSchemeTarget"]) == (
        (native_true, native_false) if native_true_is_light else
        (native_false, native_true))


def pattern(im, start, size, role):
    result = []
    for off in range(0, size, 4):
        at = start + off
        w = word(im, at)
        mask = base.mask(w)
        if w & 0xfc000000 == 0x14000000:
            dest = target(im, at, 26, 0)
            if start <= dest < start + size:
                mask = 0xffffffff
        elif w & 0xff000010 == 0x54000000:
            dest = target(im, at, 19, 5)
            if start <= dest < start + size:
                mask = 0xffffffff
        elif w & 0x7e000000 in (0x34000000, 0x36000000):
            bits = 14 if w & 0x7e000000 == 0x36000000 else 19
            dest = target(im, at, bits, 5)
            if start <= dest < start + size:
                mask = 0xffffffff
        if role == "ExtraFolderNew" and off == 0x54:
            # Explicit non-null caller argument bypass; its register is linked
            # back to CMP/B.NE immediately before the cached Wallpaper window.
            mask &= ~31
        if role == "ExtraWidgetAllocator":
            if off == 0:
                mask = 0xffe1ffff  # retain object size/flags; class-id low bits vary
            elif off in (4, 0x18):
                mask = 0xffe0001f
        if role in ("ExtraFolderDarkConfig", "ExtraFolderLightConfig") and off == 0x34:
            # Independent native blur-radius pool load; retain D0 / base X17.
            assert w & 0xffc003ff == 0xfd400220
            mask = 0xffc003ff
        result.append((w & mask, mask))
    return result


def main():
    evidence, patterns = {}, {}
    for version in ("7722", "8593", "8602", "9209", "9226"):
        im = base.elf.Image(base.ROOT / ".artifacts/inputs/launchers" / version / "libapp.so")
        folder, size = im.syms["FolderBlurUtils.buildFolderGlass"][0]
        getter, _ = im.syms["RxObjectMixin.value"][0]
        calls = [folder + off for off in range(0, size, 4)
                 if word(im, folder + off) & 0xfc000000 == 0x94000000
                 and base.branch(im, folder + off) == getter]
        assert len(calls) == 1
        source = calls[0] - 0x48
        old = word(im, source + 0x50) == 0xf9403f40
        allocation = source + (0xe0 if old else 0xe8)
        allocator = base.branch(im, allocation)
        folder_row = dict(kind=1, source=source, branch=calls[0] + 4,
                          word=word(im, calls[0] + 4), darkTarget=calls[0] + 8,
                          lightTarget=target(im, calls[0] + 4, 14, 5),
                          valueCall=calls[0], valueOriginal=getter, resultRegister=0, boolBit=4,
                          nativeJoin=source + (0xbc if old else 0xc4),
                          widgetAllocator=allocator)
        folder_row["trueTokenSlot"] = word(im, source + (0x54 if old else 0x5c)) >> 10 & 4095
        folder_row["trueTokenSlot"] *= 8
        folder_row["falseTokenSlot"] = word(im, folder_row["lightTarget"] + 4) >> 10 & 4095
        folder_row["falseTokenSlot"] *= 8
        assert folder_row["trueTokenSlot"] == folder_row["falseTokenSlot"] + 8
        candidates = []
        for lo, hi, ex in im.ranges:
            if not ex:
                continue
            data = im.read(lo, hi - lo)
            # Independent call/token/constructor oracle, not production patterns.
            for off in range(0, len(data) - 0x1a0, 4):
                pc = lo + off
                if struct.unpack_from("<I", data, off)[0] != 0xf9403f40:
                    continue
                if (word(im, pc + 0x48) & 0xfc000000 != 0x94000000
                        or base.branch(im, pc + 0x48) != getter
                        or word(im, pc + 0x50) != 0xf9403f40
                        or word(im, pc + 0xd8) & 0xfc000000 != 0x94000000
                        or base.branch(im, pc + 0xd8) != allocator):
                    continue
                if (word(im, pc + 0x54) >> 10 & 4095) * 8 != folder_row["trueTokenSlot"]:
                    continue
                branch = pc + 0x4c
                if word(im, branch) & 0xfff8001f != 0x37200000:
                    continue
                light = target(im, branch, 14, 5)
                if light != pc + 0x78:
                    continue
                if (word(im, light + 4) >> 10 & 4095) * 8 != folder_row["falseTokenSlot"]:
                    continue
                candidates.append(dict(kind=2, source=pc, branch=branch, word=word(im, branch),
                                       darkTarget=branch + 4, lightTarget=light,
                                       valueCall=branch - 4, valueOriginal=getter,
                                       resultRegister=0, boolBit=4,
                                       trueTokenSlot=folder_row["trueTokenSlot"],
                                       falseTokenSlot=folder_row["falseTokenSlot"],
                                       nativeJoin=pc + 0x9c, widgetAllocator=allocator))
        assert len(candidates) == 1, (version, candidates)
        for row in (folder_row, candidates[0]):
            row["lightSchemeTarget"] = row["darkTarget"]
            row["darkSchemeTarget"] = row["lightTarget"]
            row["darkConfigFactory"] = row["lightConfigFactory"] = 0
            choose_polarity(im, row, True)
        colors, colors_size = im.syms["FolderBlurUtils.getFolderIconColorConfig"][0]
        dark_config, dark_size = im.syms["FolderIconGetxController.getBlurBlendDarkColorConfig"][0]
        light_config, light_size = im.syms["FolderIconGetxController.getBlurBlendLightColorConfig"][0]
        assert (colors_size, dark_size, light_size) == (0xd8, 0x80, 0x80)
        assert base.branch(im, dark_config + 0x48) == colors
        assert base.branch(im, light_config + 0x48) == colors
        assert word(im, dark_config + 0x40) == 0x910082c1
        assert word(im, light_config + 0x40) == 0x9100c2c1
        defaults = dict(kind=3, source=colors, branch=colors + 0xc,
                        word=word(im, colors + 0xc), resultRegister=1, boolBit=4,
                        lightSchemeTarget=target(im, colors + 0xc, 14, 5),
                        darkSchemeTarget=colors + 0x10,
                        darkConfigFactory=dark_config, lightConfigFactory=light_config,
                        darkTarget=colors + 0x10,
                        lightTarget=target(im, colors + 0xc, 14, 5),
                        valueCall=0, valueOriginal=0, nativeJoin=0,
                        trueTokenSlot=0, falseTokenSlot=0,
                        widgetAllocator=base.branch(im, dark_config + 0x50))
        choose_polarity(im, defaults, False)
        evidence[version] = dict(folder=folder_row, searchCapsule=candidates[0],
                                 folderDefaultColors=defaults)
        roles = {
            "ExtraFolderOld" if old else "ExtraFolderNew": (source, 0x180 if old else 0x188),
            "ExtraSearchCapsule": (candidates[0]["source"], 0x1a0),
            "ExtraPressLight": im.syms["GlassToken.withPressLight"][0],
            "ExtraWidgetAllocator": (allocator, 0x30),
            "ExtraFolderDefaultColors": (colors, colors_size),
            "ExtraFolderDarkConfig": (dark_config, dark_size),
            "ExtraFolderLightConfig": (light_config, light_size),
        }
        for role, (start, length) in roles.items():
            current = pattern(im, start, length, role)
            if role in patterns:
                assert current == patterns[role], (version, role, [
                    hex(i * 4) for i, (a, b) in enumerate(zip(current, patterns[role])) if a != b])
            patterns[role] = current
        lines = []
        for role, row in evidence[version].items():
            lines.extend(f"{role}.{key}\t0x{value:x}" for key, value in row.items())
        (base.OUT / f"material-extra-{version}.tsv").write_text("\n".join(lines) + "\n")
        print(version, "folder", hex(folder_row["branch"]), "search capsule",
              hex(candidates[0]["branch"]))
    (base.OUT / "material-extra-oracles.json").write_text(json.dumps(evidence, indent=2) + "\n")
    lines = []
    for role, words in patterns.items():
        lines.append(f"constexpr WordMask k{role}[] = {{")
        lines.extend(f"    {{0x{w:08x}u, 0x{mask:08x}u}}," for w, mask in words)
        lines.append("};")
    (base.OUT / "material-extra-patterns.txt").write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""HOST-only bounded source audit; admission is not resting-material proof.

Private symbols locate saved source, never production sites. No new wallpaper
preset, production profile, runtime hook or rendered outcome is inferred here.
"""

import json
from pathlib import Path
import struct
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[3]
RESEARCH = ROOT / ".artifacts/research/dock-material-20261105"
sys.path.insert(0, str(RESEARCH / "aot-comparison"))
from compare_native import reader  # noqa: E402

VERSIONS = ("7722", "8593", "8602", "9209", "9226")
GLASS = {
    "_GlassWidgetState.build": 2,
    "_GlassWidgetState._buildGlassFilter": 1,
    "ImageFilterCompat.glass": 1,
}
RESTING = {
    "_BlurBlendColorState.build": 2,
    "_BlurBlendColorState._buildMultiColorFilter": 2,
    "CompatBlurBlendColorWidget.build": 1,
    "ImageFilterCompat.blur": 1,
    "resolveBlurBlendRouting": 1,
    "buildBlurImageFilter": 1,
}
OWNERS = {
    "_FolderBlurChildState._buildChild": 1,
    "_FolderBlurChildState.initState": 1,
    "_FolderBlurChildState._onChanged": 2,
    "_FolderGlassLightWidgetState.build": 1,
    "GlassToken.withPressLight": 1,
    "FolderBlurUtils.buildFolderGlass": 1,
}


def calls(r, ins):
    return [
        {"pc": hex(x.address), "target": hex(x.operands[0].imm),
         "name": r.name(x.operands[0].imm)}
        for x in ins if x.mnemonic == "bl"
    ]


def has_forbidden(rows, forbidden):
    return any(int(row["target"], 16) in forbidden for row in rows)


def audit(fixture):
    r = reader.Reader(fixture)
    r.md.detail = True
    forbidden = {
        pc for name, entries in r.im.syms.items()
        if name.startswith(("WallpaperGetxController.", "RxObjectMixin."))
        for pc, _ in entries
    }
    rx_getters = {pc for pc, _ in r.im.syms["RxObjectMixin.value"]}
    theme = {pc for pc, _ in r.im.syms["Theme.of"]}
    methods, blocks = {}, {}
    checks = 0
    excerpts = [
        "// HOST-only saved source; no installed/runtime addresses.",
        f"// fixture {fixture}; all offsets are ELF virtual addresses.",
    ]
    for group, counts in (("glass", GLASS), ("resting", RESTING), ("owner", OWNERS)):
        for name, expected in counts.items():
            entries = sorted(r.im.syms[name])
            assert len(entries) == expected, (fixture, name, entries)
            methods[name], blocks[name] = [], []
            for start, size in entries:
                ins = list(r.md.disasm(r.im.read(start, size), start))
                assert len(ins) * 4 == size
                rows = calls(r, ins)
                if group != "owner":
                    assert not has_forbidden(rows, forbidden), (fixture, name, rows)
                    # Positive rejection control: detect a direct original
                    # Rx.value BL substituted into the inspected source.
                    getter = next(iter(rx_getters))
                    branch = 0x94000000 | (((getter - start) // 4) & 0x03ffffff)
                    bad = list(r.md.disasm(struct.pack("<I", branch), start))
                    assert has_forbidden(calls(r, bad), forbidden)
                    checks += 2
                methods[name].append({
                    "start": hex(start), "size": hex(size), "calls": rows,
                    "wallpaper_or_rx_calls": [
                        x for x in rows if int(x["target"], 16) in forbidden
                    ],
                })
                blocks[name].append(ins)
                if name in ("_GlassWidgetState.build", "_BlurBlendColorState.build",
                            "_FolderBlurChildState._buildChild",
                            "GlassToken.withPressLight"):
                    limit = min(size, 0x110 if "build" in name else 0xc8)
                    excerpts.append(f"\n// {name} @ {start:x}, size {size:x}")
                    excerpts.append(r.asm(start, limit).rstrip())

    selectors = []
    for ins in blocks["_BlurBlendColorState.build"]:
        theme_calls = [
            i for i, x in enumerate(ins)
            if x.mnemonic == "bl" and x.operands[0].imm in theme
        ]
        assert len(theme_calls) == 1
        index = theme_calls[0]
        candidates = [x for x in ins[index + 1:index + 12] if x.mnemonic == "b.ne"]
        assert len(candidates) == 1
        branch = candidates[0]
        # Retained paths select widget config +0xf / +0xb after Theme.of.
        tail = ins[index + 1:index + 30]
        for field in (0xb, 0xf):
            assert any(x.mnemonic == "ldur" and x.operands[-1].type == 3
                       and x.operands[-1].mem.disp == field for x in tail)
        checks += 4
        selectors.append({
            "source": hex(ins[0].address), "theme_call": hex(ins[index].address),
            "branch": hex(branch.address),
            "branch_word": hex(int.from_bytes(branch.bytes, "little")),
            "taken": hex(branch.operands[0].imm), "config_fields": ["0xf", "0xb"],
            "role": "Theme brightness, not Wallpaper Rx",
        })

    folder = blocks["_FolderBlurChildState._buildChild"][0]
    # Some sources read the cached mode directly; others additionally read the
    # folder controller's setting. Neither is a Wallpaper selection.
    mode_getters = [x for x in folder
                    if x.mnemonic == "bl" and x.operands[0].imm in rx_getters]
    assert len(mode_getters) <= 1
    assert any(x.mnemonic == "ldur" and x.operands[-1].type == 3
               and x.operands[-1].mem.disp == 0x13 for x in folder)
    field_stores = {}
    for x in folder:
        if x.mnemonic == "stur" and x.operands[-1].type == 3:
            if x.operands[-1].mem.disp in (0x33, 0x37):
                field_stores[hex(x.operands[-1].mem.disp)] = hex(x.address)
    assert len(field_stores) == 2
    changed = blocks["_FolderBlurChildState._onChanged"][1]
    set_states = {pc for pc, _ in r.im.syms["State.setState"]}
    set_state = [
        i for i, x in enumerate(changed)
        if x.mnemonic == "bl" and x.operands[0].imm in set_states
    ]
    assert len(set_state) == 1
    before = changed[set_state[0] - 12:set_state[0]]
    skips = [x for x in before if x.mnemonic == "b.eq"
             and x.operands[0].imm > changed[set_state[0]].address]
    assert len(skips) == 1
    press = blocks["GlassToken.withPressLight"][0]
    assert any(x.mnemonic == "stur" and x.operands[-1].type == 3
               and x.operands[-1].mem.disp == 0x23 for x in press)
    checks += 5
    return {
        "methods": methods, "theme_selectors": selectors, "checks": checks,
        "folder_mode_getter_calls": [hex(x.address) for x in mode_getters],
        "resting_folder_blend_list_stores": field_stores,
        "same_value_refresh_skips_set_state": {
            "branch": hex(skips[0].address), "target": hex(skips[0].operands[0].imm),
            "set_state_call": hex(changed[set_state[0]].address),
        },
        "resting_wallpaper_preset_pair_admitted": False,
    }, "\n".join(excerpts) + "\n"


def main():
    output = RESEARCH / "material-sidecar"
    result = {
        "method": "Bounded named-source/call/field audit; HOST oracle only.",
        "limits": (
            "No GPU/backdrop-luminance proof, exhaustive whole-program graph, "
            "native wallpaper preset pair, runtime hook or visual acceptance. "
            "No new preset is inferred from the absence of a direct Rx call."
        ),
        "fixtures": {},
    }
    for fixture in VERSIONS:
        proof, asm = audit(fixture)
        result["fixtures"][fixture] = proof
        (output / f"material-resting-source-{fixture}.asm").write_text(asm)
        print(f"{fixture}: {proof['checks']} checks; 2 Theme selectors; "
              "no direct Wallpaper/Rx calls in bounded normal factories; "
              "unchanged-value folder refresh skipped")
    (output / "material-resting-source-oracles.json").write_text(
        json.dumps(result, indent=2) + "\n")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""HOST-only symbols/oracle for native default blur colors and rebuild roles."""
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("extra", HERE / "material_extra_fixture_metadata.py")
extra = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extra)
base = extra.base


def pool(im, add, load):
    return ((extra.word(im, add) >> 10) & 4095) * 4096 + (
        (extra.word(im, load) >> 10) & 4095) * 8


def main():
    patterns, evidence = {}, {}
    for version in ("7722", "8593", "8602", "9209", "9226"):
        im = base.elf.Image(base.ROOT / ".artifacts/inputs/launchers" / version / "libapp.so")
        build = sorted(im.syms["_BlurBlendColorState.build"])
        init, init_size = im.syms["_FolderBlurChildState.initState"][0]
        changed, changed_size = sorted(im.syms["_FolderBlurChildState._onChanged"])[1]
        defaults, defaults_size = im.syms["FolderBlurUtils.getFolderIconColorConfig"][0]
        compat, compat_size = build[0]
        direct, direct_size = build[1]
        assert (compat_size, direct_size, init_size, changed_size) == (0x2dc, 0xf4, 0x178, 0x9c)
        theme_compat = base.branch(im, compat + 0x24)
        theme_direct = base.branch(im, direct + 0x18)
        themes = dict(im.syms["Theme.of"])
        grow = base.branch(im, defaults + 0x50)
        array = base.branch(im, defaults + 0x1c)
        child, child_size = im.syms["_FolderBlurChildState._buildChild"][0]
        state_build, state_build_size = im.syms["_FolderBlurChildState.build"][0]
        observer, observer_size = sorted(im.syms["_FolderBlurChildState._onChanged"])[0]
        callback = changed + changed_size
        callback_size = next(off + 4 for off in range(0, 0x100, 4)
                             if extra.word(im, callback + off) == 0xd65f03c0)
        state_set = base.branch(im, changed + 0x7c)
        assert dict(im.syms["State.setState"])[state_set] == 0x14c
        closure = base.branch(im, changed + 0x70)
        ever = base.branch(im, init + 0x144)
        ever_size = dict(im.syms["ever"])[ever]
        dark_config = im.syms["FolderIconGetxController.getBlurBlendDarkColorConfig"][0][0]
        config_allocator = base.branch(im, dark_config + 0x50)
        getter = base.branch(im, init + 0x70)
        [refresh] = [a for a, size in im.syms["RxObjectMixin.refresh"]
                     if size == 0x38 and base.branch(im, a + 0x18) == getter]
        writer, writer_size = im.syms[
            "WorkspaceGetxController.updateFolderPassWindowBlurEnabled"][0]
        assert (observer_size, callback_size, state_build_size) == (0x28, 0x84, 0x2c)
        named = {
            "NativeStyleFolderInit": (init, init_size),
            "NativeStyleFolderChanged": (changed, changed_size),
            "NativeStyleFolderObserver": (observer, observer_size),
            "NativeStyleFolderCallback": (callback, callback_size),
            "NativeStyleFolderBuild": (state_build, state_build_size),
            "NativeStyleStateSet": (state_set, 0x14c),
            "NativeStyleClosure": (closure, 0x4c),
            "NativeStyleModeWriter": (writer, writer_size),
            "NativeStyleEver": (ever, ever_size),
            "NativeStyleRefresh": (refresh, 0x38),
            "NativeStyleConfigAllocator": (config_allocator, 0x30),
            ("NativeStyleChildOld" if version == "7722" else "NativeStyleChild"): (child, child_size),
            "NativeStyleGrowable": (grow, 0x3c),
            "NativeStyleArray": (array, 0x80),
        }
        for role, (start, length) in named.items():
            current = []
            for off in range(0, length, 4):
                w = extra.word(im, start + off)
                mask = extra.pattern(im, start, length, role)[off // 4][1]
                # Address relocations only: pool F64 loads or static-table
                # slots whose source relationships are checked separately.
                if w & 0xffc003e0 in (0xfd400220,):
                    mask = 0xffc003ff
                # The native GDT selector number is relocated with the class
                # universe. Keep the MOV/register and following dispatch flow.
                if role == "NativeStyleEver" and off == 0x9c:
                    assert w & 0xffe0001f == 0xd2800011
                    mask = 0xffe0001f
                if role == "NativeStyleConfigAllocator" and off == 0x18:
                    assert w & 0xffc003ff == 0xf100013f
                    mask = 0xffc003ff
                if role in ("NativeStyleGrowable", "NativeStyleArray", "NativeStyleClosure",
                            "NativeStyleConfigAllocator") and (
                        w & 0xffe00000 in (0xd2800000, 0xf2a00000)):
                    mask = 0xffe0001f
                current.append((w & mask, mask))
            if role in patterns:
                assert current == patterns[role], (version, role, [
                    hex(i * 4) for i, (a, b) in enumerate(zip(patterns[role], current)) if a != b
                ])
            patterns[role] = current
        dark_slots = [pool(im, defaults + a, defaults + b)
                      for a, b in ((0x24, 0x28), (0x30, 0x34), (0x3c, 0x40))]
        light_slots = [pool(im, defaults + a, defaults + b)
                       for a, b in ((0x8c, 0x90), (0x98, 0x9c), (0xa4, 0xa8))]
        row = dict(compatSource=compat, directSource=direct,
                   compatBranch=compat + 0x44, directBranch=direct + 0x38,
                   defaultFactory=defaults, changed=changed, init=init,
                   growableAllocator=grow, arrayAllocator=array)
        row.update(buildChild=child, childSize=child_size, stateBuild=state_build,
                   observerCallback=observer, rebuildCallback=callback,
                   stateSetState=state_set, closureAllocator=closure,
                   modeWriter=writer, controllerSlot=(extra.word(im, init + 0x14) >> 10 & 4095) * 8,
                   rxField=extra.word(im, init + 0x60) >> 5 & 65535,
                   observerDescriptorSlot=pool(im, init + 0x124, init + 0x128),
                   rebuildDescriptorSlot=pool(im, changed + 0x68, changed + 0x6c),
                   equalityBranch=changed + 0x60, equalityWord=extra.word(im, changed + 0x60),
                   equalityFallthrough=changed + 0x64, equalityTaken=changed + 0x80,
                   onChangedRet=changed + 0x8c, onChangedRetWord=extra.word(im, changed + 0x8c))
        row.update(refresh=refresh, refreshRet=refresh + 0x34,
                   refreshRetWord=extra.word(im, refresh + 0x34),
                   refreshValueReturn=refresh + 0x1c, refreshAddReturn=refresh + 0x28,
                   ever=ever, everSize=ever_size)
        primary = dict((k, int(v, 16)) for k, v in
                       (line.split() for line in
                        (base.OUT / f"material-{version}.tsv").read_text().splitlines()))
        add_map = base.stack_maps(im)(refresh)[refresh + 0x28]
        assert add_map["spills"] == 1 and add_map["other"] == 0
        assert add_map["bitmap"] == "00"
        def field(at):
            v = extra.word(im, at) >> 12 & 511
            return (v ^ 256) - 256
        row.update(initState=init, onChanged=changed,
                   subjectField=field(refresh + 0xc),
                   valueField=field(getter + 0x3c),
                   observerSlot=(extra.word(im, getter + 0x18) >> 10 & 4095) * 8,
                   subjectListenersField=primary["subject_listeners_field"],
                   subjectBusyField=primary["subject_busy_field"],
                   subjectListenerLengthField=primary["subject_listener_length_field"],
                   refreshSubjectFrameOffset=-8, refreshRetSubjectDartSpOffset=-24,
                   refreshFrameBytes=8, onChangedStateFrameOffset=-8, onChangedFrameBytes=16,
                   refreshSubjectLiveAcrossAdd=int(add_map["bitmap"], 16) & 1,
                   refreshAddSpillBits=add_map["spills"],
                   refreshAddBitmap=int(add_map["bitmap"], 16),
                   stateModeField=field(changed + 0x30), stateChildField=field(init + 0xa4),
                   contextStateField=field(changed + 0x24),
                   contextValueField=field(changed + 0x2c),
                   functionEntryField=field(closure + 0x40),
                   closureEntryField=field(closure + 0x44),
                   smiShift=1, smiValueBits=31, boxedIntValueField=field(changed + 0x4c),
                   nativeModeMin=1, nativeModeMax=2, x0DeadOnBothSuccessors=1,
                   requiresRuntimeCallbackEntryCheck=1)
        assert row["controllerSlot"] != primary["wallpaper_slot"]
        assert field(init + 0x78) == field(callback + 0x2c) == row["stateModeField"]
        assert field(callback + 0x58) == row["stateChildField"]
        assert extra.word(im, writer + 0x1c8) == 0xd1000400  # SUB x0,x0,#1
        assert extra.word(im, writer + 0x1cc) == 0x927f0000  # AND x0,x0,#2
        assert extra.word(im, writer + 0x1d0) == 0x91000800  # ADD x0,x0,#2
        assert row["onChangedRetWord"] == 0xd65f03c0
        for i, slot in enumerate(dark_slots):
            row[f"darkSlot{i}"] = slot
        for i, slot in enumerate(light_slots):
            row[f"lightSlot{i}"] = slot
        for role, prefix in ((compat, "compat"), (direct, "direct")):
            branch = row[f"{prefix}Branch"]
            row[f"{prefix}Word"] = extra.word(im, branch)
            # Brightness.dark is equality; B.NE selects light config.
            row[f"{prefix}Dark"] = branch + 4
            row[f"{prefix}Light"] = extra.target(im, branch, 19, 5)
        evidence[version] = row
        (base.OUT / f"material-native-style-{version}.tsv").write_text(
            "".join(f"{k}\t{v:x}\n" for k, v in row.items()))
        print(version, "default native colors", hex(defaults),
              "normal Theme sites", hex(row["compatBranch"]), hex(row["directBranch"]))
    (base.OUT / "material-native-style-oracles.json").write_text(
        json.dumps(evidence, indent=2) + "\n")
    (base.OUT / "material-native-style-patterns.txt").write_text(
        "\n\n".join(
            f"constexpr WordMask k{role}[] = {{\n" +
            "".join(f"    {{0x{w:08x}u, 0x{mask:08x}u}},\n" for w, mask in words) + "};"
            for role, words in patterns.items()) + "\n")


if __name__ == "__main__":
    main()

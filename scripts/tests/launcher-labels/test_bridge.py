#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Execute linked production ARM64 bridges; stub only original Dart callees.

Requires unicorn and pyelftools from the existing native-test environment.
This verifies register/root/return-PC contracts, not Flutter rendering or VM GC.
"""
import argparse
import math
from pathlib import Path
import struct
import subprocess
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn import arm64_const as ar

X = [getattr(ar, f"UC_ARM64_REG_X{i}") for i in range(31)]
CODE, BRIDGE, THREAD, FIELDS, POOL, POLICY, STACK, END = (
    0x10000000, 0x13000000, 0x30000000, 0x31000000, 0x32000000,
    0x33000000, 0x40010000, 0x50000000)


def linked_template(path):
    with open(path, "rb") as stream:
        elf = ELFFile(stream)
        syms = {s.name: s["st_value"] for s in elf.get_section_by_name(".symtab").iter_symbols()}
        begin, end = syms["kh_label_template"], syms["kh_label_end"]
        for segment in elf.iter_segments():
            if segment["p_type"] == "PT_LOAD" and segment["p_vaddr"] <= begin < end <= segment["p_vaddr"] + segment["p_filesz"]:
                stream.seek(segment["p_offset"] + begin - segment["p_vaddr"])
                return stream.read(end-begin), {n: syms["kh_label_"+n]-begin for n in ("pre", "post", "lines", "refresh", "page_post", "page_replay", "fade", "cache_post", "factory_one", "factory_two", "config")}
    raise AssertionError("bridge not in one file-backed ELF range")


def execute(template, entries, p, high, mode, width=150.0, maximum=80.0, pressure=False, relocate=False, change=None, prefix=False, prefix_change=None, editing=False, editing_state="ready", title_pressure=False):
    heap = high << 32
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    for at, size in [(BRIDGE, 4096), (THREAD, 4096), (FIELDS, 0x10000), (POOL, 0x100000),
                     (POLICY, 4096), (STACK-0x10000, 0x20000), (END, 4096), (heap+0x100000, 0x20000)]:
        uc.mem_map(at, size)
    def put(at, value): uc.mem_write(at, struct.pack("<Q", value & 0xffffffffffffffff))
    def get(at): return struct.unpack("<Q", uc.mem_read(at, 8))[0]
    def word(at, value): uc.mem_write(at, struct.pack("<I", value & 0xffffffff))
    def number(at, value): uc.mem_write(at, struct.pack("<d", value))
    def obj(at, cid): put(at-1, (cid << 12) | 0x51c)
    null, grid, rx, config, source, double, text, style, original, size, direction = [
        heap + 0x100001 + 0x100*i for i in range(11)]
    for i, at in enumerate((null, grid, rx, config, source, double, text, style, original, size, direction)):
        obj(at, 100+i)
    obj(original, p["textCid"]); obj(size, p["sizeCid"]); obj(double, 62)
    prefix_widget, prefix_result = heap+0x100b01, heap+0x100c01
    obj(prefix_widget, 998); obj(prefix_result, 94)
    word(prefix_result+7,2 if prefix else 0)
    put(STACK+p["prefixRoot"],prefix_widget)
    top = heap + 0x110000
    put(THREAD + 0x60, top); put(THREAD + 0x68, top + (0x70 if pressure else 0x1000))
    put(THREAD + 0x78, FIELDS); put(FIELDS + p["grid"], grid)
    word(grid + 0x2b, rx); word(rx + 0x13, config)
    number(config + p["width"], 92.0)
    word(source + p["sourceWidth"], double); number(double + 7, maximum)
    number(size + 7, width); number(size + 15, 16.0)
    put(POOL + p["ltr"], direction); word(POLICY, mode)
    for slot, value in [("source", source), ("text", text), ("style", style), ("widget", null), ("line", 1)]:
        put(STACK + p[slot], value)
    data = [POLICY, CODE+p["textAlloc"], CODE+p["measure"], CODE+p["ctor"], CODE+p["alloc"]+20,
            CODE+p["after"], p["textCid"], p["sizeCid"], p["marqueeTag"], p["grid"], 0x2b, 0x13,
            p["width"], p["sourceWidth"], p["ltr"], p["source"], p["text"], p["style"], p["widget"], p["line"]]+[0]*11+[p["prefixRoot"], CODE+p["prefix"]]
    data += [0]*13
    data[24]=p['tickerTag'];data[36]=CODE+p['editing'];data[37]=p['editingSlot']
    editor, sentinel = heap+0x100d01, heap+0x100e01
    put(POOL+0x40,sentinel)
    put(FIELDS+p['editingSlot'], {'ready':editor,'null':null,'sentinel':sentinel}[editing_state])
    blob = bytearray(template)
    struct.pack_into("<46Q", blob, entries["config"], *(v & 0xffffffffffffffff for v in data))
    uc.mem_write(BRIDGE, bytes(blob))
    # Real methods return to this original allocation-site PC; emulate its detour.
    return_pc = CODE+p["alloc"]+4
    uc.mem_map(return_pc & ~4095, 4096)
    uc.mem_write(return_pc, struct.pack("<IIQ", 0x58000050, 0xd61f0200, BRIDGE+entries["post"]))
    pages = {return_pc & ~4095}
    for target in (CODE+p["textAlloc"], CODE+p["measure"], CODE+p["ctor"], CODE+p["after"], CODE+p["alloc"]+20, CODE+p["prefix"], CODE+p["editing"]):
        page = target & ~4095
        if page not in pages:
            uc.mem_map(page, 4096)
            pages.add(page)
    for i, reg in enumerate(X): uc.reg_write(reg, 0xa000+i)
    uc.reg_write(X[15], STACK-0x70); uc.reg_write(X[21], 0x21000000)
    uc.reg_write(X[22], null); uc.reg_write(X[26], THREAD); uc.reg_write(X[27], POOL)
    uc.reg_write(X[28], high); uc.reg_write(X[29], STACK); uc.reg_write(X[30], return_pc)
    uc.reg_write(ar.UC_ARM64_REG_SP, STACK+0xf000)
    calls, finished = [], []
    expected_text, expected_style, expected_marquee = text, style, None
    expected_prefix_widget=prefix_widget
    expected_title_wrapper=None

    def hook(cpu, pc, count, unused):
        nonlocal expected_text, expected_style, expected_marquee, expected_prefix_widget, expected_title_wrapper
        if pc == CODE+p['editing']:
            calls.append('Editing')
            assert cpu.reg_read(X[1])==editor and cpu.reg_read(X[30])==return_pc
            expected_title_wrapper=get(STACK+p['text'])
            assert get(expected_title_wrapper-1)==p['tickerTag']
            if relocate:
                old_wrapper=expected_title_wrapper;expected_title_wrapper+=0x1000
                uc.mem_write(expected_title_wrapper-1,bytes(uc.mem_read(old_wrapper-1,32)))
                old_marquee=expected_marquee;expected_marquee+=0x1000
                uc.mem_write(expected_marquee-1,bytes(uc.mem_read(old_marquee-1,0x70)))
                expected_text+=0x1000;expected_style+=0x1000
                word(expected_title_wrapper+7,expected_text);word(expected_title_wrapper+15,expected_marquee)
                word(expected_marquee+11,expected_text);word(expected_marquee+15,expected_style)
                put(STACK+p['text'],expected_title_wrapper);put(STACK+p['style'],expected_style)
                uc.mem_write(old_wrapper-1,b'\xcc'*32)
            cpu.reg_write(X[0],null+(0x20 if editing else 0x30))
            cpu.reg_write(ar.UC_ARM64_REG_PC,return_pc)
        elif pc == CODE+p["measure"]:
            assert cpu.reg_read(X[30]) == return_pc
            assert (cpu.reg_read(X[1]), cpu.reg_read(X[2])) == (expected_text, expected_style)
            calls.append("measure")
            if relocate:
                expected_text += 0x3000; expected_style += 0x3000
                expected_prefix_widget+=0x3000;put(STACK+p["prefixRoot"],expected_prefix_widget)
                put(STACK+p["text"], expected_text); put(STACK+p["style"], expected_style)
            if change is not None: word(POLICY, change)
            cpu.reg_write(X[0], size); cpu.reg_write(ar.UC_ARM64_REG_PC, return_pc)
        elif pc == CODE+p["prefix"]:
            calls.append("Prefix")
            assert cpu.reg_read(X[1])==expected_prefix_widget and cpu.reg_read(X[30])==return_pc
            candidate=get(STACK+p["text"])
            assert struct.unpack("<I",uc.mem_read(candidate+0xb,4))[0]==expected_text&0xffffffff
            if relocate:
                moved=candidate+0x5000
                uc.mem_write(moved-1,bytes(uc.mem_read(candidate-1,0x70)))
                put(STACK+p["text"],moved)
                uc.mem_write(candidate-1,b"\xcc"*0x70)
            if prefix_change is not None: word(POLICY,prefix_change)
            cpu.reg_write(X[0],prefix_result);cpu.reg_write(ar.UC_ARM64_REG_PC,return_pc)
        elif pc == CODE+p["textAlloc"]:
            calls.append("Text")
            assert cpu.reg_read(X[30]) == return_pc
            cpu.reg_write(X[0], original); cpu.reg_write(ar.UC_ARM64_REG_PC, return_pc)
        elif pc == CODE+p["ctor"]:
            calls.append("Marquee")
            widget = cpu.reg_read(X[1]); expected_marquee = widget
            assert cpu.reg_read(X[30]) == return_pc
            assert get(widget-1) == p["marqueeTag"]
            assert cpu.reg_read(X[2]) == null
            assert cpu.reg_read(X[3]) == expected_style and cpu.reg_read(X[5]) == expected_text
            assert cpu.reg_read(X[6]) == direction and get(STACK+p["text"]) == widget
            assert get(THREAD+0x60) == top+0x70
            if relocate:
                expected_marquee = widget + 0x5000
                cpu.mem_write(expected_marquee-1, bytes(cpu.mem_read(widget-1, 0x70)))
                put(STACK+p["text"], expected_marquee)
            word(expected_marquee+0xb, expected_text); word(expected_marquee+0xf, expected_style)
            if title_pressure:put(THREAD+0x68,top+0x70+32)
            cpu.reg_write(X[0], null); cpu.reg_write(ar.UC_ARM64_REG_PC, return_pc)
        elif pc in (CODE+p["after"], CODE+p["alloc"]+20):
            finished.append(pc); cpu.emu_stop()

    uc.hook_add(UC_HOOK_CODE, hook)
    uc.emu_start(BRIDGE+entries["pre"], END, count=1000)
    assert len(finished) == 1, calls
    marquee = mode == 1 and change is None and math.isfinite(width) and math.isfinite(maximum) and maximum > 0 and width > (min(92, maximum) if prefix else max(92, maximum)) > 0 and not pressure
    if "Prefix" in calls and prefix_change is not None: marquee=False
    assert ("Marquee" in calls) == marquee, (mode, width, maximum, pressure, change, calls)
    assert get(STACK+p["text"]) == expected_text
    assert get(STACK+p["style"]) == expected_style
    assert get(STACK+p["source"]) == source
    assert get(STACK+p["prefixRoot"])==expected_prefix_widget
    assert get(STACK+p["widget"]) == ((expected_title_wrapper or expected_marquee) if marquee else original)
    if expected_title_wrapper:
        assert struct.unpack('<I',uc.mem_read(expected_title_wrapper+7,4))[0]==null&0xffffffff
        assert struct.unpack('<I',uc.mem_read(expected_title_wrapper+11,4))[0]==(null+(0x30 if editing else 0x20))&0xffffffff
        assert struct.unpack('<I',uc.mem_read(expected_title_wrapper+15,4))[0]==expected_marquee&0xffffffff
    if marquee: assert uc.reg_read(X[4]) == 2 and finished == [CODE+p["after"]]
    else:
        assert get(THREAD+0x60) == top+(0x70 if "Prefix" in calls else 0)
        assert uc.reg_read(X[2]) == expected_text and uc.reg_read(X[3]) == expected_style
    for i, value in [(15, STACK-0x70), (21, 0x21000000), (22, null), (26, THREAD), (27, POOL), (28, high), (29, STACK)]:
        assert uc.reg_read(X[i]) == value, (i, hex(uc.reg_read(X[i])))
    # Execute the actual line-result epilogue for both possible native results.
    for original_lines in (1, 2):
        word(POLICY, mode); put(STACK, STACK+0x100); put(STACK+8, END)
        uc.reg_write(X[1], original_lines); uc.reg_write(X[29], STACK); uc.reg_write(X[15], STACK-0x20)
        uc.emu_start(BRIDGE+entries["lines"], END, count=100)
        assert uc.reg_read(X[0]) == (original_lines if mode == 0 else mode)
        assert uc.reg_read(X[15]) == STACK+16 and uc.reg_read(X[29]) == STACK+0x100


def execute_fade(template, entries, p, high, mode, start=0.0, end=0.0, missing=False):
    """No-op fading must return the same native ListView without a Dart call."""
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    heap = (high << 32) + 0x100000
    for address, length in ((BRIDGE,4096),(POLICY,4096),(heap,4096),(END,4096),
                            ((CODE+p["fadeWrap"])&~4095,4096)):
        uc.mem_map(address,length)
    def put(at,value): uc.mem_write(at,struct.pack('<Q',value))
    null, state, widget, scroll, wrapped = [heap+1+i*0x100 for i in range(5)]
    uc.mem_write(state+0xb,struct.pack('<I',(null if missing else widget)&0xffffffff))
    uc.mem_write(widget+p['fadeStart'],struct.pack('<d',start))
    uc.mem_write(widget+p['fadeEnd'],struct.pack('<d',end))
    uc.mem_write(POLICY,struct.pack('<I',mode))
    blob=bytearray(template)
    struct.pack_into('<Q',blob,entries['config'],POLICY)
    struct.pack_into('<3Q',blob,entries['config']+264,CODE+p['fadeWrap'],p['fadeStart'],p['fadeEnd'])
    uc.mem_write(BRIDGE,bytes(blob))
    for index,reg in enumerate(X): uc.reg_write(reg,0xa000+index)
    uc.reg_write(X[1],state);uc.reg_write(X[2],scroll)
    uc.reg_write(X[22],null);uc.reg_write(X[28],high);uc.reg_write(X[30],END)
    original=[]
    def hook(cpu,pc,count,unused):
        if pc==CODE+p['fadeWrap']:
            assert cpu.reg_read(X[1])==state and cpu.reg_read(X[2])==scroll
            assert cpu.reg_read(X[30])==END
            original.append(pc);cpu.reg_write(X[0],wrapped);cpu.emu_stop()
        elif pc==END: cpu.emu_stop()
    uc.hook_add(UC_HOOK_CODE,hook)
    uc.emu_start(BRIDGE+entries['fade'],END+4,count=100)
    skip = mode==1 and not missing and start==0 and end==0
    assert bool(original)==(not skip),(mode,start,end,missing,original)
    assert uc.reg_read(X[0])==(scroll if skip else wrapped)
    for index in (15,21,26,27,29): assert uc.reg_read(X[index])==0xa000+index
    assert uc.reg_read(X[22])==null and uc.reg_read(X[28])==high


def execute_refresh(template, entries, p, high, revision, notified, grid_state, race=False):
    """A real UI event may refresh once; keep its original Dart caller frame."""
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    for at, size in ((BRIDGE, 4096), (THREAD, 4096), (FIELDS, 0x10000),
                     (POOL, 4096), (POLICY, 4096), (STACK-4096, 8192)):
        uc.mem_map(at, size)
    def put(at, value): uc.mem_write(at, struct.pack('<Q', value))
    def get(at): return struct.unpack('<Q', uc.mem_read(at, 8))[0]
    null, sentinel, grid = (high << 32) + 0x100001, (high << 32) + 0x110001, (high << 32) + 0x120001
    put(THREAD+0x78, FIELDS); put(POOL+0x40, sentinel)
    put(FIELDS+p['grid'], {'ready': grid, 'null': null, 'sentinel': sentinel}[grid_state])
    put(POLICY+0x100, revision); put(POLICY+0x108, notified)
    blob = bytearray(template)
    struct.pack_into('<Q', blob, entries['config']+72, p['grid'])
    struct.pack_into('<4Q', blob, entries['config']+160,
                     CODE+p['logger'], CODE+p['font'], POLICY+0x100, POLICY+0x108)
    uc.mem_write(BRIDGE, bytes(blob))
    return_pc = CODE+p['event']+4
    for page in {return_pc & ~4095, (CODE+p['logger']) & ~4095, (CODE+p['font']) & ~4095}:
        uc.mem_map(page, 4096)
    for i, reg in enumerate(X): uc.reg_write(reg, 0xb000+i)
    for i, value in ((15, STACK-0x298), (22, null), (26, THREAD), (27, POOL),
                     (28, high), (29, STACK), (30, return_pc)):
        uc.reg_write(X[i], value)
    uc.reg_write(ar.UC_ARM64_REG_SP, STACK+0x1000)
    saved = [uc.reg_read(r) for r in X]
    calls = []
    root = STACK-0x148
    put(root, (high << 32)+0x150001)
    def hook(cpu, pc, count, unused):
        if pc in (CODE+p['logger'], CODE+p['font']):
            calls.append('font' if pc == CODE+p['font'] else 'log')
            assert all(cpu.reg_read(r) == saved[i] for i,r in enumerate(X) if i not in (16,17))
            assert cpu.reg_read(ar.UC_ARM64_REG_SP) == STACK+0x1000
            # A relocating collection still sees the ORIGINAL caller roots/PC.
            put(root, get(root)+0x1000)
            if race and calls == ['font']: put(POLICY+0x100, revision+1)
            cpu.reg_write(X[0], null); cpu.reg_write(ar.UC_ARM64_REG_PC, return_pc)
        elif pc == return_pc: cpu.emu_stop()
    uc.hook_add(UC_HOOK_CODE, hook)
    def invoke():
        for i,r in enumerate(X): uc.reg_write(r,saved[i])
        uc.emu_start(BRIDGE+entries['refresh'], return_pc+4, count=150)
    expected = revision != notified and grid_state == 'ready'
    invoke()
    assert calls == [('font' if expected else 'log')]
    assert get(POLICY+0x108) == (revision if expected else notified)
    invoke()
    assert calls[-1] == ('font' if race and expected else 'log')
    invoke()
    assert calls[-1] == 'log'  # Repeated callbacks/heartbeats cannot rebuild again.
    assert get(root) == (high << 32)+0x153001


def execute_page(template, entries, p, high, visible, pressure=False, relocate=False,
                 editing=False, controller_state="ready", relocate_editor=False):
    heap = high << 32
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    for at, size in [(BRIDGE, 4096), (THREAD, 4096), (FIELDS, 0x10000), (POOL, 0x100000),
                     (STACK-0x10000, 0x20000), (END, 4096), (heap+0x100000, 0x20000)]:
        uc.mem_map(at, size)
    def put(at, value): uc.mem_write(at, struct.pack("<Q", value & 0xffffffffffffffff))
    def get(at): return struct.unpack("<Q", uc.mem_read(at, 8))[0]
    def word(at, value): uc.mem_write(at, struct.pack("<I", value & 0xffffffff))
    def object(at, cid): put(at-1, (cid<<12)|0x21c)
    null, source, controller, child = [heap+0x100001+0x100*i for i in range(4)]
    object(source, 999); object(controller, 998); object(child, p["textCid"])
    array_obj, obx, closure, context, cell_widget = [heap+0x100401+0x100*i for i in range(5)]
    for obj in (array_obj,obx,closure,context,cell_widget): object(obj, 997)
    word(source+15,array_obj);word(array_obj+15,obx);word(obx+11,closure)
    word(closure+23,context);word(context+15,cell_widget)
    word(cell_widget+p["controller"],controller);put(controller+p["screen"],19)
    for offset in (7,11,15,19,23,27): word(child+offset,null)
    uc.mem_write(POOL,struct.pack("<Q",null)*0x20000)
    editor, sentinel = heap+0x101a01, heap+0x101b01
    object(editor, 996); object(sentinel, 995)
    put(POOL+0x40, sentinel); put(THREAD+0x78, FIELDS)
    put(FIELDS+p["editingSlot"], {"ready":editor,"null":null,"sentinel":sentinel}[controller_state])
    put(STACK+p["cellRoot"],source)
    top = heap+0x110000
    put(THREAD+0x60, top); put(THREAD+0x68, top+(0x20 if pressure else 0x1000))
    config = [0]*24+[p["tickerTag"], CODE+p["visible"], p["cellRoot"], 0,
                       p["controller"], p["screen"], CODE+p["cell"]+48]
    config += [0]*15
    config[36:38] = [CODE+p["editing"], p["editingSlot"]]
    blob = bytearray(template)
    struct.pack_into("<46Q", blob, entries["config"], *(v & 0xffffffffffffffff for v in config))
    uc.mem_write(BRIDGE,bytes(blob))
    uc.mem_write(BRIDGE+entries["page_replay"],p["cellReplay"])
    return_pc = CODE+p["cell"]+4
    for page in {return_pc&~4095, (CODE+p["visible"])&~4095, (CODE+p["editing"])&~4095}:
        uc.mem_map(page, 4096)
    jump = 0x14000000 | (((BRIDGE+entries["page_post"]-return_pc)//4)&0x3ffffff)
    word(return_pc, jump)
    for i, value in [(0,child), (15,STACK-0x20), (21,0x21000000), (22,null), (26,THREAD),
                     (27,POOL), (28,high), (29,STACK), (30,return_pc)]:
        uc.reg_write(X[i],value)
    calls=[];finished=[];expected_source=source;expected_child=child;wrapper=top+1
    def hook(cpu, pc, size, _):
        nonlocal expected_source, expected_child, wrapper
        if pc==CODE+p["visible"]:
            calls.append(pc)
            assert cpu.reg_read(X[1])==controller and cpu.reg_read(X[30])==return_pc
            assert struct.unpack("<I",uc.mem_read(child+11,4))[0]==source&0xffffffff
            assert get(STACK+p["cellRoot"])==wrapper
            assert struct.unpack("<I",uc.mem_read(wrapper+7,4))[0]==source&0xffffffff
            assert struct.unpack("<I",uc.mem_read(wrapper+15,4))[0]==child&0xffffffff
            if relocate:
                old_wrapper=wrapper;wrapper=heap+0x118001
                uc.mem_write(wrapper-1,bytes(uc.mem_read(old_wrapper-1,0x20)))
                expected_source=source+0x1000;expected_child=child+0x1000
                uc.mem_write(expected_source-1,bytes(uc.mem_read(source-1,0x20)))
                uc.mem_write(expected_child-1,bytes(uc.mem_read(child-1,0x50)))
                word(wrapper+7,expected_source);word(wrapper+15,expected_child)
                word(expected_child+11,expected_source)
                put(STACK+p["cellRoot"],wrapper)
                uc.mem_write(old_wrapper-1,b"\xcc"*0x20)
            cpu.reg_write(X[0],null+(0x20 if visible else 0x30))
            cpu.reg_write(ar.UC_ARM64_REG_PC,return_pc)
        elif pc==CODE+p["editing"]:
            calls.append(pc)
            assert cpu.reg_read(X[1])==editor and cpu.reg_read(X[30])==return_pc
            assert get(STACK+p["cellRoot"])==wrapper
            assert struct.unpack("<I",uc.mem_read(wrapper+11,4))[0]==0
            if relocate_editor:
                old_wrapper=wrapper;wrapper=heap+0x119001
                uc.mem_write(wrapper-1,bytes(uc.mem_read(old_wrapper-1,0x20)))
                old_source,old_child=expected_source,expected_child
                expected_source+=0x2000;expected_child+=0x2000
                uc.mem_write(expected_source-1,bytes(uc.mem_read(old_source-1,0x20)))
                uc.mem_write(expected_child-1,bytes(uc.mem_read(old_child-1,0x50)))
                word(wrapper+7,expected_source);word(wrapper+15,expected_child)
                word(expected_child+11,expected_source)
                put(STACK+p["cellRoot"],wrapper)
                uc.mem_write(old_wrapper-1,b"\xcc"*0x20)
            cpu.reg_write(X[0],null+(0x20 if editing else 0x30))
            cpu.reg_write(ar.UC_ARM64_REG_PC,return_pc)
        elif pc==CODE+p["cell"]+48:
            finished.append(pc);cpu.emu_stop()
    uc.hook_add(UC_HOOK_CODE,hook)
    uc.emu_start(return_pc,END,count=1000)
    assert len(finished)==1
    assert get(STACK+p["cellRoot"])==expected_source
    if pressure:
        assert calls==[] and uc.reg_read(X[0])==child
        assert get(THREAD+0x60)==top
    else:
        assert calls==[CODE+p["visible"]]+([CODE+p["editing"]] if not visible and controller_state=="ready" else [])
        assert uc.reg_read(X[0])==wrapper
        assert struct.unpack("<I",uc.mem_read(wrapper+7,4))[0]==null&0xffffffff
        enabled=visible or editing or controller_state!="ready"
        assert struct.unpack("<I",uc.mem_read(wrapper+11,4))[0]==(null+(0x20 if enabled else 0x30))&0xffffffff
        assert struct.unpack("<I",uc.mem_read(wrapper+15,4))[0]==expected_child&0xffffffff
    assert struct.unpack("<I",uc.mem_read(expected_child+11,4))[0]==expected_source&0xffffffff
    for i,value in [(15,STACK-0x20),(21,0x21000000),(22,null),(26,THREAD),(27,POOL),(28,high),(29,STACK)]:
        assert uc.reg_read(X[i])==value,(i,hex(uc.reg_read(X[i])))


def execute_cache(template, entries, p, high, mode, editing, pressure=False,
                  relocate=False, ordinary=True, controller_state='ready', change=None, factory=0, wrapped_input=False):
    heap = high << 32
    uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    for at, size in [(BRIDGE,4096),(THREAD,4096),(FIELDS,0x10000),(POOL,0x100000),
                     (POLICY,4096),(STACK-0x10000,0x20000),(END,4096),
                     (heap+0x100000,0x20000)]: uc.mem_map(at,size)
    def put(at,v): uc.mem_write(at,struct.pack('<Q',v&0xffffffffffffffff))
    def get(at): return struct.unpack('<Q',uc.mem_read(at,8))[0]
    def word(at,v): uc.mem_write(at,struct.pack('<I',v&0xffffffff))
    def u32(at): return struct.unpack('<I',uc.mem_read(at,4))[0]
    null, entry, child, controller, sentinel, shortcut = [heap+0x100001+0x100*i for i in range(6)]
    put(entry-1,(999<<12)|0x31c)
    uc.mem_write(child-1,struct.pack('<Q',null)*10)
    put(child-1,p['dragTag']); put(shortcut-1,((p['shortcutCid'] if ordinary else 997)<<12)|0x31c)
    word(child+19,shortcut)
    word(entry+11,child); word(child+7,null)
    root_slot = p['factoryRoot'] if factory else p['cacheRoot']
    put(STACK+root_slot,child if factory else entry)
    if wrapped_input:
        old_ticker=heap+0x101001
        put(old_ticker-1,p['tickerTag']);word(old_ticker+15,shortcut);word(child+19,old_ticker)
    put(THREAD+0x78,FIELDS); put(POOL+0x40,sentinel)
    put(FIELDS+p['editingSlot'], {'ready':controller,'null':null,'sentinel':sentinel}[controller_state])
    word(POLICY,mode)
    top=heap+0x110000; wrapper=top+1; expected_entry=entry; expected_child=child
    put(THREAD+0x60,top); put(THREAD+0x68,top+(160 if pressure else 4096))
    config=[0]*46
    config[0]=POLICY; config[24]=p['tickerTag']
    config[36:]=[CODE+p['editing'],p['editingSlot'],CODE+p['cache'],p['cacheRoot'],p['shortcutCid'],p['dragTag'],CODE+p['factoryOne'],CODE+p['factoryTwo'],p['factoryRoot'],p['shortcutTag']]
    blob=bytearray(template)
    struct.pack_into('<46Q',blob,entries['config'],*(v&0xffffffffffffffff for v in config))
    uc.mem_write(BRIDGE,bytes(blob))
    return_pc=CODE+(p['factoryOne'] if factory==1 else p['factoryTwo'] if factory==2 else p['cache'])
    for page in {return_pc&~4095,(CODE+p['editing'])&~4095}: uc.mem_map(page,4096)
    word(return_pc,0x14000000|(((BRIDGE+entries['factory_one' if factory==1 else 'factory_two' if factory==2 else 'cache_post']-return_pc)//4)&0x3ffffff))
    for i,v in [(0,null),(15,STACK-0x28),(21,0x21000000),(22,null),(26,THREAD),
                (27,POOL),(28,high),(29,STACK),(30,return_pc)]: uc.reg_write(X[i],v)
    calls=[]; finished=[]
    def hook(cpu,pc,size,_):
        nonlocal wrapper,expected_entry,expected_child
        if pc==CODE+p['editing']:
            calls.append(pc)
            assert cpu.reg_read(X[1])==controller and cpu.reg_read(X[30])==return_pc
            assert get(STACK+root_slot)==wrapper
            assert u32(wrapper+7)==(child if factory else entry)&0xffffffff
            assert u32(wrapper+15)==(top+113)&0xffffffff
            assert u32(wrapper+11)==(top+33)&0xffffffff
            assert u32(top+33+19)==(top+113)&0xffffffff
            assert get(wrapper-1)==p['tickerTag']
            if relocate:
                old_wrapper=wrapper; wrapper+=0x1000
                expected_entry=entry+0x2000; expected_child=child+0x2000
                uc.mem_write(wrapper-1,bytes(uc.mem_read(old_wrapper-1,160)))
                uc.mem_write(expected_entry-1,bytes(uc.mem_read(entry-1,48)))
                uc.mem_write(expected_child-1,bytes(uc.mem_read(child-1,80)))
                word(wrapper+7,expected_child if factory else expected_entry);word(wrapper+11,wrapper+32);word(wrapper+15,wrapper+112);word(wrapper+32+19,wrapper+112)
                word(expected_entry+11,expected_child)
                put(STACK+root_slot,wrapper)
                for old,n in [(old_wrapper,160),(entry,48),(child,80)]: uc.mem_write(old-1,b'\xcc'*n)
            if change is not None: word(POLICY,change)
            cpu.reg_write(X[0],null+(0x20 if editing else 0x30))
            cpu.reg_write(ar.UC_ARM64_REG_PC,return_pc)
        elif pc==return_pc+(8 if factory else 16):
            finished.append(pc);cpu.emu_stop()
    uc.hook_add(UC_HOOK_CODE,hook)
    uc.emu_start(return_pc,END,count=500)
    assert len(finished)==1 and get(STACK+root_slot)==(expected_child if factory else expected_entry)
    wrapped=mode==1 and ordinary and not pressure and controller_state=='ready' and not wrapped_input
    assert calls==([CODE+p['editing']] if wrapped else [])
    assert uc.reg_read(X[0])==(wrapper+32 if wrapped else child)
    if wrapped:
        assert u32(wrapper+7)==(expected_child if factory else expected_entry)&0xffffffff
        assert u32(wrapper+11)==(wrapper+32)&0xffffffff
        assert u32(wrapper+15)==(wrapper+112)&0xffffffff
        assert u32(wrapper+32+19)==(wrapper+112)&0xffffffff
        assert get(wrapper+112-1)==p['shortcutTag']
        assert bytes(uc.mem_read(wrapper+112-1+8,40))==bytes(uc.mem_read(shortcut-1+8,40))
        assert bytes(uc.mem_read(wrapper+32-1+24,56))==bytes(uc.mem_read(expected_child-1+24,56))
        assert get(THREAD+0x60)==top+160
    else: assert get(THREAD+0x60)==top
    assert u32(expected_entry+11)==expected_child&0xffffffff
    for i,v in [(15,STACK),(21,0x21000000),(22,null),(26,THREAD),(27,POOL),(28,high),(29,STACK)]:
        assert uc.reg_read(X[i])==v,(i,hex(uc.reg_read(X[i])))


def main():
    parser = argparse.ArgumentParser(); parser.add_argument("--payload", required=True)
    parser.add_argument("--resolver", required=True); parser.add_argument("launchers", nargs="+")
    args = parser.parse_args(); template, entries = linked_template(args.payload)
    total = 0
    for path in args.launchers:
        row = subprocess.check_output([args.resolver, path], text=True).splitlines()[0]
        decimal = {"textCid", "sizeCid", "source", "text", "style", "widget", "line", "cellRoot", "cellResult", "prefixRoot", "cacheRoot", "shortcutCid", "factoryRoot"}
        p = {k: int(v, 10 if k in decimal else 16) for k, v in (pair.split("=") for pair in row.split())}
        p["cellReplay"] = Path(path).read_bytes()[p["cell"]+4:p["cell"]+48]
        for high in (0, 0x74):
            for editing in (False,True):
                for editing_state in ('ready','null','sentinel'):
                    for relocate in (False,True):
                        for title_pressure in (False,True):
                            execute(template,entries,p,high,1,editing=editing,editing_state=editing_state,relocate=relocate,title_pressure=title_pressure);total+=1
            for factory in (1,2):
                for mode in (0,1,2):
                    for editing in (False,True):
                        for pressure in (False,True):
                            for relocate in (False,True):
                                execute_cache(template,entries,p,high,mode,editing,pressure,relocate,factory=factory);total+=1
            for editing in (False,True):
                for relocate in (False,True):
                    execute_cache(template,entries,p,high,1,editing,relocate=relocate,wrapped_input=True);total+=1
            for mode in (0,1,2):
                for editing in (False,True):
                    for pressure in (False,True):
                        for relocate in (False,True):
                            execute_cache(template,entries,p,high,mode,editing,pressure,relocate);total+=1
                    for ordinary,controller_state in ((False,'ready'),(True,'null'),(True,'sentinel')):
                        execute_cache(template,entries,p,high,mode,editing,ordinary=ordinary,controller_state=controller_state);total+=1
            for change in (0,2):
                execute_cache(template,entries,p,high,1,True,relocate=True,change=change);total+=1
            for visible in (False, True):
                for pressure in (False, True):
                    for relocate in (False, True):
                        for editing in (False,True):
                            for controller_state in ("ready","null","sentinel"):
                                for relocate_editor in (False,True):
                                    execute_page(template, entries, p, high, visible, pressure, relocate,
                                                 editing,controller_state,relocate_editor); total += 1
            for revision, notified in ((0,0), (1,0), (1,1), (2,1)):
                for grid_state in ('ready','null','sentinel'):
                    execute_refresh(template, entries, p, high, revision, notified, grid_state); total += 1
            execute_refresh(template, entries, p, high, 1, 0, 'ready', race=True); total += 1
            for mode in (0, 1, 2):
                for width in (60.0, 80.0, 80.01, 92.0, 92.01, 150.0, float("nan"), float("inf")):
                    execute(template, entries, p, high, mode, width=width); total += 1
                for kwargs in ({"pressure": True}, {"relocate": True}, {"change": 0}, {"change": 2}, {"maximum": float("nan")},
                               {"width": 110.0, "maximum": 120.0}, {"width": 120.0, "maximum": 120.0},
                               {"width": 120.01, "maximum": 120.0}, {"maximum": 0.0},
                               {"maximum": -1.0}, {"maximum": float("inf")}):
                    execute(template, entries, p, high, mode, **kwargs); total += 1
            for prefix in (False, True):
                for width in (80.0,80.01,90.0,92.0,92.01):
                    for relocate in (False,True):
                        execute(template,entries,p,high,1,width=width,prefix=prefix,relocate=relocate);total+=1
                for changed in (0,2):
                    execute(template,entries,p,high,1,width=90,prefix=prefix,prefix_change=changed);total+=1
            for mode in (0,1,2):
                for start,end in ((0,0),(-0.0,0),(0,-0.0),(.1,0),(0,.1),(-.1,0),
                                  (float('nan'),0),(0,float('nan')),(float('inf'),0),(0,float('inf'))):
                    execute_fade(template,entries,p,high,mode,start,end);total+=1
                execute_fade(template,entries,p,high,mode,missing=True);total+=1
        print(Path(path).parent.name, "bridge/root/return-PC cases passed")
    print(total, "ARM64 cases passed; actual Flutter/GC acceptance remains a device check")


if __name__ == "__main__": main()

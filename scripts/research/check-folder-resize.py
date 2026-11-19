#!/usr/bin/env python3
"""Check the stock resize resolver and exercise a grid-based resize offline."""

import argparse
from contextlib import closing
from dataclasses import astuple, fields
import math
from pathlib import Path
import runpy
import sqlite3
import struct
import subprocess
import tempfile
from types import SimpleNamespace

from unicorn import Uc, UC_ARCH_ARM64, UC_HOOK_CODE, UC_MODE_ARM
from unicorn.arm64_const import (
    UC_ARM64_REG_D0,
    UC_ARM64_REG_PC,
    UC_ARM64_REG_X0,
    UC_ARM64_REG_X1,
    UC_ARM64_REG_X15,
    UC_ARM64_REG_X22,
    UC_ARM64_REG_X26,
    UC_ARM64_REG_X27,
    UC_ARM64_REG_X30,
)


resolver = runpy.run_path(str(Path(__file__).with_name("resolve-folder-resize.py")))
Elf = resolver["Elf"]
FOOTPRINTS = resolver["FOOTPRINTS"]
USER_SPANS = ((4, 1), (1, 4), (4, 2), (2, 4))
STOCK_NAMES = (
    "_CornerDraggableWrapperState._spanToDefaultItemType",
    "_CornerDraggableWrapperState._getFolderTypeBySize",
    "_CornerDraggableWrapperState._finishDrag",
    "_CornerDraggableWrapperState._dispatchEvent",
    "FolderHelper.getLayoutStrategy",
)
PREVIEW_NAMES = tuple(
    f"FolderIconGetxController.{name}"
    for name in (
        "onInit", "checkOrUpdateStrategy", "newFolderIconWidth", "newFolderIconHeight",
        "newPaddingHorizontal", "newPaddingVertical",
    )
)
STRATEGY_FACTORY_NAME = "FolderLayoutStrategy.getFolderLayoutStrategy"
SIZE_NAME = "FolderIconGetxController.folderIconSize"
EDIT_SCALE_NAME = "EditModeGetxController.editScale"
RX_VALUE_NAME = "RxObjectMixin.value"
REPAIR_NAME = "ItemInfoModel.fixInvalidSpan"
GRID_CLAMP_NAME = "FolderIconGetxController._clampFolderSpanWithinGrid"
GRID_CONFIG_NAME = "GridController.currentConfig"
RETURN = 0x1000
OBJECT = 0x2001


def load_symbols(nm, path, names):
    symbols = {}
    output = subprocess.check_output([nm, "-S", path], text=True)
    for line in output.splitlines():
        parts = line.split(maxsplit=3)
        if len(parts) == 4 and parts[3] in names:
            address, size, _, name = parts
            if name in symbols:
                raise ValueError(f"ambiguous fixture symbol {name}")
            symbols[name] = (int(address, 16), int(size, 16))
    return symbols


def leaf_cpu(image, entry, size):
    cpu = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
    start = entry & ~4095
    end = (entry + size + 4095) & ~4095
    cpu.mem_map(start, end - start)
    cpu.mem_write(entry, image.read(entry, size))
    cpu.mem_map(RETURN, 0x2000)
    cpu.reg_write(UC_ARM64_REG_X1, OBJECT)
    cpu.reg_write(UC_ARM64_REG_X30, RETURN)
    return cpu


def run_leaf(cpu, entry):
    cpu.emu_start(entry, RETURN, count=80)
    assert cpu.reg_read(UC_ARM64_REG_PC) == RETURN
    return cpu.reg_read(UC_ARM64_REG_X0)


def check_mapper(image, entry, size):
    cpu = leaf_cpu(image, entry, size)
    slots = resolver["read_mapper"](image.read(entry, size), 0)
    assert slots is not None
    pool = 0x10000
    extent = (max(slots.values()) + 8 + 4095) & ~4095
    cpu.mem_map(pool, extent)
    cpu.reg_write(UC_ARM64_REG_X27, pool)
    ids = {span: index for index, span in enumerate(FOOTPRINTS, 1)}
    for span, slot in slots.items():
        cpu.mem_write(pool + slot, struct.pack("<Q", ids[span]))
    for width in range(1, 5):
        for height in range(1, 7):
            cpu.mem_write(OBJECT + 7, struct.pack("<qq", width, height))
            assert run_leaf(cpu, entry) == ids.get((width, height), ids[(1, 1)])
    return {span: span if span in ids else (1, 1) for span in USER_SPANS}


def check_span_repair(image, entry, size):
    cpu = leaf_cpu(image, entry, size)
    cpu.reg_write(UC_ARM64_REG_X22, 0x4000)
    for span in (*USER_SPANS, (0, 4), (4, -1), (-1, 0)):
        cpu.mem_write(OBJECT + 0x1F, struct.pack("<qq", *span))
        run_leaf(cpu, entry)
        assert struct.unpack("<qq", cpu.mem_read(OBJECT + 0x1F, 16)) == tuple(
            max(1, value) for value in span
        )


def check_workspace_size(image, symbols):
    # Model 0308's workspace controller, with the edit scale, Rx value and
    # Size allocation supplied by the harness. Run the native size arithmetic.
    entry, size = symbols[SIZE_NAME]
    edit_scale, _ = symbols[EDIT_SCALE_NAME]
    rx_value, _ = symbols[RX_VALUE_NAME]
    cpu = leaf_cpu(image, entry, size)
    cpu.mem_map(0x3000, 0xC000)
    item, grid, rx, metrics, result = 0x4001, 0x5001, 0x6001, 0x7001, 0x8001
    thread, pool, fields_base, dart_null = 0x9000, 0xA000, 0xB000, 0xD001
    cpu.reg_write(UC_ARM64_REG_X22, dart_null)
    cpu.reg_write(UC_ARM64_REG_X26, thread)
    cpu.reg_write(UC_ARM64_REG_X27, pool)
    cpu.mem_write(thread + 0x78, struct.pack("<Q", fields_base))
    cpu.mem_write(pool + 0x40, struct.pack("<Q", 0xD101))
    cpu.mem_write(fields_base + 0x36F0, struct.pack("<Q", 0xD201))
    cpu.mem_write(fields_base + 0x2898, struct.pack("<Q", grid))
    cpu.mem_write(grid + 0x2F, struct.pack("<I", rx))
    cpu.mem_write(OBJECT + 0x83, struct.pack("<I", item))
    # The only call followed by stores of both Size fields is the allocator.
    allocator_calls = list(resolver["aligned_matches"](
        image.read(entry, size), struct.pack("<IIII", 0xFC5E83A0, 0xFC007000,
                                             0xFC5F03A0, 0xFC00F000),
    ))
    assert len(allocator_calls) == 1
    allocate_site = entry + allocator_calls[0] - 4
    assert resolver["call_target"](struct.unpack("<I", image.read(allocate_site, 4))[0],
                                   allocate_site) is not None

    for span in USER_SPANS:
        for cell_width, cell_height, scale in ((96.0, 132.0, 0.8), (81.0, 94.0, 1.0)):
            expected = [edit_scale, rx_value, rx_value, allocate_site]
            observed = []

            def stub_call(machine, address, _, __):
                word, = struct.unpack("<I", machine.mem_read(address, 4))
                target = resolver["call_target"](word, address)
                if target is None:
                    return
                if target == edit_scale:
                    machine.reg_write(UC_ARM64_REG_D0, struct.unpack("<Q", struct.pack("<d", scale))[0])
                    observed.append(target)
                elif target == rx_value:
                    assert machine.reg_read(UC_ARM64_REG_X1) == rx
                    machine.reg_write(UC_ARM64_REG_X0, metrics)
                    observed.append(target)
                else:
                    assert address == allocate_site
                    machine.reg_write(UC_ARM64_REG_X0, result)
                    observed.append(address)
                machine.reg_write(UC_ARM64_REG_PC, address + 4)

            cpu.reg_write(UC_ARM64_REG_X1, OBJECT)
            cpu.reg_write(UC_ARM64_REG_X15, 0x3FF0)
            cpu.mem_write(item + 0x1F, struct.pack("<qq", *span))
            cpu.mem_write(metrics + 0x2B, struct.pack("<dd", cell_width, cell_height))
            hook = cpu.hook_add(UC_HOOK_CODE, stub_call, begin=entry, end=entry + size)
            cpu.emu_start(entry, RETURN, count=256)
            cpu.hook_del(hook)
            assert cpu.reg_read(UC_ARM64_REG_PC) == RETURN and observed == expected
            assert cpu.reg_read(UC_ARM64_REG_X0) == result
            assert struct.unpack("<dd", cpu.mem_read(result + 7, 16)) == (
                cell_width * scale * span[0], cell_height * scale * span[1]
            )


def check_grid_clamp(image, symbols):
    entry, size = symbols[GRID_CLAMP_NAME]
    current_config, _ = symbols[GRID_CONFIG_NAME]
    cpu = leaf_cpu(image, entry, size)
    cpu.mem_map(0x3000, 0x8000)
    # Both position clamps call the same integer routine and unbox its result.
    code = image.read(entry, size)
    clamps = [
        resolver["call_target"](struct.unpack_from("<I", code, offset - 4)[0], entry + offset - 4)
        for offset in resolver["aligned_matches"](code, struct.pack("<I", 0x93417C01))
        if offset >= 4
    ]
    assert len(clamps) == 2 and clamps[0] is not None and clamps[0] == clamps[1]
    int_clamp = clamps[0]
    int_code = image.read(int_clamp, 0xC4)
    first_page = int_clamp & ~4095
    last_page = (int_clamp + len(int_code) + 4095) & ~4095
    cpu.mem_map(first_page, last_page - first_page)
    cpu.mem_write(int_clamp, int_code)
    item, grid, config = 0x4001, 0x5001, 0x6001
    thread, fields_base, pool = 0x7000, 0x8000, 0x9000
    cpu.reg_write(UC_ARM64_REG_X22, 0x9101)
    cpu.reg_write(UC_ARM64_REG_X26, thread)
    cpu.reg_write(UC_ARM64_REG_X27, pool)
    cpu.mem_write(thread + 0x78, struct.pack("<Q", fields_base))
    cpu.mem_write(fields_base + 0x2898, struct.pack("<Q", grid))
    cpu.mem_write(OBJECT + 0x83, struct.pack("<I", item))
    cpu.mem_write(item + 0x47, struct.pack("<q", -100))
    cpu.mem_write(config + 0x1B, struct.pack("<qq", 4, 6))

    def stub_call(machine, address, _, __):
        word, = struct.unpack("<I", machine.mem_read(address, 4))
        target = resolver["call_target"](word, address)
        if target is None or target == int_clamp:
            return
        assert target == current_config and machine.reg_read(UC_ARM64_REG_X1) == grid
        machine.reg_write(UC_ARM64_REG_X0, config)
        machine.reg_write(UC_ARM64_REG_PC, address + 4)

    cpu.hook_add(UC_HOOK_CODE, stub_call, begin=entry, end=entry + size)
    cases = [(span, (0, 0), (0, 0)) for span in USER_SPANS]
    cases += [((4, 1), (2, 1), (0, 1)), ((2, 4), (3, 5), (2, 2)),
              ((5, 7), (1, 1), (0, 0))]
    for span, origin, expected in cases:
        cpu.reg_write(UC_ARM64_REG_X1, OBJECT)
        cpu.reg_write(UC_ARM64_REG_X15, 0x3FF0)
        cpu.mem_write(item + 0x1F, struct.pack("<qq", *span))
        cpu.mem_write(item + 0x37, struct.pack("<qq", *origin))
        cpu.emu_start(entry, RETURN, count=256)
        assert cpu.reg_read(UC_ARM64_REG_PC) == RETURN
        assert struct.unpack("<qq", cpu.mem_read(item + 0x1F, 16)) == span
        assert struct.unpack("<qq", cpu.mem_read(item + 0x37, 16)) == expected


def check_rejection(image, profile, sizes, preview):
    for _, flags, offset, base, _, size, _, _ in image.loads:
        if flags & 5 == 5 and base <= profile.span_to_type < base + size:
            segment = image.data[offset:offset + size]
            break
    else:
        raise AssertionError("resize profile not in executable segment")

    def fixture(data, flags=5):
        offset = 4096
        header = bytearray(image.data[:offset])
        return SimpleNamespace(
            data=header + data,
            loads=[(1, flags, offset, base, base, len(data), len(data), 4096)],
        )

    def expect_rejected(data, count, flags=5, kind="resize"):
        resolve = resolver["resolve_preview" if kind == "preview" else "resolve"]
        try:
            resolve(fixture(data, flags))
        except ValueError as error:
            assert str(error) == f"expected one folder {kind} graph, found {count}"
        else:
            raise AssertionError(f"invalid {kind} graph was accepted")

    # Preserve AOT branch and pool relationships while shifting all addresses.
    addresses = tuple(address - base for address in astuple(profile))
    shifted_image = fixture(b"\0" * 4096 + segment)
    shifted = resolver["resolve"](shifted_image)
    assert astuple(shifted) == tuple(address + 4096 for address in astuple(profile))
    shifted_preview = resolver["resolve_preview"](shifted_image)
    for field in fields(preview):
        difference = 0 if field.name.endswith("_field") else 4096
        assert getattr(shifted_preview, field.name) == getattr(preview, field.name) + difference

    mapper, select, finish, dispatch, _ = addresses
    shifted_pool = bytearray(segment)
    pool_adds = [mapper + 4 * (6 + index * 8) for index in range(len(FOOTPRINTS))]
    pool_adds += [mapper + 4 * 41]
    pool_adds += [select + 0x20 + index * 20 for index in range(len(FOOTPRINTS))]
    for pc in pool_adds:
        opcode, = struct.unpack_from("<I", shifted_pool, pc)
        assert opcode & 0xFFC003E0 == 0x91400360
        struct.pack_into("<I", shifted_pool, pc, opcode + (1 << 10))
    assert resolver["resolve"](fixture(shifted_pool)) == profile

    finish_calls = [
        pc for pc in range(finish, finish + sizes[2], 4)
        if resolver["call_target"](struct.unpack_from("<I", segment, pc)[0], pc) == dispatch
    ]
    assert len(finish_calls) == 1
    for pc in (mapper + 8, select + 0x2C, finish_calls[0]):
        modified = bytearray(segment)
        struct.pack_into("<I", modified, pc, 0xD503201F)  # NOP
        expect_rejected(modified, 0)
    expect_rejected(segment, 0, flags=4)

    # A second complete graph must remain ambiguous even with different addresses.
    aligned = (len(segment) + 4095) & ~4095
    duplicate = segment.ljust(aligned, b"\0") + segment
    expect_rejected(duplicate, 2)
    expect_rejected(duplicate, 2, kind="preview")

    # The four accessors must agree on the controller field.
    modified = bytearray(segment)
    width_getter = preview.width_getter - base
    move, = struct.unpack_from("<I", modified, width_getter)
    struct.pack_into("<I", modified, width_getter, move + (4 << 5))
    expect_rejected(modified, 0, kind="preview")

    modified = bytearray(segment)
    update = preview.update_strategy - base
    ownership_read = struct.pack("<I", 0xB843F022)
    ownership, = resolver["aligned_matches"](segment[update:update + 0x40], ownership_read)
    struct.pack_into("<I", modified, update + ownership, 0xD503201F)
    expect_rejected(modified, 0, kind="preview")


def check_preview(image, symbols):
    preview = resolver["resolve_preview"](image)
    operations = (preview.initialize_strategy, preview.update_strategy, preview.width_getter,
                  preview.height_getter, preview.horizontal_padding_getter,
                  preview.vertical_padding_getter)
    assert operations == tuple(symbols[name][0] for name in PREVIEW_NAMES)
    assert preview.strategy_factory == symbols[STRATEGY_FACTORY_NAME][0]
    call = preview.strategy_factory_return - 4
    assert resolver["call_target"](struct.unpack("<I", image.read(call, 4))[0], call) == preview.strategy_factory
    for getter, field in (
        (preview.width_getter, preview.width_field),
        (preview.height_getter, preview.height_field),
        (preview.horizontal_padding_getter, preview.horizontal_padding_field),
        (preview.vertical_padding_getter, preview.vertical_padding_field),
    ):
        load, = struct.unpack("<I", image.read(getter + 24, 4))
        assert load == 0xFC400000 | (field << 12)
    return preview


def snap_span(width, height, cell_width, cell_height):
    if not all(math.isfinite(value) for value in (width, height, cell_width, cell_height)):
        raise ValueError("non-finite drag geometry")
    if cell_width <= 0 or cell_height <= 0 or width < 0 or height < 0:
        raise ValueError("invalid drag geometry")
    return max(1, math.floor(width / cell_width + 0.5)), max(
        1, math.floor(height / cell_height + 0.5)
    )


def propose_rect(current, span, grid, neighbors, rtl=False):
    x, y, old_width, _ = current
    width, height = span
    columns, rows = grid
    x = x + old_width - width if rtl else x
    if not (width > 0 and height > 0 and 0 <= x <= columns - width and 0 <= y <= rows - height):
        return None
    for other_x, other_y, other_width, other_height in neighbors:
        if (x < other_x + other_width and other_x < x + width
                and y < other_y + other_height and other_y < y + height):
            return None
    return x, y, width, height


def check_storage(path):
    # Import the exact schema only. Run all writes against newly created rows.
    uri = path.resolve().as_uri() + "?mode=ro&immutable=1"
    with closing(sqlite3.connect(uri, uri=True)) as source:
        schema, = source.execute(
            "SELECT sql FROM sqlite_schema WHERE type='table' AND name='favorites'"
        ).fetchone()
    load = "SELECT cellX,cellY,spanX,spanY,itemType,container,screen FROM favorites WHERE _id=?"
    with tempfile.TemporaryDirectory(prefix="keihome-folder-resize-") as temp:
        fixture = Path(temp) / "resize.db"
        with closing(sqlite3.connect(fixture, autocommit=False)) as db, db:
            db.execute(schema)
            db.executemany(
                "INSERT INTO favorites (_id,cellX,cellY,spanX,spanY,itemType,container,screen)"
                " VALUES (?,?,?,?,?,?,?,?)",
                [(1, 0, 0, 1, 1, 2, -100, 1), (2, 0, 0, 1, 1, 0, 1, 0),
                 (3, 3, 2, 1, 1, 0, -100, 1)],
            )
        for width, height in USER_SPANS:
            cell_width, cell_height = (96.0, 132.0)
            assert snap_span(width * cell_width - 3, height * cell_height + 2,
                             cell_width, cell_height) == (width, height)
            with closing(sqlite3.connect(fixture, autocommit=False)) as db, db:
                current = db.execute(load, (1,)).fetchone()
                neighbor = db.execute(load, (3,)).fetchone()
                next_rect = propose_rect(current[:4], (width, height), (4, 6), [neighbor[:4]])
                assert next_rect == (0, 0, width, height)
                db.execute(
                    "UPDATE favorites SET cellX=?,cellY=?,spanX=?,spanY=? WHERE _id=1",
                    next_rect,
                )
            with closing(sqlite3.connect(fixture, autocommit=False)) as db:
                assert db.execute(load, (1,)).fetchone() == (*next_rect, 2, -100, 1)
                assert db.execute(load, (2,)).fetchone() == (0, 0, 1, 1, 0, 1, 0)
                assert db.execute(load, (3,)).fetchone() == neighbor
                db.execute("UPDATE favorites SET spanX=1,spanY=1 WHERE _id=1")
                db.rollback()
                assert db.execute(load, (1,)).fetchone() == (*next_rect, 2, -100, 1)
    origin = (0, 0, 1, 1)
    assert propose_rect(origin, (4, 4), (4, 6), [(3, 2, 1, 1)]) is None
    assert propose_rect(origin, (5, 2), (4, 6), []) is None
    assert propose_rect((0, 4, 1, 1), (2, 4), (4, 6), []) is None
    assert propose_rect(origin, (0, 1), (4, 6), []) is None
    assert propose_rect((3, 0, 1, 1), (4, 1), (4, 6), [], rtl=True) == (0, 0, 4, 1)
    assert propose_rect((1, 0, 1, 1), (4, 1), (4, 6), [], rtl=True) is None
    assert propose_rect(origin, (8, 3), (8, 6), [(7, 3, 1, 3)]) == (0, 0, 8, 3)
    print("PASS custom rectangles in isolated SQLite schema, rollback, collision, grid edges and RTL")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixtures", type=Path, help="subdirectories with libapp.so and debug.elf")
    parser.add_argument("--nm", default="llvm-nm")
    parser.add_argument("--layout-schema", type=Path, required=True)
    args = parser.parse_args()
    fixtures = sorted(args.fixtures.glob("*/libapp.so"))
    if not fixtures:
        parser.error("no launcher fixtures found")
    for file in fixtures:
        image = Elf(file.read_bytes())
        profile = resolver["resolve"](image)
        names = (*STOCK_NAMES, *PREVIEW_NAMES, STRATEGY_FACTORY_NAME)
        if file.parent.name == "0308":
            names += (REPAIR_NAME, SIZE_NAME, EDIT_SCALE_NAME, RX_VALUE_NAME)
            names += (GRID_CLAMP_NAME, GRID_CONFIG_NAME)
        symbols = load_symbols(args.nm, file.with_name("debug.elf"), names)
        locations, sizes = zip(*(symbols[name] for name in STOCK_NAMES))
        assert astuple(profile) == locations, dict(zip(
            (field.name for field in fields(profile)), locations,
        ))
        mapped = check_mapper(image, locations[0], sizes[0])
        preview = check_preview(image, symbols)
        print(f"PASS {file.parent.name}: twelve symbol oracles, 24 ARM64 mappings; "
              f"preview strategy field {preview.strategy_field:#x}; requested {mapped}")
        if file.parent.name == "0308":
            check_span_repair(image, *symbols[REPAIR_NAME])
            check_workspace_size(image, symbols)
            check_grid_clamp(image, symbols)
            check_rejection(image, profile, sizes, preview)
            print("PASS 0308: span repair, eight native size and seven native clamp cases, "
                  "code and pool relocation, five damaged graphs, non-executable graph "
                  "and ambiguous resize and preview graphs")
    check_storage(args.layout_schema)


if __name__ == "__main__":
    main()

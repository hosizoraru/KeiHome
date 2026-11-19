#!/usr/bin/env python3
"""Locate stock folder resize and preview geometry in an ARM64 launcher."""

import argparse
from bisect import bisect_right
from dataclasses import asdict, dataclass, fields, replace
import json
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests/monet-selection"))
from elf_reader import Elf


FRAME = struct.pack("<II", 0xA9BF79FD, 0xAA0F03FD)
MOV_X1_X0 = 0xAA0003E1
RET = 0xD65F03C0
FOOTPRINTS = ((1, 1), (2, 1), (1, 2), (2, 2), (4, 2))


@dataclass(frozen=True)
class FolderResize:
    span_to_type: int
    select_span: int
    finish_drag: int
    dispatch_event: int
    lookup_strategy: int


@dataclass(frozen=True)
class FolderPreview:
    initialize_strategy: int
    strategy_factory: int
    strategy_factory_return: int
    update_strategy: int
    strategy_field: int
    width_field: int
    height_field: int
    horizontal_padding_field: int
    vertical_padding_field: int
    width_getter: int
    height_getter: int
    horizontal_padding_getter: int
    vertical_padding_getter: int


def aligned_matches(data, pattern):
    start = data.find(pattern)
    while start != -1:
        if start % 4 == 0:
            yield start
        start = data.find(pattern, start + 1)


def compare_immediate(register, value):
    return 0xF100001F | value << 10 | register << 5


def pool_slot(add, load, register):
    if (add & 0xFFC003FF != (0x91400360 | register)
            or load & 0xFFC003FF != (0xF9400000 | register << 5 | register)):
        return None
    return ((add >> 10) & 0xFFF) * 4096 + ((load >> 10) & 0xFFF) * 8


def read_mapper(data, entry):
    if entry + 0xB0 > len(data):
        return None
    words = struct.unpack_from("<44I", data, entry)
    if words[0] != 0xF8407022:  # Load the first member of the span record.
        return None
    slots = []
    for index, (width, height) in enumerate(FOOTPRINTS):
        second_register = 2 if index == len(FOOTPRINTS) - 1 else 3
        start = 1 + index * 8
        expected = (
            compare_immediate(2, width), 0x540000E1,
            0xF840F020 | second_register, compare_immediate(second_register, height),
            0x54000081,
        )
        if words[start:start + 5] != expected or words[start + 7] != RET:
            return None
        slots.append(pool_slot(*words[start + 5:start + 7], 0))
    fallback = pool_slot(*words[-3:-1], 0)
    if None in slots or len(set(slots)) != len(slots) or fallback != slots[0] or words[-1] != RET:
        return None
    return dict(zip(FOOTPRINTS, slots))


def call_target(word, pc):
    if word >> 26 != 0b100101:
        return None
    return pc + 4 * ((word & 0x1FFFFFF) - (word & 0x2000000))


def resolve_segment(data):
    frames = list(aligned_matches(data, FRAME))
    frame_set = set(frames)
    mapper_head = struct.pack("<II", 0xF8407022, compare_immediate(2, 1))
    mappers = {
        entry: slots
        for entry in aligned_matches(data, mapper_head)
        if (slots := read_mapper(data, entry)) is not None
    }
    if not mappers:
        return []
    mapper_calls = {entry: {} for entry in mappers}
    for index, (word,) in enumerate(struct.iter_unpack("<I", memoryview(data)[:len(data) & ~3])):
        pc = index * 4
        target = call_target(word, pc)
        if target in mappers and pc + 12 <= len(data):
            frame_index = bisect_right(frames, pc) - 1
            if frame_index >= 0 and pc - frames[frame_index] < 0x1000:
                mapper_calls[target].setdefault(frames[frame_index], []).append(pc)

    profiles = []
    for mapper, callers in mapper_calls.items():
        for dispatch, sites in callers.items():
            if len(sites) != 2:
                continue
            lookups = [forwarded_lookup(data, site) for site in sites]
            lookup = lookups[0]
            if lookup not in frame_set or lookups[1] != lookup:
                continue
            for finish, finish_sites in callers.items():
                if finish == dispatch or len(finish_sites) != 2:
                    continue
                first, last = finish_sites
                if forwarded_lookup(data, first) != lookup:
                    continue
                # Finishing calls the dispatcher before recalculating the corner radius.
                dispatch_count = sum(
                    call_target(struct.unpack_from("<I", data, pc)[0], pc) == dispatch
                    for pc in range(first + 12, last, 4)
                )
                if dispatch_count != 1:
                    continue
                for select in frames:
                    if reads_strategy_sizes(data, select, lookup, mappers[mapper]):
                        profiles.append(FolderResize(mapper, select, finish, dispatch, lookup))
    return profiles


def forwarded_lookup(data, mapper_call):
    move, call = struct.unpack_from("<II", data, mapper_call + 4)
    return call_target(call, mapper_call + 8) if move == MOV_X1_X0 else None


def reads_strategy_sizes(data, entry, lookup, slots):
    # The sizing pass reads width baselines from 1x1, 2x1 and 4x2, then height
    # baselines from 1x1 and 1x2. Tie these to the same pool objects as the mapper.
    cases = (((1, 1), 0xFC40F000), ((2, 1), 0xFC40F000), ((4, 2), 0xFC40F000),
             ((1, 1), 0xFC417000), ((1, 2), 0xFC417000))
    if (entry + 0x80 > len(data)
            or struct.unpack_from("<I", data, entry + 8)[0] & 0xFFC003FF != 0xD10001EF):
        return False
    for index, (footprint, field) in enumerate(cases):
        pc = entry + 0x20 + index * 20
        add, load, call, read = struct.unpack_from("<IIII", data, pc)
        if (pool_slot(add, load, 1) != slots[footprint]
                or call_target(call, pc + 8) != lookup or read != field):
            return False
    return True


def preview_getters(data):
    # The four leaf getters read the same compressed strategy pointer and use
    # the same slow path when the strategy has not been initialized.
    read_strategy = struct.pack("<IIIII", 0xB8716820, 0x8B1C8000, 0xF9402370,
                                0x6B10001F, 0x54000060)
    accessors = (0xFC40F000, 0xFC417000, 0xFC437000, 0xFC42F000)
    groups = {}
    for pc in aligned_matches(data, read_strategy):
        entry = pc - 4
        if entry < 0 or entry + 52 > len(data):
            continue
        words = struct.unpack_from("<13I", data, entry)
        if (words[0] & 0xFFE0001F != 0xD2800011 or words[6] not in accessors
                or words[7] != RET or data[entry + 32:entry + 40] != FRAME):
            continue
        error_target = call_target(words[12], entry + 48)
        slot = pool_slot(*words[10:12], 9)
        if error_target is None or not 0 <= error_target < len(data) or slot is None:
            continue
        groups.setdefault((words[0], slot, error_target), []).append((words[6], entry))
    for (load_offset, _, error_target), getters in groups.items():
        by_opcode = dict(getters)
        if len(getters) == len(accessors) and all(opcode in by_opcode for opcode in accessors):
            yield load_offset, tuple((opcode, by_opcode[opcode]) for opcode in accessors), error_target


def resolve_preview_segment(data, base):
    frames = list(aligned_matches(data, FRAME))
    frame_set = set(frames)
    profiles = []
    for load_offset, getters, error_target in preview_getters(data):
        # Initialization installs the strategy returned by a call. Validate the
        # compressed pointer store together with Dart's post-store barrier.
        install = struct.pack("<IIIIIII", load_offset, 0xB8316840, 0x385FF050,
                              0x385FF011, 0x8A500A30, 0xEA5C821F, 0x54000040)
        for store in aligned_matches(data, install):
            constructor = preceding_call(data, store - 8)
            init_index = bisect_right(frames, store) - 1
            if init_index < 0 or constructor not in frame_set:
                continue
            initialize = frames[init_index]
            if (store - initialize >= 0x1000
                    or struct.unpack_from("<I", data, store - 4)[0] != 0xF85F03A2):
                continue
            init_calls = set(call_targets(data, initialize, min(len(data), initialize + 0x2000)))
            if error_target not in init_calls:
                continue
            for update in preview_updates(data, frames, frame_set, load_offset, error_target):
                profiles.append(FolderPreview(
                    initialize_strategy=base + initialize,
                    strategy_factory=base + constructor,
                    strategy_factory_return=base + store - 4,
                    update_strategy=base + update,
                    strategy_field=(load_offset >> 5) & 0xFFFF,
                    width_field=(getters[0][0] >> 12) & 0x1FF,
                    height_field=(getters[1][0] >> 12) & 0x1FF,
                    horizontal_padding_field=(getters[2][0] >> 12) & 0x1FF,
                    vertical_padding_field=(getters[3][0] >> 12) & 0x1FF,
                    width_getter=base + getters[0][1],
                    height_getter=base + getters[1][1],
                    horizontal_padding_getter=base + getters[2][1],
                    vertical_padding_getter=base + getters[3][1],
                ))
    return profiles


def preceding_call(data, pc):
    return call_target(struct.unpack_from("<I", data, pc)[0], pc) if pc >= 0 else None


def preview_updates(data, frames, frame_set, load_offset, error_target):
    # In both compiler shapes, the updater starts by reading the existing
    # strategy with the controller field confirmed by the getters and onInit.
    for prefix, receiver, decompress, compare_null, read_ownership in (
        (0x14, 0xB8716801, 0x8B1C8021, 0x6B10003F, 0xB843F022),
        (0x10, 0xB8716820, 0x8B1C8000, 0x6B10001F, 0xB843F002),
    ):
        read = struct.pack("<IIIII", load_offset, receiver, decompress, 0xF9402370, compare_null)
        for site in aligned_matches(data, read):
            entry = site - prefix
            if entry not in frame_set or site + 28 > len(data):
                continue
            branch, read_field = struct.unpack_from("<II", data, site + 20)
            if branch & 0xFF00001F != 0x54000000 or read_field != read_ownership:
                continue
            index = bisect_right(frames, entry)
            end = frames[index] if index < len(frames) else len(data)
            code = data[site:min(end, entry + 0x200)]
            # The updater reads the controller's strategy again and uses the
            # same uninitialized-field slow path as the leaf getters.
            if code.count(struct.pack("<I", load_offset)) < 2:
                continue
            if error_target not in call_targets(data, entry, min(end, entry + 0x200)):
                continue
            yield entry


def call_targets(data, start, end):
    for pc in range(start, end - 3, 4):
        if (target := preceding_call(data, pc)) is not None:
            yield target


def executable_segments(image):
    if struct.unpack_from("<H", image.data, 18)[0] != 183:
        raise ValueError("expected ARM64 ELF")
    for kind, flags, offset, base, _, size, _, _ in image.loads:
        if kind != 1 or flags & 5 != 5:
            continue
        data = image.data[offset:offset + size]
        if len(data) != size or base % 4:
            raise ValueError("invalid executable segment")
        yield base, data


def resolve(image):
    profiles = []
    for base, data in executable_segments(image):
        for profile in resolve_segment(data):
            profiles.append(replace(profile, **{
                field.name: base + getattr(profile, field.name) for field in fields(profile)
            }))
    if len(profiles) != 1:
        raise ValueError(f"expected one folder resize graph, found {len(profiles)}")
    return profiles[0]


def resolve_preview(image):
    profiles = [
        profile
        for base, data in executable_segments(image)
        for profile in resolve_preview_segment(data, base)
    ]
    if len(profiles) != 1:
        raise ValueError(f"expected one folder preview graph, found {len(profiles)}")
    return profiles[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("libapp", type=Path)
    parser.add_argument("--preview", action="store_true")
    args = parser.parse_args()
    image = Elf(args.libapp.read_bytes())
    profile = resolve_preview(image) if args.preview else resolve(image)
    print(json.dumps({name: hex(address) for name, address in asdict(profile).items()}, indent=2))


if __name__ == "__main__":
    main()

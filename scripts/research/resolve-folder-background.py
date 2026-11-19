#!/usr/bin/env python3
"""Locate the resting HyperOS folder background graph in a local ARM64 ELF."""

import argparse
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests/monet-selection"))
from elf_reader import Elf


FRAME = (0xA9BF79FD, 0xAA0F03FD)
RETURN = (0xAA1D03EF, 0xA8C179FD, 0xD65F03C0)
FRAME_BYTES = struct.pack("<2I", *FRAME)
EM_AARCH64 = 183
TAIL_PREFIX = struct.pack("<I", 0xAA0003E5)  # mov x5, x0 (color fallback)


def signed(value, bits):
    return (value ^ (1 << (bits - 1))) - (1 << (bits - 1))


def branch_link(word, pc):
    if word & 0xFC000000 == 0x94000000:
        return pc + 4 * signed(word & 0x03FFFFFF, 26)
    return None


def stack_slot(word, opcode):
    if word & 0xFFE003FF == opcode:
        return signed((word >> 12) & 0x1FF, 9)
    return 0


def theme_wrapper(words, pc, base, end):
    # The wrapper allocates a geometry widget and uses five saved arguments
    # to initialize it. The returned object must be the original allocation.
    if (words[2] & 0xFFC003FF != 0xD10001EF
            or words[6] != 0xFC413021 or words[8] != 0xFC41B020
            or words[11] != 0xAA0003E1
            or words[18] & 0xFFC003FF != 0xF9400364
            or words[21:] != RETURN):
        return False
    slots = [stack_slot(words[save], store) for save, _, store, _ in THEME_ROOTS]
    frame_size = (words[2] >> 10) & 0xFFF
    # The allocation can reuse the x2 slot once x2 has been reloaded.
    if (len(set(slots[:-1])) != len(slots) - 1
            or any(slot > -8 or slot < -frame_size or slot % 8 for slot in slots)):
        return False
    for slot, (_, load, _, opcode) in zip(slots, THEME_ROOTS):
        if stack_slot(words[load], opcode) != slot:
            return False
    callees = [branch_link(words[i], pc + i * 4) for i in (10, 19)]
    return all(callee is not None and base <= callee < end for callee in callees)


# Instruction indices and register-specific unscaled store/load opcodes.
THEME_ROOTS = (
    (3, 12, 0xF80003A2, 0xF84003A2),
    (4, 13, 0xF80003A3, 0xF84003A3),
    (5, 15, 0xF80003A5, 0xF84003A5),
    (7, 16, 0xFC0003A1, 0xFC4003A1),
    (9, 14, 0xFC0003A0, 0xFC4003A0),
    (17, 20, 0xF80003A0, 0xF84003A0),
)


@dataclass(frozen=True)
class Builder:
    entry: int
    color_builder: int
    default_gate: int
    default_color_call: int
    theme_fallback_call: int
    theme_call: int


@dataclass(frozen=True)
class FolderBackground:
    theme_wrapper: int
    theme_provider: int
    drawable_lookup: int
    builders: tuple[Builder, Builder]


def find_words(data, needle):
    position = data.find(needle)
    while position != -1:
        if position % 4 == 0:
            yield position
        position = data.find(needle, position + 1)


def read_builder(data, base, tail):
    # The final Dart call receives the color widget as a themed-image fallback.
    earliest = max(0, tail - 0x280)
    entry = data.rfind(FRAME_BYTES, earliest, tail)
    if entry < 0 or entry % 4:
        return None
    words = struct.unpack_from(f"<{(tail - entry) // 4}I", data, entry)
    if len(words) < 12:
        return None
    pc = base + entry
    calls = {index: callee for index, word in enumerate(words)
             if (callee := branch_link(word, pc + index * 4)) is not None}

    # The same local color builder must be used before the early return and
    # after the theme lookup. The second result flows directly into the wrapper.
    fallback = len(words) - 4
    color = calls.get(fallback)
    if color is None or not base <= color <= base + len(data) - len(FRAME_BYTES):
        return None
    if not data.startswith(FRAME_BYTES, color - base):
        return None
    defaults = [index for index, callee in calls.items()
                if callee == color and words[index + 1:index + 4] == RETURN]
    if len(defaults) != 1:
        return None
    default = defaults[0]
    gates = [index for index, word in enumerate(words[:default])
             if word & 0xFFF80000 == 0x37200000
             and index + signed((word >> 5) & 0x3FFF, 14) == default + 4]
    if len(gates) != 1:
        return None
    gate = gates[0]
    theme_provider = calls.get(gate + 2)
    if theme_provider is None or not base <= theme_provider < base + len(data):
        return None

    lookups = set()
    for index in range(default + 4, fallback):
        destination = calls.get(index)
        if destination is None or not base <= destination < base + len(data):
            continue
        # Cache lookup takes a resource key loaded from the Dart frame. Some
        # compilers also pass an argument descriptor loaded from the pool.
        key_load = index - 1
        if words[key_load] & 0xFFC003FF == 0xF9400364:
            key_load -= 1
        if (words[key_load - 1] == 0xAA0003E1
                and words[key_load] & 0xFFE003FF == 0xF84003A2):
            lookups.add(destination)

    return Builder(
        entry=pc,
        color_builder=color,
        default_gate=pc + gate * 4,
        default_color_call=pc + default * 4,
        theme_fallback_call=pc + fallback * 4,
        theme_call=base + tail + 4,
    ), theme_provider, lookups


def resolve(image):
    if struct.unpack_from("<H", image.data, 18)[0] != EM_AARCH64:
        raise ValueError("expected ARM64 ELF")
    profiles = []
    for kind, flags, offset, base, _, size, _, _ in image.loads:
        if kind != 1 or flags & 5 != 5 or size < 0x60:
            continue
        data = image.data[offset:offset + size]
        if len(data) != size:
            raise ValueError("truncated executable segment")
        wrappers = set()
        for entry in find_words(data, FRAME_BYTES):
            if entry + 0x60 > len(data):
                continue
            words = struct.unpack_from("<24I", data, entry)
            if theme_wrapper(words, base + entry, base, base + size):
                wrappers.add(base + entry)
        if not wrappers:
            continue

        callers = {entry: [] for entry in wrappers}
        for tail in find_words(data, TAIL_PREFIX):
            if tail + 20 > len(data):
                continue
            _, instruction, *ret = struct.unpack_from("<5I", data, tail)
            target = branch_link(instruction, base + tail + 4)
            if target in wrappers and tuple(ret) == RETURN:
                candidate = read_builder(data, base, tail)
                if candidate is not None:
                    callers[target].append(candidate)
        for wrapper, candidates in callers.items():
            if len(candidates) != 2:
                continue
            (first, first_provider, first_lookups), (second, second_provider, second_lookups) = candidates
            shared = first_lookups & second_lookups
            if (first_provider == second_provider and len(shared) == 1
                    and first.color_builder != second.color_builder):
                profiles.append(FolderBackground(
                    wrapper, first_provider, shared.pop(), (first, second)
                ))
    if len(profiles) != 1:
        raise ValueError(f"expected one resting-folder background graph, found {len(profiles)}")
    return profiles[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    args = parser.parse_args()
    try:
        profile = resolve(Elf(args.image.read_bytes()))
    except (OSError, ValueError, struct.error) as error:
        parser.exit(1, f"{args.image}: {error}\n")
    print(json.dumps(asdict(profile), indent=2))


if __name__ == "__main__":
    main()

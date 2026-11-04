#!/usr/bin/env python3
"""Reviewed inline-grid label contracts from external debug-data oracles."""
import argparse
import importlib.util
from pathlib import Path
import struct

spec = importlib.util.spec_from_file_location('label_elf', Path(__file__).parents[1] / 'icon-highlight/elf_fixture.py')
elf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(elf)


def mask(word):
    if word & 0xfc000000 == 0x94000000:
        return 0xfc000000
    if word & 0xffc003ff == 0xd10001ef:
        return 0xffc003ff
    if word & 0xffe00c00 in (0xf8400000, 0xf8000000, 0xfc400000, 0xfc000000) and (word >> 5) & 31 == 29:
        return 0xffe00fff
    if word & 0xffc00000 in (0xf9400000, 0xfd400000):
        return 0xffc003ff
    if word & 0xffc00000 == 0x91400000 and (word >> 5) & 31 == 27:
        return 0xffc003ff
    return 0xffffffff


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--library', type=Path, action='append', required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    contracts = {}
    roles = [('LinesInline', 'GridController.titleLines', 0, 39),
             ('ConfigExpanded', 'GridController.currentConfig', 0, 25),
             ('ChangeEventExpanded', 'ItemChangeProcessor.processItemsChanged', 0, 36),
             ('CellGridBodyExpanded', 'CellLayout.buildGridCellAndBackground', 0x64, 17),
             ('EditingState', 'ScreenLayerGetXController._isInNormalEditing', 0, 25),
             ('CacheGet', 'WidgetCacheManager.get', 0, 30),
             ('ShortcutFactory', 'WidgetShortCutBuilder.build', -0xc8, 20),
             ('DragFactory', 'WidgetFactory.wrapDraggable', 0x3d8, 22),
             # Fixture location is in the adjacent constructor; production follows
             # the factory's constructor BL before authenticating this field store.
             ('DragChild', 'WidgetFactory.wrapDraggable', 0x77c, 7),
             ('ParameterizedAllocator', 'WidgetFactory.wrapDraggable', 0x3dc, 23)]
    for library in args.library:
        image = elf.Image(library)
        [(lines, size)] = image.syms['GridController.titleLines']
        assert size == 0x9c and image.read(lines+0x8c, 16) == struct.pack('<4I', 0xaa0103e0, 0xaa1d03ef, 0xa8c179fd, 0xd65f03c0)
        for role, name, offset, count in roles:
            [(address, size)] = image.syms[name]
            assert size >= count*4 or offset == size
            address += offset if offset >= 0 else size + offset
            if role == 'ParameterizedAllocator':
                def branch(pc):
                    w = struct.unpack('<I', image.read(pc,4))[0] & 0x3ffffff
                    return pc + (w - (0x4000000 if w & 0x2000000 else 0))*4
                address = branch(branch(address)+8)
            words = struct.unpack('<'+'I'*count, image.read(address, count*4))
            pattern = []
            for index, word in enumerate(words):
                bits = 0xffe00fff if role == 'EditingState' and index == 19 else mask(word)
                pattern.append((word & bits, bits))
            assert role not in contracts or contracts[role] == pattern, (role, library)
            contracts[role] = pattern
    lines = ['// SPDX-License-Identifier: Apache-2.0',
             '// Reviewed structural variants; no image identity or absolute code address.']
    for role, pattern in contracts.items():
        lines.append(f'static constexpr LabelWord kLabel{role}[] = {{')
        lines += [f'    {{0x{word:08x}u, 0x{bits:08x}u}},' for word, bits in pattern]
        lines.append('};')
    args.output.write_text('\n'.join(lines)+'\n')


if __name__ == '__main__':
    main()

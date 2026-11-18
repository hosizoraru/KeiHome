#!/usr/bin/env python3
"""Emit reviewed tint widget variants from external independent debug oracles."""
import argparse
import hashlib
import importlib.util
from pathlib import Path
import struct

spec = importlib.util.spec_from_file_location('tint_elf', Path(__file__).parents[1] / 'icon-highlight/elf_fixture.py')
elf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(elf)


def normalized(words, role=None):
    out = []
    for i, word in enumerate(words):
        previous = words[i-1] if i else 0
        following = words[i+1] if i+1 < len(words) else 0
        value = word
        if word & 0xfc000000 == 0x94000000:
            value &= 0xfc000000
        elif word & 0xfc000000 == 0x14000000:
            target = i + (((word & 0x3ffffff) ^ 0x2000000) - 0x2000000)
            if not 0 <= target < len(words):
                value &= 0xfc000000
        elif word & 0xffc003e0 == 0x91400360:
            value &= 0xffc003ff
        elif previous & 0xffc003e0 == 0x91400360 and (word >> 5 & 31) == (previous & 31) and word & 0x3b000000 == 0x39000000:
            value &= 0xffc003ff
        elif (word & 0xffc003e0 == 0xf9400360 or
              role == 'BackgroundClosure' and word & 0xffc003e0 == 0xfd400360) and (word >> 10 & 4095)*8 > 0x100:
            value &= 0xffc003ff
        elif previous & 0xffffffe0 == 0xf9403f40 and (word >> 5 & 31) == (previous & 31) and word & 0xff800000 == 0xf9000000:
            value &= 0xffc003ff
        elif word & 0xffc003ff == 0x9100001e and following == 0xf87e7abe:
            value &= 0xffc003ff
        out.append(value)
    return out


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--library', type=Path, action='append', required=True)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    variants = set()
    for path in args.library:
        image = elf.Image(path)
        [(foreground, size)] = image.syms['_MonochromeIconWidgetState.build']
        # Use the independent symbol's full body, including older stack layouts.
        # Production still authenticates every normalized word of each variant.
        assert size % 4 == 0 and 16 <= size <= 320*4
        render, render_size = image.syms['_ColorFilterRenderObject.colorFilter='][0]
        for role, start, length in [('ForegroundBuild', foreground, size),
                                    ('BackgroundClosure', foreground+size, 0x1a0),
                                    ('RenderSetter', render, render_size)]:
            words = struct.unpack('<'+'I'*(length//4), image.read(start, length))
            canonical = normalized(words, role)
            digest = hashlib.sha256(struct.pack('<'+'I'*len(canonical), *canonical)).digest()
            variants.add((role, len(words), tuple(canonical[:4]), digest))
    lines = ['// SPDX-License-Identifier: Apache-2.0',
             '// Reviewed instruction variants; generated from external debug-data oracles.',
             'constexpr Variant kTintVariants[] = {']
    for role, count, prefix, digest in sorted(variants):
        lines += ['    {k%s, {"%s", %d, {%s},\n      {%s}}},' % (
            role, role, count, ', '.join(f'0x{v:08x}u' for v in prefix),
            ', '.join(f'0x{v:02x}' for v in digest))]
    lines.append('};')
    args.output.write_text('\n'.join(lines)+'\n')


if __name__ == '__main__':
    main()

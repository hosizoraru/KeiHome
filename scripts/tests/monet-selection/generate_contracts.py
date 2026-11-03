#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate bounded instruction contracts from external, independently decoded ELFs.

Symbols are an offline audit oracle only. The generated runtime data contains no
ELF identity, symbol names, absolute addresses, RVAs or extracted function bodies.
The resolver scans executable mappings and decodes every relevant branch target.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import sys
sys.dont_write_bytecode = True
from elf_reader import Elf

# Function-relative spans for the reviewed ABI, not image offsets. Most complete
# function sizes come from the independent debug symbol file. Anonymous closure
# entry offsets are cross-checked by the complete emitted contract and callers.
ROLES = [
    ('Has', 'ShortcutIconGetxController._hasMonochromeLayer'),
    ('Should', 'ShortcutIconGetxController.shouldUseMonochrome'),
    ('Update', 'ShortcutIconGetxController._updateMonochromeState'),
    ('Resource', 'Resources.getMonochromeResId'),
    ('Init', 'ShortcutIconGetxController._initMonochromeState'),
    ('UpdateCallback', None), ('ResourceProbe', None),
    ('OneByteEquals', None), ('TwoByteEquals', None),
    ('GenericEquals', '_StringBase.=='),
    ('OneByteAllocateFast', 'allocateOneByteString'),
    ('PackageGetter', 'ShortcutInfoModel.getPackageName'),
    ('WidgetPackageGetter', 'ShortcutInfoModel.getWidgetPkgName'),
    ('IconBuild', 'IconWidget.buildIconDrawable'),
    ('IconQueryCallback', None),
    ('FolderBuild', 'FolderPreviewIcon.build'),
    ('FolderFallback', 'FolderPreviewIcon._buildFallbackIcon'),
    ('LoadIcon', '_MonochromeIconWidgetState._loadIcon'),
    ('PutIfAbsent', None), ('ClosureAllocateFast', None),
    ('ContextAllocateFast', None),
    ('ResourcesOf', 'ResourcesManager.of'),
    ('CreateResources', 'ResourcesManager._createResources'),
    ('AppTypeFromStr', 'FrbAppType.fromStr'),
    ('AppTypeBridge', 'RustLibApiImpl.crateContentResResourcesFrbAppTypeFromStr'),
    ('AppTypeEncodeCallback', None), ('EncodeString', None),
    ('Utf8Convert', 'Utf8Encoder.convert'),
    ('Utf8FillPrefix', '_Utf8Encoder._fillBuffer'),
    ('CacheQuery', 'IconCacheV2.getDesktopIconImage'),
    ('PreviewUpdate', 'IconCustomizePreviewGetxController._updateMonochromeState'),
]
ROLE_IDS = {n: i for i, (n, _) in enumerate(ROLES)}
OPTIONAL_ROLES = {'IconQueryCallback', 'CacheQuery', 'PreviewUpdate'}


def branch(pc, w):
    if w & 0x7c000000 == 0x14000000:
        mask, shift, bits = 0x03ffffff, 0, 26
    elif w & 0xff000010 == 0x54000000 or w & 0x7e000000 == 0x34000000:
        mask, shift, bits = 0x00ffffe0, 5, 19
    elif w & 0x7e000000 == 0x36000000:
        mask, shift, bits = 0x0007ffe0, 5, 14
    else:
        return None
    imm = (w & mask) >> shift
    return pc + ((imm ^ (1 << (bits - 1))) - (1 << (bits - 1))) * 4, mask


def canonical(words, base):
    """Keep opcodes/registers/layout/CFG; relocate only decoded external edges,
    PP slots and static-table slots. Preserve repeated-slot identity locally.
    This algorithm is mirrored (and fixture-checked) in selection_profile.cpp.
    """
    out = list(words)
    pools, statics = [], []

    def ordinal(values, value):
        if value not in values:
            values.append(value)
        assert len(values) <= 256
        return values.index(value) + 1

    for i, w in enumerate(words):
        b = branch(base + 4*i, w)
        if b and not base <= b[0] < base + 4*len(words):
            out[i] = w & ~b[1]
        # LDR Xt, [PP, #slot]; low VM reserved slots remain exact.
        if w & 0xffc003e0 == 0xf9400360 and ((w >> 10) & 4095)*8 >= 256:
            out[i] = (w & ~0x003ffc00) | ordinal(pools, ((w >> 10) & 4095)*8) << 10
        if i + 1 == len(words):
            continue
        next_word = words[i+1]
        reg = w & 31
        # ADD Xt, PP, #page, LSL #12; LDR Xd, [Xt, #slot].
        if w & 0xffc003e0 == 0x91400360 and next_word & 0xffc003e0 == 0xf9400000 | reg << 5:
            slot = ((w >> 10) & 4095)*4096 + ((next_word >> 10) & 4095)*8
            assert 256 <= slot <= 0x100000
            out[i] = w & ~0x003ffc00
            out[i+1] = (next_word & ~0x003ffc00) | ordinal(pools, slot) << 10
        # LDR Xt, [THR, #0x78]; LDR Xt, [Xt, #static_slot].
        if w & ~31 == 0xf9403f40 and next_word & 0xffc003ff == 0xf9400000 | reg << 5 | reg:
            out[i+1] = (next_word & ~0x003ffc00) | ordinal(statics, (next_word >> 10) & 4095) << 10
    return struct.pack('<' + 'I'*len(out), *out)


class Fixture:
    def __init__(self, library, debug):
        self.library = Path(library)
        self.elf = Elf(self.library.read_bytes())
        self.symbols = [(v, s, n) for v, s, n, _, _ in Elf(Path(debug).read_bytes()).symbols() if s]
        self.sizes = {v: s for v, s, _ in self.symbols}
        self.names = {v: n for v, _, n in self.symbols}
        self.incoming = {}
        for p in self.elf.loads:
            if p[0] != 1 or not p[1] & 1:
                continue
            for i, (w,) in enumerate(struct.iter_unpack('<I', self.elf.read(p[3], p[5]))):
                pc = p[3] + i*4
                b = branch(pc, w)
                if b:
                    self.incoming.setdefault(b[0], []).append((pc, w & 0xfc000000 == 0x94000000))
        self.roles = {}
        for role, name in ROLES:
            if name:
                found = {(v, s) for v, s, n in self.symbols if n == name}
                if not found and role in OPTIONAL_ROLES:
                    continue
                assert len(found) == 1, (role, found)
                self.roles[role] = next(iter(found))
        # Cache/preview paths are accepted only when their actual bodies call
        # the same String-taking Should role. Other cache implementations are
        # dependencies, not alternative direct-caller contracts.
        for role in ('CacheQuery', 'PreviewUpdate'):
            if role not in self.roles:
                continue
            start, size = self.roles[role]
            if not any(start <= pc < start + size and link
                       for pc, link in self.incoming[self.roles['Should'][0]]):
                del self.roles[role]
        self.roles['OneByteAllocateFast'] = (self.roles['OneByteAllocateFast'][0], 120)
        self.roles['Utf8FillPrefix'] = (self.roles['Utf8FillPrefix'][0], 168)
        for role, size in [('OneByteEquals', 176), ('TwoByteEquals', 172)]:
            found = {(v, s) for v, s, n in self.symbols if n == 'String.==' and s == size}
            assert len(found) == 1
            self.roles[role] = next(iter(found))
        for role, offset, size in [('ContextAllocateFast', 20, 164), ('ClosureAllocateFast', 100, 76), ('PutIfAbsent', 116, 632)]:
            self.roles[role] = (self.call_target(self.roles['Has'][0] + offset), size)
        for role, callee, offset, exclude, size in [
            ('UpdateCallback', 'Update', 52, 'Init', 188),
            ('ResourceProbe', 'Resource', 36, 'LoadIcon', 120),
            ('IconQueryCallback', 'Should', 144, 'FolderFallback', 788),
        ]:
            start, length = self.roles[exclude]
            excluded = [(start, length)]
            if role == 'IconQueryCallback':
                excluded += [self.roles[r] for r in ('CacheQuery', 'PreviewUpdate') if r in self.roles]
            callers = [pc for pc, link in self.incoming[self.roles[callee][0]] if link and
                       not any(a <= pc < a+s for a, s in excluded)]
            if not callers and role == 'IconQueryCallback':
                assert 'CacheQuery' in self.roles and 'PreviewUpdate' in self.roles
                continue
            assert len(callers) == 1, (role, callers)
            self.roles[role] = (callers[0] - offset, size)
        start, length = self.roles['AppTypeBridge']
        self.roles['AppTypeEncodeCallback'] = (start+length, 184)
        self.roles['EncodeString'] = (self.call_target(start+length+100), 72)
        self.by_address = {v: ROLE_IDS[n] for n, (v, _) in self.roles.items()}

    def words(self, address, size):
        assert 0 < size <= 8192 and size % 4 == 0
        return list(struct.unpack('<'+'I'*(size//4), self.elf.read(address, size)))

    def call_target(self, pc):
        w = self.words(pc, 4)[0]
        assert w & 0xfc000000 == 0x94000000
        return branch(pc, w)[0]


def emit(fixtures):
    bodies, body_ids, patterns = [], {}, []

    def add_body(f, address, size, depth):
        words = f.words(address, size)
        normalized = depth < 2
        digest = hashlib.sha256(canonical(words, address) if normalized else struct.pack('<'+'I'*len(words), *words)).digest()
        edges = []
        if normalized:
            for i, w in enumerate(words):
                b = branch(address+4*i, w)
                if not b or address <= b[0] < address+size:
                    continue
                target = b[0]
                if target in f.by_address:
                    edges.append((i, f.by_address[target], 0))
                else:
                    # Preserve every original dependency interval: named
                    # functions in full, otherwise the original 128-byte stub
                    # contract. Terminal edges additionally authenticate 32
                    # unmasked bytes; never erase a BL without a target check.
                    length = f.sizes.get(target, 128) if depth == 0 else 32
                    assert length <= 8192, (f.names.get(target), length)
                    child = add_body(f, target, length, depth+1)
                    edges.append((i, -1, child))
        key = (len(words), digest, normalized, tuple(edges))
        if key not in body_ids:
            body_ids[key] = len(bodies)
            bodies.append(key)
        return body_ids[key]

    evidence = []
    for f in fixtures:
        ev = {'path': str(f.library), 'sha256': hashlib.sha256(f.elf.data).hexdigest(), 'roles': {}, 'incoming': {}}
        for role, _ in ROLES:
            if role not in f.roles:
                continue
            address, size = f.roles[role]
            body = add_body(f, address, size, 0)
            p = (ROLE_IDS[role], tuple(f.words(address, 16)), body)
            if p not in patterns:
                patterns.append(p)
            ev['roles'][role] = {'rva': address, 'size': size}
        for role in ['Has', 'Should', 'Update', 'Resource']:
            ev['incoming'][role] = f.incoming[f.roles[role][0]]
            cached = 'CacheQuery' in f.roles and 'PreviewUpdate' in f.roles
            expected = 3 if role == 'Should' and cached else 1 if role == 'Resource' and cached else 2
            assert len(ev['incoming'][role]) == expected and all(link for _, link in ev['incoming'][role]), role
        evidence.append(ev)
    out = ['// SPDX-License-Identifier: Apache-2.0',
           '// Generated by generate_contracts.py. No image RVAs or identity whitelist.',
           '// Normalized bodies retain every non-relocation bit; terminal anchors are unmasked.',
           'enum Role : size_t { ' + ', '.join('k'+n for n, _ in ROLES) + ', kPatternCount };']
    edge_data = []
    starts = []
    for count, digest, normalized, edges in bodies:
        starts.append(len(edge_data)); edge_data.extend(edges)
    out += ['constexpr Edge kEdges[] = {']
    out += [f'    {{{i}, {role}, {child}}},' for i, role, child in edge_data]
    out += ['};', 'constexpr Body kBodies[] = {']
    for start, (count, digest, normalized, edges) in zip(starts, bodies):
        dg = ','.join(f'0x{x:02x}' for x in digest)
        out.append(f'    {{{count}, {{{dg}}}, {start}, {len(edges)}, {str(normalized).lower()}}},')
    out += ['};', 'constexpr Pattern kPatterns[] = {']
    for role, prefix, body in patterns:
        words = ','.join(f'0x{x:08x}u' for x in prefix)
        out.append(f'    {{k{ROLES[role][0]}, {{{words}}}, {body}}},')
    out += ['};', '']
    return '\n'.join(out), evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', nargs=2, action='append', required=True, metavar=('LIBAPP', 'DEBUG_ELF'))
    parser.add_argument('--output', type=Path,
                        default=Path(__file__).resolve().parents[3]/'app/src/main/cpp/monet_selection_patterns.h')
    parser.add_argument('--evidence', type=Path, required=True, help='external temporary JSON; contains audit RVAs')
    args = parser.parse_args()
    data, evidence = emit([Fixture(*f) for f in args.fixture])
    args.output.write_text(data)
    args.evidence.write_text(json.dumps(evidence, indent=2)+'\n')
    print(f'generated {args.output}; audit evidence {args.evidence}')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Host-only exact ELF/debugdata oracle. No device or external pool access."""
import lzma
import struct
from pathlib import Path


class Elf:
    def __init__(self, data):
        self.data = data
        h = struct.unpack_from("<16sHHIQQQIHHHHHH", data)
        assert h[0][:7] == b"\x7fELF\x02\x01\x01" and h[2] == 183
        self.loads = [struct.unpack_from("<IIQQQQQQ", data, h[5] + i * h[9])
                      for i in range(h[10])]
        self.sections = [struct.unpack_from("<IIQQQQIIQQ", data, h[6] + i * h[11])
                         for i in range(h[12])]
        s = self.sections[h[13]]
        names = data[s[4]:s[4] + s[5]]
        self.by_name = {names[s[0]:].split(b"\0", 1)[0].decode(): s
                        for s in self.sections}

    def section(self, name):
        s = self.by_name[name]
        return self.data[s[4]:s[4] + s[5]]

    def read(self, va, size):
        for p in self.loads:
            if p[0] == 1 and p[3] <= va and va + size <= p[3] + p[5]:
                at = p[2] + va - p[3]
                return self.data[at:at + size]
        raise ValueError(hex(va))

    def symbols(self):
        out = {}
        for s in self.sections:
            if s[1] not in (2, 11):
                continue
            table = self.sections[s[6]]
            strings = self.data[table[4]:table[4] + table[5]]
            for at in range(s[4], s[4] + s[5], s[9]):
                n, _, _, shndx, value, size = struct.unpack_from("<IBBHQQ", self.data, at)
                if shndx and size:
                    name = strings[n:strings.index(b"\0", n)].decode()
                    out.setdefault(name, set()).add((value, size))
        return {name: sorted(items) for name, items in out.items()}


class Image(Elf):
    def __init__(self, path):
        super().__init__(Path(path).read_bytes())
        self.syms = Elf(lzma.decompress(self.section(".gnu_debugdata"))).symbols()
        self.span = max(p[3] + p[6] for p in self.loads if p[0] == 1)
        self.mapped = bytearray(self.span)
        self.ranges = []
        for p in self.loads:
            if p[0] == 1 and p[1] & 4:
                self.mapped[p[3]:p[3] + p[5]] = self.data[p[2]:p[2] + p[5]]
                self.ranges.append((p[3], p[3] + p[6], bool(p[1] & 1)))

    def words(self, name):
        [(start, size)] = self.syms[name]
        return struct.unpack("<" + "I" * (size // 4), self.read(start, size))


ROLES = (
    ("Highlight", "IconLightConfig.isHighLightEnabled"),
    ("Disabled", "BlurUtilities.isMaterialDisabled"),
    ("Supported", "BlurUtilities.isHyperMaterialSupported"),
    ("Writer", "DeviceConfig.updateIsDefaultIcon"),
    ("Stroke", "IconPainter.convertGlassStrokeTokenToStrokeConfig"),
)


def masks(words, role):
    result = [0xffffffff] * len(words)
    for i, word in enumerate(words):
        if word & 0xfc000000 == 0x94000000:
            result[i] = 0xfc000000
        if word & 0xfc000000 == 0x14000000:
            delta = ((word & 0x3ffffff) ^ 0x2000000) - 0x2000000
            if not 0 <= i + delta < len(words):
                result[i] = 0xfc000000
        if word & 0xffc003e0 == 0x91400360:
            result[i] = 0xffc003ff
            reg = word & 31
            if (i + 1 < len(words) and words[i + 1] >> 5 & 31 == reg
                    and words[i + 1] & 0x3b000000 == 0x39000000):
                result[i + 1] = 0xffc003ff
        if word & 0xffc003e0 == 0xf9400360 and (word >> 10 & 4095) * 8 > 0x100:
            result[i] = 0xffc003ff
        if word & 0xffffffe0 == 0xf9403f40 and i + 1 < len(words):
            reg = word & 31
            if words[i + 1] >> 5 & 31 == reg and words[i + 1] & 0xffc00000 == 0xf9400000:
                result[i + 1] = 0xffc003ff
    relocatable_fields = {"Highlight": (0x5c,), "Supported": (0x60,),
                          "Writer": (0x9c, 0xac, 0xc0)}
    for offset in relocatable_fields.get(role, ()):
        result[offset // 4] = 0xffe00fff
    return result

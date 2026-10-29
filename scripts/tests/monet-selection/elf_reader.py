"""Minimal ELF64 reader for compiler-produced test artifacts (stdlib only)."""
import struct

class Elf:
    def __init__(self, data):
        self.data = data
        h = struct.unpack_from('<16sHHIQQQIHHHHHH', data)
        if h[0][:7] != b'\x7fELF\x02\x01\x01':
            raise ValueError('expected little-endian ELF64')
        self.loads = [struct.unpack_from('<IIQQQQQQ', data, h[5]+i*h[9]) for i in range(h[10])]
        self.sections = [struct.unpack_from('<IIQQQQIIQQ', data, h[6]+i*h[11]) for i in range(h[12])]
        names = self.sections[h[13]]
        strings = data[names[4]:names[4]+names[5]]
        self.by_name = {strings[v[0]:].split(b'\0',1)[0].decode():v for v in self.sections}
    def section(self, name):
        s = self.by_name[name]
        return self.data[s[4]:s[4]+s[5]]
    def read(self, va, size):
        for p in self.loads:
            if p[0] == 1 and p[3] <= va and va+size <= p[3]+p[5]:
                off = p[2]+va-p[3]
                return self.data[off:off+size]
        raise ValueError('outside file-backed LOAD: '+hex(va))
    def symbols(self):
        out = []
        for s in self.sections:
            if s[1] not in (2,11): continue
            t = self.sections[s[6]]
            strings = self.data[t[4]:t[4]+t[5]]
            for off in range(s[4],s[4]+s[5],s[9]):
                n,info,other,shndx,value,size = struct.unpack_from('<IBBHQQ',self.data,off)
                out.append((value,size,strings[n:].split(b'\0',1)[0].decode(),info,shndx))
        return out

#!/usr/bin/env python3
"""Byte-preserving shared ZIP objects and a versioned catalog, built without image codecs.

The .kicons ZIP is stored uncompressed in the APK. Catalog offsets point at independent
raw-DEFLATE payloads inside it, allowing bounded positional reads via AssetFileDescriptor.
Original ZIPs remain optional local inputs; neither OEM artwork nor outputs are committed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import tempfile
import zipfile


def digest_file(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def validate_path(path):
    parts = path.split('/')
    if (not path or len(path) > 1024 or len(parts) > 12 or
            any(x in path for x in ('\\', ':')) or any(ord(x) < 32 or ord(x) == 127 for x in path) or
            any(p in ('', '.', '..') for p in parts)):
        raise ValueError(f'Unsafe asset path: {path!r}')


def pack(inputs, output):
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    objects, profiles = {}, []
    for source in sorted(map(Path, inputs)):
        if not re.fullmatch(r'[a-z0-9-]+\.zip', source.name):
            raise ValueError('Invalid builtin name')
        paths = {}
        with zipfile.ZipFile(source) as archive:
            for entry in archive.infolist():
                if entry.is_dir():
                    continue
                validate_path(entry.filename)
                if entry.filename in paths or entry.file_size > 96 * 1024 * 1024:
                    raise ValueError('Duplicate or oversized builtin entry')
                data = archive.read(entry)
                digest = hashlib.sha256(data).hexdigest()
                previous = objects.setdefault(digest, data)
                if previous != data:
                    raise ValueError('Object digest collision')
                paths[entry.filename] = digest
        if not any(p in paths for p in ('appfilter.xml', 'assets/appfilter.xml', 'res/xml/appfilter.xml')):
            raise ValueError('Builtin must be a classic appfilter archive')
        profiles.append({'key': source.stem, 'id': digest_file(source),
                         'paths': paths})
    if len({p['key'] for p in profiles}) != len(profiles):
        raise ValueError('Duplicate builtin key')
    with tempfile.TemporaryDirectory(prefix='.builtin-', dir=output.parent) as temp:
        stage = Path(temp)
        blob = stage / 'objects.kicons'
        with zipfile.ZipFile(blob, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for digest, data in sorted(objects.items()):
                entry = zipfile.ZipInfo('objects/' + digest, date_time=(1980, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, data)
        table = {}
        with zipfile.ZipFile(blob) as archive, blob.open('rb') as raw:
            for entry in archive.infolist():
                raw.seek(entry.header_offset)
                header = raw.read(30)
                signature, = struct.unpack_from('<I', header)
                name_len, extra_len = struct.unpack_from('<HH', header, 26)
                if signature != 0x04034B50 or entry.flag_bits & 1:
                    raise ValueError('Unexpected ZIP header')
                digest = entry.filename.removeprefix('objects/')
                # Verify the actual stored object before publishing either output.
                if archive.read(entry) != objects[digest]:
                    raise ValueError('Object roundtrip failed')
                table[digest] = [entry.header_offset + 30 + name_len + extra_len,
                                 entry.compress_size, entry.file_size]
        catalog = {'schema': 1, 'blobBytes': blob.stat().st_size,
                   'blobSha256': digest_file(blob),
                   'objects': table, 'packs': profiles}
        encoded = json.dumps(catalog, sort_keys=True, separators=(',', ':')).encode()
        (stage / 'catalog.json').write_bytes(encoded)
        # Gradle owns this output directory; never write back into original packs.
        output.mkdir(exist_ok=True)
        for name in ('objects.kicons', 'catalog.json'):
            shutil.move(str(stage / name), str(output / name))
    return catalog


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack', type=Path, action='append', default=[])
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = pack(args.pack, args.output)
    print(f"Shared builtins: {len(result['packs'])} profiles, {len(result['objects'])} objects, "
          f"{result['blobBytes']} blob bytes")

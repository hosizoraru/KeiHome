"""Shared asset format: exact resource bytes, deterministic identity and seekable objects."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile
import zlib

spec = importlib.util.spec_from_file_location('builtin_pack', Path(__file__).parents[1] / 'pack-builtin-icons.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SharedBuiltinTests(unittest.TestCase):
    def source(self, directory, name, extra=None):
        path = directory / name
        entries = {'appfilter.xml': b'<resources/>', 'res/drawable/icon.png': b'original-png-bytes\0\xff',
                   'coloros-dynamic/clock/manifest.xml': b'<Icon><Image/></Icon>'}
        entries.update(extra or {})
        with zipfile.ZipFile(path, 'w') as archive:
            for key, value in entries.items():
                archive.writestr(key, value)
        return path

    def test_shared_content_and_every_original_path_roundtrip_without_transcoding(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            inputs = [self.source(root, 'one.zip'), self.source(root, 'two.zip', {'res/drawable-night/icon.png': b'night'})]
            catalog = module.pack(inputs, root / 'output')
            self.assertEqual(4, len(catalog['objects']))
            blob = (root / 'output/objects.kicons').read_bytes()
            for pack, original in zip(catalog['packs'], inputs):
                self.assertEqual(hashlib.sha256(original.read_bytes()).hexdigest(), pack['id'])
                with zipfile.ZipFile(original) as archive:
                    for path, digest in pack['paths'].items():
                        offset, compressed, raw = catalog['objects'][digest]
                        data = zlib.decompress(blob[offset:offset + compressed], -15)
                        self.assertEqual(raw, len(data))
                        self.assertEqual(archive.read(path), data)
            self.assertEqual(catalog, json.loads((root / 'output/catalog.json').read_text()))

    def test_order_independent_deterministic_output_and_originals_unchanged(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            inputs = [self.source(root, 'one.zip'), self.source(root, 'two.zip')]
            originals = [p.read_bytes() for p in inputs]
            module.pack(inputs, root / 'a')
            module.pack(list(reversed(inputs)), root / 'b')
            for name in ('objects.kicons', 'catalog.json'):
                self.assertEqual((root / 'a' / name).read_bytes(), (root / 'b' / name).read_bytes())
            self.assertEqual(originals, [p.read_bytes() for p in inputs])

    def test_empty_optional_catalog(self):
        with tempfile.TemporaryDirectory() as temp:
            result = module.pack([], Path(temp) / 'output')
            self.assertEqual([], result['packs'])
            self.assertEqual({}, result['objects'])

    def test_reject_unsafe_paths(self):
        for name in ('../icon', '/icon', 'res//icon', 'res/../icon', 'a\\b', 'a:b', 'a\0b'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                module.validate_path(name)

    def test_duplicate_paths_fail_without_replacing_previous_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.source(root, 'one.zip')
            module.pack([source], root / 'output')
            previous = (root / 'output/catalog.json').read_bytes()
            with warnings.catch_warnings(), zipfile.ZipFile(source, 'a') as archive:
                warnings.simplefilter('ignore', UserWarning)
                archive.writestr('appfilter.xml', b'duplicate')
            with self.assertRaises(ValueError):
                module.pack([source], root / 'output')
            self.assertEqual(previous, (root / 'output/catalog.json').read_bytes())

    def test_reject_duplicate_keys_and_nonclassic_inputs(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = self.source(root, 'one.zip')
            with self.assertRaises(ValueError):
                module.pack([source, source], root / 'output')
            with zipfile.ZipFile(source, 'w') as archive:
                archive.writestr('unrelated', b'value')
            with self.assertRaises(ValueError):
                module.pack([source], root / 'output')


if __name__ == '__main__':
    unittest.main()

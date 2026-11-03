"""Catalog contract checks; no device, network, or OEM artwork required."""
import importlib.util
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("fetch_coloros", Path(__file__).resolve().parents[1] / "fetch-coloros-runtime-icons.py")
FETCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FETCH)


class FetchColorOsRuntimeIconsTest(unittest.TestCase):
    def catalog(self, whitelist, rows):
        with tempfile.TemporaryDirectory() as scratch:
            database = Path(scratch) / "icons.db"
            with closing(sqlite3.connect(database)) as db:
                db.execute("CREATE TABLE icon(package_name TEXT,status INTEGER,aid_status TEXT)")
                db.executemany("INSERT INTO icon VALUES(?,?,?)", rows)
                db.commit()
            return FETCH.catalog(whitelist, database)

    def test_unions_catalog_and_whitelist_without_losing_database_only_art(self):
        packages, report = self.catalog("""<icons><version>7</version>
          <package_name> com.example.white </package_name><package_name>com.example.white</package_name>
          <package_name> </package_name><package_name>android</package_name>
          <package_name>com.invalid/escape</package_name></icons>""",
          [("com.example.downloaded", 1, "published"), ("oplus", 0, "default")])
        self.assertEqual(packages, ["com.example.downloaded", "com.example.white"])
        self.assertEqual(report["whitelistVersion"], "7")
        self.assertEqual(report["nativeStates"], {"com.example.downloaded": {"status": 1, "state": "published"}})
        self.assertEqual(report["excludedInternalNames"], ["android", "com.invalid/escape", "oplus"])

    def test_refuses_empty_or_unbounded_native_catalog(self):
        for rows in ([], [(f"com.example.p{i}", 0, "default") for i in range(5001)]):
            with self.subTest(size=len(rows)), self.assertRaises(ValueError):
                self.catalog("<icons/>", rows)

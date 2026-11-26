#!/usr/bin/env python3
"""Reject a bundled scheme that cannot compile its native rewrite resources."""
import importlib.util
import io
from pathlib import Path
import unittest
import zipfile

spec = importlib.util.spec_from_file_location(
    "bundle_wanxiang_scheme", Path(__file__).with_name("bundle-wanxiang-scheme.py"))
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class BundleValidationTest(unittest.TestCase):
    def archive(self, omit=(), empty=()):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as archive:
            for name in bundle.REQUIRED - set(omit):
                body = b"fixture"
                if name == "default.yaml":
                    body = b"schema_list:\n  - schema: wanxiang\n"
                elif name == "wanxiang.schema.yaml":
                    body = b"input_types: {}\n"
                archive.writestr(name, b"" if name in empty else body)
        return zipfile.ZipFile(data)

    def test_missing_charset_rejected(self):
        with self.archive(omit={"data/charset.txt"}) as archive:
            with self.assertRaisesRegex(ValueError, "data/charset.txt"):
                bundle.validate_zip(archive)

    def test_empty_charset_rejected(self):
        with self.archive(empty={"data/charset.txt"}) as archive:
            with self.assertRaisesRegex(ValueError, "data/charset.txt"):
                bundle.validate_zip(archive)

    def test_all_required_resources(self):
        with self.archive() as archive:
            self.assertEqual(len(bundle.REQUIRED), bundle.validate_zip(archive))


if __name__ == "__main__":
    unittest.main()

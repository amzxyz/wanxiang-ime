import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("release_version", Path(__file__).with_name("validate-release-version.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReleaseVersionTest(unittest.TestCase):
    def test_read_version(self):
        self.assertEqual(module.read_version('const val baseVersionName = "0.1.3"\nconst val baseVersionCode = 11\n'), ("0.1.3", 11))

    def test_invalid_version_or_code(self):
        for name, code in [("0.01.3", 11), ("0.1.3-dev", 11), ("0.1.3", 0), ("0.1.3", 210000000)]:
            with self.subTest(name=name, code=code), self.assertRaises(ValueError):
                module.read_version(f'const val baseVersionName = "{name}"\nconst val baseVersionCode = {code}')

    def test_tag_matches_source(self):
        module.validate_release(("0.1.3", 11), "v0.1.3", [])
        with self.assertRaisesRegex(ValueError, "does not match"):
            module.validate_release(("0.1.3", 11), "v0.1.4", [])

    def test_monotonic_release(self):
        previous = [("v0.1.9", ("0.1.9", 11))]
        module.validate_release(("0.1.10", 12), "v0.1.10", previous)
        with self.assertRaisesRegex(ValueError, "baseVersionCode"):
            module.validate_release(("0.1.10", 11), "v0.1.10", previous)
        with self.assertRaisesRegex(ValueError, "baseVersionName"):
            module.validate_release(("0.1.8", 12), "v0.1.8", previous)

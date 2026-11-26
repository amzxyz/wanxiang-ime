#!/usr/bin/env python3
"""Validate the release tag and Android upgrade order against Versions.kt."""
import argparse
import re
import subprocess
import sys
from pathlib import Path

VERSION_FILE = "build-logic/convention/src/main/kotlin/Versions.kt"
VERSION_RE = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
TAG_RE = re.compile("v(" + VERSION_RE + ")")


def read_version(source):
    names = re.findall(r'^\s*const val baseVersionName\s*=\s*"([^"]+)"\s*$', source, re.M)
    codes = re.findall(r'^\s*const val baseVersionCode\s*=\s*([0-9]+)\s*$', source, re.M)
    if len(names) != 1 or not re.fullmatch(VERSION_RE, names[0]):
        raise ValueError("Versions.kt must define one baseVersionName in X.Y.Z form")
    if len(codes) != 1 or not 1 <= int(codes[0]) <= 209_999_999:
        raise ValueError("Versions.kt must define a positive baseVersionCode within Android limits")
    return names[0], int(codes[0])


def version_tuple(version):
    return tuple(map(int, version.split(".")))


def validate_release(current, tag, previous):
    version, code = current
    if tag != "v" + version:
        raise ValueError(f"Release tag {tag} does not match Versions.kt (expected v{version})")
    for old_tag, (old_version, old_code) in previous:
        if old_tag != "v" + old_version:
            raise ValueError(f"Previous release {old_tag} has inconsistent version metadata")
        if version_tuple(version) <= version_tuple(old_version):
            raise ValueError(f"baseVersionName must increase beyond {old_version} ({old_tag})")
        if code <= old_code:
            raise ValueError(f"baseVersionCode must increase beyond {old_code} ({old_tag})")


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="Release tag; omit for a manual build")
    args = parser.parse_args()
    try:
        current = read_version(Path(VERSION_FILE).read_text(encoding="utf-8"))
        if args.tag:
            previous = []
            for tag in git("tag", "--merged", "HEAD").splitlines():
                if tag == args.tag or not TAG_RE.fullmatch(tag):
                    continue
                previous.append((tag, read_version(git("show", f"refs/tags/{tag}:{VERSION_FILE}"))))
            validate_release(current, args.tag, previous)
        print(f"value={current[0]}")
        print(f"code={current[1]}")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Release version validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

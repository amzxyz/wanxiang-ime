#!/usr/bin/env python3
"""Package the EVO-specific Wanxiang scheme without models or user state."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
import zipfile

LOCK = Path("native/wanxiang-scheme.lock.json")
REQUIRED = {"default.yaml", "wanxiang.schema.yaml", "wanxiang.dict.yaml",
            "aux_code.yaml", "wanxiang_algebra.yaml", "dicts/zi.dict.yaml",
            "dicts/jichu.dict.yaml"}


def allowed(name):
    p = PurePosixPath(name)
    if p.is_absolute() or ".." in p.parts or "\\" in name or not p.parts:
        return False
    if any(part.startswith(".") or part in {"build", "sync", "userdb"} or
           part.endswith((".userdb", ".userdb.txt")) for part in p.parts):
        return False
    if name.endswith(".custom.yaml") or p.name in {"user.yaml", "installation.yaml"}:
        return False
    if len(p.parts) == 1:
        return p.suffix == ".yaml"
    return p.parts[0] in {"dicts", "data", "lua"} and (
        p.suffix in {".yaml", ".lua", ".txt", ".json"} or name == "lua/data/charset.reverse.bin")


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_zip(archive):
    infos = archive.infolist()
    names = [i.filename for i in infos]
    assert len(names) == len(set(names)), "duplicate scheme entries"
    assert REQUIRED <= set(names), "scheme missing: " + str(REQUIRED - set(names))
    assert all(allowed(n) for n in names), "unexpected scheme file"
    assert sum(i.file_size for i in infos) <= 512 * 1024 * 1024, "scheme too large"
    assert archive.testzip() is None, "invalid scheme ZIP"
    assert b"schema: wanxiang" in archive.read("default.yaml"), "default scheme not enabled"
    assert b"input_types:" in archive.read("wanxiang.schema.yaml"), "not the EVO-specific scheme"
    return len(names)


def build(source, output, locked):
    lock = json.loads(LOCK.read_text())
    commit = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if locked and commit != lock["commit"]:
        raise ValueError(f"Expected scheme {lock['commit']}, got {commit}")
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output) as temporary:
        raw = Path(temporary) / "source.zip"
        subprocess.run(["git", "-C", str(source), "archive", "--format=zip",
                        "--output=" + str(raw.resolve()), commit], check=True)
        packed = Path(temporary) / "wanxiang.zip"
        with zipfile.ZipFile(raw) as original, zipfile.ZipFile(packed, "w",
                compression=zipfile.ZIP_DEFLATED, compresslevel=9) as dest:
            for info in sorted(original.infolist(), key=lambda i: i.filename):
                if info.is_dir() or not allowed(info.filename):
                    continue
                if (info.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError("Symlink in scheme: " + info.filename)
                fixed = zipfile.ZipInfo(info.filename, (1980, 1, 1, 0, 0, 0))
                fixed.compress_type = zipfile.ZIP_DEFLATED
                fixed.external_attr = 0o100644 << 16
                dest.writestr(fixed, original.read(info), compresslevel=9)
        with zipfile.ZipFile(packed) as archive:
            count = validate_zip(archive)
        manifest = {"repository": lock["repository"], "branch": lock["branch"],
                    "commit": commit, "sha256": sha(packed), "files": count}
        packed.replace(output / "wanxiang.zip")
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest))


def verify(apk, bundle):
    manifest = json.loads((bundle / "manifest.json").read_text())
    with zipfile.ZipFile(apk) as archive:
        packed = archive.read("assets/bundled/rime/wanxiang.zip")
        actual = json.loads(archive.read("assets/bundled/rime/manifest.json"))
        assert actual == manifest, "APK scheme provenance mismatch"
        assert hashlib.sha256(packed).hexdigest() == manifest["sha256"], "APK scheme checksum mismatch"
        descriptor = json.loads(archive.read("assets/descriptor.json"))
        assert not any(n.startswith("bundled/rime/") for n in descriptor["files"]), "scheme must not enter shared data sync"
    print(f"Verified bundled Wanxiang {manifest['commit']} in {apk}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--output", type=Path, default=Path("native/wanxiang-bundle"))
    parser.add_argument("--locked", action="store_true")
    parser.add_argument("--verify-apk", type=Path)
    args = parser.parse_args()
    if args.verify_apk:
        verify(args.verify_apk, args.output)
    elif args.source:
        build(args.source, args.output, args.locked)
    else:
        parser.error("--source or --verify-apk is required")

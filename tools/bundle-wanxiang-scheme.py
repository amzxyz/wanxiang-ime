#!/usr/bin/env python3
"""Package the EVO-specific Wanxiang scheme without models or user state."""
import argparse
import hashlib
import io
import json
import os
import re
import shutil
from pathlib import Path, PurePosixPath
import subprocess
import tempfile
import zipfile

SOURCE = Path("native/wanxiang-scheme.json")
REQUIRED = {"default.yaml", "wanxiang.schema.yaml", "wanxiang.dict.yaml",
            "aux_code.yaml", "wanxiang_algebra.yaml", "dicts/zi.dict.yaml",
            "dicts/jichu.dict.yaml"}
# Inputs of the default schema's native Rewrite compiler, not generated binaries.
REWRITE_SOURCES = {"data/charset.txt", "data/emoji.txt", "data/abbrev.txt",
                   "data/sentence.txt", "data/STPhrases.txt", "data/STCharacters.txt",
                   "data/HKVariants.txt", "data/TWVariants.txt"}
REQUIRED |= REWRITE_SOURCES


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
    missing = REQUIRED - set(names)
    if missing:
        raise ValueError("scheme missing required files: " + ", ".join(sorted(missing)))
    empty = sorted(info.filename for info in infos
                   if info.filename in REQUIRED and info.file_size == 0)
    if empty:
        raise ValueError("scheme has empty required files: " + ", ".join(empty))
    assert all(allowed(n) for n in names), "unexpected scheme file"
    assert sum(i.file_size for i in infos) <= 512 * 1024 * 1024, "scheme too large"
    assert archive.testzip() is None, "invalid scheme ZIP"
    assert b"schema: wanxiang" in archive.read("default.yaml"), "default scheme not enabled"
    assert b"input_types:" in archive.read("wanxiang.schema.yaml"), "not the EVO-specific scheme"
    return len(names)


def source_config():
    config = json.loads(SOURCE.read_text())
    if config != {"repository": "amzxyz/rime-wanxiang",
                  "branch": "rime-wanxiang-for-librime-evo"}:
        raise ValueError("Unexpected Wanxiang scheme source")
    return config


def resolve():
    config = source_config()
    ref = "refs/heads/" + config["branch"]
    result = subprocess.check_output(
        ["git", "ls-remote", "--exit-code", "https://github.com/" + config["repository"] + ".git", ref],
        text=True,
    ).strip().splitlines()
    if len(result) != 1:
        raise ValueError("Expected exactly one scheme branch ref")
    commit, actual_ref = result[0].split("\t")
    if actual_ref != ref or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid scheme branch ref")
    return commit


def check_commit(commit, expected):
    if not re.fullmatch(r"[0-9a-f]{40}", commit) or (expected is not None and commit != expected):
        raise ValueError(f"Expected scheme {expected}, got {commit}")


def optimize_zip(packed):
    """Re-encode the validated ZIP with 7-Zip's standard Deflate encoder."""
    encoder = shutil.which("7z") or shutil.which("7zz")
    if encoder is None:
        raise RuntimeError("7-Zip is required for high-compression scheme builds")
    with tempfile.TemporaryDirectory(dir=packed.parent) as temporary:
        root = Path(temporary).resolve()
        stage = root / "files"
        stage.mkdir()
        candidate = root / "optimized.zip"
        with zipfile.ZipFile(packed) as original:
            validate_zip(original)
            entries = {i.filename: (i.file_size, i.CRC) for i in original.infolist()}
            for name in sorted(entries):
                target = stage / name
                target.parent.mkdir(parents=True, exist_ok=True)
                with original.open(name) as source, target.open("wb") as dest:
                    shutil.copyfileobj(source, dest)
                target.chmod(0o644)
                os.utime(target, (315532800, 315532800))
        names = sorted(entries)
        if any("\n" in name or "\r" in name for name in names):
            raise ValueError("Invalid scheme entry for 7-Zip list file")
        listing = root / "files.txt"
        listing.write_text("\n".join(names) + "\n", encoding="utf-8")
        subprocess.run(
            [encoder, "a", "-tzip", "-mm=Deflate", "-mx=9", "-mfb=258",
             "-mpass=15", "-mmt=1", "-mtc=off", "-mta=off", "-mcu=on",
             "-scsUTF-8", "-spd", str(candidate), "@" + str(listing)],
            cwd=stage, env={**os.environ, "TZ": "UTC"}, check=True)
        with zipfile.ZipFile(candidate) as archive:
            validate_zip(archive)
            assert all(i.compress_type in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}
                       for i in archive.infolist()), "unsupported scheme compression method"
            assert entries == {i.filename: (i.file_size, i.CRC) for i in archive.infolist()}, (
                "7-Zip changed scheme entries")
        before, after = packed.stat().st_size, candidate.stat().st_size
        selected = after < before
        if selected:
            candidate.replace(packed)
        report = (f"Wanxiang scheme ZIP: Python Deflate-9 {before:,} bytes; "
                  f"7-Zip Deflate {after:,} bytes; "
                  f"selected {'7-Zip' if selected else 'Python'} "
                  f"(saved {before - min(before, after):,} bytes, "
                  f"{100 * (before - min(before, after)) / before:.2f}%)")
        print(report)
        if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(summary, "a", encoding="utf-8") as stream:
                stream.write(report + "\n")


def build(source, output, expected, high_compression=False):
    config = source_config()
    commit = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    check_commit(commit, expected)
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
        if high_compression:
            optimize_zip(packed)
        with zipfile.ZipFile(packed) as archive:
            count = validate_zip(archive)
        manifest = {"repository": config["repository"], "branch": config["branch"],
                    "commit": commit, "sha256": sha(packed), "files": count}
        packed.replace(output / "wanxiang.zip")
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest))


def verify(apk, expected, bundle=None):
    config = source_config()
    with zipfile.ZipFile(apk) as archive:
        packed = archive.read("assets/bundled/rime/wanxiang.zip")
        manifest = json.loads(archive.read("assets/bundled/rime/manifest.json"))
        check_commit(manifest["commit"], expected)
        assert all(manifest[k] == v for k, v in config.items()), "APK scheme source mismatch"
        if bundle is not None:
            assert manifest == json.loads((bundle / "manifest.json").read_text()), "APK scheme provenance mismatch"
        assert hashlib.sha256(packed).hexdigest() == manifest["sha256"], "APK scheme checksum mismatch"
        with zipfile.ZipFile(io.BytesIO(packed)) as scheme:
            assert validate_zip(scheme) == manifest["files"], "APK scheme file count mismatch"
        descriptor = json.loads(archive.read("assets/descriptor.json"))
        assert not any(n.startswith("bundled/rime/") for n in descriptor["files"]), "scheme must not enter shared data sync"
    print(f"Verified bundled Wanxiang {manifest['commit']} in {apk}")
    return manifest


def verify_release(directory, expected):
    apks = sorted(directory.glob("wanxiang-*.apk"))
    if len(apks) != 4:
        raise ValueError("Expected four release APKs")
    manifests = [verify(apk, expected) for apk in apks]
    if any(manifest != manifests[0] for manifest in manifests[1:]):
        raise ValueError("Release APKs contain different scheme bundles")
    (directory / "wanxiang-scheme-manifest.json").write_text(
        json.dumps(manifests[0], indent=2) + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path)
    parser.add_argument("--output", type=Path, default=Path("native/wanxiang-bundle"))
    parser.add_argument("--resolve", action="store_true")
    parser.add_argument("--expected-commit")
    parser.add_argument("--high-compression", action="store_true",
                        help="Use 7-Zip Deflate optimization (always enabled in GitHub Actions)")
    parser.add_argument("--verify-apk", type=Path)
    parser.add_argument("--verify-release", type=Path)
    args = parser.parse_args()
    if args.resolve:
        print(resolve())
    elif args.verify_release:
        if not args.expected_commit:
            parser.error("--verify-release requires --expected-commit")
        verify_release(args.verify_release, args.expected_commit)
    elif args.verify_apk:
        verify(args.verify_apk, args.expected_commit, args.output)
    elif args.source:
        build(args.source, args.output, args.expected_commit,
              high_compression=args.high_compression or os.environ.get("GITHUB_ACTIONS") == "true")
    else:
        parser.error("--resolve, --source, --verify-apk or --verify-release is required")

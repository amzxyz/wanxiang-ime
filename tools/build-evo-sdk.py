#!/usr/bin/env python3
"""Build the current EVO core, reusing only verified pinned dependency archives."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

BOOST_URL = "https://github.com/boostorg/boost/releases/download/boost-1.92.0/boost-1.92.0-cmake.tar.xz"
BOOST_SHA = "9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d"
NDK = "28.0.13004108"
CMAKE = "3.31.6"
ABI_TARGETS = {
    "arm64-v8a": "aarch64-linux-android", "armeabi-v7a": "armv7a-linux-androideabi",
    "x86": "i686-linux-android", "x86_64": "x86_64-linux-android",
}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def prepare_boost(work):
    archive = work / "boost.tar.xz"
    with urllib.request.urlopen(BOOST_URL, timeout=120) as response, archive.open("wb") as output:
        shutil.copyfileobj(response, output)
    if digest(archive) != BOOST_SHA:
        raise RuntimeError("Boost source checksum mismatch")
    unpacked = work / "boost-src"
    unpacked.mkdir()
    with tarfile.open(archive) as source:
        source.extractall(unpacked, filter="data")
    include = work / "boost" / "include"
    include.mkdir(parents=True)
    for directory in sorted(unpacked.glob("*/libs/**/include/boost")):
        shutil.copytree(directory, include / "boost", dirs_exist_ok=True)
    if not (include / "boost/version.hpp").is_file():
        raise RuntimeError("Boost headers were not assembled")
    return include


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core", type=Path, required=True)
    parser.add_argument("--dependencies", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--abi", choices=ABI_TARGETS, required=True)
    args = parser.parse_args()
    core, deps, output = (p.resolve() for p in (args.core, args.dependencies, args.output))
    if output.exists():
        raise RuntimeError("EVO SDK output must be a new directory")
    abi = args.abi
    commit = git(core, "rev-parse", "HEAD")
    tree = git(core, "rev-parse", "HEAD^{tree}")
    plugin = core / "plugins/lua"
    lua_commit = git(plugin, "rev-parse", "HEAD")
    sdk = Path(os.environ["ANDROID_HOME"])
    ndk = sdk / "ndk" / NDK
    cmake = sdk / "cmake" / CMAKE / "bin/cmake"
    tools = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
    target_cc = tools / (ABI_TARGETS[abi] + "23-clang")
    if not target_cc.is_file():
        raise RuntimeError(f"Missing Android compiler: {target_cc}")

    dependency_manifest = json.loads((deps / "sdk-manifest.json").read_text())
    old_source = json.loads((deps / "librime" / abi / "share/rime/source-manifest.json").read_text())
    dependency_names = {"leveldb": "libleveldb.a", "marisa": "libmarisa.a", "yaml-cpp": "libyaml-cpp.a"}
    gitlinks = {"leveldb": "deps/leveldb", "marisa": "deps/marisa-trie", "yaml-cpp": "deps/yaml-cpp"}
    for name, submodule in gitlinks.items():
        actual = git(core, "rev-parse", "HEAD:" + submodule)
        if old_source["sources"][name]["commit"] != actual:
            raise RuntimeError(f"Dependency {name} differs from EVO gitlink {actual}; update the dependency SDK")
        relative = f"{name}/{abi}/lib/{dependency_names[name]}"
        if digest(deps / relative) != dependency_manifest["files"][relative]:
            raise RuntimeError(f"Dependency checksum mismatch: {relative}")

    # LuaJIT invokes GNU make, not the Android CMake toolchain. Supply the
    # NDK's target/API-specific compiler wrapper; the host compiler remains
    # native for generated VM tools. This modifies only the CI checkout.
    template = plugin / "cmake/BuildLuaJIT.cmake.in"
    original = template.read_text()
    old = '"CC=\\"@CMAKE_C_COMPILER@\\""'
    new = '"CC=\\"@LUAJIT_ANDROID_CC@\\""'
    if original.count(old) != 1 or original.count('"TARGET_SYS=@CMAKE_SYSTEM_NAME@"') != 1:
        raise RuntimeError("LuaJIT cross-build recipe changed; review Android adaptation")
    template.write_text(original.replace(old, new).replace(
        '"TARGET_SYS=@CMAKE_SYSTEM_NAME@"', '"TARGET_SYS=Linux"'))
    patch_digest = digest(template)

    with tempfile.TemporaryDirectory(prefix="evo-android-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        work = Path(temporary)
        boost = prepare_boost(work)
        build = work / "build"
        install = work / "install"
        flags = []
        for name, variable in (("leveldb", "LevelDb"), ("marisa", "Marisa"), ("yaml-cpp", "YamlCpp")):
            prefix = deps / name / abi
            flags.extend([f"-D{variable}_INCLUDE_PATH={prefix / 'include'}",
                          f"-D{variable}_LIBRARY={prefix / 'lib' / dependency_names[name]}"])
        flags.append(f"-DYamlCpp_NEW_API={deps / 'yaml-cpp' / abi / 'include'}")
        run(cmake, "-S", core, "-B", build, "-G", "Ninja",
            "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
            f"-DCMAKE_TOOLCHAIN_FILE={ndk / 'build/cmake/android.toolchain.cmake'}",
            f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=23", "-DANDROID_STL=c++_shared",
            "-DBUILD_SHARED_LIBS=OFF", "-DBUILD_STATIC=ON", "-DBUILD_MERGED_PLUGINS=ON",
            "-DBUILD_TEST=OFF", "-DBUILD_DATA=OFF", "-DALSO_LOG_TO_STDERR=ON",
            f"-DCMAKE_INSTALL_PREFIX={install}", "-DCMAKE_INSTALL_LIBDIR=lib",
            f"-DBoost_INCLUDE_DIR={boost}", "-DBoost_NO_BOOST_CMAKE=ON",
            f"-DLUAJIT_ANDROID_CC={target_cc}", "-DCMAKE_CXX_FLAGS=-DBOOST_ALL_NO_EMBEDDED_GDB_SCRIPTS",
            *flags)
        run(cmake, "--build", build, "--parallel", "2", "--target", "rime-static", "spdlog")
        run(cmake, "--install", build)
        header = install / "include/rime_api.h"
        if "#define RIME_EVO_CODE_CHOICES 1" not in header.read_text():
            raise RuntimeError("EVO source lacks the code-choice API")
        # Only dependencies from the old SDK are retained, never its core,
        # Lua runtime or OpenCC archive. EVO embeds its own LuaJIT objects.
        for name in dependency_names:
            shutil.copytree(deps / name / abi, output / name / abi)
        shutil.copytree(deps / "opencc/data", output / "opencc/data")
        shutil.copytree(deps / "licenses", output / "licenses")
        shutil.copy2(core / "LICENSE", output / "licenses/librime-LICENSE")
        shutil.copytree(install / "include", output / "librime" / abi / "include")
        libdir = output / "librime" / abi / "lib"
        libdir.mkdir(parents=True)
        shutil.copy2(install / "lib/librime.a", libdir / "librime.a")
        spdlog = list(build.rglob("libspdlog.a"))
        if len(spdlog) != 1:
            raise RuntimeError("Cannot locate freshly built spdlog archive")
        spdlog_dir = output / "spdlog" / abi / "lib"
        spdlog_dir.mkdir(parents=True)
        shutil.copy2(spdlog[0], spdlog_dir / "libspdlog.a")
        shutil.copytree(core / "deps/spdlog/include", output / "spdlog" / abi / "include")
        archives = [libdir / "librime.a", spdlog_dir / "libspdlog.a"] + [
            output / name / abi / "lib" / filename for name, filename in dependency_names.items()
        ]
        for archive in archives:
            run(tools / "llvm-strip", "--strip-unneeded", archive)
        probe = work / "probe.cc"
        probe.write_text('extern "C" void* rime_get_api();\nextern "C" void* sdk_api() { return rime_get_api(); }\n')
        run(tools / (ABI_TARGETS[abi] + "23-clang++"), "-shared", "-fPIC", probe,
            "-Wl,--no-undefined", "-Wl,--whole-archive", archives[0], "-Wl,--no-whole-archive",
            *archives[1:], "-llog", "-landroid", "-ldl", "-lm", "-o", work / "probe.so")
    provenance = output / "librime" / abi / "share/rime/source-manifest.json"
    provenance.parent.mkdir(parents=True)
    provenance.write_text(json.dumps({
        "librime_repository": "amzxyz/librime-evo", "librime_commit": commit,
        "source_tree": tree, "lua_commit": lua_commit, "lua_android_recipe_sha256": patch_digest,
        "abi": abi, "ndk": NDK, "cmake": CMAKE, "boost_sha256": BOOST_SHA,
        "dependency_sdk": dependency_manifest["librime_commit"],
        "link_smoke_test": "passed",
    }, indent=2) + "\n")
    files = {p.relative_to(output).as_posix(): digest(p) for p in sorted(output.rglob("*")) if p.is_file()}
    (output / "sdk-manifest.json").write_text(json.dumps({
        "schema_version": 1, "engine_family": "evo", "development": True,
        "librime_commit": commit, "upstream_commit": commit, "source_tree": tree, "abis": [abi],
        "sources_lock_sha256": dependency_manifest["sources_lock_sha256"], "files": files,
    }, indent=2) + "\n")
    print(f"Built EVO {commit}, Lua {lua_commit}, {abi}: {output}")


if __name__ == "__main__":
    main()

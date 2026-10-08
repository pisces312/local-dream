#!/usr/bin/env python3
"""Record which commit and toolchain produced a native artifact.

The app cannot ask the .so files who built them: libstable_diffusion_core.so is
spawned as an executable and libdit_engine.so is dlopen'ed by it, so nothing on
the Java side ever loads either one. What they *do* carry is an ELF build-id --
a content hash the linker bakes in, which survives the strip AGP applies while
packaging the APK. This script pairs that fingerprint with the git state and
tool versions of the build that produced it, and writes the pairing next to the
APK's other assets, so the running app can report exactly what it shipped.

Run from a native build script once the .so files are staged for packaging:

    python3 tools/collect-build-info.py \\
        --name dit-engine \\
        --out app/src/main/assets/build-info/dit-engine.json \\
        --abi-version 3 \\
        --toolchain-path hexagonSdk=/opt/hexagon-sdk-v6.6.0.0 \\
        --toolchain cmake=3.28.3 \\
        --artifact app/src/main/jniLibs/arm64-v8a/libdit_engine.so

Exit code is non-zero only on real errors; an artifact without a build-id (the
Hexagon DSP skels are linked without one) is recorded with buildId: null.
"""

import argparse
import datetime
import hashlib
import json
import os
import re
import struct
import subprocess

ELF_MAGIC = b"\x7fELF"
PT_NOTE = 4
NT_GNU_BUILD_ID = 3
NT_ANDROID_IDENT = 1

# .note.android.ident describes the toolchain that linked the library:
#   uint32 sdk_version; char ndk_version[64]; char ndk_build_number[64];
ANDROID_IDENT_VERSION_OFFSET = 4
ANDROID_IDENT_BUILD_OFFSET = 68
ANDROID_IDENT_BUILD_LENGTH = 64


def parse_notes(buf, endian):
    """Decode every note packed into one PT_NOTE segment."""
    notes = []
    offset = 0
    size = len(buf)
    while offset + 12 <= size:
        namesz, descsz, ntype = struct.unpack_from(endian + "III", buf, offset)
        offset += 12
        if namesz == 0 or offset + namesz > size:
            break
        name = buf[offset:offset + namesz].split(b"\0")[0].decode("ascii", "replace")
        offset += (namesz + 3) & ~3
        if offset + descsz > size:
            break
        desc = buf[offset:offset + descsz]
        offset += (descsz + 3) & ~3
        notes.append((name, ntype, desc))
    return notes


def read_notes(path):
    """Collect PT_NOTE notes from an ELF file, 32- and 64-bit alike.

    The DSP skels are ELF32; the Android libraries are ELF64. Both carry their
    program header table at a different offset and use different field widths,
    so the layout is chosen from e_ident rather than assumed.
    """
    notes = []
    with open(path, "rb") as fh:
        ident = fh.read(16)
        if ident[:4] != ELF_MAGIC:
            return notes
        is64 = ident[4] == 2
        endian = "<" if ident[5] == 1 else ">"
        word = "Q" if is64 else "I"
        word_size = 8 if is64 else 4

        fh.seek(0x20 if is64 else 0x1C)
        phoff = struct.unpack(endian + word, fh.read(word_size))[0]
        fh.seek(0x36 if is64 else 0x2A)
        phentsize, phnum = struct.unpack(endian + "HH", fh.read(4))

        for index in range(phnum):
            base = phoff + index * phentsize
            fh.seek(base)
            ptype = struct.unpack(endian + "I", fh.read(4))[0]
            if ptype != PT_NOTE:
                continue
            # p_offset and p_filesz sit at different ph offsets per layout.
            fh.seek(base + (8 if is64 else 4))
            p_offset = struct.unpack(endian + word, fh.read(word_size))[0]
            fh.seek(base + (32 if is64 else 16))
            p_filesz = struct.unpack(endian + word, fh.read(word_size))[0]
            fh.seek(p_offset)
            notes.extend(parse_notes(fh.read(p_filesz), endian))
    return notes


def version_from_path(path):
    """Reduce an SDK install directory to its version, for the manifest.

    Callers pass the SDK path as-is, in whatever form their shell uses --
    "D:\\dev\\qairt\\2.50.0.260828" under Git Bash, "/opt/hexagon-sdk-v6.6.0.0"
    under WSL. Splitting it here keeps the shell out of the business of
    escaping backslashes, which is where this would otherwise go wrong.
    """
    name = path.replace("\\", "/").rstrip("/").rsplit("/", 1)[-1]
    match = re.search(r"[vV]?\d[\d.]*$", name)
    return match.group(0).lstrip("vV") if match else name


def ndk_toolchain(desc):
    """Render a .note.android.ident payload as "r29 (142068635)"."""
    if len(desc) < ANDROID_IDENT_BUILD_OFFSET:
        return None
    version = desc[ANDROID_IDENT_VERSION_OFFSET:ANDROID_IDENT_BUILD_OFFSET]
    version = version.split(b"\0")[0].decode("ascii", "replace").strip()
    if not version:
        return None
    end = ANDROID_IDENT_BUILD_OFFSET + ANDROID_IDENT_BUILD_LENGTH
    build = desc[ANDROID_IDENT_BUILD_OFFSET:end].split(b"\0")[0]
    build = build.decode("ascii", "replace").strip()
    return "{} ({})".format(version, build) if build else version


def describe(path):
    """Fingerprint one artifact: what it is, and what the file itself says."""
    entry = {
        "name": os.path.basename(path),
        "size": os.path.getsize(path),
        "buildId": None,
        "ndk": None,
    }
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    entry["sha256"] = digest.hexdigest()

    for name, ntype, desc in read_notes(path):
        if name == "GNU" and ntype == NT_GNU_BUILD_ID:
            entry["buildId"] = desc.hex()
        elif name == "Android" and ntype == NT_ANDROID_IDENT:
            entry["ndk"] = ndk_toolchain(desc)
    return entry


def git_state(repo):
    """Commit the build came from, and whether the tree had uncommitted work."""

    def run(*args):
        try:
            result = subprocess.run(
                ["git", "-C", repo] + list(args),
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                check=True,
            )
            return result.stdout.decode("utf-8", "replace").strip()
        except (OSError, subprocess.CalledProcessError):
            return None

    commit = run("rev-parse", "HEAD")
    # The upstream in-place patches leave the vendored submodules permanently
    # dirty, so only the main working tree counts as a build-state signal.
    status = run("status", "--porcelain", "--ignore-submodules=dirty")
    return {
        "commit": commit,
        "commitShort": commit[:12] if commit else None,
        "dirty": bool(status),
    }


def main():
    parser = argparse.ArgumentParser(
        description="Write a build-provenance manifest for native artifacts."
    )
    parser.add_argument("--name", required=True, help="manifest id, e.g. dit-engine")
    parser.add_argument("--out", required=True, help="JSON manifest to write")
    parser.add_argument("--repo", default=".", help="git repository root")
    parser.add_argument(
        "--abi-version",
        type=int,
        default=None,
        help="DIT_ENGINE_ABI_VERSION this build compiled against",
    )
    parser.add_argument(
        "--toolchain",
        action="append",
        default=[],
        metavar="KEY=VALUE",
        help="extra tool version, repeatable",
    )
    parser.add_argument(
        "--toolchain-path",
        action="append",
        default=[],
        metavar="KEY=PATH",
        help="tool version derived from an install directory, repeatable",
    )
    parser.add_argument(
        "--artifact",
        action="append",
        default=[],
        metavar="PATH",
        help="staged .so to fingerprint, repeatable",
    )
    args = parser.parse_args()

    toolchain = {}
    for item in args.toolchain:
        key, _, value = item.partition("=")
        # Build scripts pass "unknown" when a tool sits outside PATH, which is
        # the norm for cmake (gradle and the SDK supply their own). Leave the
        # key out entirely rather than claiming a version that was not read.
        if key and value and value.lower() != "unknown":
            toolchain[key] = value
    for item in args.toolchain_path:
        key, _, value = item.partition("=")
        if key and value and value.lower() != "unknown":
            toolchain[key] = version_from_path(value)

    manifest = {
        "manifest": args.name,
        "builtAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    manifest.update(git_state(args.repo))
    if args.abi_version is not None:
        manifest["abiVersion"] = args.abi_version
    if toolchain:
        manifest["toolchain"] = toolchain
    manifest["artifacts"] = [describe(path) for path in args.artifact]

    out_dir = os.path.dirname(os.path.abspath(args.out))
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(manifest, fh, indent=4)
        fh.write("\n")

    print(
        "wrote {} ({} artifact{}, commit {})".format(
            args.out,
            len(manifest["artifacts"]),
            "" if len(manifest["artifacts"]) == 1 else "s",
            manifest.get("commitShort") or "unknown",
        )
    )


if __name__ == "__main__":
    main()

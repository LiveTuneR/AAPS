#!/usr/bin/env python3
"""Inventory immutable Medtrum input artifacts with hashes and format hints."""

from __future__ import annotations

import argparse
import zipfile
from pathlib import Path

from common import IntelHex, json_dump, read_u32, sha256_file


def classify(path: Path) -> dict[str, object]:
    data = path.read_bytes()[:256]
    result: dict[str, object] = {"kind": "unknown", "architecture": "UNKNOWN", "load_address": "UNKNOWN"}
    if path.suffix.lower() == ".apk" and zipfile.is_zipfile(path):
        result.update(kind="android-apk", architecture="Android multi-ABI")
    elif data.startswith(b"\x7fELF"):
        elf_class = "64-bit" if data[4] == 2 else "32-bit"
        machine = int.from_bytes(data[18:20], "little")
        architecture = {40: "ARM", 62: "x86-64", 183: "AArch64"}.get(machine, f"ELF machine {machine}")
        result.update(kind="elf-shared-object", architecture=f"{architecture} {elf_class}")
    elif path.suffix.lower() == ".zip" and zipfile.is_zipfile(path):
        result.update(kind="zip-archive")
    elif path.suffix.lower() in {".hex", ".ihex"}:
        image = IntelHex(str(path))
        segments = image.segments()
        result.update(
            kind="intel-hex",
            architecture="ARM Thumb (vector-table inference)",
            load_address=[{"start": hex(start), "end_exclusive": hex(end)} for start, end in segments],
        )
    elif len(data) >= 8:
        stack, reset = read_u32(data, 0), read_u32(data, 4)
        if 0x20000000 <= stack < 0x30000000 and reset & 1:
            result.update(
                kind="raw-firmware",
                architecture="ARM Cortex-M Thumb (vector-table inference)",
                vector_initial_sp=hex(stack),
                vector_reset=hex(reset),
            )
    elif path.suffix.lower() in {".pcap", ".pcapng"}:
        result.update(kind="packet-capture")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    rows = []
    for path in args.paths:
        resolved = path.resolve()
        stat = resolved.stat()
        row = {
            "path": str(resolved),
            "size": stat.st_size,
            "sha256": sha256_file(resolved),
            **classify(resolved),
        }
        rows.append(row)
    json_dump({"schema": 1, "artifacts": rows}, args.output)


if __name__ == "__main__":
    main()

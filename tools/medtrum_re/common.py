#!/usr/bin/env python3
"""Shared helpers for reproducible Medtrum firmware analysis."""

from __future__ import annotations

import hashlib
import json
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

from capstone import CS_ARCH_ARM, CS_MODE_LITTLE_ENDIAN, CS_MODE_THUMB, Cs
from intelhex import IntelHex


@dataclass(frozen=True)
class FirmwareImage:
    path: Path
    base: int
    data: bytes
    source_format: str
    segments: tuple[tuple[int, int], ...]

    @property
    def end(self) -> int:
        return self.base + len(self.data)

    def contains(self, address: int) -> bool:
        return self.base <= address < self.end

    def offset(self, address: int) -> int:
        if not self.contains(address):
            raise ValueError(f"address 0x{address:x} is outside image")
        return address - self.base


def parse_int(value: str) -> int:
    return int(value, 0)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_image(path: Path, base: int | None = None, fill: int = 0xFF) -> FirmwareImage:
    suffix = path.suffix.lower()
    if suffix in {".hex", ".ihex"}:
        image = IntelHex(str(path))
        segments = tuple(image.segments())
        if not segments:
            raise ValueError("Intel HEX contains no data")
        if base is None:
            base = min(start for start, _ in segments)
        end = max(end for start, end in segments if end > base)
        data = bytes(image.tobinarray(start=base, end=end - 1, pad=fill))
        return FirmwareImage(path, base, data, "ihex", segments)
    if base is None:
        raise ValueError("raw binary requires --base")
    data = path.read_bytes()
    return FirmwareImage(path, base, data, "bin", ((base, base + len(data)),))


def make_thumb_disassembler(skip_data: bool = False) -> Cs:
    disassembler = Cs(CS_ARCH_ARM, CS_MODE_THUMB | CS_MODE_LITTLE_ENDIAN)
    disassembler.detail = True
    disassembler.skipdata = skip_data
    return disassembler


def iter_ascii_strings(data: bytes, minimum: int = 4) -> Iterator[tuple[int, str]]:
    start: int | None = None
    for index, value in enumerate(data + b"\x00"):
        if 0x20 <= value <= 0x7E:
            if start is None:
                start = index
        elif start is not None:
            if index - start >= minimum:
                yield start, data[start:index].decode("ascii")
            start = None


def read_u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def read_u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def vector_table(image: FirmwareImage, offset: int = 0, max_entries: int = 64) -> list[dict[str, int | bool]]:
    rows: list[dict[str, int | bool]] = []
    for index in range(max_entries):
        item_offset = offset + index * 4
        if item_offset + 4 > len(image.data):
            break
        value = read_u32(image.data, item_offset)
        rows.append(
            {
                "index": index,
                "address": image.base + item_offset,
                "value": value,
                "thumb": bool(value & 1),
                "points_inside_image": image.contains(value & ~1),
            }
        )
    return rows


def json_dump(value: object, path: Path | None = None) -> None:
    text = json.dumps(value, indent=2, ensure_ascii=True) + "\n"
    if path is None:
        print(text, end="")
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")


def code_ranges(image: FirmwareImage, start: int | None, end: int | None) -> Iterable[tuple[int, bytes]]:
    actual_start = image.base if start is None else start
    actual_end = image.end if end is None else end
    if actual_start < image.base or actual_end > image.end or actual_start >= actual_end:
        raise ValueError("invalid code range")
    yield actual_start, image.data[actual_start - image.base : actual_end - image.base]

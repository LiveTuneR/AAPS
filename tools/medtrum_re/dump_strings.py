#!/usr/bin/env python3
"""Dump address-stable printable strings from a firmware image."""

from __future__ import annotations

import argparse
from pathlib import Path

from common import iter_ascii_strings, load_image, parse_int


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--base", type=parse_int)
    parser.add_argument("--minimum", type=int, default=4)
    parser.add_argument("--contains")
    args = parser.parse_args()
    image = load_image(args.image, args.base)
    needle = args.contains.lower() if args.contains else None
    for offset, value in iter_ascii_strings(image.data, args.minimum):
        if needle is None or needle in value.lower():
            print(f"0x{image.base + offset:08x}\t0x{offset:08x}\t{value}")


if __name__ == "__main__":
    main()

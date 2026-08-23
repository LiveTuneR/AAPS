#!/usr/bin/env python3
"""Compare raw base images without assuming identical load addresses."""

from __future__ import annotations

import argparse
import difflib
from pathlib import Path

from common import iter_ascii_strings, sha256_file


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("left", type=Path)
    parser.add_argument("right", type=Path)
    args = parser.parse_args()
    left, right = args.left.read_bytes(), args.right.read_bytes()
    matcher = difflib.SequenceMatcher(None, left, right, autojunk=False)
    matches = sorted(matcher.get_matching_blocks(), key=lambda item: item.size, reverse=True)
    left_strings = {value for _, value in iter_ascii_strings(left, 8)}
    right_strings = {value for _, value in iter_ascii_strings(right, 8)}
    print(f"left_sha256={sha256_file(args.left)} size={len(left)}")
    print(f"right_sha256={sha256_file(args.right)} size={len(right)}")
    print(f"similarity_ratio={matcher.ratio():.8f}")
    print("largest_matching_blocks:")
    for item in matches[:20]:
        print(f"  left=0x{item.a:x} right=0x{item.b:x} size=0x{item.size:x}")
    print(f"shared_ascii_strings_ge_8={len(left_strings & right_strings)}")


if __name__ == "__main__":
    main()

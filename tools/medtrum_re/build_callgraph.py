#!/usr/bin/env python3
"""Build a conservative direct Thumb BL call graph in CSV form."""

from __future__ import annotations

import argparse
from pathlib import Path

from capstone.arm import ARM_OP_IMM

from common import load_image, make_thumb_disassembler, parse_int


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--base", required=True, type=parse_int)
    parser.add_argument("--start", type=parse_int)
    parser.add_argument("--end", type=parse_int)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    image = load_image(args.image, args.base)
    start, end = args.start or image.base, args.end or image.end
    md = make_thumb_disassembler(skip_data=True)
    rows: set[tuple[int, int, str]] = set()
    for instruction in md.disasm(image.data[start - image.base : end - image.base], start):
        if instruction.id == 0 or instruction.mnemonic not in {"bl", "blx"} or not instruction.operands:
            continue
        operand = instruction.operands[0]
        target = (operand.imm & 0xFFFFFFFF) & ~1
        if operand.type == ARM_OP_IMM and image.contains(target):
            rows.add((instruction.address, target, instruction.mnemonic))
    lines = ["call_site,target,kind"] + [f"0x{site:08x},0x{target:08x},{kind}" for site, target, kind in sorted(rows)]
    text = "\n".join(lines) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8", newline="\n")
    else:
        print(text, end="")


if __name__ == "__main__":
    main()

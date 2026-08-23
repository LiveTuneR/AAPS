#!/usr/bin/env python3
"""Locate Thumb TBB/TBH dispatch sites and recover their relative targets."""

from __future__ import annotations

import argparse
from pathlib import Path

from capstone.arm import ARM_OP_IMM

from common import load_image, make_thumb_disassembler, parse_int, read_u16


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--base", required=True, type=parse_int)
    parser.add_argument("--start", type=parse_int)
    parser.add_argument("--end", type=parse_int)
    parser.add_argument("--lookback", type=int, default=12)
    args = parser.parse_args()
    image = load_image(args.image, args.base)
    start, end = args.start or image.base, args.end or image.end
    md = make_thumb_disassembler(skip_data=True)
    history = []
    for instruction in md.disasm(image.data[start - image.base : end - image.base], start):
        if instruction.id == 0:
            history.clear()
            continue
        if instruction.mnemonic in {"tbb", "tbh"}:
            maximum = None
            for previous in reversed(history[-args.lookback :]):
                if previous.mnemonic.startswith("cmp") and len(previous.operands) >= 2 and previous.operands[1].type == ARM_OP_IMM:
                    maximum = previous.operands[1].imm
                    break
            table = instruction.address + 4
            print(f"DISPATCH 0x{instruction.address:08x} kind={instruction.mnemonic} max={maximum if maximum is not None else 'UNKNOWN'} table=0x{table:08x}")
            if maximum is not None and 0 <= maximum <= 255:
                for index in range(maximum + 1):
                    offset = table - image.base + index * (2 if instruction.mnemonic == "tbh" else 1)
                    if offset < 0 or offset + (2 if instruction.mnemonic == "tbh" else 1) > len(image.data):
                        break
                    scale = read_u16(image.data, offset) if instruction.mnemonic == "tbh" else image.data[offset]
                    target = table + scale * 2
                    print(f"  {index:3d} 0x{target:08x}")
        history.append(instruction)
        if len(history) > args.lookback * 2:
            history.pop(0)


if __name__ == "__main__":
    main()

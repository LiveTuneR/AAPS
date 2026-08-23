#!/usr/bin/env python3
"""Extract compare/test instructions involving known Medtrum state values."""

from __future__ import annotations

import argparse
from pathlib import Path

from capstone.arm import ARM_OP_IMM

from common import load_image, make_thumb_disassembler, parse_int


STATE_NAMES = {
    0: "NONE",
    1: "IDLE",
    2: "FILLED",
    3: "PRIMING",
    4: "PRIMED",
    5: "EJECTING",
    6: "EJECTED",
    32: "ACTIVE",
    33: "ACTIVE_ALT",
    64: "LOW_BG_SUSPENDED",
    65: "LOW_BG_SUSPENDED2",
    66: "AUTO_SUSPENDED",
    67: "HOURLY_MAX_SUSPENDED",
    68: "DAILY_MAX_SUSPENDED",
    69: "SUSPENDED",
    70: "PAUSED",
    96: "OCCLUSION",
    97: "EXPIRED",
    98: "RESERVOIR_EMPTY",
    99: "PATCH_FAULT",
    100: "PATCH_FAULT2",
    101: "BASE_FAULT",
    102: "BATTERY_OUT",
    103: "NO_CALIBRATION",
    128: "STOPPED",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--base", required=True, type=parse_int)
    parser.add_argument("--start", type=parse_int)
    parser.add_argument("--end", type=parse_int)
    args = parser.parse_args()
    image = load_image(args.image, args.base)
    start, end = args.start or image.base, args.end or image.end
    md = make_thumb_disassembler(skip_data=True)
    for instruction in md.disasm(image.data[start - image.base : end - image.base], start):
        if instruction.id == 0 or instruction.mnemonic not in {"cmp", "cmp.w", "cmn", "tst"}:
            continue
        values = [operand.imm for operand in instruction.operands if operand.type == ARM_OP_IMM]
        for value in values:
            if value in STATE_NAMES:
                print(f"0x{instruction.address:08x}\t{instruction.mnemonic} {instruction.op_str}\t{value}:{STATE_NAMES[value]}")


if __name__ == "__main__":
    main()

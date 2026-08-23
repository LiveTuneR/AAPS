#!/usr/bin/env python3
"""Recover an immediate-compare Thumb command dispatcher and its handler calls."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

from capstone.arm import ARM_OP_IMM

from common import load_image, make_thumb_disassembler, parse_int


def target(instruction) -> int | None:
    for operand in instruction.operands:
        if operand.type == ARM_OP_IMM:
            return operand.imm & 0xFFFFFFFF
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--base", required=True, type=parse_int)
    parser.add_argument("--dispatcher", required=True, type=parse_int)
    parser.add_argument("--end", required=True, type=parse_int)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()

    image = load_image(args.image, args.base)
    code = image.data[args.dispatcher - image.base : args.end - image.base]
    instructions = list(make_thumb_disassembler().disasm(code, args.dispatcher))
    by_address = {item.address: index for index, item in enumerate(instructions)}
    rows: list[tuple[int, int, int, int]] = []

    for index, instruction in enumerate(instructions[:-1]):
        if instruction.mnemonic != "cmp" or not instruction.op_str.startswith("r4, #"):
            continue
        opcode = target(instruction)
        branch = next((item for item in instructions[index + 1 : index + 3] if item.mnemonic.startswith("beq")), None)
        stub = target(branch) if branch is not None else None
        if opcode is None or stub is None or stub not in by_address:
            continue
        calls = []
        for item in instructions[by_address[stub] : by_address[stub] + 4]:
            if item.mnemonic in {"bl", "blx"}:
                call_target = target(item)
                if call_target is not None:
                    calls.append(call_target & ~1)
        if len(calls) >= 2:
            rows.append((opcode, instruction.address, stub, calls[1]))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["opcode_decimal", "opcode_hex", "compare_address", "stub_address", "handler_address"])
        for opcode, compare_address, stub, handler in sorted(rows):
            writer.writerow([opcode, f"0x{opcode:02x}", f"0x{compare_address:08x}", f"0x{stub:08x}", f"0x{handler:08x}"])
    print(f"commands={len(rows)}")
    print("opcode_17_present=" + str(any(row[0] == 17 for row in rows)).lower())


if __name__ == "__main__":
    main()

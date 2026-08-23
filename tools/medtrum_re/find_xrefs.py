#!/usr/bin/env python3
"""Find direct, literal-pool, and MOVW/MOVT references to an address."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path

from capstone.arm import ARM_OP_IMM, ARM_OP_MEM, ARM_REG_PC

from common import load_image, make_thumb_disassembler, parse_int, read_u32


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("target", type=parse_int)
    parser.add_argument("--base", required=True, type=parse_int)
    parser.add_argument("--start", type=parse_int)
    parser.add_argument("--end", type=parse_int)
    args = parser.parse_args()
    image = load_image(args.image, args.base)
    target_bytes = struct.pack("<I", args.target)
    direct_offsets = [index for index in range(len(image.data) - 3) if image.data.startswith(target_bytes, index)]
    for offset in direct_offsets:
        print(f"DIRECT\t0x{image.base + offset:08x}\t-> 0x{args.target:08x}")

    start = args.start or image.base
    end = args.end or image.end
    md = make_thumb_disassembler(skip_data=True)
    pending_movw: dict[int, tuple[int, int]] = {}
    code = image.data[start - image.base : end - image.base]
    for instruction in md.disasm(code, start):
        if instruction.id == 0:
            pending_movw.clear()
            continue
        if instruction.mnemonic in {"b", "b.w", "bl", "blx"}:
            branch_target = next((operand.imm & 0xFFFFFFFF for operand in instruction.operands if operand.type == ARM_OP_IMM), None)
            if branch_target == args.target:
                print(f"BRANCH\t0x{instruction.address:08x}\t-> 0x{branch_target:08x}")
        if instruction.mnemonic == "movw" and len(instruction.operands) == 2:
            reg, imm = instruction.operands
            if imm.type == ARM_OP_IMM:
                pending_movw[reg.reg] = (instruction.address, imm.imm & 0xFFFF)
        elif instruction.mnemonic == "movt" and len(instruction.operands) == 2:
            reg, imm = instruction.operands
            if imm.type == ARM_OP_IMM and reg.reg in pending_movw:
                movw_address, low = pending_movw[reg.reg]
                value = ((imm.imm & 0xFFFF) << 16) | low
                if value == args.target:
                    print(f"MOVW_MOVT\t0x{movw_address:08x}\t0x{instruction.address:08x}\t-> 0x{value:08x}")
        elif instruction.mnemonic.startswith("ldr") and len(instruction.operands) >= 2:
            memory = instruction.operands[1]
            if memory.type == ARM_OP_MEM and memory.mem.base == ARM_REG_PC:
                literal = ((instruction.address + 4) & ~3) + memory.mem.disp
                if image.contains(literal) and literal + 4 <= image.end:
                    value = read_u32(image.data, literal - image.base)
                    if value == args.target:
                        print(f"LITERAL\t0x{instruction.address:08x}\tpool=0x{literal:08x}\t-> 0x{value:08x}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Recursively recover direct Thumb code from vectors and branch targets."""

from __future__ import annotations

import argparse
from collections import deque
from pathlib import Path

from capstone.arm import ARM_OP_IMM, ARM_REG_LR, ARM_REG_PC

from common import load_image, make_thumb_disassembler, parse_int, read_u32, vector_table


CONDITIONAL_BRANCHES = {
    "beq", "bne", "bhs", "blo", "bmi", "bpl", "bvs", "bvc", "bhi", "bls",
    "bge", "blt", "bgt", "ble", "cbz", "cbnz",
}


def immediate_target(instruction) -> int | None:
    for operand in instruction.operands:
        if operand.type == ARM_OP_IMM:
            return (operand.imm & 0xFFFFFFFF) & ~1
    return None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", type=Path)
    parser.add_argument("--base", required=True, type=parse_int)
    parser.add_argument("--vector-offset", type=parse_int, default=0)
    parser.add_argument("--vector-count", type=int, default=64)
    parser.add_argument("--seed", action="append", default=[], type=parse_int)
    parser.add_argument("--scan-thumb-pointers", action="store_true")
    parser.add_argument("--instructions", required=True, type=Path)
    parser.add_argument("--calls", required=True, type=Path)
    args = parser.parse_args()
    image = load_image(args.image, args.base)
    vectors = vector_table(image, args.vector_offset, args.vector_count)
    seeds = {(int(row["value"]) & ~1) for row in vectors if row["points_inside_image"]}
    seeds.update(value & ~1 for value in args.seed if image.contains(value & ~1))
    if args.scan_thumb_pointers:
        for offset in range(0, len(image.data) - 3, 4):
            value = read_u32(image.data, offset)
            target = value & ~1
            if value & 1 and image.contains(target) and target % 2 == 0:
                seeds.add(target)
    function_starts = set(seeds)
    blocks = deque((seed, seed) for seed in sorted(seeds))
    seen: set[int] = set()
    recovered: dict[int, tuple[int, str, str, str]] = {}
    calls: set[tuple[int, int, int]] = set()
    md = make_thumb_disassembler()

    while blocks:
        address, owner = blocks.popleft()
        while image.contains(address) and address not in seen:
            offset = address - image.base
            instruction = next(md.disasm(image.data[offset : offset + 4], address, count=1), None)
            if instruction is None:
                break
            seen.add(address)
            recovered[address] = (owner, instruction.bytes.hex(), instruction.mnemonic, instruction.op_str)
            next_address = instruction.address + instruction.size
            mnemonic = instruction.mnemonic
            target = immediate_target(instruction)

            if mnemonic in {"bl", "blx"} and target is not None and image.contains(target):
                calls.add((owner, instruction.address, target))
                function_starts.add(target)
                blocks.append((target, target))
                address = next_address
                continue
            if mnemonic in CONDITIONAL_BRANCHES and target is not None and image.contains(target):
                blocks.append((target, owner))
                address = next_address
                continue
            if mnemonic in {"b", "b.w"}:
                if target is not None and image.contains(target):
                    blocks.append((target, owner))
                break
            if mnemonic in {"bx", "blx"} and instruction.operands:
                register = instruction.operands[0].reg
                if register in {ARM_REG_LR, ARM_REG_PC}:
                    break
            if mnemonic == "pop" and any(operand.reg == ARM_REG_PC for operand in instruction.operands):
                break
            if mnemonic in {"tbb", "tbh", "udf"}:
                break
            address = next_address

    args.instructions.parent.mkdir(parents=True, exist_ok=True)
    instruction_lines = ["address,function,bytes,mnemonic,operands"]
    for address, (owner, raw, mnemonic, operands) in sorted(recovered.items()):
        escaped = operands.replace('"', '""')
        instruction_lines.append(f'0x{address:08x},0x{owner:08x},{raw},{mnemonic},"{escaped}"')
    args.instructions.write_text("\n".join(instruction_lines) + "\n", encoding="utf-8", newline="\n")

    call_lines = ["function,call_site,target"]
    call_lines.extend(f"0x{owner:08x},0x{site:08x},0x{target:08x}" for owner, site, target in sorted(calls))
    args.calls.write_text("\n".join(call_lines) + "\n", encoding="utf-8", newline="\n")
    print(f"vector_seeds={len(seeds)}")
    print(f"functions={len(function_starts)}")
    print(f"instructions={len(recovered)}")
    print(f"direct_calls={len(calls)}")


if __name__ == "__main__":
    main()

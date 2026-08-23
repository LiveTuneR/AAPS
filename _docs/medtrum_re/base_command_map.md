# Medtrum base command map

## Proven dispatcher

- Base image: `bin_file_jn.bin`, inferred load address `0x00026000`.
- Packet parser: `0x0002d066`; call to dispatcher at `0x0002d0a0`.
- Dispatcher: `0x0002cc7a`.
- Reproducible extractor: `tools/medtrum_re/extract_command_dispatch.py`.
- Complete machine-readable output: `base_command_map.csv`.
- Recovered command slots: **52**.
- Slots correlated with current AAPS protocol names: **22**.
- Slots with semantics still unknown: **30**.

The dispatcher is a direct `cmp r4, #opcode` / conditional-branch chain. Each
selected stub forwards the request/response arguments and calls one handler.

## Safety-relevant rows

| Opcode | Handler | Classification | Evidence |
|---:|---:|---|---|
| `0x03` | `0x2a4ea` | SYNCHRONIZE | AAPS packet and base slot agree |
| `0x10` | `0x2a6a2` | PRIME | AAPS packet and field trace agree; forbidden in campaign |
| `0x12` | `0x2a6e6` | ACTIVATE | AAPS packet and field trace agree; requires state 6 |
| `0x1f` | `0x2ada8` | STOP_PATCH | AAPS packet and base handler agree; forbidden in campaign |
| `0x23` | `0x2ae9c` | SET_PATCH | AAPS 12-byte builder and base slot agree |
| `0x94` | `0x2c240` | activation/configuration-like | Requires state 6 and ends in state `0x20` |

## Disproved opcode-17 hypothesis

Decimal opcode **17** (`0x11`) is absent between the `0x10` and `0x12`
comparisons. This disproves the earlier proposal that an unused opcode 17 is a
base-supported jump-prime command. Do not confuse decimal 17 (`0x11`, absent)
with opcode `0x17` (decimal 23, present and routed to `0x2aabe`).

## Confidence

Dispatcher addresses and handler slots are **CONFIRMED** by direct static
disassembly. Human-readable names are **CONFIRMED** only where independently
matched to an AAPS packet or field trace; the remaining names are `UNKNOWN`.

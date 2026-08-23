# Pump state graph

State byte provenance is the base command handlers plus the existing AAPS
`MedtrumPumpState` decoder. The common state writer is `0x2a018`, which stores
the byte at pump context offset `+0x87` and marks status dirty.

## Confirmed restart-relevant transitions

- `ACTIVATE` handler `0x2a6e6` checks source state `6/EJECTED` and calls
  `0x2a018(..., 0x20)`: `EJECTED -> ACTIVE`.
- `0x94` handler `0x2c240` also rejects a source state other than
  `6/EJECTED` and calls `0x2a018(..., 0x20)` at `0x2c61a`.
- `STOP_PATCH` handler `0x2ada8` routes active/suspended/fault operation state
  through the state setter with value `0x80/STOPPED`.

No independently proven non-STOP/non-PRIME edge from `ACTIVE` or `ACTIVE_ALT`
to `PRIMED`/`EJECTED` was found. That missing edge is exactly the restart
candidate required by the campaign.

The DOT file intentionally omits speculative edges. Intermediate normal
priming/ejection progression is supported by field traces but not proposed as
an experimental campaign path.

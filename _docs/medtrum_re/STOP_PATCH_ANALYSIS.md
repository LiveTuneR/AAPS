# STOP_PATCH analysis

- Opcode: `0x1f` (decimal 31).
- Dispatcher slot: compare at `0x2cd20`, handler `0x2ada8`.
- AAPS sender: `StopPatchPacket`; it is not referenced by the experimental
  controller.

The handler checks command readiness and current state. For active-operation
states it performs cleanup and calls the common state setter through the small
wrapper at `0x2aafe` with `r1 = 0x80`, producing `STOPPED`. It then invokes
additional persistence/event cleanup (including the path using event/operation
value `0x0e`). These side effects are broader than a reversible state toggle.

No command path from `STOPPED` back to a reusable active patch was proven.
Recoverability is therefore **UNKNOWN and potentially terminal**. The bench
campaign never imports `StopPatchPacket`, never exposes STOP as a candidate,
and has no destructive cleanup path.

# Experimental write surface

Production write whitelist: **empty**.

`MedtrumBenchRestartController.ReadOnlyProductionIo` can call only:

- `MedtrumService.readBenchRestartBaseline(false)` -> SYNCHRONIZE;
- `MedtrumService.readBenchRestartBaseline(true)` -> read history;
- `MedtrumBleTrace.export()`.

Its three write methods return failure without touching BLE. The confirmed
candidate registry returns `null`, so the campaign reaches `BLOCKED` before a
settings, transition or ACTIVATE write.

The controller has no dependency on `PrimePacket` or `StopPatchPacket` and
cannot call `startPrime`, `deactivatePatch`, `performUnpair`, or
`resetPatchParameters`.

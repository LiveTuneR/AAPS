# Apex7 final hardening worklog

Accepted baseline: `0624357b4f6ef4ce1bef8e440c152aad2612ad8b`.

Software status: `IMPLEMENTED_AND_LOCALLY_TESTED`.

Overall status: `EXPERIMENTAL_DEVICE_VALIDATION_REQUIRED`.

## Implemented

- One active MAIN calculation with a durable latest-pending request, generation invalidation, retry recovery, durable BG claims and command provenance.
- Fast-CGM handling for genuine 60/120-second readings. `referenceTime` belongs to one bucketing pass, is reset in `finally`, and is not cloned.
- Single-lock ADS ownership, deep-copy boundaries, history pruning and concurrent ownership stress coverage.
- Structured seven-day therapy telemetry with ordered JSONL, compressed closed segments, retention, loss ledger, redaction, integrity hashes, CSV joins and export through the Android document picker.
- Structured APS decisions, real AutoISF/dynamic-ISF factor telemetry, activity context, pump diagnostics, queue lifecycle and history-reconciliation records.
- Apex and Medtrum diagnostic correlation without raw BLE payloads, credentials, message text or pump serial numbers.
- Event-driven Overview refresh, compact activity/device layout, explicit ON/WAIT/OFF/UNKNOWN states and responsive screenshot coverage.
- Russian physical-device acceptance procedure for Apex and Medtrum, including disconnects, retries, delayed callbacks and therapy command verification.

## Evidence

- Full 13-module local run: `build/apex7-final-integration2.log`, successful in 15m16s.
- Final focused integration after command, pump and UI changes: `build/apex7-final-integration7.log`, successful in 2m04s.
- Fast-CGM scheduler retry suite: `build/apex7-final-scheduler-retry.log`, successful.
- Seven-day synthetic telemetry: 10080 minute samples, 336 therapy events, 8 settings snapshots; 849644 bytes on disk and 1178631-byte export.
- Mutation campaign killed all required mutations: cloned/persistent anchor, shallow ADS rows/carbs, oldest-pending, history loss, starvation, stale publish, unsafe metadata, sample loss, secret leakage and duplicate command correlation.
- Compose screenshot suite generated portrait, landscape, large-font and pump/target state variants under `ui/build/reports/apex7-screenshots`.

## Boundary

No physical pump, Samsung Health SDK or clinical closed-loop acceptance was available in this environment. CI and unit tests do not prove safe therapy on hardware. The APK must remain experimental until the procedure in `doc/APEX7_DEVICE_ACCEPTANCE_RU.md` is completed on the intended phone, CGM source and pump.

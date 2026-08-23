# Timer subsystem

## Device-visible fields

- Notification mask `0x0040` carries four bytes of pump `START_TIME`. AAPS
  converts it with `MedtrumTimeUtil` and now also stores it separately as
  `deviceReportedPatchStartTime`.
- Notification mask `0x0400` carries four bytes of pump `AGE`. AAPS parses the
  little-endian value and now stores it separately as
  `deviceReportedPatchAge`. Seconds are a strong inference from field behavior,
  not a newly proven unit in this static analysis.

These fields originate in device packets. They are stronger restart evidence
than local `handleNewPatch()` or the persisted AAPS start timestamp.

## Base call sites

Both normal ACTIVATE and handler `0x94` call:

- `0x32a88` (`ACTIVATE` call `0x2a8ba`, `0x94` call `0x2c520`);
- `0x33566` (`ACTIVATE` call `0x2a8c0`, `0x94` call `0x2c526`).

`0x32a88` calls `0x327ec` and `0x328d8`. Those functions scan/reconstruct
time-indexed history/storage structures around a context time field; this is
not sufficient to call them a timer reset. `0x33566` selects/copies a fixed
28-byte structure from a 25-entry table; its timer meaning is also unproven.

## Honest result

- Start/reset behavior: **UNKNOWN** below the shared activation
  post-processing calls.
- Pause behavior: **UNKNOWN**.
- NVM persistence of AGE/START_TIME: **UNKNOWN**.
- Runtime proof rule: a restart is accepted only when device AGE resets,
  subsequently increases, and device START_TIME changes. Merely returning to
  ACTIVE is insufficient.

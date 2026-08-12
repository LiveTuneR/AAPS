# Apex field log analysis, 2026-08-12

Time zone: Europe/Moscow (UTC+03:00).

## Inputs

- `apex-diagnostics-1786526193457.zip`
- `AndroidAPS_LOG_1786526320873.log.zip`
- App version: `4.0.0-beta-apex2`
- Pump firmware/protocol: `1.1.1.0 / 4.12`

## Meal bolus at 08:35

- Carbs record: 43 g at 08:35:06.989.
- Bolus queued: 08:35:08.706.
- Bolus sent: 08:35:10.122.
- Pump accepted it: 08:35:10.242, response counter `203`.
- Progress ran from `0` through `202`.
- Pump reported completion at 08:36:52.542 with counter `203`.
- Duration from queueing to completion: 103.8 seconds.
- The AAPS treatment database later exposed this bolus as 5.1 U at 08:35:59.
- There was no command timeout or disconnect during this bolus. The closest link incidents were at 08:08 and 08:50.

The apex2 trace did not record the dose encoded into the outgoing `Bolus` command or the raw
`BolusEntry.standardDose/standardPerformed` history fields. Therefore the response counter `203`
alone does not prove that 203 pump steps were requested or delivered. The progress sequence may
use a zero-based counter. The next build records requested units, outgoing step count, encoded
units, and pump history step counts so this can be resolved from one diagnostic archive.

A value such as 5.75 U displayed as 5.8 U can also be one-decimal UI formatting. The pump step is
0.025 U, so 5.75 U is exactly 230 steps and requires no dose normalization.

## Link incidents

The archive contains:

- 12 command timeouts: 11 `GetValue`, 1 `RequestHeartbeat`.
- 22 scheduled reconnects.
- 10 immediate `services_missing_0` failures on the first reconnect attempt.
- 1 BLE write failure and 1 ignored stale callback.
- 0 watchdog stalls.

All 11 `GetValue` timeouts started 2-24 ms after an unsolicited pump `Heartbeat` frame and then
expired after about 10 seconds. This repeated at 06:12, 06:22, 08:08, 08:50, 09:04, 09:26,
09:54, 10:30, 11:06, 11:30, and 12:16. The evidence indicates that the pump sometimes ignores a
read command issued immediately after its heartbeat.

## Changes based on evidence

- Non-cancel commands wait 1500 ms after an unsolicited heartbeat.
- `CancelBolus` bypasses the heartbeat delay.
- Bolus and cancel requests have reserved queue capacity and overtake routine reads.
- `CancelBolus` preempts an in-flight read-only wait instead of waiting for its response timeout.
- Bolus diagnostics now correlate requested units, encoded steps, progress, and pump history.
- Concurrency tests use coroutine virtual time and cover priority, read preemption, heartbeat
  settling, stale callbacks, disconnects, and issue timeout.

## Remaining field validation

No Fill-history failure or Bluetooth-adapter-off incident appears in these archives. Fill freshness
and a dedicated `AdapterOff` state are therefore not implemented from assumptions alone. The
transport already records `bluetooth_unavailable`; a captured real incident is needed to define
recovery behavior without creating a state that cannot leave reliably.

This change is not proof of medical-device safety. Validate with controlled pump tests: normal
heartbeat cycles, screen-off operation, reconnect, bolus, cancel during bolus, TBR set/cancel, and
export a new Apex diagnostic archive after the first dose-display discrepancy or link interruption.

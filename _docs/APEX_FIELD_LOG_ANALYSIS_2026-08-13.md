# Apex field log analysis, 2026-08-13

Time zone: Europe/Moscow (UTC+03:00).

## Inputs

- `apex-diagnostics-1786595649743.zip`
- `AndroidAPS_LOG_1786595667297.log.zip`
- App version: `4.0.0-beta-apex3`
- Pump firmware/protocol: `1.1.1.0 / 4.12`

The Apex trace covers about 18.5 hours. The rotating AAPS archive only retained approximately
00:55-01:00 and 07:28-07:34, so absence of an AAPS event outside those windows is not evidence
that it did not happen.

## Command reliability

- 2,688 commands started and 2,682 completed.
- All 6 timeouts were read-only `GetValue` commands.
- All 162 temporary-basal commands completed.
- All 38 bolus commands completed.
- All 7 heartbeat requests, 7 clock synchronizations, 6 TBR cancellations, and the bolus
  cancellation completed.
- No therapy-command retry, duplicate delivery, or watchdog stall was observed.

Five read timeouts started 1,500-1,501 ms after an unsolicited heartbeat. The sixth was a
`StatusV2` read sent 1,559 ms after the preceding `StatusV1` read. This shows that the former
1,500 ms boundary was still occasionally inside the pump's busy interval.

Five first reconnect attempts returned `services_missing_0` very shortly after GATT creation;
the following reconnect succeeded. This is secondary recovery churn after a command timeout,
not a therapy-command failure. No speculative Bluetooth cache refresh is added until the wider
command gap is field-tested.

## Bolus reconciliation

Of 38 boluses:

- 36 matched the sent step count exactly;
- 1 was cancelled and correctly reconciled as a partial delivery;
- 1 was sent and accepted as 121 steps, while pump history later reported 122 performed steps.

The driver did not encode the discrepant bolus incorrectly. Subtracting one step from future
commands would under-deliver the normal cases. The next build records a dedicated
`bolus_delivery_mismatch` event with sent, pump-requested, and pump-performed step counts.

## Changes based on evidence

- Read-only commands now use a 2,000 ms gap.
- Commands after an unsolicited heartbeat now use a 2,000 ms gap.
- Therapy commands retain the 1,500 ms normal gap; `CancelBolus` retains priority and heartbeat
  bypass behavior.
- A new heartbeat arriving while a command is waiting restarts the applicable quiet interval.
- `GetValue` trace events now include the requested value name and expected response type.
- `Always use short average delta` once again replaces the effective SMB input delta when enabled.
- Apex Russian resources now cover all module strings and preserve decimal SMB formatting.

## Next field validation

Install `4.0.0-beta-apex4` over apex3 and repeat normal closed-loop operation. Export Apex
diagnostics after the first timeout, dose mismatch, failed profile switch, or unexplained therapy
result. Increase the AAPS maintenance log retention from 2 to 10 files before the next long run so
the general application log covers more than a few minutes.

This analysis does not establish medical-device safety. Keep the first bolus, cancel, TBR set,
and TBR cancel checks controlled and observable after updating.

# Apex TruCare III diagnostics

This integration is based on an observed legacy protocol, not manufacturer protocol documentation. Therapy control is disabled by default and unknown firmware/protocol pairs are rejected before setup or therapy commands are sent. Manual firmware selection can be used for diagnostics, but it never enables therapy control; control requires a version read directly from the pump.

Known observed pairs:

- firmware 6.24 / protocol 4.9
- firmware 6.25 / protocol 4.10
- firmware 6.27 / protocol 4.11
- firmware 6.28 / protocol 4.11
- firmware 1.1.1.0 (reported as 1.1) / protocol 4.12

Protocol 4.12 uses a compatibility fallback for basal-profile selection only after an explicit `Invalid` response. A timeout is never retried using another wire format. Firmware 1.1/protocol 4.12 may also reject the non-critical settings synchronization command; that exact rejection is traced and does not block initialization.

## Phone-only collection

No root or connected computer is required. Open the Apex plugin screen and use the Share action in the toolbar. The app creates a ZIP containing bounded JSONL traces and metadata, then opens the Android share sheet.

The trace records link-state transitions, connection generation, command lifecycle, response type, timeouts, reconnects, compatibility decisions, watchdog stalls, process-exit information on Android 11+, and thread dumps captured after a stall. It does not export raw BLE payloads, pump serial numbers, or Bluetooth addresses. Identifier-like fields are hashed.

Bolus traces include requested units, the outgoing 0.025 U step count, encoded units, pump history
requested/performed step counts, and reconciliation deltas. Command traces also identify priority,
queue-capacity timeout, issue timeout, and a read preempted by `CancelBolus`.

Storage is bounded to six 1 MiB trace files and two exported ZIP files. A watchdog checks every 30 seconds. It captures a thread dump and ZIP when a command is pending for 45 seconds, connection takes 40 seconds, handshake takes 75 seconds, or a ready connection makes no progress for five minutes.

## Field validation order

1. Keep experimental therapy control disabled. Validate detection, connect, version check, status, basal profiles, and history. In this mode the driver does not set pump time, connection profile, or remote heartbeat configuration.
2. Repeat disconnect/reconnect cycles and leave the app running through screen-off and process recreation.
3. Confirm late callbacks are ignored and every reconnect increments the generation in the trace.
4. With an appropriate independent safety plan, validate cancellation before any start command, then temporary basal, then bolus.
5. For each command, interrupt Bluetooth before write acknowledgement, before response, after acceptance, during progress, and before history reconciliation.

An APK build passing unit tests is not a field-validation result. Keep an independent treatment method available during testing.

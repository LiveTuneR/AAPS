# Experimental write surface

This surface is available in normal and Simple mode with the explicit Medtrum
bench option enabled. Engineering mode is not required. It is intended
exclusively for a physically off-body, already `ACTIVE` Medtrum Nano running
firmware `1.80.89`.

## Whitelist

| Command | Encoder | Maximum TX per campaign | Automatic retry |
|---|---|---:|---:|
| `SET_PATCH` | existing `SetPatchPacket` | 3 | 0 |
| `ACTIVATE` | existing `ActivatePacket` | 1 | 0 |

The three `SET_PATCH` packets are fixed phases: current settings, expiration
toggle only, then exact restoration. `ACTIVATE` is sent only after a second
real synchronization still reports `ACTIVE` or `ACTIVE_ALT`.

`MedtrumPacket.allowTransportRetries` is disabled for both command types. A
timeout or ambiguous transport error causes read-only reconnect/authentication
and synchronization, never retransmission.

## Blacklist

The experimental controller cannot send or reach:

- `PRIME` or `STOP_PATCH`;
- bolus, temporary basal, basal-profile update or insulin-delivery commands;
- `CLEAR_ALARM`, unpair, firmware upgrade, deactivation or Change Patch;
- `resetPatchParameters` or local pump-state spoofing;
- opcode `0x11`, raw `0x94`, any unknown opcode, payload fuzzing or scanning;
- arbitrary `sendRaw(opcode, bytes)` functionality.

Connection recovery may use the normal read-only authentication, device/time
query, synchronization and subscription flow. During bench recovery, a clock
difference is observed but pump time/timezone writes are skipped.

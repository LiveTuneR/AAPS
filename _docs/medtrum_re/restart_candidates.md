# Restart candidate evidence

## Evidence gate result: BLOCKED

No candidate reaches `CONFIRMED`, which requires agreement between a PDM packet
builder or official HCI capture and the base handler.

| Candidate | PDM sender | Base handler | Source/result | Result |
|---|---|---|---|---|
| decimal `17` / `0x11` | none | dispatcher slot absent | unsupported | **DISPROVED** |
| `0x94` | no recovered sender/capture | `0x2c240` confirmed | `EJECTED -> ACTIVE` | **DISPROVED for ACTIVE restart** |
| `inj_jump_prime` | string only at about `0x9026f85a`; no executable xref | no linked opcode | unknown | **UNKNOWN** |

## `inj_jump_prime`

The PDM firmware string cluster includes `[INJ]inj_jump_prime`, but searches for
direct pointers, Thumb literal loads, MOVW/MOVT construction and plausible
relative references found no executable xref in the PDM code range. Nearby INJ
strings have the same property, consistent with a retained log/string table.
The EasyTouch APK is Qihoo/Jiagu protected; the available JADX tree exposes the
loader/resources, not the runtime packet builder. No Android emulator/device is
available in the analysis environment for dynamic unpacking.

The supplied field BLE traces came from AAPS, not official EasyTouch. They
confirm the normal PRIME (`0x10`) and ACTIVATE (`0x12`) flow only. They contain
neither an official jump-prime packet nor an official `0x94` sender.

## Cross-firmware limit

Only one pump-base image (`bin_file_jn.bin`) was available. `bin_file_ty.bin`
has a different vector base and appears to be the CGM-side image; the large PDM
HEX is not a second pump-base version. A semantic cross-version comparison of
the restart handler therefore remains unavailable and is not fabricated.

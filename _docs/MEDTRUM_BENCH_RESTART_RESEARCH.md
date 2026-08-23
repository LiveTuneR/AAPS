# Medtrum Nano 1.80.89 bench restart research

## Executive result

The restart evidence gate is **BLOCKED**. No non-STOP, non-PRIME transition
from a real `ACTIVE`/`ACTIVE_ALT` device state to `PRIMED`/`EJECTED` reached
`CONFIRMED` confidence. The bench build therefore performs the maximum known
read-only baseline, exports evidence, and stops before every write.

This is an intentional patch-preservation result, not a partial runtime guess.

## A. Repository

- Repository: `LiveTuneR/AAPS`.
- Exact base commit: `1483e350e81fe265c94c805ee9b288e7a9ddfed6`.
- Reference/tracking branch: `livetuner/codex/apex-fsm`.
- Research branch: `codex/medtrum-bench-restart-research`.
- Implementation/build commit: `de285e05a5049bacd3961cf23e2589415b4b8eb7`.
- Final commit: recorded in the delivery response; a commit cannot embed its
  own object ID.

Changed files:

- `_docs/MEDTRUM_BENCH_RESTART_RESEARCH.md`
- `_docs/medtrum_re/EXPERIMENTAL_WRITE_SURFACE.md`
- `_docs/medtrum_re/STOP_PATCH_ANALYSIS.md`
- `_docs/medtrum_re/TIMER_SUBSYSTEM.md`
- `_docs/medtrum_re/base_command_map.csv`
- `_docs/medtrum_re/base_command_map.md`
- `_docs/medtrum_re/input_artifacts.json`
- `_docs/medtrum_re/pump_state_graph.dot`
- `_docs/medtrum_re/pump_state_graph.md`
- `_docs/medtrum_re/restart_candidate_evidence.json`
- `_docs/medtrum_re/restart_candidates.md`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/MedtrumPlugin.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/MedtrumPump.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/bench/BenchRestartCampaign.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/bench/BenchRestartModels.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/bench/BenchRestartReport.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/bench/MedtrumBenchRestartCommand.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/bench/MedtrumBenchRestartController.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/bench/MedtrumBenchRestartJournal.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/comm/packets/NotificationPacket.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/compose/MedtrumComposeContent.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/compose/MedtrumOverviewViewModel.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/diagnostics/MedtrumBleTrace.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/keys/MedtrumBooleanKey.kt`
- `pump/medtrum/src/main/kotlin/app/aaps/pump/medtrum/services/MedtrumService.kt`
- `pump/medtrum/src/main/res/values/strings.xml`
- `pump/medtrum/src/main/res/values-ru-rRU/strings.xml`
- `pump/medtrum/src/test/kotlin/app/aaps/pump/medtrum/bench/BenchRestartCampaignTest.kt`
- `pump/medtrum/src/test/kotlin/app/aaps/pump/medtrum/bench/BenchRestartReportTest.kt`
- `pump/medtrum/src/test/kotlin/app/aaps/pump/medtrum/compose/MedtrumOverviewViewModelTest.kt`
- `tools/medtrum_re/*`

No extracted proprietary firmware blob is committed.

## B. Raw artifacts

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| EasyTouch 1.4.67 APK | 43,852,983 | `1f7fa7c77a7835421f429bf111ec72eabba5b0770840e3ee02e2693b1c12e1a3` |
| `fm008_latest.hex` | 7,487,866 | `999e56068780a4dae8233d9325a1382e9a2206ea1152c53f84b1a9276a9f34b0` |
| `bin_file_jn.bin` | 106,739 | `5e6e7894dfa2975e756bc24b3f0cad6eb7984eee894a8afe9fe82e3712eebd86` |
| `bin_file_ty.bin` | 48,684 | `679bdfe5a87ea6a1e8b9e245f92774e6d86624f3f1433cb41c26bd680997a4bf` |
| 32-bit `libjiagu.so` | 837,012 | `2312314fad6d8f315940ccf2c616024a136368894dddab7ce738a770f64df47d` |
| 64-bit `libjiagu_a64.so` | 1,155,504 | `1042a284aa476e36b249d1914d9713ed7f240d2d4db6ddf584c93063586eb818` |
| BLE archive 1786642965648 | 7,992 | `667c72a8703a6f2532d8cfd8e6e2580833a0ea89db154022170f4e43c8cd6611` |
| BLE archive 1786894052421 | 810,766 | `bde58acb5bdabbb5b6d2524141003aaa9254c737c9ac5cb345983645f98084be` |

`bin_file_jn.bin` is ARM Cortex-M Thumb with load address `0x26000`
(`STRONG INFERENCE`). `bin_file_ty.bin` has inferred base `0x16000` and appears
CGM-side rather than a second pump-base image. Full provenance and HEX segments
are in `medtrum_re/input_artifacts.json`.

## C. Firmware map

- `CONFIRMED`: base parser `0x2d066`, dispatcher call `0x2d0a0`, dispatcher
  `0x2cc7a`.
- Recovered Thumb function starts: **914**.
- Decoded instructions: **26,036**; direct calls: **2,165**.
- Dispatcher command slots / BLE handlers: **52**.
- Correlated to known protocol semantics: **22**.
- Handlers with semantic name still `UNKNOWN`: **30**.
- Reproducible extraction: `tools/medtrum_re/extract_command_dispatch.py`.

Request-length, response, state/NVM and reversibility fields that were not
proven remain `UNKNOWN` in the command catalog. They were not filled from old
reports.

## D. State machine

- `CONFIRMED`: common state writer `0x2a018`, state byte at context `+0x87`.
- `CONFIRMED`: normal `ACTIVATE` handler `0x2a6e6` requires state
  `6/EJECTED` and writes `0x20/ACTIVE`.
- `CONFIRMED`: handler `0x94` also requires `EJECTED` and writes `ACTIVE`.
- `CONFIRMED`: `STOP_PATCH` handler `0x2ada8` can route operational states to
  `0x80/STOPPED`.
- `UNKNOWN`: any legitimate `ACTIVE`/`ACTIVE_ALT -> PRIMED/EJECTED` edge that
  avoids both STOP and PRIME. No such edge was found.

The graph intentionally contains no speculative restart edge.

## E. STOP_PATCH

- `CONFIRMED`: opcode `0x1f`, handler `0x2ada8`.
- `CONFIRMED`: it performs cleanup, persists/events additional operation data,
  and reaches `STOPPED`; it is broader than a reversible state toggle.
- Recoverability: **UNKNOWN and potentially terminal**. No `STOPPED -> reusable`
  command path was proven.
- Campaign effect: **none**. The controller does not import or reach
  `StopPatchPacket`.

## F. Timer subsystem

- `CONFIRMED`: notification mask `0x0040` carries device `START_TIME`;
  `0x0400` carries device `AGE`.
- Android stores device start, device age, and local start independently.
- `CONFIRMED` call sites shared by ACTIVATE and `0x94`:
  `0x2a8ba -> 0x32a88`, `0x2a8c0 -> 0x33566`,
  `0x2c520 -> 0x32a88`, `0x2c526 -> 0x33566`.
- `STRONG INFERENCE`: `0x32a88` participates in reconstruction of time-indexed
  history/storage through `0x327ec` and `0x328d8`.
- `UNKNOWN`: exact start/reset/pause semantics and AGE/START_TIME NVM
  persistence. The recovered calls are insufficient to label a timer reset.

A restart is accepted only if device AGE falls, subsequently rises, and device
START_TIME changes. A local AAPS timestamp or `ACTIVE` state alone never proves
a restart.

## G. inj_jump_prime

- PDM string: approximately `0x9026f85a`.
- Direct pointers, Thumb literal loads, MOVW/MOVT construction and plausible
  relative references: none recovered.
- Executable caller graph: **UNKNOWN**.
- BLE packet builder/opcode/payload/base handler/result state: **UNKNOWN**.
- Confidence: **UNKNOWN**.

The EasyTouch DEX is protected by Qihoo/Jiagu. Manifest, resources and native
loaders were inspected, but the environment has no runnable Android system
image/device for passive post-loader DEX dumping. Supplied captures are AAPS
captures and show normal PRIME/ACTIVATE, not an official jump-prime sender.

## H. Previous undocumented-command hypothesis

Opcode `0x94` as an `ACTIVE` restart is **DISPROVED**.

The base handler at `0x2c240` exists (`CONFIRMED`) but checks for
`6/EJECTED`, consumes a large structured configuration payload, performs
multiple storage operations, and ends in `ACTIVE`. No PDM sender or official
capture was recovered. It is activation/configuration-like, not the missing
`ACTIVE -> activation-ready` transition.

## I. Opcode-17 hypothesis

Decimal opcode 17 (`0x11`) as jump-prime is **DISPROVED**. Its dispatcher slot
is absent between `0x10/PRIME` and `0x12/ACTIVATE`. Opcode `0x17` (decimal 23)
does exist, but is a different command and must not be confused with decimal
17.

## J. Restart candidate

Final gate: **BLOCKED**.

No candidate has both an official PDM/capture sender and an independently
matching base handler. Runtime candidate registry therefore returns `null`.
No opcode scan, payload fuzzing or fallback list exists.

## K. Android implementation

- One management action: `Restart — test` / `Перезапуск — тест`.
- Visibility requires engineering mode **and** the explicit non-exportable
  Medtrum experimental preference; default is hidden/off.
- Dedicated `BenchRestartCampaign` FSM and controller; normal Change Patch and
  retry activation FSMs are untouched.
- A queued custom command provides the exclusive serialized command window and
  checks command-queue/bolus safety before the campaign.
- Production IO is explicitly read-only: two SYNCHRONIZE operations, existing
  read-only history and trace export.
- Minimal process-death journal persists campaign ID, phase, last write command,
  last confirmed real device state, start/update timestamps. Restart marks an
  incomplete campaign `INTERRUPTED`; no write resumes.
- Automatic archive contains raw JSONL trace, metadata,
  `bench_restart_summary.json` and `bench_restart_summary.md`.

## L. Critical invariants

- PRIME is impossible in the production campaign write surface.
- STOP_PATCH is impossible in the production campaign write surface.
- No local pump-state spoofing is present.
- No deactivation, unpair, local reset, destructive cleanup or automatic
  ambiguous-write retry is reachable.
- Current production write whitelist is empty; all three future write-port
  methods fail closed without touching BLE.

## M. Tests

Focused mandatory tests:

```powershell
.\gradlew.bat :pump:medtrum:testFullDebugUnitTest --tests "app.aaps.pump.medtrum.bench.BenchRestartCampaignTest"
```

Result: **25/25 PASS**.

Full module, lint and application build:

```powershell
.\gradlew.bat :pump:medtrum:testFullDebugUnitTest :pump:medtrum:lintFullDebug :app:assembleFullDebug
```

Result: **140/140 PASS**, 0 failed, 0 skipped; lint 0 errors (47 warnings);
build successful. The extra report test verifies the blocked JSON
and Markdown summary and zero PRIME/STOP counts.

Python tooling passed `py_compile`. Static forbidden-symbol audit and
`git diff --check` are part of final repository verification.

## N. Build

- Task: `:app:assembleFullDebug`.
- APK: `X:\Projects\lumiflex\aaps-medtrum-bench-research-de285e0-e72e8e20.apk`.
- Size: `202,692,475` bytes.
- SHA-256: `e72e8e20a9fadf6d7cd666cea9985aeb43db8c58b52952cd18484dbbadb7bd1f`.
- Embedded `BUILDVERSION`: `de285e0-2026.08.23`; application ID
  `info.nightscout.androidaps`, version `4.0.0-beta-apex5` (`2004`).
- Signing: normal Gradle Full Debug signing, not a release/production key.
- No live pump command was run from Codex.

## O. One-button bench procedure

1. Verify the patch is physically off-body and `ACTIVE`.
2. Enable engineering mode and the Medtrum experimental option.
3. Open Medtrum overview.
4. Press **Перезапуск — тест** exactly once.
5. Do not use Change Patch or Deactivate during the campaign.
6. Wait for `BLOCKED`.
7. Share the automatically produced diagnostic archive.

In this build the button cannot restart the patch. It collects the read-only
baseline and blocks before every settings, candidate or ACTIVATE write. A real
restart path must not be enabled until an official TX capture or PDM packet
builder independently confirms the missing base transition.

# Apex7 validation checkpoint

Date: 2026-09-11. Status: PARTIAL. Desktop verification, not device or clinical
acceptance. Starting HEAD: `a800bc11003d4dfeb327724380e00a22bb3dcac4`.
Branch: `codex/apex7-reliability-overview`. The delivery commit SHA is recorded
in Git and the handoff; this file cannot embed its own commit SHA.

## Final verification

The command in [implementation status](APEX7_IMPLEMENTATION_STATUS.md#validation)
completed with exit code 0: `BUILD SUCCESSFUL in 2m 18s`.
`:app:compileFullDebugKotlin` succeeded (up-to-date on the final run after an
earlier successful compile). No signed APK was assembled or installed.

| Module | Tests | Failures / errors / skipped |
| --- | ---: | --- |
| workflow | 13 | 0 / 0 / 0 |
| plugins/main | 26 | 0 / 0 / 0 |
| plugins/sensitivity | 58 | 0 / 0 / 0 |
| core/interfaces | 51 | 0 / 0 / 0 |
| plugins/sync | 843 | 0 / 0 / 0 |
| pump/apex | 25 | 0 / 0 / 0 |
| core/data | 31 | 0 / 0 / 0 |
| ui (selected new tests only) | 3 | 0 / 0 / 0 |
| Total | 1050 | 0 / 0 / 0 |

Counts were read from JUnit XML, not inferred from Gradle task success. This is
the selected module suite, not every test in the repository. Local evidence:
`build/apex7-acceptance.log` and each module's `build/test-results` directory.
Build logs, JVM crash dumps and medical/device records are not committed.

## Oref1 equivalence and benchmark

`LegacySensitivityOref1Plugin` is the frozen pre-change implementation from the
starting HEAD, used only as an independent test oracle. The optimized result is
compared by exact `AutosensResult` equality, including exported fields, across
5 table sizes (3, 24, 288, 4605, 100000), 4 windows (0, 8, 24, 72 hours), and
2 upper bounds: 40 combinations. Fixtures include low BG, invalid deviations,
extra deviations and site changes. Separate lower-bound tests exercise range
edges and lookup cost. This is finite regression evidence, not proof for every
possible clinical input.

Final desktop timing, mean of 10 calls after 3 warm-ups per implementation;
real `AutosensDataStoreObject`, synthetic data, logging enabled:

| Table rows | Legacy, ms | Optimized, ms |
| ---: | ---: | ---: |
| 288 | 1.25380 | 1.42272 |
| 4605 | 1.10453 | 0.82689 |
| 100000 | 3.34091 | 0.83901 |

The largest fixture is about 4x faster in this run. The small fixture is slower.
These short desktop measurements are noisy and do not establish Android battery,
whole-workflow latency or clinical improvement. No math/filter/rounding change.

## Regression coverage and limits

- Workflow publication tests intentionally race late completions across 1000
  generations and reject stopped, invalidated and superseded reference writes.
  They do not establish deep isolation of mutable ADS rows.
- Replacement-tail tests allow `triggeredByNewBG=false` to claim a new actual BG,
  reject no-BG runs, and exercise 100 duplicate attempts. They do not prove
  starvation freedom under continuous WorkManager REPLACE cancellation.
- Two-hour 1-minute and 2-minute cadence fixtures characterize the baseline:
  live persistent anchor can hold the newest bucket for five minutes; clone
  drops the anchor. Neither the requested anchor-copy/phase change nor the
  conflicting HTML reset proposal was applied. Phase-shift/jitter acceptance
  for a new implementation remains pending.
- Debounce tests verify cleanup after failure and successful later scheduling.
  Startup coverage checks scheduler availability during observer registration
  with an immediate simulated callback, not full database startup integration.
- Resilient collection tests verify a failed item does not kill the next item,
  while cancellation remains cancellation. GV classification is telemetry only;
  no safe completed-input provenance exists yet for metadata suppression.
- Apex tests include delayed handshake completion and old-generation responses
  unable to satisfy the new request. Existing firmware/protocol therapy gates
  and runtime FSM remain unchanged. Full service reconciliation/therapy paths
  and hardware reconnect acceptance are not proved by director tests.
- Activity tests cover absent/denied sources, walking/running/swimming, delayed
  events, upsert/restart, missing end, clock skew, and swimming despite a newer
  unknown/zero-movement phone event. No Samsung SDK ingestion is implemented.
- UI tests cover unknown values and five representative detail sheets in English
  portrait, plus Russian landscape detail access. Codec round-trip is tested.
  Full ten-sheet state matrix, mg/dL/mmol formatting through the real ViewModel,
  large-font layout and actual legacy toggle integration need broader coverage.

## Screenshot gate: environment-blocked

An earlier run failed in Robolectric's `WindowCapture.forceRedraw` with a timeout.
Screenshot capture calls were removed from interaction tests; semantic assertions
remain and pass. No screenshots are delivered. This is not a screenshot pass and
does not establish absence of overlap or clipping. There is no emulator installed
in the local SDK. A physical device or a working emulator/rendering environment
is needed for Enhanced Overview and AutoISF/Activity/Pump/Sensor/Loop Health images.

Earlier compiler/test fixture errors were corrected. Windows test-process launch
failures and an identified Gradle daemon JAR lock were resolved using bounded
test forks and stopping the exact lock owner. Those runs were not successful
acceptance runs; the final result above supersedes them.

## Preserved boundaries

No live BLE writes, firmware changes, therapy-gate bypass, dosing formula/limit
changes, active force-loop watchdog or ActivityContext-to-dosing connection.
Samsung permission/SDK/licensing integration, full dashboard specification,
CGM liveness/anchor ownership, and real-device acceptance remain open. See the
[audit](APEX7_RELIABILITY_AUDIT.md) and [status](APEX7_IMPLEMENTATION_STATUS.md)
for the upstream comparison and deliberately stopped sub-changes.

Changed-file inventory for this checkpoint is reproducible with:

```powershell
git diff --name-status a800bc11003d4dfeb327724380e00a22bb3dcac4...codex/apex7-reliability-overview
```

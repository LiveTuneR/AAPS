# ADR: per-pass bucket anchor (supersedes the diagnostic-only decision)

Decision updated 2026-09-12 under the explicit fast-CGM follow-up request.
`referenceTime` belongs to one bucketing pass and is cleared in `finally`.
Clones continue to omit the anchor. No other part of 17dd2bbd8e is reverted.
The live-store reload path failed before this change, even with clone isolation.
FastCgmAnchorTest covers 60/120-second streams for 30 minutes at 12:01:17 phase,
completed publication and superseded/live reload, 5-minute jitter and phase changes.
Three deliberate mutations were detected; see APEX7_FAST_CGM_REFERENCE_RU.md.

Scheduler policy remains unchanged. The real enqueue/slot boundary experiment with
virtual execution demonstrates starvation for 70/90/120-second work under continuous
60-second arrivals. A separate pending-latest design needs approval and validation.
Bucket correctness alone does not establish end-to-end loop liveness or dosing safety.

## Previous decision (historical, superseded)

Status: current behavior retained; full policy/backport acceptance pending.
No new per-minute dosing path is authorized by this ADR.

## Evidence

The local upstream objects were inspected, not inferred from their subjects:

- `17dd2bbd8e69f96c3b78808e3078d46d3aff2c9f`: preserve referenceTime in clone,
  re-anchor a new 5-minute sensor phase, account for anchorShift in jitter checks;
  also changes orchestration and loop invocation. This upstream uses commonMain
  runners, unlike this branch's Android WorkManager workers.
- `dbd137fa9ca3f27bca0b98d7378a158a019cbd27`: per-row holdsSameData before min(timestamp).
  Our live ADS load is not proof of a completed calculation; transplanting that
  predicate alone would not establish the requested processed-input witness.
- `e50cf65590f8870b0ba8cac3f0372f04dc52b282`: prune relative to the calculation window,
  not wall clock, deleting indices downwards. It is a memory bound, not a scan-time cure.

`BucketCadenceCharacterizationTest` exercises two hours of 1- and 2-minute readings:

| Variant | Newest bucket | Raw-to-bucket timestamp offset |
| --- | --- | --- |
| Persistent anchor | start + floor(minute/5) * 5 min | 0 through 4 min |
| Current clone resets anchor | newest raw timestamp on each completed publication | 0 |

These tests characterize the real bucket store. They are not a 30-minute
integration liveness test and do not measure completed dosing at those cadences.
Cache performance and realistic table density have not been benchmarked for both
complete designs. New CalculationTiming provides measurements on the running app;
no seconds-of-speedup claim is made without recordings.

## Decision for this diagnostic artifact

Do not silently combine contradictory policies. Deep copying rows preserves the
existing anchor-reset behavior; it does not copy referenceTime. Keep existing
Oref1 lower-bound and non-finite guards unchanged. Do not install the HTML reset
patch, replace actualBg with raw BG, suppress unknown metadata events, or introduce
a minute-dose path. Record this limitation explicitly rather than call it fixed.

## Required next acceptance

Choose and test the complete stable-grid backport with sensor phase changes,
or explicitly accept reset-grid costs. A bounded scheduler first needs coherent
input ownership and therapy invalidation semantics. Test continuous arrivals
while work runs, not only the last chain after input stops. Metadata suppression
must use a committed or guaranteed-to-complete witness, survive cancellation and
restart safely, and default to recalculation for unknown fields/cases. Pruning
requires replay/history tests retaining all mathematical contributions.

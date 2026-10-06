# Energy R1 baseline — 2026-10-06

Source baseline: `da3220a5982000b63940f03004fa3a51af531017` (4.0.0-beta-apex7). Work branch: `codex/energy-r1-2026-10-06`.
Scope is the supplied energy assignment E0–E6. Minute CGM input, the insulin algorithm and its safety gates retain their existing behavior. Lumiflex BLE integration, pump UI and notification action labels remain separate R2 work.

## Evidence and comparable conditions

`metrics/baseline-log-summary.json` contains sanitized aggregate measurements from the user's pre-R1 export, verified against its manifest hashes. The Samsung SM-S938B/API36 export uses minute glucose input, SMB with autosens, DynISF disabled and a five-minute maximum SMB frequency setting. These are observed settings, not recommended therapy settings. Apex TruCare III and Medtrum Nano must be evaluated separately. The watch model is unknown.

| Measured interval | Samples | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| Original prepare worker | 12,691 | 8,126 ms | 41,378 ms | 90,405 ms |
| Original IOB graph section | 12,687 | 7,966 ms | 40,375 ms | 88,771 ms |
| Original autosens section | 12,688 | 108 ms | 859 ms | 2,202 ms |
| Original prepare, Apex | 8,295 | 7,957 ms | 48,517 ms | unavailable |
| Original prepare, Medtrum | 4,396 | 8,349 ms | 27,627 ms | unavailable |

The graph section accounts for 97.85% of summed prepare elapsed time. Nested sections overlap; do not sum all components. Elapsed time includes suspension/IO and is not CPU time or battery energy. ADS cache hits in the original export already exceed 99%; R1's historical **IOB** cache is a different cache.

The supplied battery screenshots identify AAPS as a major consumer, with reported high CPU use. They do not provide a controlled before/after run, charge capacity, temperature, CPU trace or a way to assign battery savings to an individual component.

## Baseline verification

The clean baseline is checked out separately. Local JDK21 and Android compile/build tools37 were used; no production source changes or private keys were introduced into that checkout. All 16 modules required by the release workflow were executed: 3,535 tests, zero failures/errors/skips. Counts by module are in `metrics/baseline-tests.json`. The first four-module baseline and the additional twelve-module run were separate executions; both finished successfully. Unit tests use fullDebug configurations and cannot replace release energy profiling.

Old Phone and Wear APKs were supplied in `my/` and independently verified using Android apksigner/aapt2. See `metrics/previous-apks.json`: both are `info.nightscout.androidaps`, code2006/name4.0.0-beta-apex7, same valid signing certificate `1b20d5c3807e9e6d895728d68099e21801ec05f860d4cc457eee25e530a8a084`. Phone min/target31/35; Wear30/30; four existing ABIs. The filenames identify the old short SHA; neither old APK contains the full 40-character SHA as a DEX string. R1 explicitly embeds the full SHA in each APK. These checks establish upgrade identity; they are not an installation test.

## Diagnostic durability map established before writer changes

| Path | Durability/role | R1 policy |
|---|---|---|
| Apex bolus-operation journal | Dedicated durable pre-dispatch/unknown-delivery recovery evidence | Unchanged; never enters batching or deduplication |
| CGM, APS_INPUT/DECISION, CONSTRAINT, SMB/TBR request, PUMP_QUEUE/DISPATCH/SENT/RESULT, reconciliation, ERROR, settings and process events | Existing serialized writer, per-record admission WAL and record fsync | Retain the existing writer contract and record payloads |
| PUMP_STATE, ACTIVITY, CALCULATION, SCHEDULER | Observations, not instructions to re-enact a command | Bounded RAM batching; consecutive semantically identical PUMP_STATE records may aggregate |
| Raw BLE payload trace | Recent forensic detail; cannot establish delivered insulin alone | Bounded RAM ring with explicit truncation counts on driver export |

TherapyTelemetry.record remains an asynchronous writer API. Per-record admission and fsync occur on that writer, not as a promise of synchronous durability at the caller. Dosing safety depends on the separate durable operation journal and existing reconciliation/command gates. The new exporter does not promise complete diagnostic RAM coverage after a crash.

## Measurement map and reproducibility

Prepare timings use monotonic `nanoTime`: load, smoothing, bucket/BG graph, profile, autosens/treatments IOB, optional IOB graph, historical IOB, base-basal IOB, forecast IOB and APS result read/decode. The existing clinical timestamp, generation and command correlation fields are retained. `historicalIobCache` counters are additive to calculation telemetry; graph work must be separated from mandatory preparation when aggregating results. Wear `TX_API_TOTALS` records cumulative UTF-8 bytes, attempts, API success/failure and monotonic report intervals; API success is not proof of radio energy or watch rendering. Telemetry exports include bounded-buffer and store IO counters.

Run `python doc/performance/measure_energy_fixture.py` from repository root for the **synthetic** SQLite comparison. It instantiates the actual Room schema35 and its existing indexes and extracts production DAO SQL. It checks 2,885 slots/boundaries per TBR/TT/EB table, including left overlap, invalid/tracked rows, equal timestamps and right/end boundaries. Twelve timed runs per scenario use `perf_counter_ns`; query plans and schema checksum are retained. This is not the user's DB and no new index/migration is justified by this fixture.

Run the 16 Gradle tasks listed in `.github/workflows/apex-test-build.yml` with `-I doc/apex7-validation.init.gradle`, JDK21, `--max-workers=2`, and `-Pkotlin.compiler.execution.strategy=in-process`. The init script forces actual test execution and bounds test heaps; it does not disable tests. The diagnostic store burst test writes `implementation/build/reports/energy-fixtures/diagnostic-burst.json`. Its repeated state fixture is synthetic; it does not establish a threefold reduction across an hour of real traffic.

For a device comparison use paired ordinary **fullRelease** builds with the same settings, pump, sensor app, phone/watch, connectivity, display activity and battery conditions. Record SHA, timestamps, number of loops and screen-on/off periods. Collect Android CPU/Perfetto and batterystats before/after comparable hours, separate Apex/Medtrum results, and report dropped samples. Trace/debug runs are separate from ordinary-mode measurements. Never change the minute input cadence or safety limits to obtain a favorable result.

Distinguish CGM measurement/receipt, ADS readiness, APS start/end and command send/result. A glucose age at APS_INPUT is not delivery latency. The target prepare p95<5–10s, hour-normalized diagnostic bytes≥3x reduction and actual battery savings remain unverified until paired device measurements exist.

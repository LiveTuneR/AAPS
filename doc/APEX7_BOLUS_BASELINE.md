# Apex bolus safety baseline

- Captured UTC: 2026-09-13
- Branch: `codex/apex7-reliability-overview`
- Accepted HEAD: `19a88c00a5b09b841eabc2936346d0d9832922e8`
- AndroidAPS upstream `dev`: `5d2852b72f14749daf3379cb3ffc2dc620465f11`
- `ApexService.kt` blob: `796394a992ae5756c089a491ae71931520ae0838`
- `ApexCommDirector.kt` blob: `b8f2c81b11337e0287b26cf487130ca4dc93f62f`
- `ApexPump.kt` blob: `08638535422db142104a2841366b7c8b150311df`
- `ApexPumpPlugin.kt` blob: `5885d2c24af51b724cdfd111e6af8310d2625003`
- `PumpSyncImplementation.kt` blob: `8356135e7a66378036c0cddeb9dfe7ebc45cbf73`
- Apex CI tests: 26, failures 0, errors 0, skipped 0
- Full accepted CI tests: 2775, failures 0, errors 0, skipped 0
- Accepted CI run: https://github.com/LiveTuneR/AAPS/actions/runs/34754173117

The baseline already contains fast-CGM, per-pass `referenceTime`, deep ADS ownership, latest-pending scheduling, structured APS telemetry and Enhanced Overview. These are invariants for this change.

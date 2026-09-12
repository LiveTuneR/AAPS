# Signed build receipt

Date: 2026-09-11. Implementation status: PARTIAL, experimental, not device or
clinical validation. The requested complete feature acceptance is NOT achieved.

## Artifact identity

- Code commit: `30f58c7572161a72d38029803b9da608ae12083c`.
- Workflow: [34617011164](https://github.com/LiveTuneR/AAPS/actions/runs/34617011164), success.
- APK artifact: [aaps-4.0-apex-14](https://github.com/LiveTuneR/AAPS/actions/runs/34617011164/artifacts/10271561258).
- Test artifact: [apex7-tests-14](https://github.com/LiveTuneR/AAPS/actions/runs/34617011164/artifacts/10271437473).
- Filename: `aaps-4.0-apex-30f58c7.apk`.
- Size: 125803268 bytes.
- SHA-256: `8bc8e2459574f86fd3e2019711826e7ce205a291c23bac33ed66a91c714bbfb8`.
- Package: `info.nightscout.androidaps`.
- Version: `4.0.0-beta-apex7`, versionCode `2006`, targetSdk `35`.

The downloaded APK hash matches the workflow checksum. Its provenance file
identifies the exact code commit and run above. Subsequent report-only commits
are not changes included in that APK.

## Signing verification

`apksigner verify --verbose --print-certs` succeeded locally with one v2 signer.
Certificate SHA-256:
`1b20d5c3807e9e6d895728d68099e21801ec05f860d4cc457eee25e530a8a084`.

This matches the user's original, non-custom `aaps-3.4.2.3.apk` certificate.
Both packages are `info.nightscout.androidaps`; the original versionCode is 1500.
This verifies signing identity, not database migration, preserved preferences,
installed APK identity or real-device acceptance. The private key remained in
GitHub secrets and was not downloaded. No phone installation was performed.

## Verification evidence

Downloaded JUnit XML: 1051 tests, 0 failures, 0 errors, 0 skipped across the
selected modules. UI selection is the three new tests, not the entire UI suite.
Full release assembly, signing and APK verification succeeded after those tests.

12 synthetic component PNGs were uploaded. The downloaded Overview image was
visually inspected; locally Overview, Activity and Russian landscape Loop were
also inspected. They are nonblank, minimal unknown-value fixtures, not the full
application with medical data. This closes the screenshot transport/capture
blocker, NOT the full production-data visual/state-matrix acceptance.

Previous run 34614952235 was cancelled for a newer code commit. Run 34615183509
failed with 2 Compose hardware-redraw timeouts; it produced no APK. Direct View
rendering fixed that test-environment failure. The successful run is the only
APK delivery claimed here.

## Still open

- Starvation-free calculation under continuous WorkManager REPLACE, complete
  mutable ADS isolation, stable referenceTime/phase semantics, and safe metadata
  event suppression require additional therapy-equivalence evidence. No active
  watchdog or unverified queue/anchor substitution was introduced.
- Samsung Health SDK binary, permission UI and actual source ingestion remain
  unimplemented; SDK distribution/use requirements remain unresolved.
- The dashboard is a diagnostic extension with ten sheets, not the complete
  requested redesign. Unsupported calculation factors, SMB permission/cap,
  confirmed sensor expiry and planned site interval remain unknown.
- Hardware reconnect/therapy validation, migration, full data-state UI matrix,
  and optional upstream extras are not complete.

Existing Apex FSM, firmware/protocol experimental gates, non-finite protections
and dosing formulas/limits remain intact. No live BLE write was made.

# Apex7 reliability implementation

Status: PARTIAL, not a clinically validated release. Do not install this branch for
therapy on the strength of compilation or desktop tests alone.

Start: `a800bc11003d4dfeb327724380e00a22bb3dcac4`.
Branch: `codex/apex7-reliability-overview` in `LiveTuneR/AAPS`.
Preflight and upstream comparison: [audit](APEX7_RELIABILITY_AUDIT.md).

## Implemented

- Completed MAIN chains claim a previously unclaimed actual bucket timestamp,
  independent of the `triggeredByNewBG` flag. The claim is synchronized and the
  Loop timestamp is volatile. No raw-BG substitution or force-run watchdog.
- ADS reference publication is serialized with generation replacement and stop
  invalidation. Superseded publications are counted and logged.
- Scheduler initialization precedes observers. History debounce clears state in
  `finally`. Shutdown cancels waiting work under the scheduling monitor.
- Calculation collectors reuse `collectResilient`, preserve cancellation, and log
  stream identity, exception type and timestamp.
- Oref1 uses binary lower-bound lookup and stops at the upper bound. The frozen
  pre-change implementation is a test-only oracle. Dosing mathematics is unchanged.
- Conservative glucose-change classification and counters. No event suppression:
  the loaded mutable ADS is not proof of a successfully completed input snapshot.
- Passive LoopHealth observer (60 seconds, state-transition logging), per-calculator
  workflow counters, and an immutable UI snapshot. No commands or recalculation.
- Tidepool payload/body copying removed; no URL or auth headers logged. Wear
  transport payload dumps replaced by counts.
- Apex public read-only diagnostics snapshot and additional generation/handshake
  tests. The connection FSM and experimental therapy gates are unchanged.
- Shadow ActivityContext model, bounded upsert cache, restart serialization,
  source-access states, explicit staleness and Samsung reader/provider boundary.
- Enhanced Overview feature flag (OFF by default), ten detail sheets, RU/EN
  resources, preserved existing BG/target/control widgets and graphs on all three
  layout paths. Enable in General preferences: Enhanced Overview.

## Unresolved Acceptance Gates

### CGM cadence and ADS ownership

The original task asks for a persistent five-minute anchor; the supplied HTML
asks to reset it after every pass. Tests reproduce both consequences. Neither
anchor change is applied. A constant anchor can hold `actualBg()` for five minutes;
resetting it shifts the Autosens cache grid. Neither is a calculation-neutral
substitution in this implementation. This is a STOPPED safety sub-change.

REPLACE scheduling can still starve a calculation when every calculation takes
longer than the input interval. The new tests prove replacement-tail opportunity,
duplicate claims, and publication rejection, NOT general end-to-end liveness.
An isolated input-snapshot / coalescing design and therapy-equivalence tests are
required before claiming the original one-minute acceptance criterion.

Generation protection covers ADS reference assignment, not every mutation:
the baseline loads/smooths into live ADS and clone retains shallow row references.
Complete mutable-state ownership isolation is still required. No stale fallback
was introduced to conceal this limitation.

### Activity source

The Samsung Health Data SDK is NOT bundled, permission UI is NOT integrated, and
no real Samsung record is currently ingested. The dashboard states this explicitly.
`SamsungExerciseReader` is an adapter contract, not a claim of working SDK access.
The private cache has no exported receiver and no connection to dosing code.

Official requirements: Samsung Health >= 6.30.2, supported physical Android device,
SDK license review and package/signature partnership registration for distribution.
The SDK does not support emulators; its published use restriction is fitness and
wellness, not medical treatment. Integration/distribution needs to be resolved
before adding the binary to this medical-app fork. No signature or permission bypass.

Sources: [SDK overview](https://developer.samsung.com/health/data/overview.html),
[app verification](https://developer.samsung.com/health/data/guide/app-verification.html).

### Dashboard

This is an opt-in diagnostic extension to the existing overview, not a replacement
of the complete top-level design. Existing graphs/time controls remain unchanged.
Missing fields are deliberately unknown: structured AutoISF contributor factors,
instantaneous SMB permission/cap, planned site interval/remaining time, confirmed
sensor expiry, per-SMB IOB, detailed absorption and future-carb editor navigation.
Last calculation values are accompanied by their timestamps, not presented as a
freshly recomputed therapy result. Site/sensor starts are recorded therapy events,
not inferred from the first glucose row. Pump reservoir is labeled in pump units.

No screenshots are delivered: Robolectric window capture timed out. The remaining
Compose tests use synthetic fixtures, not a phone with an active pump. Real-device
layout, background SDK sync and clinical acceptance remain pending. There is no
Android emulator installation in the current SDK.

### Apex and optional changes

Director tests verify callback generations and that Ready waits for the handshake
callback. They do not prove every service/queue therapy path on hardware. Transport
must permit handshake traffic during Handshaking; treating that as complete therapy
gating coverage would be incorrect. Service reconciliation and gates are preserved.
No new live BLE experiment was performed.

Optional upstream smoothing, Wear running-mode and CI-signing PRs are NOT ported;
they are outside this partial acceptance checkpoint.

## Validation

Run with the local JDK and Android SDK environment variables set:

```powershell
.\gradlew.bat :workflow:testFullDebugUnitTest :plugins:main:testFullDebugUnitTest :plugins:sensitivity:testFullDebugUnitTest :core:interfaces:testFullDebugUnitTest :plugins:sync:testFullDebugUnitTest :pump:apex:testFullDebugUnitTest :core:data:test :ui:testFullDebugUnitTest --tests '*EnhancedOverviewContentTest' --tests '*ActivityEventCodecTest' :app:compileFullDebugKotlin --continue --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' -I doc/apex7-validation.init.gradle --console=plain
```

The init script only bounds desktop test forks; production Gradle defaults are
unchanged. Windows validation initially encountered a JAR held by a stopped but
still-alive Gradle daemon and failed test-process launches at high parallelism.
The exact JAR owner was identified through Windows Restart Manager and stopped.
Compiler and test-fixture errors found during implementation were corrected; they
are not classified as pre-existing failures.

Final test totals, benchmark and screenshot availability are recorded in
`APEX7_VALIDATION.md` after the final run. No signed APK or hardware acceptance is
implied by `compileFullDebugKotlin`.

# Signed GitHub build

Follow-up to `11b69dd31eff911d2375b2ce6d9f7219f767df7b`.
Status remains PARTIAL; see `APEX7_IMPLEMENTATION_STATUS.md` for open acceptance.

## Dispatch

The registered `branch-ci.yml` delegates this specific experimental branch to
`apex-test-build.yml`. Other branches retain their existing workflow. Only
`fullRelease` is accepted by the experimental build path; other choices fail
explicitly instead of silently producing another variant.

```powershell
gh workflow run branch-ci.yml --repo LiveTuneR/AAPS --ref codex/apex7-reliability-overview -f buildVariant=fullRelease
```

The workflow first runs the selected reliability, Apex, ActivityContext and
Compose tests. It then signs with the existing repository signing secrets,
verifies the APK signature, and uploads APK, SHA-256 and commit/run provenance
for 14 days. Signing secrets are not supplied to checkout or test steps. The
temporary keystore is removed even after a failed build. Nothing is installed
on a phone or sent to a pump by this workflow.

The secret's presence alone does not prove that its certificate matches the
installed app. Verify the APK certificate against the actual installed/original
APK before assuming an in-place update is possible. Do not export the private key.

## Additional diagnostic corrections

- Current calculation duration advances while running; a future start remains
  unknown. Completed calculations retain their measured duration.
- Activity source access, last successful read, clock mismatch and watch
  reachability come from the snapshot. Unknown reachability is not false.
- Latency/duration include units. Old effective ISF is not a current tile summary;
  its timestamped last-calculation value remains in details.
- Compose interaction fixtures now exercise all ten detail sheets.

These changes do not implement Samsung SDK ingestion, persistent-grid phase
semantics, complete mutable ADS isolation, or starvation-free calculation
scheduling. They do not change any insulin formula or therapy gate. GitHub
build success must not be read as completion of these unresolved requirements.

Build/test outcome and artifact identity are reported separately after the run.
No APK from an earlier commit should be described as the result of a later one.

## Screenshot follow-up

Run 34615183509 failed with two `WindowCapture.forceRedraw` timeouts, on Linux
as well as the earlier Windows attempt. Its JUnit evidence contains 1051 tests,
2 failures, no errors or skips. The failed run did not sign or produce an APK.

The capture implementation now draws the actual Robolectric test-window View
into a bitmap on the UI thread, avoiding the unavailable hardware redraw
handshake. A clean local rerun completed successfully with capture enabled:
12 PNGs (overview, ten detail sheets, Russian landscape Loop sheet). Overview,
Activity and Russian Loop images were visually inspected and are nonblank.

These are minimal synthetic component fixtures, with unknown summaries and a
sample generation field, NOT production-data screenshots or full ViewModel /
clinical / complete-dashboard acceptance. Transparent areas outside a modal
are expected in a topmost-window capture. CI uploads the PNGs and test reports.

```powershell
$env:APEX7_CAPTURE_SCREENSHOTS='true'
.\gradlew.bat :ui:testFullDebugUnitTest --tests '*EnhancedOverviewContentTest' --tests '*ActivityEventCodecTest' --rerun-tasks --no-daemon --max-workers=2 '-Pkotlin.compiler.execution.strategy=in-process' -I doc/apex7-validation.init.gradle --console=plain
```

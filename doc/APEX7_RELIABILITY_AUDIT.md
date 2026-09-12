# Apex7 reliability audit (2026-09-11)

Starting HEAD: a800bc11003d4dfeb327724380e00a22bb3dcac4.
Source checkout: codex/medtrum-bench-restart-research, clean.
Implementation branch: codex/apex7-reliability-overview.
Remote codex/apex-fsm: 1483e350e81fe265c94c805ee9b288e7a9ddfed6.
Merge base with current upstream dev: 7fc8205e9a73259cec2982fc199f3d2055f84347.
Installed APK identity has not been verified on a device.

## Pre-flight

Already present: WorkflowChainData generations, enqueue lock, ApexCommDirector FSM,
experimental therapy gates, ApexTrace, non-finite/tripwire guards in APS algorithms.
Missing: atomic ADS publication/invalidation, exception-safe debounce cleanup,
resilient calculation collectors, scheduler-before-observers initialization,
range-bounded Oref1 scan, passive loop health, activity/dashboard integration.
Overview uses Compose in ui/compose/overview, with existing graph view models.
Calculation uses WorkManager workers in workflow/src/main, not upstream KMP runners.
SDK levels: min 31, target 35, compile 37. Samsung Data SDK requires separate
package/signature registration for distribution; no permission bypass is planned.

## Upstream comparison

17dd2bbd8e69f96c3b78808e3078d46d3aff2c9f removes trigger-origin gating,
adds volatile loop timestamp, preserves the bucket anchor with phase/jitter guards,
and rejects superseded ADS publication. Its publish check still documents a race
before generation registration. The backport must invalidate generations before
cache invalidation and serialize validation with publication.

Sources: AndroidAPS issues #5016, #5066, #5101 and PR #5087. Later #5101 comments
correct the early profiling claim: a 4605-row scan measured about 3 ms, not the
reported seconds per bucket. Do not claim this optimization resolves that cost.

## Deliberately deferred semantic conflict

The supplied aaps-loop-5min-fix.html proposes dropping referenceTime every pass.
The original task requires preserving it. Both affect actualBg(), which returns
bucketedData[0], and consequently automatic Loop/SMB opportunities. Upstream
#5087 comments confirm a persistent anchor gives a five-minute cadence for
one-minute sources. Neither anchor policy is silently introduced here: retain
the starting behavior pending isolated cadence/cache equivalence evidence.
This leaves stable-anchor acceptance explicitly unresolved. Do not substitute
raw BG for a freshly calculated bucket merely to trigger an additional cycle.

No active force-calculation watchdog, dosing formula, insulin limit, maxIOB,
or non-finite guard change is authorized by this backport.

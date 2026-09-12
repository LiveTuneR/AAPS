# Apex7 upstream backport audit (2026-09-12)

Status: IMPLEMENTATION IN PROGRESS. No device/hardware acceptance.

- Starting custom HEAD: `9da7e34c567aac306b2d98fa87f138b121c01eb2`.
- Freshly fetched `origin/dev`: `5d2852b72f14749daf3379cb3ffc2dc620465f11`.
- No additional upstream commits after the task's reference HEAD.
- No wholesale merge, Metro/KMP/DI migration, settings changes or live pump writes.
- USER CORRECTION: retain the accepted per-pass anchor ADR. Persistent upstream anchoring is explicitly rejected. `finally` resets live state even without publication; clone must not copy the anchor. The initial stable-grid instruction is superseded.

## Implementation matrix (before edits)

| Item / upstream SHA | Upstream behavior | Current Apex7 / present? | Conflict and chosen implementation | Required evidence |
|---|---|---|---|---|
| #5066 `17dd2bbd8e69f96c3b78808e3078d46d3aff2c9f` | Preserve anchor; re-anchor native 5-min phase beyond 90s; improve loop run | Custom per-pass lifetime is intentional | DO NOT PORT persistent anchor. Initial attempt withdrawn per user correction. Preserve deep copies, `finally` reset, no anchor cloning, no fixed-grid loop gate | 30-min 60s/120s raw-to-Loop advance; superseded live reload; three mutation guards; native 5-minute behavior |
| Prune `e50cf65590f8870b0ba8cac3f0372f04dc52b282` | Bound ADS by run window | No pruning | Native lock and in-place reverse removal; historical end, not wall clock | Cut boundary, consecutive removal, historical window |
| Metadata `dbd137fa9ca3f27bca0b98d7378a158a019cbd27` | Skip only proven identical rows | Partial classifier, always recalculates | Must prove completed provenance, not mutable loaded snapshot | Mixed insert/writeback, unknown, stale generation |
| TDD `e88ae5d956566972536f926b8dc6f0e81b358501` | Validate entire result before cache writes | No: writes before validity check | Move validity gate ahead of all writes without changing interval math | 1/7 -> no writes, 7/7, allowMissingDays |
| EPS TDD `872d1ab316df052cae0d39a34575996f4de1b35f` | Common invalidation path clears TDD from midnight | No: scheduler-only clear | Centralize within existing common path; retain ordering | EPS, bolus/carb/TBR, midnight boundary |
| #5082 `5f566ea01f81b40e8b912709dad2269a9d8810bc` | Complementary basal rate for short zero-temp | No: inverted formula in SMB and AutoISF; DynISF shares SMB | Change only active two expressions; retired non-Kotlin file is not compiled | Real branch 1/15/29 minutes, pump rounding, frozen replays |
| #5100 `5cd2a543bd2f12d0048cd75f9b35485085c0ee00` | Protect TBR -> SMB and accepted open-loop enactment | No protected enactment | Keep constraints/calculation cancellable; check cancellation at boundary; no protocol-gate removal | Cancel before/during TBR, failed/stale SMB, result recording, no duplicate |
| Insulin activation `985eede5089df8999c51c1d79abb1e804634e690` | Boolean false invokes onError | No: result discarded | Existing batch error callback; no retries | True/false, exactly once |
| Scheduler (custom) | Latest pending with ordered therapy invalidation | No: WorkManager REPLACE; deterministic starvation evidence already exists | Requires joint scheduler/history/worker lifecycle change, not KEEP alone | 70/90s under 60s input, ordered therapy events, shutdown/restart |
| Logs (custom existing) | Numeric rotations, payload suppression, verified ZIP | Yes, stress acceptance partial | Retain current LogFileOrder/RxBus/Tidepool fixes; measure synthetic workload | Rotation .99/.100; category bytes/hour |
| Structured diagnostics / UI (custom) | Adaptive algorithm tile, status semantics, current age, reference layout | Partial | Extend typed snapshot only; preserve Vico and safety/navigation controls | ON/WAIT/OFF, RU/EN/landscape/large font, no reason parsing |

## Validation boundaries

Static/unit and synthetic replay are separate evidence. Android-device, Apex and Medtrum hardware validation are NOT VERIFIED. Samsung reader is an adapter until actual SDK and device evidence establish otherwise. Activity remains shadow-only.

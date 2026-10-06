# Health Connect integration assessment

User clarification: preserve collection and assess usefulness before reducing it. R1.1 therefore retains the approximately two-minute background reader while the enhanced overview ViewModel is alive and enabled; only the two-second display loop follows screen subscription. This preserves the existing collection lifetime, not a claim of a new always-on service. The reader is serialized to avoid concurrent manual/automatic reads. It does not require an open overview state collector.

Already integrated: read-only ExerciseSessionRecord, StepsRecord and HeartRateRecord adapters; source/package attribution; walking/running/cycling/swimming categories; private bounded exercise cache; overview activity card and diagnostic ACTIVITY events. `usedForDosing` remains false. The classifier and clinical algorithms are unchanged.

## Supplied log evidence and calculation decision

The user's pre-energy archive was checked directly: telemetry SHA-256 matches its manifest. 5,811 ACTIVITY observations represent only 17 distinct exercise/source updates: 0 ACTIVE, 568 POST_ACTIVITY, 5,243 STALE_ACTIVITY. Source-update age median is 1,078.64 minutes, maximum 3,060.73 minutes. Repeated two-minute reads do not produce a live exercise signal. The final cached session lasts 18.15 minutes; that number is duration, not “18 minutes ago”. Its source update is roughly two days old. Reproduce with `audit_activity_log.py`; anonymized aggregates are in `metrics/r1_1-activity-log.json`.

The UI bug conflated source-record modification time with reader freshness and did not label historical session duration. R1.1 displays “Checked …” from the last read attempt; the session is explicitly “Last activity / Duration … / Ended … ago”. Detailed source update age is retained. A recent source check never promotes a stale session to ACTIVE. Tests reproduce the actual 18-minute historical example and verify denied access does not refresh last-successful-read.

Activity-aware AID can be useful, but direction/magnitude depends on exercise type/intensity, insulin on board and timing. A blanket reduction from any exercise record is not established. [The EASD/ISPAD position statement](https://pmc.ncbi.nlm.nih.gov/articles/PMC11732933/) discusses AID exercise strategies and different glucose responses; it does not validate this delayed Health Connect adapter as an automatic dosing signal.

The user's conditional request (“if useful, integrate for testing”) does not justify claiming benefit from an archive with no live ACTIVE data. This reader is retained and tested as a data/presentation integration, but no automatic insulin modifier is enabled. Before a non-enacting algorithm comparison, collect live/accurately dated input, specify the proposed existing exercise-mode rule and compare requested SMB/TBR against the unchanged baseline with stale/duplicate/revoked/corrected/deleted-data gates. Such replay must not issue pump commands. Hardware observation and a clinically validated rule are still required before therapy integration.

Useful established functions: display a recorded workout and its source, associate recorded steps/heart rate with a workout, show post-activity/stale status, retain detection/read latency in support evidence. Health Connect is a datastore; the current reader receives only records that an upstream app has written. A short polling interval cannot guarantee that a Galaxy Watch workout appears while it is still in progress. ExerciseSessionRecord has an end time, so this adapter normally represents a recorded session, not a direct live heart-rate subscription.

Issues found, not hidden by this energy change:

- Only ordinary exercise/steps/heart-rate read permissions are declared. Reading other apps' records in the background requires the supported background-read feature and explicit user grant. A surviving coroutine alone does not grant access.
- Current 24h reads use the first page only. Pagination must terminate on both null and empty pageToken. Real populated histories can require more pages.
- Steps from overlapping providers require attribution/deduplication or aggregate APIs before being interpreted as total daily activity; current display associates only the workout's origin.
- Re-reading the window does not propagate deletions into the private exercise store. Incremental getChanges needs deletion handling, expired-token recovery, a bounded full refresh and token persistence after cache commit. A timestamp watermark alone would miss corrected/backdated records.
- The current ViewModel owns collection: a separate application-scoped optional activity provider is required for collection after full ViewModel destruction. That change must include explicit background access and its own measured execution contract, rather than starting another unconditional FGS.

R1.1 records `healthConnect.refreshes`, returned record count, read wall latency and concurrent-read skips without an extra timer. The support activity evidence continues to include event start/end/update timestamps and source access status.

Device verification protocol: with Health Connect grants and Samsung Health synchronization documented, record a walking and a swimming workout with the phone screen off. Note watch start/stop times, first upstream record availability, AAPS first receipt, read status, and whether the card becomes POST_ACTIVITY or STALE_ACTIVITY. Repeat with permission revoked/restored and with a corrected/deleted session. Measure reader CPU/IO separately from the display loop. Failure to see a workout before its end is a source-availability result, not evidence to lower a dosing target automatically.

Integration decision: keep the existing activity display and forensic integration; verify background permissions, pagination, corrections/deletions and real watch latency before promoting this source to a broader activity provider. Automatic treatment adjustment is outside Energy R1.1's explicit unchanged-therapy constraint and is not enabled.

Primary references: [Android read-data and background access](https://developer.android.com/health-and-fitness/health-connect/read-data), [official Health Connect changes sample](https://github.com/android/health-samples/blob/main/health-connect/HealthConnectSample/app/src/main/java/com/example/healthconnectsample/data/HealthConnectManager.kt). These document API contracts; they are not measurements of this phone/watch.

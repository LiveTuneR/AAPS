# Known-protocol probe review

| Command | Purpose | Restart evidence | State risk | NVM change | Recommendation |
|---|---|---|---|---|---|
| `SYNCHRONIZE` | Read current state and telemetry | Required for `START_TIME`/`AGE` | Low/read-only | No evidence of write | Use for every snapshot |
| `GET_DEVICE_TYPE` | Read model and firmware | Confirms Nano 1.80.89 gate | Low/read-only | No | Use during normal connection |
| `GET_TIME` | Read pump time | Connection health only | Low/read-only | No | Use during recovery; do not set time |
| `GET_RECORD` | Read history/storage | Baseline context | Low/read-only | No | Use only in initial baseline |
| `SUBSCRIBE` | Enable notifications | Required for telemetry | Low/session control | No known NVM write | Use during normal connection |
| `SET_PATCH` | Apply patch settings | Tests ACTIVE acceptance and timer invariants | Low but writes configuration | Likely settings storage | Use only the three fixed phases |
| `ACTIVATE` | Standard activation with current profile | Directly tests ACTIVE state guard | State-changing if unexpectedly accepted | Yes/likely | One negative-state probe only |
| `SET_TIME` / `SET_TIME_ZONE` | Change pump clock | No useful restart evidence | Can alter history/time behavior | Yes | Exclude from bench recovery |
| `CLEAR_ALARM` | Clear pump alarm | No direct restart evidence | May alter suspension/alarm state | Possible | Exclude |
| `RESUME_PUMP` | Resume delivery | No restart evidence | Insulin-delivery state change | Possible | Exclude |

No additional real write has a better preservation/evidence profile than the
fixed `SET_PATCH` and `ACTIVATE` probes, so none is added.

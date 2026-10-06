"""Normalize same-process support-export counters. Does not manufacture missing baseline OS metrics."""
import argparse
import json
import zipfile
from pathlib import Path

def read(path):
    with zipfile.ZipFile(path) as z:
        return json.loads(z.read("build-provenance.json"))["energyRuntime"]

def interval(start, end):
    elapsed = end["elapsedMs"] - start["elapsedMs"]
    if elapsed <= 0:
        raise ValueError("No positive same-process interval; process restarted or exports reversed")
    a, b = start["counters"], end["counters"]
    deltas = {key: b.get(key, 0) - a.get(key, 0) for key in a.keys() | b.keys()}
    if any(v < 0 for v in deltas.values()):
        raise ValueError("Counter reset: reject this pair and collect new same-process boundaries")
    return {"durationHours": elapsed / 3_600_000, "counterDeltas": deltas,
        "ratesPerHour": {k: v * 3_600_000 / elapsed for k, v in deltas.items()},
        "endProcessLifetimeDurations": end["durations"],
        "limitations": "Percentiles are end-of-process-window, not interval percentile deltas. Use OS/Perfetto for CPU/wake totals. Baseline exports lack these counters."}

if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("--start", required=True); p.add_argument("--end", required=True); p.add_argument("--output", required=True)
    args = p.parse_args()
    value = interval(read(args.start), read(args.end))
    Path(args.output).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(value, indent=2))

"""Read-only activity signal audit. Verify archive telemetry checksum; report no medical dosing rule."""
import argparse
import collections
import hashlib
import json
import statistics
import zipfile
from pathlib import Path

p = argparse.ArgumentParser(); p.add_argument("--archive", required=True); p.add_argument("--output", required=True)
args = p.parse_args()
counts, updates, sessions, ages = collections.Counter(), set(), set(), []
digest = hashlib.sha256(); total = 0; last = None
with zipfile.ZipFile(args.archive) as z:
    manifest = json.loads(z.read("manifest.json"))
    with z.open("telemetry.jsonl") as f:
        for line in f:
            digest.update(line)
            if b'"ACTIVITY"' not in line:
                continue
            row = json.loads(line)
            if row["type"] != "ACTIVITY":
                continue
            data = row["data"]; total += 1; last = row
            counts[(data.get("activityState"), data.get("availability"))] += 1
            updates.add(data.get("lastUpdatedAt")); sessions.add((data.get("eventTimestamp"), data.get("endTimestamp")))
            if isinstance(data.get("lastUpdatedAt"), (int, float)):
                ages.append((row["timestampUtc"] - data["lastUpdatedAt"]) / 60_000)
    assert digest.hexdigest() == manifest["fileSha256"]["telemetry.jsonl"], "Telemetry checksum mismatch"
active = sum(n for (state, access), n in counts.items() if state == "ACTIVE" and access == "AVAILABLE")
latest = last["data"] if last else {}
result = {"schemaVersion": 1, "telemetrySha256Verified": True, "archiveBuildSha": manifest["buildSha"],
    "activityEvents": total, "availableActiveEvents": active, "distinctSourceUpdates": len(updates), "distinctSessions": len(sessions),
    "states": [{"state": k[0], "access": k[1], "count": n} for k, n in counts.items()],
    "sourceAgeMinutes": {"min": min(ages), "median": statistics.median(ages), "max": max(ages)} if ages else {},
    "lastSessionDurationMinutes": (latest["endTimestamp"] - latest["eventTimestamp"]) / 60_000 if latest.get("endTimestamp") and latest.get("eventTimestamp") else None,
    "assessment": "No live signal demonstrated; automatic dosing integration not supported by this archive" if not active else "Live signal observed; benefit still requires non-enacting clinical replay and source reliability validation",
    "limitations": "Retrospective observations are not a counterfactual trial; no insulin adjustment or efficacy estimate is derived."}
Path(args.output).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))

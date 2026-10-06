"""Synthetic SQLite query equivalence/profile; no patient DB and no battery estimate.
Run: python doc/performance/measure_energy_fixture.py
"""
import argparse
import hashlib
import json
import re
import sqlite3
import statistics
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = Path(__file__).resolve().parent / "metrics"
OUT.mkdir(exist_ok=True)
parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path, default=OUT / "sql-fixture.json")
args = parser.parse_args()
schemas = ROOT / "database/impl/schemas/app.aaps.database.AppDatabase"
schema_path = max(schemas.glob("*.json"), key=lambda x: int(x.stem))
schema = json.loads(schema_path.read_text())
db = sqlite3.connect(":memory:")

def query_for(dao, method, table):
    source = (ROOT / f"database/impl/src/main/kotlin/app/aaps/database/daos/{dao}.kt").read_text()
    match = re.search(r'@Query\("([^"\n]+)"\)\s+suspend fun ' + method + r'\(', source)
    assert match, method
    return re.sub(r'\$TABLE_[A-Z_]+', table, match[1])

def percentiles(samples):
    s = sorted(samples)
    return {"n": len(s), "p50Ms": statistics.median(s), "p95Ms": s[int((len(s)-1)*.95)],
            "p99Ms": s[int((len(s)-1)*.99)], "maxMs": max(s)}

results = []
start, end = 40 * 86_400_000, 42 * 86_400_000
specs = [
    ("temporaryBasals", "TemporaryBasalDao", "getTemporaryBasalActiveAt", "getTemporaryBasalActiveBetweenTimeAndTime"),
    ("temporaryTargets", "TemporaryTargetDao", "getTemporaryTargetActiveAt", "getTemporaryTargetsActiveBetweenTimeAndTime"),
    ("extendedBoluses", "ExtendedBolusDao", "getExtendedBolusActiveAt", "getExtendedBolusesActiveBetweenTimeAndTime"),
]
for table, dao, point_method, range_method in specs:
    entity = next(e for e in schema["database"]["entities"] if e["tableName"] == table)
    db.execute(entity["createSql"].replace("${TABLE_NAME}", table))
    for index in entity["indices"]:
        db.execute(index["createSql"].replace("${TABLE_NAME}", table))
    fields = entity["fields"]
    columns = [f["columnName"] for f in fields]
    insert = f'INSERT INTO {table} (' + ','.join('"'+c+'"' for c in columns) + ') VALUES (' + ','.join('?' for c in columns) + ')'
    def row(i, ts, duration, valid=True, reference=None):
        values = {f["columnName"]: (None if not f.get("notNull", False) else "NORMAL" if f["affinity"] == "TEXT" else 0) for f in fields}
        values.update(id=i, timestamp=ts, duration=duration, isValid=int(valid), referenceId=reference)
        db.execute(insert, [values[c] for c in columns])
    # 90-day history, corrections, tracked copies and cancellations. Deterministic.
    for i in range(1, 10_801):
        row(i, i*720_000, 1_000_000+(i%6)*60_000, i%13 != 0, 1 if i%17 == 0 else None)
    row(20_001, start-3_600_000, 4_000_000)  # left overlap
    row(20_002, start, 60_000)
    row(20_003, end, 60_000)  # right boundary included
    row(20_004, end+1, 60_000)
    row(20_005, start-1, 1)  # ends exactly at left boundary: excluded
    row(20_006, start, 600_000, reference=20_002)  # tracked old version
    row(20_007, start+1, 600_000, valid=False)
    # Tie order must agree with the real point query, too.
    row(20_008, start+120_000, 120_000)
    row(20_009, start+120_000, 120_000)
    point = query_for(dao, point_method, table)
    ranged = query_for(dao, range_method, table)
    slots = list(range(start, end+1, 60_000))
    boundary_slots = sorted(set(slots + [start-1, start+1, start+59_999, start+60_000, start+239_999, start+240_000]))
    idx_ts, idx_duration = columns.index("timestamp"), columns.index("duration")
    # Broaden fixture window by 1ms for the explicit left-1 test.
    rows = db.execute(ranged, {"from":start-1, "to":end}).fetchall()
    assert any(r[columns.index("id")] == 20_001 for r in rows)
    for ts in boundary_slots:
        expected = db.execute(point, {"timestamp":ts}).fetchone()
        actual = next((r for r in rows if r[idx_ts] <= ts < r[idx_ts]+r[idx_duration]), None)
        assert expected == actual, (table, ts, expected, actual)
    cold, batched = [], []
    for _ in range(12):
        begun = time.perf_counter_ns()
        for ts in slots:
            db.execute(point, {"timestamp":ts}).fetchone()
        cold.append((time.perf_counter_ns()-begun)/1e6)
        begun = time.perf_counter_ns()
        rows = db.execute(ranged, {"from":start, "to":end}).fetchall()
        for ts in slots:
            next((r for r in rows if r[idx_ts] <= ts < r[idx_ts]+r[idx_duration]), None)
        batched.append((time.perf_counter_ns()-begun)/1e6)
    results.append({"table":table, "rows":db.execute(f'SELECT count(*) FROM {table}').fetchone()[0],
                    "slots":len(slots), "equalityAssertions":len(boundary_slots), "pointQueries":len(slots), "rangeQueries":1,
                    "point":point, "range":ranged,
                    "pointPlan":db.execute('EXPLAIN QUERY PLAN '+point, {"timestamp":start}).fetchall(),
                    "rangePlan":db.execute('EXPLAIN QUERY PLAN '+ranged, {"from":start,"to":end}).fetchall(),
                    "pointTiming":percentiles(cold), "rangeTiming":percentiles(batched)})
report = {"schemaVersion":1,"source":"SYNTHETIC_IN_MEMORY_SQLITE", "sqliteVersion":sqlite3.sqlite_version,
          "roomSchema":str(schema_path.relative_to(ROOT)),"roomSchemaSha256":hashlib.sha256(schema_path.read_bytes()).hexdigest(),
          "clock":"perf_counter_ns", "batteryMeasured":False, "samplesPerScenario":12, "results":results}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps({"fixture":"SYNTHETIC", "tables":[{"table":r["table"], "equal":r["equalityAssertions"],
      "queriesBefore":r["pointQueries"], "queriesAfter":1, "p95BeforeMs":r["pointTiming"]["p95Ms"],
      "p95AfterMs":r["rangeTiming"]["p95Ms"]} for r in results]}, indent=2))

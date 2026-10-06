"""Complement executable therapy replay tests with exact source equality, never a battery proof."""
import argparse
import json
import subprocess
from pathlib import Path

R1 = "7cc441acd7dfec408042615c9f868e0dea3aafab"
BASELINE = "da3220a5982000b63940f03004fa3a51af531017"
def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()

parser = argparse.ArgumentParser()
parser.add_argument("--output", required=True)
args = parser.parse_args()
head = git("rev-parse", "HEAD")
files = git("ls-tree", "-r", "--name-only", R1).splitlines()
protected = [f for f in files if "/src/main/" in f and (
    f.startswith(("plugins/aps/", "plugins/smoothing/", "plugins/constraints/", "plugins/source/")) or
    f.startswith(("pump/medtrum/", "pump/apex/")) and "/diagnostics/" not in f)]
assert protected
changes = sorted(set(git("diff", "--name-only", R1, head).splitlines()) & set(protected))
assert not changes, ("R1.1 changed a protected therapy source", changes)
math_files = [f for f in protected if ("DetermineBasal" in f or f.startswith("plugins/smoothing/")) and f.endswith(".kt")]
baseline_changes = sorted(set(git("diff", "--name-only", BASELINE, head).splitlines()) & set(math_files))
assert not baseline_changes, ("Pre-energy algorithm or smoothing source changed", baseline_changes)
result = {"sourceSha": head, "r1BaseSha": R1, "preEnergySha": BASELINE,
    "protectedSourceFilesUnchangedFromR1": len(protected), "mathAndSmoothingFilesUnchangedFromPreEnergy": len(math_files),
    "executableEvidence": ["DetermineBasalSMBTest: 768 complete serialized outputs, frozen SHA-256 727dd06bbab8d19c18dc4ce7b5a980a8d735e16803c581261ed9eb0042f058f0",
        "HistoricalIobEquivalenceTest: full IOB totals cold/warm/correction; caller-specific basal profiles",
        "FastCgmLoopBoundaryTest and ContinuousCgmWorkflowTest: raw BG, smoothed ADS and current generation fences",
        "ProcessedTbrEbRangeTest and SQL fixture: point versus range boundary equivalence",
        "Medtrum/Apex protocol, rounding and unknown-delivery regression suites"],
    "limitations": "Source proof complements, does not replace, executed tests or real hardware acceptance."}
out = Path(args.output); out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))

"""Verify APK upgrade identity using Android build-tools, never a private signing key."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import zipfile
from pathlib import Path

CERT = "1b20d5c3807e9e6d895728d68099e21801ec05f860d4cc457eee25e530a8a084"
parser = argparse.ArgumentParser()
parser.add_argument("--phone", required=True)
parser.add_argument("--wear", required=True)
parser.add_argument("--build-tools", required=True)
parser.add_argument("--output", required=True)
parser.add_argument("--commit")
parser.add_argument("--previous", action="store_true")
args = parser.parse_args()
bt = Path(args.build_tools)
java = str(Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java"))
aapt = str(bt / ("aapt2.exe" if os.name == "nt" else "aapt2"))
records = []
for kind, name in [("phone", args.phone), ("wear", args.wear)]:
    apk = Path(name)
    sig = subprocess.check_output([java, "-jar", str(bt / "lib/apksigner.jar"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    certs = re.findall(r"certificate SHA-256 digest: ([0-9a-f]+)", sig)
    assert certs == [CERT], (kind, "certificate mismatch")
    manifest = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True)
    package, version_code, version_name = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", manifest).groups()
    sdk = re.search(r"(?:minSdkVersion|sdkVersion):'([^']+)'", manifest)[1]
    target = re.search(r"targetSdkVersion:'([^']+)'", manifest)[1]
    assert package == "info.nightscout.androidaps"
    assert int(version_code) == (2006 if args.previous else 2007)
    assert version_name == "4.0.0-beta-apex7"
    assert (sdk, target) == (("31", "35") if kind == "phone" else ("30", "30"))
    with zipfile.ZipFile(apk) as z:
        assert z.testzip() is None
        abis = sorted({n.split('/')[1] for n in z.namelist() if n.startswith('lib/') and n.endswith('.so')})
        assert abis == ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"], (kind, abis)
        embedded = None
        if args.commit:
            assert re.fullmatch(r"[0-9a-f]{40}", args.commit)
            embedded = any(args.commit.encode() in z.read(n) for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n))
            assert embedded, (kind, "full source commit missing from DEX")
    records.append({"kind":kind, "file":apk.name,"bytes":apk.stat().st_size,
                    "sha256":hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(), "package":package,
                    "versionCode":int(version_code), "versionName":version_name,"minSdk":int(sdk), "targetSdk":int(target),
                    "abis":abis, "certificateSha256":CERT,"signatureVerified":True,"embeddedSourceSha":args.commit if embedded else None})
out = Path(args.output)
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({"schemaVersion":1,"previousApks":args.previous,"apks":records,
                          "deviceUpgradeTested":False,"deviceEnergyMeasured":False}, indent=2)+'\n')
print(json.dumps(records, indent=2))
